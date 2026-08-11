package model.registry

import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SuccessorDemandTest {
    @Test
    fun `boundary demand retains behavioral paths but omits passive leaves`() {
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
                        schema.field("Query", "root") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.Error
                            },
                        schema.field("Root", "consumer") to
                            model.testing.fieldResolverOf(
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
                                Value.String.of("consumer")
                            },
                        schema.field("Box", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Box"),
                            ) { _, _ ->
                                Value.String.of("computed")
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
                selections.successorDemand().merge(schema.query).instantiateBindings()
            }[schema.key(schema.query, "root")]
                .subselections
        val boundaries =
            context(world) {
                selections.successorBoundaryDemand().merge(schema.query).instantiateBindings()
            }[schema.key(schema.query, "root")]
                .subselections
        val rootType = schema.type("Root") as Schema.ObjectType
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

        val boxType = schema.type("Box") as Schema.ObjectType
        val fullBox = fullRoot[schema.key(rootType, "box")]
        val boundaryBox = boundaryRoot[schema.key(rootType, "box")]
        assertEquals(
            setOf("passive", "computed", "__typename"),
            context(world) {
                fullBox.subselections
                    .merge(boxType)
                    .instantiateBindings()
                    .groundKeys()
                    .fieldNames()
            },
        )
        assertEquals(
            setOf("computed", "__typename"),
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
                      consume(value: Int!): Int!
                      result: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Error
                            },
                        schema.objectField("Item", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ ->
                                Value.Error
                            },
                        schema.objectField("Item", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { _, _ ->
                                Value.Error
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Item", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
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
            context(world) {
                selections.successorDemandDeferringTemplates()
            }
        val itemType = world.schema.type("Item") as Schema.ObjectType
        val itemSelections =
            deferred
                .merge(world.schema.query)
                .single()
                .subselections
                .merge(itemType)

        assertEquals(
            setOf("result", "source"),
            itemSelections.groundKeys().fieldNames(),
        )
    }

    private fun Set<Value.GroundKey>.fieldNames(): Set<String> =
        mapTo(mutableSetOf()) { key -> key.field.fieldName }

    private fun Schema.key(
        type: Schema.ObjectType,
        fieldName: String,
    ): Value.GroundKey =
        Value.GroundKey.of(
            field = objectField(type.typeName, fieldName),
            arguments = emptyMap(),
        )
}
