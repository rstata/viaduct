package semantics.resolver25

import model.EngineResult
import model.Schema
import model.SelectionForest
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class NestedDescendantVariableUseRegressionTest {
    @Test
    fun `descendant owner contributes nested demand through an existing passive object`() {
        val resultFragment =
            """
            fragment Result on Item {
              source
              holder {
                consume(value: ${'$'}value)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Holder {
                      consume(value: Int!): Int!
                    }

                    type Item {
                      source: Int!
                      holder: Holder!
                      result: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val holderKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "holder"),
                            emptyMap(),
                        )
                    val consume = schema.objectField("Holder", "consume")
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "source" setTo 7
                                    "holder" setTo schema.objectOf("Holder")
                                }
                            },
                        schema.objectField("Item", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val holder =
                                    input.fieldValues.getValue(holderKey) as Value.Object
                                holder.fieldValues.getValue(
                                    Value.GroundKey.of(
                                        consume,
                                        mapOf("value" to 7),
                                    ),
                                )
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Holder")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
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
            )
        val world = testWorld.assumptions
        val itemKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "item"),
                emptyMap(),
            )
        val resultKey =
            Value.GroundKey.of(
                world.schema.objectField("Item", "result"),
                emptyMap(),
            )

        val resolved =
            context(world) {
                resolve(
                    world.fragmentFrom(
                        "fragment ignored on Query { item { result } }",
                    ).subselections,
                )
            }
        val item = resolved.getCell(itemKey).getValue().get() as EngineResult.Object

        assertEquals(Value.Int.of(7), item.getCell(resultKey).getValue().get())
    }

    @Test
    fun `late descendant demand merges into an existing exact resolver instance`() {
        var consumeApplications = 0
        var consumeDemand: SelectionForest? = null
        val resultFragment =
            """
            fragment Result on Item {
              source
              holder {
                consume(value: ${'$'}value) {
                  two
                }
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      one: Int!
                      two: Int!
                    }

                    type Holder {
                      consume(value: Int!): Payload!
                    }

                    type Item {
                      source: Int!
                      holder: Holder!
                      result: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val holderKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "holder"),
                            emptyMap(),
                        )
                    val consume = schema.objectField("Holder", "consume")
                    val consumeKey = Value.GroundKey.of(consume, mapOf("value" to 7))
                    val twoKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "two"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "source" setTo 7
                                    "holder" setTo schema.objectOf("Holder")
                                }
                            },
                        schema.objectField("Item", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val holder =
                                    input.fieldValues.getValue(holderKey) as Value.Object
                                val payload =
                                    holder.fieldValues.getValue(consumeKey) as Value.Object
                                payload.fieldValues.getValue(twoKey)
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Holder")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            }.observeApplications { _, _, demand ->
                                consumeApplications += 1
                                consumeDemand = demand
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
            )
        val world = testWorld.assumptions
        val itemKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "item"),
                emptyMap(),
            )
        val resultKey =
            Value.GroundKey.of(
                world.schema.objectField("Item", "result"),
                emptyMap(),
            )

        val observation =
            observeWithLifecycleValidation(
                world = world,
                root = world.objectOf("Query"),
                selections =
                    world.fragmentFrom(
                        """
                            fragment ignored on Query {
                              item {
                                holder {
                                  consume(value: 7) {
                                    one
                                  }
                                }
                                result
                              }
                            }
                        """.trimIndent(),
                    ).subselections,
            )
        val resolved = observation.result
        val signatures = observation.lifecycleEvents.resolver25StructuralSignatures()

        assertContains(
            signatures,
            Resolver25StructuralSignature.POSTLAUNCH_DEMAND_DEEPENING,
        )
        assertContains(
            signatures,
            Resolver25StructuralSignature.NESTED_VARIABLE_USE,
        )
        assertContains(
            signatures,
            Resolver25StructuralSignature.DESCENDANT_VARIABLE_OWNER,
        )
        val item = resolved.getCell(itemKey).getValue().get() as EngineResult.Object
        val payloadType = world.schema.type("Payload") as Schema.ObjectType

        assertEquals(Value.Int.of(5), item.getCell(resultKey).getValue().get())
        assertEquals(1, consumeApplications)
        assertEquals(
            setOf("one", "two"),
            context(world) {
                requireNotNull(consumeDemand)
                    .merge(payloadType)
                    .instantiateBindings()
                    .groundKeys()
                    .mapTo(linkedSetOf()) { key -> key.field.fieldName }
            },
        )
    }
}
