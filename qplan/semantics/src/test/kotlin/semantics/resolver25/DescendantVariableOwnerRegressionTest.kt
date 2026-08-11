package semantics.resolver25

import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals

class DescendantVariableOwnerRegressionTest {
    @Test
    fun `binds a path variable owned by a resolver in a list element`() {
        val resultFragment =
            """
            fragment Result on Item {
              source
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
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
                      items: [Item!]!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val items = schema.objectField("Query", "items")
                    val itemType =
                        (items.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val consume = schema.objectField("Item", "consume")
                    val sourceKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "source"),
                            emptyMap(),
                        )
                    val fixedKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "fixed"),
                            emptyMap(),
                        )
                    mapOf(
                        items to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.OutputList.of(
                                    itemType,
                                    listOf(
                                        schema.objectOf("Item") {
                                            "source" setTo 3
                                            "fixed" setTo 10
                                        },
                                        schema.objectOf("Item") {
                                            "source" setTo 5
                                            "fixed" setTo 20
                                        },
                                    ),
                                )
                            },
                        consume to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Consume on Item { fixed }",
                                ),
                            ) { input, arguments ->
                                val value =
                                    arguments.fieldValues.getValue("value") as Value.Int
                                val fixed = input.fieldValues.getValue(fixedKey) as Value.Int
                                Value.Int.of(value.intValue + fixed.intValue)
                            },
                        schema.objectField("Item", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val source =
                                    input.fieldValues.getValue(sourceKey) as Value.Int
                                input.fieldValues.getValue(
                                    Value.GroundKey.of(
                                        consume,
                                        mapOf("value" to source.intValue),
                                    ),
                                )
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Item", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(
                                resultFragment,
                                listOf("source"),
                            ),
                    )
                },
            )
        val world = testWorld.assumptions

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(
                    world.fragmentFrom(
                        "fragment ignored on Query { items { result } }",
                    ).subselections,
                )
            }
        val items =
            resolved
                .getValue(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "items"),
                        emptyMap(),
                    ),
                ).get() as EngineResult.List
        assertEquals(
            listOf(Value.Int.of(13), Value.Int.of(25)),
            items.map { value ->
                val item = value as EngineResult.Object
                item
                    .getValue(
                        Value.GroundKey.of(
                            world.schema.objectField("Item", "result"),
                            emptyMap(),
                        ),
                    ).get()
            },
        )
    }
}
