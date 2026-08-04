package semantics.resolver03

import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Seeded program-level properties over exact occurrences, arguments, and application counts.
 *
 * Keep broad repeated constructions here; deterministic value-shape cases have their own suite.
 */
class ResolverPropertyTest {
    @Test
    fun `randomized programs preserve exact occurrences demand and application count`() {
        val random = Random(3202404)
        var sawEvenMetric = false
        var sawOddMetric = false

        repeat(50) {
            val seed = random.nextInt(1, 100)
            val firstFactor = random.nextInt(2, 20)
            val secondFactor = firstFactor + 1
            var itemApplications = 0
            var itemsApplications = 0
            var computedApplications = 0
            var evenMetricApplications = 0
            var oddMetricApplications = 0

            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA,
                    fieldResolvers = { schema ->
                        val seedKey = Value.Key.of(schema.field("Item", "seed"), emptyMap())
                        val itemsField = schema.field("Query", "items")
                        val itemsType = itemsField.typeExpr as TypeExpr.List<Schema.OutputType>

                        fun item(value: Int): Value.Object =
                            schema.objectOf("Item") {
                                "seed" setTo value
                                "child" setTo
                                    schema.objectOf("Item") {
                                        "seed" setTo value + 1
                                    }
                                "metric" setTo
                                    if (value % 2 == 0) {
                                        schema.objectOf("EvenMetric") {
                                            "common" setTo value
                                        }
                                    } else {
                                        schema.objectOf("OddMetric") {
                                            "common" setTo value
                                        }
                                    }
                            }

                        mapOf(
                            schema.field("Query", "item") to
                                model.testing.fieldResolverOf(
                                    schema.emptyFragmentOf("Query"),
                                ) { _, arguments ->
                                    itemApplications += 1
                                    val value =
                                        arguments.fieldValues.getValue("seed") as Value.Int
                                    item(value.intValue)
                                },
                            itemsField to
                                model.testing.fieldResolverOf(
                                    schema.emptyFragmentOf("Query"),
                                ) { _, arguments ->
                                    itemsApplications += 1
                                    val value =
                                        arguments.fieldValues.getValue("seed") as Value.Int
                                    Value.OutputList.of(
                                        itemsType.elementType,
                                        listOf(item(value.intValue), item(value.intValue + 10)),
                                    )
                                },
                            schema.field("Item", "computed") to
                                model.testing.fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment ignored on Item { seed }",
                                    ),
                                ) { input, arguments ->
                                    computedApplications += 1
                                    val inputSeed =
                                        input.fieldValues.getValue(seedKey) as Value.Int
                                    val factor =
                                        arguments.fieldValues.getValue("factor") as Value.Int
                                    Value.Int.of(inputSeed.intValue * factor.intValue)
                                },
                            schema.field("EvenMetric", "even") to
                                model.testing.fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment ignored on EvenMetric { common }",
                                    ),
                                ) { input, _ ->
                                    evenMetricApplications += 1
                                    val common =
                                        input.fieldValues.getValue(
                                            Value.Key.of(
                                                schema.field("EvenMetric", "common"),
                                                emptyMap(),
                                            ),
                                        ) as Value.Int
                                    Value.Int.of(common.intValue * 2)
                                },
                            schema.field("OddMetric", "odd") to
                                model.testing.fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment ignored on OddMetric { common }",
                                    ),
                                ) { input, _ ->
                                    oddMetricApplications += 1
                                    val common =
                                        input.fieldValues.getValue(
                                            Value.Key.of(
                                                schema.field("OddMetric", "common"),
                                                emptyMap(),
                                            ),
                                        ) as Value.Int
                                    Value.Int.of(common.intValue * 3)
                                },
                            schema.field("Query", "dead") to
                                model.testing.fieldResolverOf(
                                    schema.emptyFragmentOf("Query"),
                                ) { _, _ ->
                                    error("Unselected resolver was applied")
                                },
                        )
                    },
                )
            val world = testWorld.assumptions
            val source =
                query(
                    seed = seed,
                    firstFactor = firstFactor,
                    secondFactor = secondFactor,
                    reversed = false,
                )
            val reversedSource =
                query(
                    seed = seed,
                    firstFactor = firstFactor,
                    secondFactor = secondFactor,
                    reversed = true,
                )

            val fragment = world.fragmentFrom(source)
            val result =
                context(world) {
                    world.objectOf("Query").resolve(fragment.subselections)
                }

            assertEquals(1, itemApplications)
            assertEquals(1, itemsApplications)
            assertEquals(5, computedApplications)
            assertEquals(if (seed % 2 == 0) 1 else 0, evenMetricApplications)
            assertEquals(if (seed % 2 != 0) 1 else 0, oddMetricApplications)

            val item =
                assertIs<EngineResult.Object>(
                    result.fetch(
                        Value.Key.of(world.schema.field("Query", "item"), mapOf("seed" to seed)),
                    ).value,
                )
            assertEquals(
                Value.Int.of(seed * firstFactor),
                item.fetch(
                    Value.Key.of(
                        world.schema.field("Item", "computed"),
                        mapOf("factor" to firstFactor),
                    ),
                ).value,
            )
            assertEquals(
                Value.Int.of(seed * secondFactor),
                item.fetch(
                    Value.Key.of(
                        world.schema.field("Item", "computed"),
                        mapOf("factor" to secondFactor),
                    ),
                ).value,
            )
            val child =
                assertIs<EngineResult.Object>(
                    item.fetch(
                        Value.Key.of(world.schema.field("Item", "child"), emptyMap()),
                    ).value,
                )
            assertEquals(
                Value.Int.of((seed + 1) * firstFactor),
                child.fetch(
                    Value.Key.of(
                        world.schema.field("Item", "computed"),
                        mapOf("factor" to firstFactor),
                    ),
                ).value,
            )
            val metric =
                assertIs<EngineResult.Object>(
                    item.fetch(
                        Value.Key.of(world.schema.field("Item", "metric"), emptyMap()),
                    ).value,
                )
            assertEquals(
                Value.Int.of(seed),
                metric.fetch(
                    Value.Key.of(world.schema.field(metric.type.typeName, "common"), emptyMap()),
                ).value,
            )
            val concreteMetricField = if (seed % 2 == 0) "even" else "odd"
            val concreteMetricValue = if (seed % 2 == 0) seed * 2 else seed * 3
            if (seed % 2 == 0) sawEvenMetric = true else sawOddMetric = true
            assertEquals(
                Value.Int.of(concreteMetricValue),
                metric.fetch(
                    Value.Key.of(
                        world.schema.field(metric.type.typeName, concreteMetricField),
                        emptyMap(),
                    ),
                ).value,
            )

            val items =
                assertIs<EngineResult.List>(
                    result.fetch(
                        Value.Key.of(
                            world.schema.field("Query", "items"),
                            mapOf("seed" to seed),
                        ),
                    ).value,
                )
            assertEquals(2, items.size)
            listOf(seed, seed + 10).forEachIndexed { index, itemSeed ->
                val listItem = assertIs<EngineResult.Object>(items[index].value)
                assertEquals(
                    Value.Int.of(itemSeed * firstFactor),
                    listItem.fetch(
                        Value.Key.of(
                            world.schema.field("Item", "computed"),
                            mapOf("factor" to firstFactor),
                        ),
                    ).value,
                )
            }
            assertTrue(context(world) { result.correctResolution(fragment) })

            itemApplications = 0
            itemsApplications = 0
            computedApplications = 0
            evenMetricApplications = 0
            oddMetricApplications = 0
            val reversedFragment = world.fragmentFrom(reversedSource)
            val reversedResult =
                context(world) {
                    world.objectOf("Query").resolve(reversedFragment.subselections)
                }

            assertEquals(result, reversedResult)
            assertEquals(1, itemApplications)
            assertEquals(1, itemsApplications)
            assertEquals(5, computedApplications)
            assertEquals(if (seed % 2 == 0) 1 else 0, evenMetricApplications)
            assertEquals(if (seed % 2 != 0) 1 else 0, oddMetricApplications)
        }
        assertTrue(sawEvenMetric)
        assertTrue(sawOddMetric)
    }

    private fun query(
        seed: Int,
        firstFactor: Int,
        secondFactor: Int,
        reversed: Boolean,
    ): String {
        val selections =
            listOf(
                """
                left: item(seed: $seed) {
                  computed(factor: $firstFactor)
                  metric {
                    common
                    ... on EvenMetric { even }
                    ... on OddMetric { odd }
                  }
                }
                """.trimIndent(),
                """
                right: item(seed: $seed) {
                  computed(factor: $secondFactor)
                  child {
                    computed(factor: $firstFactor)
                  }
                }
                """.trimIndent(),
                """
                items(seed: $seed) {
                  computed(factor: $firstFactor)
                }
                """.trimIndent(),
            ).let { if (reversed) it.reversed() else it }
        return buildString {
            appendLine("fragment ignored on Query {")
            selections.forEach { appendLine(it.prependIndent("  ")) }
            append("}")
        }
    }

    private companion object {
        val SCHEMA =
            """
            interface Metric {
              common: Int!
            }

            type EvenMetric implements Metric {
              common: Int!
              even: Int!
            }

            type OddMetric implements Metric {
              common: Int!
              odd: Int!
            }

            type Item {
              seed: Int!
              computed(factor: Int!): Int!
              child: Item
              metric: Metric!
            }

            type Query {
              item(seed: Int!): Item!
              items(seed: Int!): [Item!]!
              dead: Int!
            }
            """.trimIndent()
    }
}
