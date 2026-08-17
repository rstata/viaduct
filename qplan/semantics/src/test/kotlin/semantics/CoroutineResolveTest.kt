package semantics

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Schema
import model.SelectionForest
import model.SimpleEngineResult
import model.UncompletedPromiseException
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.registry.FieldResolver
import model.registry.ResolverRegistry
import model.sameCompletedResultAs
import model.testing.TestWorld
import semantics.resolver21.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoroutineResolveTest {
    @Test
    fun `installs every local promise before any local producer starts`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { first: Int!, second: Int! }",
                selectiveResolvers = false,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "first") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> Value.Int.of(1) },
                        schema.field("Query", "second") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> Value.Int.of(2) },
                    )
                },
            )
        val world = testWorld.assumptions
        val expectedKeys =
            setOf(
                world.schema.groundKey("Query", "first"),
                world.schema.groundKey("Query", "second"),
            )
        val registeredKeys = linkedSetOf<Value.GroundKey>()
        var producerStarts = 0
        val runtimeSupport =
            object : RuntimeSupport {
                context(world: Assumptions)
                override fun complete(selections: SelectionForest): SelectionForest {
                    producerStarts += 1
                    assertEquals(expectedKeys, registeredKeys)
                    return selections
                }

                override fun registerWriter(
                    cell: EngineResult.Cell,
                    writer: List<PathComponent>,
                ) {
                    registeredKeys += writer.last() as Value.GroundKey
                }
            }
        val selections =
            world.fragmentFrom("fragment ignored on Query { first second }").subselections

        runBlocking {
            context(world, runtimeSupport) {
                world.objectOf("Query").coroutineResolve(selections)
            }
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
                        schema.field("Query", "child") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("Child") },
                        schema.field("Child", "first") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ -> Value.Int.of(1) },
                        schema.field("Child", "second") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ -> Value.Int.of(2) },
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
        val expectedChildResultKeys =
            expectedChildKeys + world.schema.groundKey("Child", "__typename")
        var rootCell: EngineResult.Cell? = null
        val childRegistrations = linkedSetOf<Value.GroundKey>()
        val runtimeSupport =
            object : RuntimeSupport {
                context(world: Assumptions)
                override fun complete(selections: SelectionForest): SelectionForest = selections

                override fun registerWriter(
                    cell: EngineResult.Cell,
                    writer: List<PathComponent>,
                ) {
                    if (writer.size == 1) {
                        rootCell = cell
                    } else {
                        assertFailsWith<UncompletedPromiseException> {
                            assertNotNull(rootCell).getValue().get()
                        }
                        childRegistrations += writer.last() as Value.GroundKey
                    }
                }
            }
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { child { first second } }")
                .subselections

        val result =
            runBlocking {
                context(world, runtimeSupport) {
                    world.objectOf("Query").coroutineResolve(selections)
                }
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
                        schema.field("Query", "first") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { second }",
                                ),
                            ) { _, _ -> Value.Int.of(1) },
                        schema.field("Query", "second") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> Value.Int.of(2) },
                    )
                },
            )
        val first = testWorld.schema.objectField("Query", "first")
        val second = testWorld.schema.objectField("Query", "second")
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
                context(world) {
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
                        schema.field("Query", "failed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> throw failure },
                        schema.field("Query", "waiting") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { failed }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(
                                    schema.groundKey("Query", "failed"),
                                )
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            world.fragmentFrom("fragment ignored on Query { waiting }").subselections

        val thrown =
            assertFailsWith<IllegalStateException> {
                context(world) {
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
                    val items = schema.field("Query", "items")
                    mapOf(
                        items to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.OutputList.of(
                                    typeExpr =
                                        (items.typeExpr as model.TypeExpr.List<Schema.OutputType>)
                                            .elementType,
                                    values =
                                        listOf(
                                            schema.objectOf("Item"),
                                            schema.objectOf("Item"),
                                        ),
                                )
                            },
                        schema.field("Item", "value") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Item"),
                            ) { _, _ -> Value.Int.of(7) },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            world.fragmentFrom("fragment ignored on Query { items { value } }").subselections

        val result =
            context(world) {
                resolve(selections)
            }

        assertCompletedAndWriteOnce(result)
        assertTrue(result.sameCompletedResultAs(result))
    }
}

private fun assertCompletedAndWriteOnce(result: EngineResult?) {
    when (result) {
        null,
        ErrorEngineResult,
        is SimpleEngineResult,
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
                assertCompletedAndWriteOnce(value)
            }
    }
}

private fun registryOverride(
    delegate: ResolverRegistry,
    resolver: (Schema.ObjectField, ResolverRegistry) -> FieldResolver?,
): ResolverRegistry =
    object : ResolverRegistry {
        override fun resolveRootQuery(): Value.Object = delegate.resolveRootQuery()

        override fun contains(field: Schema.ObjectField): Boolean =
            resolver(field, delegate) != null

        override fun resolver(field: Schema.ObjectField): FieldResolver =
            resolver(field, delegate)
                ?: error(
                    "Missing overridden resolver: " +
                        "${field.containingType.typeName}.${field.fieldName}",
                )

        override fun mayDemandFrom(field: Schema.ObjectField): Set<Schema.ObjectField> =
            delegate.mayDemandFrom(field)
    }

private fun Schema.groundKey(
    typeName: String,
    fieldName: String,
): Value.GroundKey =
    Value.GroundKey.of(
        objectField(typeName, fieldName),
        emptyMap(),
    )

private fun Schema.ObjectField.groundKey(): Value.GroundKey =
    Value.GroundKey.of(this, emptyMap())
