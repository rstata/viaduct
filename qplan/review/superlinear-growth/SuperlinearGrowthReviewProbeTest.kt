package semantics.resolver26

import model.Assumptions
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.registry.ResolverRegistry
import model.requireObjectField
import model.requireQueryTypeDef
import model.satisfiableAlternatives
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.OEROccurrence
import semantics.shared.CycleCheckState
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.SharedOperationContext
import java.util.concurrent.atomic.AtomicInteger
import viaduct.graphql.schema.ViaductSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Diagnostic review probes: these document current growth, not acceptable performance budgets. */
class SuperlinearGrowthReviewProbeTest : Resolver26DispatcherResource {
    @Test
    fun `runtime delivers expanded successor demand despite one invocation per key`() {
        val depth = 15
        val fields = (0..depth).joinToString("\n") { index ->
            val inputs = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
            "field$index: Int @resolver(of: \"$inputs\", result: 1)"
        }
        val world = TestWorld.fromDSL(
            "extend type Query { root: Box @resolver(result: {}) } type Box { $fields }",
        ).assumptions
        val applications = AtomicInteger()
        var suppliedSelections = 0
        val observer = object : ResolverObserver {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                applications.incrementAndGet()
                if (observation.field.name == "root") suppliedSelections = requireNotNull(observation.suppliedDemand).size
            }
        }
        val result = SharedOperationContext.create(world, resolverObserver = observer).resolveWithTestDispatcher(
            world.schema.fragmentFrom("fragment F on Query { root { field0 } }").subselections,
        )
        val rootKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "root"), emptyMap())
        val box = result.getCell(rootKey).getValue().get() as ObjectEngineResult
        println("GROWTH runtimeSuccessor depth=$depth suppliedSelections=$suppliedSelections boxKeys=${box.keys.size} applications=${applications.get()}")
        assertEquals(depth + 1, box.keys.size)
        assertEquals(depth + 2, applications.get())
        assertTrue(suppliedSelections > 2000)
    }
    @Test
    fun `successor demand duplicates a memoized diamond`() {
        for (depth in listOf(5, 10, 15, 20)) {
            val fields = (0..depth).joinToString("\n") { index ->
                val inputs = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
                "field$index: Int @resolver(of: \"$inputs\", result: 1)"
            }
            val world = TestWorld.fromDSL("extend type Query { $fields }").assumptions
            val input = world.schema.fragmentFrom("fragment F on Query { field0 }").subselections
            val demand = input.successorDemand(world)
            val distinct = demand.merge(world.schema.requireQueryTypeDef()).size
            println("GROWTH successor depth=$depth selections=${demand.size} distinct=$distinct")
            assertEquals(depth + 1, distinct)
            assertTrue(demand.size > distinct)
        }
    }

    @Test
    fun `parent demand duplicates nonempty memoized requests`() {
        for (depth in listOf(5, 10, 15, 20)) {
            val fields = (0..depth).joinToString("\n") { index ->
                val inputs = if (index == depth) "parent { seed }" else
                    ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
                "field$index: Int @resolver(of: \"$inputs\", result: 1)"
            }
            val world = TestWorld.fromDSL(
                """
                extend type Query { root: Parent @resolver(result: {}) }
                type Parent { seed: Int child: Child }
                type Child { parent: Parent @parent $fields }
                """.trimIndent(),
            ).assumptions
            val input = world.schema.fragmentFrom("fragment F on Query { root { child { field0 } } }").subselections
            val demand = input.liftParentConstructionDemand(world)
                .merge(world.schema.requireQueryTypeDef()).single().subselections
            val distinct = demand.merge(world.schema.requireObjectField("Parent", "seed").containingDef).size
            println("GROWTH parent depth=$depth selections=${demand.size} distinct=$distinct")
            assertEquals(1, distinct)
            assertTrue(demand.size > distinct)
        }
    }

    @Test
    fun `shared query closure enumerates guard products`() {
        for (depth in listOf(3, 6, 9, 12)) {
            val fixture = TestWorld.fromSDL(
                schemaSDL = "type Query { " + (0..depth).joinToString(" ") { "field$it: Int" } + " }",
                selectiveResolvers = true,
                fieldResolvers = { schema ->
                    (0..depth).associate { index ->
                        val field = schema.requireObjectField("Query", "field$index")
                        val empty = schema.emptyFragmentOf("Query")
                        val resolver = fieldResolverOf(
                            objectFragment = empty,
                            queryFragment = if (index == depth) empty else schema.fragmentFrom(
                                "fragment F on Query { left: field${index + 1} @include(if: ${'$'}a) right: field${index + 1} @include(if: ${'$'}b) }",
                                variableField = field,
                            ),
                        ) { _, _, _ -> 1 }
                        field to if (index == depth) resolver else
                            resolver.withVariablesProvider(setOf("a", "b")) { mapOf("a" to true, "b" to true) }
                    }
                },
            )
            val closed = close(fixture.assumptions, "field0")
            val leaf = closed.queryRooted.demand.byKey().values.single { it.key.field.name == "field$depth" }
            val alternatives = leaf.inclusionCondition.satisfiableAlternatives().size
            println("GROWTH guards depth=$depth queryKeys=${closed.queryRooted.demand.size} leafAlternatives=$alternatives")
            assertEquals(depth, closed.queryRooted.demand.size)
            assertEquals(1 shl depth, alternatives)
        }
    }

    @Test
    fun `closure repeatedly scans the entire growing chain`() {
        for (depth in listOf(16, 32, 64, 128)) {
            val fields = (0..depth).joinToString("\n") { index ->
                val inputs = if (index == depth) "" else "field${index + 1}"
                "field$index: Int @resolver(of: \"$inputs\", result: 1)"
            }
            val original = TestWorld.fromDSL("extend type Query { $fields }").assumptions
            var membershipChecks = 0
            val registry = object : ResolverRegistry by original.resolverRegistry {
                override fun contains(field: ViaductSchema.ObjectField): Boolean {
                    membershipChecks++
                    return field in original.resolverRegistry
                }
            }
            val world = Assumptions.of(original.schema, registry, original.selectiveResolvers)
            membershipChecks = 0
            val closed = close(world, "field0")
            println("GROWTH closure depth=$depth keys=${closed.objectRooted.demand.size} membershipChecks=$membershipChecks")
            assertEquals(depth + 1, closed.objectRooted.demand.size)
            assertTrue(membershipChecks > depth * depth / 2)
        }
    }

    @Test
    fun `occurrence owned variables keep equal valued diamond keys distinct`() {
        for (depth in listOf(4, 8, 12)) {
            val fields = (0..depth).joinToString("\n") { index ->
                val inputs = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it(x: ${'$'}x)" }
                "field$index(x: Int!): Int @resolver(of: \"$inputs\", result: 1)"
            }
            val world = TestWorld.fromDSL("extend type Query { $fields }").assumptions
            val closed = close(world, "field0(x: 1)")
            val keys = closed.objectRooted.demand.size
            println("GROWTH symbolic depth=$depth keys=$keys coordinates=${depth + 1}")
            assertTrue(keys > depth + 1)
        }
    }

    @Test
    fun `hashing a linear symbolic key DAG revisits the shared predecessor twice`() {
        for (depth in listOf(4, 8, 12, 16)) {
            val fields = (0..depth).joinToString(" ") { "field$it(x: Int!, y: Int!): Int" }
            val schema = TestWorld.fromSDL("type Query { $fields }").schema
            val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
            val baseField = schema.requireObjectField("Query", "field0")
            var leafHashes = 0
            val countedField = object : ViaductSchema.ObjectField by baseField {
                override fun hashCode(): Int {
                    leafHashes++
                    return baseField.hashCode()
                }
            }
            var key: ObjectEngineResult.ObjectKey = ObjectEngineResult.GroundKey.of(
                countedField, mapOf("x" to 1, "y" to 1),
            )
            for (index in 1..depth) {
                val previousField = schema.requireObjectField("Query", "field${index - 1}")
                val variable = Arguments.Variable.of(previousField, "x").instantiate(
                    ResolverOccurrenceId.at(root, listOf(key)),
                )
                key = ObjectEngineResult.ObjectKey.of(
                    schema.requireObjectField("Query", "field$index"),
                    mapOf("x" to variable, "y" to variable),
                )
            }
            leafHashes = 0
            key.hashCode()
            println("GROWTH hashing depth=$depth distinctKeys=${depth + 1} leafHashCalls=$leafHashes")
            assertEquals(1 shl depth, leafHashes)
        }
    }

    @Test
    fun `cycle checking traverses every suffix of an acyclic chain`() {
        for (depth in listOf(16, 32, 64, 128)) {
            val fields = (0..depth).joinToString(" ") { "field$it: Int" }
            val schema = TestWorld.fromSDL("type Query { $fields }").schema
            val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
            var fieldHashes = 0
            val keys = (0..depth).map { index ->
                val field = schema.requireObjectField("Query", "field$index")
                val countedField = object : ViaductSchema.ObjectField by field {
                    override fun hashCode(): Int {
                        fieldHashes++
                        return field.hashCode()
                    }
                }
                ObjectEngineResult.GroundKey.of(countedField, emptyMap())
            }
            val cells = keys.map { root.reserveCell(it) }
            val checker = CycleCheckState.create()
            keys.forEachIndexed { index, key -> checker.registerWriter(cells[index], listOf(key)) }
            fieldHashes = 0
            for (index in depth - 1 downTo 0) checker.cycleCheck(listOf(keys[index]), cells[index + 1])
            println("GROWTH cycles edges=$depth fieldHashCalls=$fieldHashes")
            assertTrue(fieldHashes > depth * depth)
        }
    }

    private fun close(world: Assumptions, fields: String): ClosedConstructionDemandContext {
        val type = world.schema.requireQueryTypeDef()
        val objectResult = ObjectEngineResult.of(type, mutable = true)
        val queryResult = ObjectEngineResult.of(type, mutable = true)
        return world.resolverRegistry.createRootQueryInput().closeOrchestratorConstructionDemand(
            world,
            OEROccurrence(objectResult, emptyList(), objectResult),
            OEROccurrence(queryResult, emptyList(), queryResult),
            world.schema.fragmentFrom("fragment F on Query { $fields }").subselections,
        )
    }
}
