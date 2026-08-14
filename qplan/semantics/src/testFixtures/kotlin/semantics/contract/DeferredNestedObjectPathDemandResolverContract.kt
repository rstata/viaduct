package semantics.contract

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals

interface DeferredNestedObjectPathDemandResolverContract : ResolverContract {
    @Test
    fun `retains potential demand beyond a deferred nested resolver`() {
        val driverFragment =
            """
            fragment Driver on Query {
              source
              item {
                result(value: ${'$'}sourceValue)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Item {
                      step: Item!
                      passive: Int!
                      result(value: Int!): Int!
                    }

                    type Query {
                      item: Item!
                      source: Int!
                      driver: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val itemKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "item"),
                            emptyMap(),
                        )
                    val resultKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "result"),
                            mapOf("value" to 7),
                        )
                    val stepKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "step"),
                            emptyMap(),
                        )
                    val passiveKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "passive"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item")
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(7)
                            },
                        schema.objectField("Query", "driver") to
                            fieldResolverOf(schema.fragmentFrom(driverFragment)) { input, _ ->
                                val item = input.fieldValues.getValue(itemKey) as Value.Object
                                item.fieldValues.getValue(resultKey)
                            },
                        schema.objectField("Item", "step") to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ ->
                                schema.objectOf("Item") {
                                    "passive" setTo 7
                                }
                            },
                        schema.objectField("Item", "result") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Result on Item { step { passive } }",
                                ),
                            ) { input, _ ->
                                val step = input.fieldValues.getValue(stepKey) as Value.Object
                                step.fieldValues.getValue(passiveKey)
                            },
                    )
                },
                variableProviders = { schema ->
                    val driver = schema.objectField("Query", "driver")
                    mapOf(
                        Value.Variable.of(driver, "sourceValue") to
                            schema.fromObjectField(driverFragment, listOf("source")),
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
                        step { __typename }
                      }
                      driver
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            Value.Int.of(7),
            resolved
                .getCell(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "driver"),
                        emptyMap(),
                    ),
                ).get(),
        )
    }
}
