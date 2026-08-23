package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.requireQueryTypeDef
import model.requireObjectField
import model.requireField
import model.requireType
import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineErrorData
import model.ObjectEngineResult
import model.Arguments
import model.Selection
import model.emptyFragmentOf
import model.fetchBindings
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import model.toSelectionForest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import viaduct.engine.api.EngineObjectData

class SuccessorDemandTest {
    @Test
    fun `boundary demand retains resolver paths but omits passive leaves`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Box {
                      passive: String
                      computed: String
                    }

                    type Root {
                      source: String
                      box: Box
                      consumer: String
                    }

                    type Query {
                      root: Root
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "root") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                EngineErrorData.of()
                            },
                        schema.requireField("Root", "consumer") to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Root {
                                          source
                                          box {
                                            passive
                                            computed
                                            __typename
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                            ) { _, _ ->
                                "consumer"
                            },
                        schema.requireField("Box", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Box"),
                            ) { _, _ ->
                                "computed"
                            },
                    )
                },
            ).assumptions
        val schema = world.schema
        val selections =
            schema.fragmentFrom(
                "fragment ignored on Query { root { consumer } }",
            ).subselections

        val full =
            context(world) {
                selections.successorDemand().merge(schema.requireQueryTypeDef()).instantiateBindings()
            }[schema.key(schema.requireQueryTypeDef(), "root")]
                .subselections
        val boundaries =
            context(world) {
                selections.successorBoundaryDemand().merge(schema.requireQueryTypeDef()).instantiateBindings()
            }[schema.key(schema.requireQueryTypeDef(), "root")]
                .subselections
        val rootType = schema.requireType("Root") as ViaductSchema.Object
        val fullRoot = context(world) { full.merge(rootType).instantiateBindings() }
        val boundaryRoot = context(world) { boundaries.merge(rootType).instantiateBindings() }

        assertEquals(
            setOf("consumer", "source", "box"),
            fullRoot.groundKeys().fieldNames(),
        )
        assertEquals(
            setOf("consumer", "box"),
            boundaryRoot.groundKeys().fieldNames(),
        )

        val boxType = schema.requireType("Box") as ViaductSchema.Object
        val fullBox = fullRoot[schema.key(rootType, "box")]
        val boundaryBox = boundaryRoot[schema.key(rootType, "box")]
        assertEquals(
            setOf("passive", "computed", "V_A_typename"),
            context(world) {
                fullBox.subselections
                    .merge(boxType)
                    .instantiateBindings()
                    .groundKeys()
                    .fieldNames()
            },
        )
        assertEquals(
            setOf("computed", "V_A_typename"),
            context(world) {
                boundaryBox.subselections
                    .merge(boxType)
                    .instantiateBindings()
                    .groundKeys()
                    .fieldNames()
            },
        )
    }

    @Test
    fun `template deferral preserves fixed demand until a descendant occurrence can stamp variables`() {
        val resultFragment =
            """
            fragment Result on Item {
              source
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      source: Int!
                      fixed: Int!
                      consume(value: Int!): Int!
                      result: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                EngineErrorData.of()
                            },
                        schema.requireObjectField("Item", "consume") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Consume on Item { fixed }",
                                ),
                            ) { _, _ ->
                                EngineErrorData.of()
                            },
                        schema.requireObjectField("Item", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { _, _ ->
                                EngineErrorData.of()
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Item", "result")
                    mapOf(
                        Arguments.Variable.of(result, "value") to
                            schema.fromObjectField(resultFragment, listOf("source")),
                    )
                },
            ).assumptions
        val selections =
            world.schema
                .fragmentFrom("fragment ignored on Query { item { result } }")
                .subselections

        assertFailsWith<IllegalStateException> {
            context(world) {
                selections.successorDemand()
            }
        }
        val deferred =
            runBlocking {
                context(world) {
                    selections.fetchSuccessorDemandDeferringTemplates()
                }
            }
        val itemType = world.schema.requireType("Item") as ViaductSchema.Object
        val itemSelections =
            deferred
                .merge(world.schema.requireQueryTypeDef())
                .single()
                .subselections
                .merge(itemType)

        assertEquals(
            setOf("result", "source", "fixed"),
            itemSelections.groundKeys().fieldNames(),
        )
    }

    @Test
    fun `deferred successor closure coalesces stamped selections by fetched key`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      successor(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "successor") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                0
                            },
                    )
                },
            ).assumptions
        val successor = world.schema.requireObjectField("Query", "successor")
        val variable = Arguments.Variable.of(successor, "value")
        val first =
            variable.stamp(
                listOf(ObjectEngineResult.GroundKey.of(successor, mapOf("value" to 1))),
            )
        val second =
            variable.stamp(
                listOf(ObjectEngineResult.GroundKey.of(successor, mapOf("value" to 2))),
            )
        world.declareBinding(first)
        world.completeBinding(first, 7)
        world.declareBinding(second)
        world.completeBinding(second, 7)
        val firstKey =
            ObjectEngineResult.Key.of(
                successor,
                Arguments.of(successor, mapOf("value" to first)),
            )
        val selections =
            selectionForestOf(
                Selection.of(
                    key = firstKey,
                    possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                    subselections = selectionForestOf(),
                ),
                Selection.of(
                    key =
                        ObjectEngineResult.Key.of(
                            successor,
                            Arguments.of(successor, mapOf("value" to second)),
                        ),
                    possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                    subselections = selectionForestOf(),
                ),
            )

        val fetched =
            runBlocking {
                context(world) {
                    selections.fetchSuccessorDemandDeferringTemplates()
                }
            }
        val reversed =
            runBlocking {
                context(world) {
                    val selectionOccurrences = mutableListOf<Selection>()
                    selections.forEach(selectionOccurrences::add)
                    selectionOccurrences
                        .asReversed()
                        .toSelectionForest()
                        .fetchSuccessorDemandDeferringTemplates()
                }
            }
        val expectedKey =
            ObjectEngineResult.GroundKey.of(successor, mapOf("value" to 7))
        assertEquals(expectedKey, fetched.single().key)
        assertEquals(expectedKey, reversed.single().key)
        assertEquals(
            setOf(expectedKey),
            fetched.merge(world.schema.requireQueryTypeDef()).groundKeys(),
        )

        val marked =
            runBlocking {
                context(world) {
                    selectionForestOf(
                        Selection.of(
                            key =
                                ObjectEngineResult.VariableKey.of(
                                    key = firstKey,
                                    variableDefinedByThisKey = first,
                                ),
                            possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                            subselections = selectionForestOf(),
                        ),
                    ).fetchSuccessorDemandDeferringTemplates()
                }
            }
        val markedKey = assertIs<ObjectEngineResult.VariableKey>(marked.single().key)
        assertEquals(first, markedKey.variableDefinedByThisKey)
        assertEquals(expectedKey.arguments, markedKey.arguments)
    }

    @Test
    fun `deferred successor closure expands each grounded resolver key once`() {
        val layers = 10
        val layerFields =
            (0..layers).flatMap { layer ->
                listOf("a$layer", "b$layer")
            }
        val originalWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      ${layerFields.joinToString("\n  ") { field -> "$field: Int!" }}
                      sink: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    buildMap {
                        put(
                            schema.requireObjectField("Query", "item"),
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                EngineErrorData.of()
                            },
                        )
                        layerFields.forEach { field ->
                            val layer = field.drop(1).toInt()
                            val objectFragment =
                                if (layer == 0) {
                                    schema.emptyFragmentOf("Item")
                                } else {
                                    schema.fragmentFrom(
                                        """
                                        fragment Layer on Item {
                                          a${layer - 1}
                                          b${layer - 1}
                                        }
                                        """.trimIndent(),
                                    )
                                }
                            put(
                                schema.requireObjectField("Item", field),
                                fieldResolverOf(objectFragment) { _, _ ->
                                    0
                                },
                            )
                        }
                        put(
                            schema.requireObjectField("Item", "sink"),
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment Sink on Item {
                                      a$layers
                                      b$layers
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                0
                            },
                        )
                    }
                },
            ).assumptions
        val registry = CountingResolverRegistry(originalWorld.resolverRegistry)
        val world =
            Assumptions.of(
                schema = originalWorld.schema,
                resolverRegistry = registry,
            )
        val selections =
            world.schema
                .fragmentFrom("fragment Demand on Item { sink }")
                .subselections

        runBlocking {
            context(world) {
                selections.fetchSuccessorDemandDeferringTemplates()
            }
        }

        assertEquals(layerFields.size + 1, registry.resolverLookups)
    }

    private fun Set<ObjectEngineResult.GroundKey>.fieldNames(): Set<String> =
        mapTo(mutableSetOf()) { key -> key.field.name }

    private fun ViaductSchema.key(
        type: ViaductSchema.Object,
        fieldName: String,
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(
            field = requireObjectField(type.name, fieldName),
            arguments = emptyMap(),
        )
}

private class CountingResolverRegistry(
    private val delegate: ResolverRegistry,
) : ResolverRegistry {
    var resolverLookups: Int = 0
        private set

    override fun createRootQueryInput(): EngineObjectData.Sync = delegate.createRootQueryInput()

    override fun contains(field: ViaductSchema.ObjectField): Boolean = field in delegate

    override fun resolver(field: ViaductSchema.ObjectField): FieldResolver {
        resolverLookups += 1
        return delegate.resolver(field)
    }

    override fun mayDemandFrom(field: ViaductSchema.ObjectField): Set<ViaductSchema.ObjectField> =
        delegate.mayDemandFrom(field)
}
