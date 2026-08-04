package semantics.resolver04

import model.Schema
import model.TypeExpr
import model.Value
import model.VariableCoordinate
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverPropertyTest {
    @Test
    fun `randomized variables cover nested list abstract nullable and multiple bindings`() {
        val random = Random(4202404)

        repeat(50) { iteration ->
            val n = random.nextInt(1, 100)
            val numbers = List(random.nextInt(0, 5)) { random.nextInt(1, 30) }
            val optional = random.nextInt(1, 100).takeIf { iteration % 2 == 0 }
            val applications = linkedMapOf<String, Int>()
            fun applied(name: String) {
                applications[name] = applications.getOrDefault(name, 0) + 1
            }

            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA,
                    fieldResolvers = { schema ->
                        val consumeKey =
                            Value.Key.of(
                                schema.field("Query", "consume"),
                                mapOf(
                                    "config" to
                                        mapOf(
                                            "nested" to
                                                mapOf(
                                                    "n" to n,
                                                    "items" to listOf(n, 3),
                                                ),
                                            "optional" to optional,
                                        ),
                                ),
                            )
                        val consumeListKey =
                            Value.Key.of(
                                schema.field("Query", "consumeList"),
                                mapOf("values" to numbers),
                            )
                        val numbersType =
                            (
                                schema.field("Query", "numbers").typeExpr
                                    as TypeExpr.List<Schema.OutputType>
                            ).elementType
                        mapOf(
                            schema.field("Query", "result") to
                                model.testing.fieldResolverOf(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          consume(
                                            config: {
                                              nested: {
                                                n: ${'$'}n
                                                items: [${'$'}n, 3]
                                              }
                                              optional: ${'$'}optional
                                            }
                                          )
                                          consumeList(values: ${'$'}values)
                                          source {
                                            common
                                          }
                                          numbers
                                          nullableBox {
                                            value
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ) { input, _ ->
                                    applied("result")
                                    val consumed =
                                        input.fieldValues.getValue(consumeKey) as Value.Int
                                    val consumedList =
                                        input.fieldValues.getValue(consumeListKey) as Value.Int
                                    Value.Int.of(consumed.intValue + consumedList.intValue)
                                },
                            schema.field("Query", "consume") to
                                model.testing.fieldResolverOf(
                                    schema.emptyFragmentOf("Query"),
                                ) { _, arguments ->
                                    applied("consume")
                                    val config =
                                        arguments.fieldValues.getValue("config") as Value.InputObject
                                    val nested =
                                        config.fieldValues.getValue("nested") as Value.InputObject
                                    val nestedN =
                                        nested.fieldValues.getValue("n") as Value.Int
                                    val items =
                                        nested.fieldValues.getValue("items") as Value.InputList
                                    val itemSum =
                                        items.values.sumOf { (it as Value.Int).intValue }
                                    val optionalValue =
                                        (config.fieldValues.getValue("optional") as? Value.Int)
                                            ?.intValue
                                            ?: 0
                                    Value.Int.of(nestedN.intValue + itemSum + optionalValue)
                                },
                            schema.field("Query", "consumeList") to
                                model.testing.fieldResolverOf(
                                    schema.emptyFragmentOf("Query"),
                                ) { _, arguments ->
                                    applied("consumeList")
                                    val values =
                                        arguments.fieldValues.getValue("values") as Value.InputList
                                    Value.Int.of(
                                        values.values.sumOf { (it as Value.Int).intValue },
                                    )
                                },
                            schema.field("Query", "source") to
                                model.testing.fieldResolverOf(
                                    schema.emptyFragmentOf("Query"),
                                ) { _, _ ->
                                    applied("source")
                                    schema.objectOf("ConcreteSource") {
                                        "common" setTo n
                                        "extra" setTo n * 2
                                    }
                                },
                            schema.field("Query", "numbers") to
                                model.testing.fieldResolverOf(
                                    schema.emptyFragmentOf("Query"),
                                ) { _, _ ->
                                    applied("numbers")
                                    Value.OutputList.of(
                                        numbersType,
                                        numbers.map(Value.Int::of),
                                    )
                                },
                            schema.field("Query", "nullableBox") to
                                model.testing.fieldResolverOf(
                                    schema.emptyFragmentOf("Query"),
                                ) { _, _ ->
                                    applied("nullableBox")
                                    optional?.let { value ->
                                        schema.objectOf("Box") {
                                            "value" setTo value
                                        }
                                    }
                                },
                        )
                    },
                    variableProviders = { schema ->
                        val owner = schema.field("Query", "result") as Schema.ObjectField
                        mapOf(
                            VariableCoordinate.of(owner, Value.Variable.of("n")) to
                                schema.fragmentFrom(
                                    "fragment ignored on Query { source { common } }",
                                ).subselections.single(),
                            VariableCoordinate.of(owner, Value.Variable.of("values")) to
                                schema.fragmentFrom(
                                    "fragment ignored on Query { numbers }",
                                ).subselections.single(),
                            VariableCoordinate.of(owner, Value.Variable.of("optional")) to
                                schema.fragmentFrom(
                                    "fragment ignored on Query { nullableBox { value } }",
                                ).subselections.single(),
                        )
                    },
                )
            val world = testWorld.assumptions
            val fragment = world.fragmentFrom("fragment ignored on Query { result }")
            val result =
                context(world) {
                    world.objectOf("Query").resolve(fragment.subselections)
                }
            val expected = 2 * n + 3 + (optional ?: 0) + numbers.sum()

            assertEquals(
                Value.Int.of(expected),
                result.fetch(
                    Value.Key.of(world.schema.field("Query", "result"), emptyMap()),
                ).value,
            )
            assertEquals(
                mapOf(
                    Value.Variable.of("n") to Value.Int.of(n),
                    Value.Variable.of("values") to
                        Value.InputList.of(
                            TypeExpr.Named.of(Schema.IntType, isNullable = false),
                            numbers.map(Value.Int::of),
                        ),
                    Value.Variable.of("optional") to optional?.let(Value.Int::of),
                ),
                result.variableValues,
            )
            assertEquals(
                mapOf(
                    "source" to 1,
                    "numbers" to 1,
                    "nullableBox" to 1,
                    "consume" to 1,
                    "consumeList" to 1,
                    "result" to 1,
                ),
                applications,
            )
            assertTrue(context(world) { result.correctResolution(fragment) })
        }
    }

    private companion object {
        val SCHEMA =
            """
            input NestedInput {
              n: Int!
              items: [Int!]!
            }

            input ConfigInput {
              nested: NestedInput!
              optional: Int
            }

            interface Source {
              common: Int!
            }

            type ConcreteSource implements Source {
              common: Int!
              extra: Int!
            }

            type Box {
              value: Int
            }

            type Query {
              result: Int!
              consume(config: ConfigInput!): Int!
              consumeList(values: [Int!]!): Int!
              source: Source!
              numbers: [Int!]!
              nullableBox: Box
            }
            """.trimIndent()
    }
}
