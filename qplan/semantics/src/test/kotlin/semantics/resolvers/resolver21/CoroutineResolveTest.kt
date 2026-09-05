package semantics.resolvers.resolver21

import model.requireField
import model.requireObjectField
import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import viaduct.graphql.schema.ViaductSchema
import model.UncompletedPromiseException
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.registry.FieldResolver
import model.registry.ResolverRegistry
import model.sameCompletedResultAs
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.contract.selectionValues
import semantics.shared.CycleCheckState
import semantics.shared.ResolverReadCycleException
import semantics.shared.OperationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData

class CoroutineResolveTest {
    @Test
    fun `installs every local promise before any local producer starts`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { first: Int!, second: Int! }",
                selectiveResolvers = false,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "first") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> 1 },
                        schema.requireField("Query", "second") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> 2 },
                    )
                },
            )
        val world = testWorld.assumptions
        val expectedKeys =
            setOf(
                world.schema.groundKey("Query", "first"),
                world.schema.groundKey("Query", "second"),
            )
        val registeredKeys = linkedSetOf<ObjectEngineResult.GroundKey>()
        var producerStarts = 0
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    cell: EngineResultCell,
                    writer: List<PathComponent>,
                ) {
                    registeredKeys += writer.last() as ObjectEngineResult.GroundKey
                }

                override fun cycleCheck(
                    reader: List<PathComponent>,
                    cell: EngineResultCell,
                ) {}
            }
        val resolver =
            CoroutineResolve(
                operation = OperationContext(world),
                complete = { completedSelections ->
                    producerStarts += 1
                    assertEquals(expectedKeys, registeredKeys)
                    completedSelections
                },
                cycleChecker = cycleChecker,
            )
        val selections =
            world.fragmentFrom("fragment ignored on Query { first second }").subselections

        runBlocking {
            resolver.resolve(world.objectOf("Query"), selections)
        }

        assertEquals(2, producerStarts)
    }

    @Test
    fun `installs active child promises before publishing their ancestor value`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Child { first: Int!, second: Int! }
                    type Query { child: Child! }
                    """.trimIndent(),
                selectiveResolvers = false,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "child") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("Child") },
                        schema.requireField("Child", "first") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ -> 1 },
                        schema.requireField("Child", "second") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ -> 2 },
                    )
                },
            )
        val world = testWorld.assumptions
        val childKey = world.schema.groundKey("Query", "child")
        val expectedChildKeys =
            setOf(
                world.schema.groundKey("Child", "first"),
                world.schema.groundKey("Child", "second"),
            )
        val expectedChildResultKeys = expectedChildKeys
        var rootCell: EngineResultCell? = null
        val childRegistrations = linkedSetOf<ObjectEngineResult.GroundKey>()
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    cell: EngineResultCell,
                    writer: List<PathComponent>,
                ) {
                    if (writer.size == 1) {
                        rootCell = cell
                    } else {
                        assertFailsWith<UncompletedPromiseException> {
                            assertNotNull(rootCell).getValue().get()
                        }
                        childRegistrations += writer.last() as ObjectEngineResult.GroundKey
                    }
                }

                override fun cycleCheck(
                    reader: List<PathComponent>,
                    cell: EngineResultCell,
                ) {}
            }
        val resolver =
            CoroutineResolve(
                operation = OperationContext(world),
                complete = { completedSelections -> completedSelections },
                cycleChecker = cycleChecker,
            )
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { child { first second } }")
                .subselections

        val result =
            runBlocking {
                resolver.resolve(world.objectOf("Query"), selections)
            }

        assertEquals(expectedChildKeys, childRegistrations)
        val child = assertIs<ObjectEngineResult>(result.getCell(childKey).getValue().get())
        assertEquals(expectedChildResultKeys, child.keys)
    }

    @Test
    fun `Resolver21 detects a resolver read cycle before timeout`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { first: Int!, second: Int! }",
                selectiveResolvers = false,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "first") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { second }",
                                ),
                            ) { _, _ -> 1 },
                        schema.requireField("Query", "second") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> 2 },
                    )
                },
            )
        val first = testWorld.schema.requireObjectField("Query", "first")
        val second = testWorld.schema.requireObjectField("Query", "second")
        val malformedRegistry =
            registryOverride(testWorld.resolverRegistry) { field, delegate ->
                when (field) {
                    first -> delegate.resolver(first)
                    second -> delegate.resolver(first)
                    else -> null
                }
            }
        val world =
            Assumptions.of(
                schema = testWorld.schema,
                resolverRegistry = malformedRegistry,
                selectiveResolvers = false,
            )
        val selections =
            world.fragmentFrom("fragment ignored on Query { first }").subselections

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                context(OperationContext(world)) {
                    resolve(selections)
                }
            }

        assertEquals(failure.cycle.first(), failure.cycle.last())
        assertTrue(failure.cycle.flatten().contains(second.groundKey()))
    }

    @Test
    fun `resolver failure escapes the root and cancels waiting siblings`() {
        val failure = IllegalStateException("resolver failed")
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { failed: Int!, waiting: Int! }",
                selectiveResolvers = false,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "failed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> throw failure },
                        schema.requireField("Query", "waiting") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { failed }",
                                ),
                            ) { input, _ ->
                                input.selectionValues().getValue("failed")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            world.fragmentFrom("fragment ignored on Query { waiting }").subselections

        val thrown =
            assertFailsWith<IllegalStateException> {
                context(OperationContext(world)) {
                    resolve(selections)
                }
            }

        assertEquals(failure.message, thrown.message)
    }

    @Test
    fun `successful return is quiescent with write-once completed promises`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item { value: Int! }
                    type Query { items: [Item!]! }
                    """.trimIndent(),
                selectiveResolvers = false,
                fieldResolvers = { schema ->
                    val items = schema.requireField("Query", "items")
                    mapOf(
                        items to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                listOf(
                                    schema.objectOf("Item"),
                                    schema.objectOf("Item"),
                                )
                            },
                        schema.requireField("Item", "value") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Item"),
                            ) { _, _ -> 7 },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            world.fragmentFrom("fragment ignored on Query { items { value } }").subselections

        val result =
            context(OperationContext(world)) {
                resolve(selections)
            }

        assertCompletedAndWriteOnce(result)
        assertTrue(result.sameCompletedResultAs(result))
    }
}

