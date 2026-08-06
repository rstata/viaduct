package semantics.resolver03

import model.EngineResult
import model.Fragment
import model.Schema
import model.Selection
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.selectionForestOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import semantics.resolver01.resolve as resolveWithResolver01
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reduced one-off regressions that do not yet form a larger Resolver03 semantic theme.
 *
 * Promote related cases to a dedicated themed suite once a common contract emerges.
 */
class ResolverRegressionTest {
    @Test
    fun `error-valued resolver argument does not import its transitive demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA,
                fieldResolvers = { schema ->
                    val parsedDependency =
                        schema
                            .fragmentFrom(
                                "fragment ignored on Query { dependency(arg: 1) }",
                            ).subselections
                            .single()
                    val errorDependency =
                        Selection.of(
                            key =
                                Value.Key.of(
                                    parsedDependency.key.field,
                                    mapOf("arg" to Value.Error),
                                ),
                            possibleTypes = parsedDependency.possibleTypes,
                            subselections = parsedDependency.subselections,
                        )
                    mapOf(
                        schema.field("Query", "container") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Container")
                            },
                        schema.field("Query", "dependency") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { container { helper } }",
                                ),
                            ) { _, _ ->
                                error("An error-bearing resolver must not be applied")
                            },
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                Fragment.of(
                                    schema.query,
                                    selectionForestOf(errorDependency),
                                ),
                            ) { _, _ ->
                                Value.Int.of(1)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { result }")

        val expected =
            context(world) {
                world.objectOf("Query").resolveWithResolver01(fragment.subselections)
            }
        assertTrue(context(world) { expected.correctResolution(fragment) })

        val actual =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertEquals(expected, actual)
        assertTrue(context(world) { actual.correctResolution(fragment) })
    }

    @Test
    fun `object outputs vary by input and arguments at equal-key list occurrences`() {
        val productApplications = mutableListOf<Pair<Int, Int>>()
        var computedApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Product {
                      label: String!
                    }

                    type EvenProduct implements Product {
                      label: String!
                      base: Int!
                      computed: Int!
                    }

                    type OddProduct implements Product {
                      label: String!
                      base: Int!
                      computed: Int!
                    }

                    type Group {
                      seed: Int!
                      product(factor: Int!): Product!
                    }

                    type Query {
                      groups: [Group!]!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val groupsField = schema.field("Query", "groups")
                    val groupElementType =
                        (groupsField.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val seedKey = Value.ObjectKey.of(schema.objectField("Group", "seed"), emptyMap())

                    fun computedResolver(typeName: String) =
                        model.testing.fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ignored on $typeName { base }",
                            ),
                        ) { input, _ ->
                            computedApplications += 1
                            val base =
                                input.fieldValues.getValue(
                                    Value.ObjectKey.of(schema.objectField(typeName, "base"), emptyMap()),
                                ) as Value.Int
                            Value.Int.of(
                                if (typeName == "EvenProduct") {
                                    base.intValue * 10
                                } else {
                                    base.intValue * 100
                                },
                            )
                        }

                    mapOf(
                        groupsField to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.OutputList.of(
                                    groupElementType,
                                    listOf(
                                        schema.objectOf("Group") {
                                            "seed" setTo 1
                                        },
                                        schema.objectOf("Group") {
                                            "seed" setTo 2
                                        },
                                    ),
                                )
                            },
                        schema.field("Group", "product") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Group { seed }",
                                ),
                            ) { input, arguments ->
                                val seed =
                                    input.fieldValues.getValue(seedKey) as Value.Int
                                val factor =
                                    arguments.fieldValues.getValue("factor") as Value.Int
                                productApplications += seed.intValue to factor.intValue
                                val base = seed.intValue * factor.intValue
                                val typeName =
                                    if (base % 2 == 0) "EvenProduct" else "OddProduct"
                                schema.objectOf(typeName) {
                                    "label" setTo "${seed.intValue}x${factor.intValue}"
                                    "base" setTo base
                                }
                            },
                        schema.field("EvenProduct", "computed") to
                            computedResolver("EvenProduct"),
                        schema.field("OddProduct", "computed") to
                            computedResolver("OddProduct"),
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  groups {
                    byTwo: product(factor: 2) {
                      label
                      ... on EvenProduct {
                        computed
                      }
                      ... on OddProduct {
                        computed
                      }
                    }
                    byThree: product(factor: 3) {
                      label
                      ... on EvenProduct {
                        computed
                      }
                      ... on OddProduct {
                        computed
                      }
                    }
                  }
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        val groups =
            assertIs<EngineResult.List>(
                result.fetch(Value.ObjectKey.of(schema.objectField("Query", "groups"), emptyMap())).value,
            )
        listOf(1, 2).forEachIndexed { index, seed ->
            val group = assertIs<EngineResult.Object>(groups[index].value)
            listOf(2, 3).forEach { factor ->
                val product =
                    assertIs<EngineResult.Object>(
                        group.fetch(
                            Value.ObjectKey.of(
                                schema.objectField("Group", "product"),
                                mapOf("factor" to factor),
                            ),
                        ).value,
                    )
                val base = seed * factor
                val expectedType = if (base % 2 == 0) "EvenProduct" else "OddProduct"
                assertEquals(expectedType, product.type.typeName)
                assertEquals(
                    Value.String.of("${seed}x$factor"),
                    product.fetch(
                        Value.ObjectKey.of(schema.objectField(expectedType, "label"), emptyMap()),
                    ).value,
                )
                assertEquals(
                    Value.Int.of(base * if (expectedType == "EvenProduct") 10 else 100),
                    product.fetch(
                        Value.ObjectKey.of(schema.objectField(expectedType, "computed"), emptyMap()),
                    ).value,
                )
            }
        }
        assertEquals(
            mapOf(
                (1 to 2) to 1,
                (1 to 3) to 1,
                (2 to 2) to 1,
                (2 to 3) to 1,
            ),
            productApplications.groupingBy { application -> application }.eachCount(),
        )
        assertEquals(4, computedApplications)
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `list outputs vary in shape by input and arguments at equal-key list occurrences`() {
        val entriesApplications = mutableListOf<Pair<Int, Int>>()
        var renderedApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Entry {
                      raw: Int!
                      rendered: String!
                    }

                    type Group {
                      seed: Int!
                      entries(count: Int!): [Entry!]!
                    }

                    type Query {
                      groups: [Group!]!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val groupsField = schema.field("Query", "groups")
                    val groupElementType =
                        (groupsField.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val entriesField = schema.field("Group", "entries")
                    val entryElementType =
                        (entriesField.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val seedKey = Value.ObjectKey.of(schema.objectField("Group", "seed"), emptyMap())
                    val rawKey = Value.ObjectKey.of(schema.objectField("Entry", "raw"), emptyMap())

                    mapOf(
                        groupsField to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.OutputList.of(
                                    groupElementType,
                                    listOf(
                                        schema.objectOf("Group") {
                                            "seed" setTo 10
                                        },
                                        schema.objectOf("Group") {
                                            "seed" setTo 20
                                        },
                                    ),
                                )
                            },
                        entriesField to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Group { seed }",
                                ),
                            ) { input, arguments ->
                                val seed =
                                    input.fieldValues.getValue(seedKey) as Value.Int
                                val count =
                                    arguments.fieldValues.getValue("count") as Value.Int
                                entriesApplications += seed.intValue to count.intValue
                                Value.OutputList.of(
                                    entryElementType,
                                    (0 until count.intValue).map { offset ->
                                        schema.objectOf("Entry") {
                                            "raw" setTo seed.intValue + offset
                                        }
                                    },
                                )
                            },
                        schema.field("Entry", "rendered") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Entry { raw }",
                                ),
                            ) { input, _ ->
                                renderedApplications += 1
                                val raw = input.fieldValues.getValue(rawKey) as Value.Int
                                Value.String.of("entry-${raw.intValue}")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  groups {
                    one: entries(count: 1) {
                      rendered
                    }
                    three: entries(count: 3) {
                      rendered
                    }
                  }
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        val groups =
            assertIs<EngineResult.List>(
                result.fetch(Value.ObjectKey.of(schema.objectField("Query", "groups"), emptyMap())).value,
            )
        listOf(10, 20).forEachIndexed { groupIndex, seed ->
            val group = assertIs<EngineResult.Object>(groups[groupIndex].value)
            listOf(1, 3).forEach { count ->
                val entries =
                    assertIs<EngineResult.List>(
                        group.fetch(
                            Value.ObjectKey.of(
                                schema.objectField("Group", "entries"),
                                mapOf("count" to count),
                            ),
                        ).value,
                    )
                assertEquals(count, entries.size)
                entries.forEachIndexed { offset, cell ->
                    val entry = assertIs<EngineResult.Object>(cell.value)
                    assertEquals(
                        Value.String.of("entry-${seed + offset}"),
                        entry.fetch(
                            Value.ObjectKey.of(schema.objectField("Entry", "rendered"), emptyMap()),
                        ).value,
                    )
                }
            }
        }
        assertEquals(
            mapOf(
                (10 to 1) to 1,
                (10 to 3) to 1,
                (20 to 1) to 1,
                (20 to 3) to 1,
            ),
            entriesApplications.groupingBy { application -> application }.eachCount(),
        )
        assertEquals(8, renderedApplications)
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    private companion object {
        val SCHEMA =
            """
            type Container {
              helper: Int!
            }

            type Query {
              container: Container!
              dependency(arg: Int!): Int!
              result: Int!
            }
            """.trimIndent()
    }
}
