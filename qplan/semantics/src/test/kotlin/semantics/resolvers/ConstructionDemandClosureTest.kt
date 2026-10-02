package semantics.resolvers

import model.Arguments
import model.EngineObjectDataEntry
import model.ListEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.Selection
import model.VariableBinding
import model.engineObjectDataOf
import model.fragmentFrom
import model.objectOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.selectionForestOf
import model.testing.TestWorld
import semantics.resolvers.resolver01.SiblingDependencyLogic
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConstructionDemandClosureTest {
    @Test
    fun `closure and order keep accumulators local to each invocation`() {
        val world = fixture().assumptions
        val schema = world.schema
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val occurrence = OEROccurrence(root, emptyList(), root)
        val operation = SharedOperationContext.create(world)
        val source = schema.objectOf("Query")
        val ordering = SiblingDependencyLogic(operation, occurrence)
        val a = key(world, "Query", "a")
        val b = key(world, "Query", "b")
        val leaf = key(world, "Query", "leaf")
        val demand = schema.fragmentFrom("fragment F on Query { a }").subselections

        repeat(2) {
            assertEquals(setOf(a, b, leaf), source.closeConstructionDemand(operation, occurrence, demand).groundKeys())
            assertEquals(listOf(leaf, b, a), ordering.order(linkedSetOf(a, b, leaf)))
            assertEquals(emptySet(), source.closeConstructionDemand(operation, occurrence, selectionForestOf()).groundKeys())
            assertEquals(emptyList(), ordering.order(emptySet()))
            assertEquals(listOf(leaf), ordering.order(setOf(leaf)))
        }
    }

    @Test
    fun `closure preserves exact root list position and consumer key for argument variables`() {
        val world = fixture().assumptions
        val schema = world.schema
        val operation = SharedOperationContext.create(world)
        val boxType = schema.requireObjectField("Box", "consumer").containingDef
        val boxes = key(world, "Query", "boxes")
        val consumer = key(world, "Box", "consumer", mapOf("seed" to 7))
        val sibling = key(world, "Box", "sibling", mapOf("value" to 7))
        val demand =
            schema.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections

        repeat(2) {
            val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
            repeat(2) { index ->
                val path = listOf(boxes, ListEngineResult.Index.of(index))
                val target = ObjectEngineResult.of(boxType, emptyMap())
                val occurrence = OEROccurrence(root, path, target)
                val closed =
                    schema.objectOf("Box").closeConstructionDemand(
                        operation,
                        occurrence,
                        demand,
                    )
                assertEquals(setOf(consumer, sibling), closed.groundKeys())
                val variable =
                    Arguments.Variable
                        .of(consumer.field, "seed")
                        .instantiate(ResolverOccurrenceId.at(root, path + consumer))
                assertEquals(
                    VariableBinding.of(7),
                    operation.variableBindings.getBinding(requireNotNull(variable.instanceId)),
                )
                assertEquals(
                    listOf(sibling, consumer),
                    SiblingDependencyLogic(operation, occurrence)
                        .order(linkedSetOf(consumer, sibling)),
                )
            }
        }
    }

    @Test
    fun `source supplied active fields do not expand standard resolver fragments`() {
        val world = fixture().assumptions
        val schema = world.schema
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val source = schema.objectOf("Query") { "a" setTo 99 }
        val operation = SharedOperationContext.create(world)
        val occurrence = OEROccurrence(root, emptyList(), root)
        val demand = schema.fragmentFrom("fragment F on Query { a }").subselections

        assertEquals(setOf(key(world, "Query", "a")), source.closeConstructionDemand(operation, occurrence, demand).groundKeys())
    }

    @Test
    fun `passive argument-bearing source validation retains error argument precedence`() {
        val world = fixture().assumptions
        val schema = world.schema
        val field = schema.requireObjectField("Box", "consumer")
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val occurrence =
            OEROccurrence(
                root,
                listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(0)),
                ObjectEngineResult.of(field.containingDef, emptyMap()),
            )
        val source =
            engineObjectDataOf(
                field.containingDef,
                listOf(EngineObjectDataEntry.of("consumer", field, 1)),
            )
        val operation = SharedOperationContext.create(world)
        val errored = ObjectEngineResult.GroundKey.of(field, Arguments.Error)
        val errorDemand =
            selectionForestOf(
                Selection.of(errored, setOf(field.containingDef), selectionForestOf()),
            )
        assertEquals(setOf(errored), source.closeConstructionDemand(operation, occurrence, errorDemand).groundKeys())

        val ordinaryDemand =
            schema.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections
        val failure =
            assertFailsWith<IllegalArgumentException> {
                source.closeConstructionDemand(operation, occurrence, ordinaryDemand)
            }
        assertEquals(
            "Resolver output must not supply argument-bearing field Box/consumer",
            failure.message,
        )
    }

    @Test
    fun `error arguments bypass missing resolver validation while ordinary demand rejects it`() {
        val world = fixture().assumptions
        val schema = world.schema
        val field = schema.requireObjectField("Box", "missing")
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val occurrence =
            OEROccurrence(
                root,
                listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(0)),
                ObjectEngineResult.of(field.containingDef, emptyMap()),
            )
        val operation = SharedOperationContext.create(world)
        val normal = key(world, "Box", "missing", mapOf("value" to 1))
        val errored = ObjectEngineResult.GroundKey.of(field, Arguments.Error)
        val ordering = SiblingDependencyLogic(operation, occurrence)
        assertEquals(listOf(errored), ordering.order(setOf(errored)))
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ordering.order(setOf(normal))
            }
        assertEquals(
            "Demanded field Box/missing is absent from its source and has no registered resolver",
            failure.message,
        )
        val demand =
            selectionForestOf(
                Selection.of(errored, setOf(field.containingDef), selectionForestOf()),
            )
        assertEquals(
            setOf(errored),
            schema.objectOf("Box")
                .closeConstructionDemand(operation, occurrence, demand)
                .groundKeys(),
        )
    }

    @Test
    fun `reclosing a bound occurrence still rejects duplicate variable completion`() {
        val world = fixture().assumptions
        val schema = world.schema
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val boxType = schema.requireObjectField("Box", "consumer").containingDef
        val occurrence =
            OEROccurrence(
                root,
                listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(0)),
                ObjectEngineResult.of(boxType, emptyMap()),
            )
        val operation = SharedOperationContext.create(world)
        val source = schema.objectOf("Box")
        val demand =
            schema.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections
        source.closeConstructionDemand(operation, occurrence, demand)

        assertFailsWith<IllegalStateException> {
            source.closeConstructionDemand(operation, occurrence, demand)
        }
    }

    private fun key(
        world: model.Assumptions,
        type: String,
        name: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField(type, name),
            arguments,
        )

    private fun fixture(): TestWorld =
        TestWorld.fromDSL(
            schemaSDL =
                """
                extend type Query {
                  a: Int @resolver(of: "b", result: "sum(b)")
                  b: Int @resolver(of: "leaf", result: "sum(leaf)")
                  leaf: Int @resolver(result: 1)
                  boxes: [Box] @resolver(result: [])
                }
                type Box {
                  consumer(seed: Int!): Int @resolver(of: "sibling(value: ${'$'}seed)", result: "sum(sibling)")
                  sibling(value: Int!): Int @resolver(result: "sum(${'$'}value)")
                  missing(value: Int!): Int
                }
                """.trimIndent(),
        )
}