private fun assertCompletedAndWriteOnce(result: EngineResult?) {
    when (result) {
        null,
        is ErrorEngineResult,
        -> Unit
        is ListEngineResult ->
            result.indices.forEach { index ->
                assertCompletedAndWriteOnce(result[index].getValue().get())
            }
        is ObjectEngineResult ->
            result.keys.forEach { key ->
                val promise = result.getCell(key).getValue()
                val value = promise.get()
                assertFailsWith<IllegalStateException> {
                    promise.complete(value)
                }
                if (key !is ObjectEngineResult.ParentKey) {
                    assertCompletedAndWriteOnce(value)
                }
            }
        else -> Unit
    }
}

private fun registryOverride(
    delegate: ResolverRegistry,
    resolver: (ViaductSchema.ObjectField, ResolverRegistry) -> FieldResolver?,
): ResolverRegistry =
    object : ResolverRegistry {
        override fun createRootQueryInput(): EngineObjectData.Sync = delegate.createRootQueryInput()

        override fun contains(field: ViaductSchema.ObjectField): Boolean =
            resolver(field, delegate) != null

        override fun resolver(field: ViaductSchema.ObjectField): FieldResolver =
            resolver(field, delegate)
                ?: error(
                    "Missing overridden resolver: " +
                        "${field.containingDef.name}.${field.name}",
                )

        override fun mayDemandFrom(field: ViaductSchema.ObjectField): Set<ViaductSchema.ObjectField> =
            delegate.mayDemandFrom(field)
    }

private fun ViaductSchema.groundKey(
    typeName: String,
    fieldName: String,
): ObjectEngineResult.GroundKey =
    ObjectEngineResult.GroundKey.of(
        requireObjectField(typeName, fieldName),
        emptyMap(),
    )

private fun ViaductSchema.ObjectField.groundKey(): ObjectEngineResult.GroundKey =
    ObjectEngineResult.GroundKey.of(this, emptyMap())
