package semantics.contract

import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import kotlin.test.Test
import kotlin.test.assertEquals

interface RecursiveListFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `deepens an already launched recursive list with typename demand`() {
        val applications = linkedMapOf<String, Int>()
        val resultFragment =
            """
            fragment Result on Query {
              item {
                consume(value: ${'$'}argumentValue)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Item {
                      common: String!
                      children: [Item!]!
                      consume(value: Int!): Int!
                    }

                    type Query {
                      item: Item!
                      result(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val children = schema.objectField("Item", "children")
                    val childType =
                        (children.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                applications.merge("item", 1, Int::plus)
                                schema.objectOf("Item") {
                                    "common" setTo "root"
                                }
                            },
                        children to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Children on Item { common }",
                                ),
                            ) { _, _ ->
                                applications.merge("children", 1, Int::plus)
                                Value.OutputList.of(
                                    childType,
                                    listOf(
                                        schema.objectOf("Item") {
                                            "common" setTo "first"
                                        },
                                        schema.objectOf("Item") {
                                            "common" setTo "second"
                                        },
                                    ),
                                )
                            },
                        schema.objectField("Item", "consume") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment Consume on Item {
                                      children { __typename }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                applications.merge("consume", 1, Int::plus)
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                applications.merge("result", 1, Int::plus)
                                val item =
                                    input.fieldValues.getValue(
                                        Value.GroundKey.of(
                                            schema.objectField("Query", "item"),
                                            emptyMap(),
                                        ),
                                    ) as Value.Object
                                item.fieldValues.getValue(
                                    Value.GroundKey.of(
                                        schema.objectField("Item", "consume"),
                                        mapOf("value" to 7),
                                    ),
                                )
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "argumentValue") to
                            schema.fromArgument(result, "value"),
                    )
                },
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom(
                    """
                    fragment ignored on Query {
                      item {
                        children { common }
                      }
                      result(value: 7)
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            Value.Int.of(1),
            resolved.getCell(
                Value.GroundKey.of(
                    world.schema.objectField("Query", "result"),
                    mapOf("value" to 7),
                ),
            ).get(),
        )
        assertEquals(1, applications.getValue("children"))
        assertEquals(1, applications.getValue("consume"))
    }
}
