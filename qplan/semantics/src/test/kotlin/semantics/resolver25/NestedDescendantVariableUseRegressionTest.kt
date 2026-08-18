package semantics.resolver25

import model.EngineResult
import model.IntEngineResult
import model.ObjectEngineResult
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
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(result: {source: 7, holder: {}})
                    }

                    type Item {
                      source: Int!
                      holder: Holder!
                      result: Int!
                        @resolver(
                          of: "source holder { consume(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(holder.consume)"
                        )
                    }

                    type Holder {
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val itemKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "item"),
                emptyMap(),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
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
        val item = resolved.getCell(itemKey).getValue().get() as ObjectEngineResult

        assertEquals(IntEngineResult.of(7), item.getCell(resultKey).getValue().get())
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
                        ObjectEngineResult.GroundKey.of(
                            schema.objectField("Item", "holder"),
                            emptyMap(),
                        )
                    val consume = schema.objectField("Holder", "consume")
                    val consumeKey = ObjectEngineResult.GroundKey.of(consume, mapOf("value" to 7))
                    val twoKey =
                        ObjectEngineResult.GroundKey.of(
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
                                    input.fieldValues.getValue(holderKey.field.fieldName)
                                        as Value.Object
                                val payload =
                                    holder.fieldValues.getValue(consumeKey.field.fieldName)
                                        as Value.Object
                                payload.fieldValues.getValue(twoKey.field.fieldName)
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
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "item"),
                emptyMap(),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
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
        val item = resolved.getCell(itemKey).getValue().get() as ObjectEngineResult
        val payloadType = world.schema.type("Payload") as Schema.ObjectType

        assertEquals(IntEngineResult.of(5), item.getCell(resultKey).getValue().get())
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
