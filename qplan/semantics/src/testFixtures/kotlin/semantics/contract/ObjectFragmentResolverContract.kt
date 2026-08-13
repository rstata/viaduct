package semantics.contract

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.Schema
import model.Selection
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.sameCompletedResultAs
import model.selectionForestOf
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import semantics.resolver01.resolve as resolveWithResolver01
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract for resolvers with nonempty object fragments and no variables.
 */
interface ObjectFragmentResolverContract : ResolverContract {
    @Test
    fun `closes and orders transitive sibling resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type User {
                      firstName: String!
                      lastName: String!
                      displayName: String!
                      greeting: String!
                    }
                    type Query { viewer: User! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val firstName = schema.contractKey("User", "firstName")
                    val lastName = schema.contractKey("User", "lastName")
                    val displayName = schema.contractKey("User", "displayName")
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("User") {
                                    "firstName" setTo "Ada"
                                    "lastName" setTo "Lovelace"
                                }
                            },
                        schema.field("User", "displayName") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { firstName lastName }",
                                ),
                            ) { input, _ ->
                                require(
                                    input.hasExactlyFields(firstName, lastName),
                                )
                                val first = input.fieldValues.getValue(firstName) as Value.String
                                val last = input.fieldValues.getValue(lastName) as Value.String
                                Value.String.of("${first.stringValue} ${last.stringValue}")
                            },
                        schema.field("User", "greeting") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { displayName }",
                                ),
                            ) { input, _ ->
                                require(input.hasExactlyFields(displayName))
                                val display =
                                    input.fieldValues.getValue(displayName) as Value.String
                                Value.String.of("Hello, ${display.stringValue}")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { viewer { greeting } }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val viewer =
            assertIs<EngineResult.Object>(
                result.getValue(world.schema.contractKey("Query", "viewer")).get(),
            )

        assertEquals(
            setOf("firstName", "lastName", "displayName", "greeting"),
            viewer.keys.map { it.field.fieldName }.toSet(),
        )
    }

    @Test
    fun `resolves descendant demand before its consuming sibling`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Profile { raw: String!, rendered: String! }
                    type User { profile: Profile!, message: String! }
                    type Query { viewer: User! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val profileKey = schema.contractKey("User", "profile")
                    val rawKey = schema.contractKey("Profile", "raw")
                    val renderedKey = schema.contractKey("Profile", "rendered")
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("User") {
                                    "profile" setTo
                                        objectOf("Profile") {
                                            "raw" setTo "engineer"
                                        }
                                }
                            },
                        schema.field("Profile", "rendered") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Profile { raw }",
                                ),
                            ) { input, _ ->
                                require(input.hasExactlyFields(rawKey))
                                val raw = input.fieldValues.getValue(rawKey) as Value.String
                                Value.String.of("Role: ${raw.stringValue}")
                            },
                        schema.field("User", "message") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { profile { rendered } }",
                                ),
                            ) { input, _ ->
                                require(input.hasExactlyFields(profileKey))
                                val profile =
                                    input.fieldValues.getValue(profileKey) as Value.Object
                                require(profile.hasExactlyFields(renderedKey))
                                profile.fieldValues.getValue(renderedKey) as Value.String
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { viewer { message } }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val viewer =
            assertIs<EngineResult.Object>(
                result.getValue(world.schema.contractKey("Query", "viewer")).get(),
            )
        val profile =
            assertIs<EngineResult.Object>(
                viewer.getValue(world.schema.contractKey("User", "profile")).get(),
            )

        assertEquals(setOf("raw", "rendered"), profile.keys.map { it.field.fieldName }.toSet())
    }

    @Test
    fun `resolves recursive demand introduced by an object fragment`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Chain { label: String!, next: Chain, computed: String! }
                    type Query { chain: Chain! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val nextKey = schema.contractKey("Chain", "next")
                    val labelKey = schema.contractKey("Chain", "label")
                    mapOf(
                        schema.field("Query", "chain") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Chain") {
                                    "label" setTo "first"
                                    "next" setTo
                                        objectOf("Chain") {
                                            "label" setTo "second"
                                            "next" setTo null
                                        }
                                }
                            },
                        schema.field("Chain", "computed") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Chain { next { label } }",
                                ),
                            ) { input, _ ->
                                val next =
                                    input.fieldValues.getValue(nextKey) as Value.Object
                                next.fieldValues.getValue(labelKey) as Value.String
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { chain { computed } }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val chain =
            assertIs<EngineResult.Object>(
                result.getValue(world.schema.contractKey("Query", "chain")).get(),
            )
        val next =
            assertIs<EngineResult.Object>(
                chain.getValue(world.schema.contractKey("Chain", "next")).get(),
            )

        assertTrue("label" in next.keys.map { it.field.fieldName })
        assertEquals(
            Value.String.of("second"),
            chain.getValue(world.schema.contractKey("Chain", "computed")).get(),
        )
    }

    @Test
    fun `applies concrete implementation defaults in transitive demand`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Item { computed: Int! }
                    type ConcreteItem implements Item {
                      computed(factor: Int = 7): Int!
                    }
                    type Holder { item: Item!, result: Int! }
                    type Query { holder: Holder! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val itemKey = schema.contractKey("Holder", "item")
                    val computedKey =
                        Value.GroundKey.of(
                            schema.objectField("ConcreteItem", "computed"),
                            mapOf("factor" to 7),
                        )
                    mapOf(
                        schema.field("Query", "holder") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Holder") {
                                    "item" setTo schema.objectOf("ConcreteItem")
                                }
                            },
                        schema.field("Holder", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Holder { item { computed } }",
                                ),
                            ) { input, _ ->
                                val item =
                                    input.fieldValues.getValue(itemKey) as Value.Object
                                item.fieldValues.getValue(computedKey) as Value.Int
                            },
                        schema.field("ConcreteItem", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("ConcreteItem"),
                            ) { _, arguments ->
                                arguments.fieldValues.getValue("factor") as Value.Int
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { holder { result } }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val holder =
            assertIs<EngineResult.Object>(
                result.getValue(world.schema.contractKey("Query", "holder")).get(),
            )

        assertEquals(
            Value.Int.of(7),
            holder.getValue(world.schema.contractKey("Holder", "result")).get(),
        )
    }

    @Test
    fun `list null and error elements preserve position and skip descendants`() {
        var itemsApplications = 0
        var computedApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Item { seed: Int!, computed: Int! }
                    type Query { items: [Item] }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val items = schema.field("Query", "items")
                    val elementType =
                        (items.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val seedKey = schema.contractKey("Item", "seed")
                    mapOf(
                        items to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { input, _ ->
                                require(input.hasExactlyFields())
                                itemsApplications += 1
                                Value.OutputList.of(
                                    elementType,
                                    listOf(
                                        null,
                                        Value.Error,
                                        schema.objectOf("Item") {
                                            "seed" setTo 3
                                        },
                                    ),
                                )
                            },
                        schema.field("Item", "computed") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Item { seed }",
                                ),
                            ) { input, _ ->
                                computedApplications += 1
                                val seed =
                                    input.fieldValues.getValue(seedKey) as Value.Int
                                Value.Int.of(seed.intValue * 2)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { items { computed } }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val items =
            assertIs<EngineResult.List>(
                result.getValue(world.schema.contractKey("Query", "items")).get(),
            )

        assertEquals(3, items.size)
        assertEquals(null, items[0])
        assertEquals(Value.Error, items[1])
        val item = assertIs<EngineResult.Object>(items[2])
        assertEquals(
            Value.Int.of(6),
            item.getValue(world.schema.contractKey("Item", "computed")).get(),
        )
        assertEquals(1, itemsApplications)
        assertEquals(1, computedApplications)
    }

    @Test
    fun `error-valued resolver argument does not import transitive demand`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Container { helper: Int! }
                    type Query {
                      container: Container!
                      dependency(arg: Int!): Int!
                      result: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependency =
                        schema
                            .fragmentFrom(
                                "fragment ignored on Query { dependency(arg: 1) }",
                            ).subselections
                            .single()
                    val errorDependency =
                        Selection.of(
                            Value.Key.of(
                                dependency.key.field,
                                mapOf("arg" to Value.Error),
                            ),
                            dependency.possibleTypes,
                            dependency.subselections,
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

        val completeWorld =
            Assumptions.of(
                schema = world.schema,
                resolverRegistry = world.resolverRegistry,
                selectiveResolvers = false,
            )
        val expected =
            context(completeWorld) {
                completeWorld.objectOf("Query").resolveWithResolver01(fragment.subselections)
            }
        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)

        assertEquals(expected.keys, result.keys)
        expected.keys.forEach { key ->
            assertTrue(
                expected
                    .getValue(key)
                    .get()
                    .sameCompletedResultAs(result.getValue(key).get()),
            )
        }
        assertEquals(
            Value.Int.of(1),
            result.getValue(world.schema.contractKey("Query", "result")).get(),
        )
    }

    @Test
    fun `object outputs vary by input and arguments at equal-key list occurrences`() {
        val productApplications = mutableListOf<Pair<Int, Int>>()
        var computedApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Product { label: String! }
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
                    type Query { groups: [Group!]! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val groups = schema.field("Query", "groups")
                    val groupElement =
                        (groups.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val seedKey = schema.contractKey("Group", "seed")

                    fun computedResolver(typeName: String) =
                        model.testing.fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ignored on $typeName { base }",
                            ),
                        ) { input, _ ->
                            computedApplications += 1
                            val base =
                                input.fieldValues.getValue(
                                    schema.contractKey(typeName, "base"),
                                ) as Value.Int
                            Value.Int.of(
                                base.intValue *
                                    if (typeName == "EvenProduct") 10 else 100,
                            )
                        }

                    mapOf(
                        groups to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.OutputList.of(
                                    groupElement,
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
                                    (input.fieldValues.getValue(seedKey) as Value.Int).intValue
                                val factor =
                                    (arguments.fieldValues.getValue("factor") as Value.Int).intValue
                                productApplications += seed to factor
                                val base = seed * factor
                                schema.objectOf(
                                    if (base % 2 == 0) "EvenProduct" else "OddProduct",
                                ) {
                                    "label" setTo "${seed}x$factor"
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
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  groups {
                    byTwo: product(factor: 2) {
                      label
                      ... on EvenProduct { computed }
                      ... on OddProduct { computed }
                    }
                    byThree: product(factor: 3) {
                      label
                      ... on EvenProduct { computed }
                      ... on OddProduct { computed }
                    }
                  }
                }
                """.trimIndent(),
            )

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val groups =
            assertIs<EngineResult.List>(
                result.getValue(world.schema.contractKey("Query", "groups")).get(),
            )

        listOf(1, 2).forEachIndexed { groupIndex, seed ->
            val group = assertIs<EngineResult.Object>(groups[groupIndex])
            listOf(2, 3).forEach { factor ->
                val product =
                    assertIs<EngineResult.Object>(
                        group.getValue(
                            Value.GroundKey.of(
                                world.schema.objectField("Group", "product"),
                                mapOf("factor" to factor),
                            ),
                        ).get(),
                    )
                val base = seed * factor
                val typeName =
                    if (base % 2 == 0) "EvenProduct" else "OddProduct"
                assertEquals(typeName, product.type.typeName)
                assertEquals(
                    Value.String.of("${seed}x$factor"),
                    product.getValue(world.schema.contractKey(typeName, "label")).get(),
                )
                assertEquals(
                    Value.Int.of(base * if (typeName == "EvenProduct") 10 else 100),
                    product.getValue(world.schema.contractKey(typeName, "computed")).get(),
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
            productApplications.groupingBy { it }.eachCount(),
        )
        assertEquals(4, computedApplications)
    }

    @Test
    fun `preserves arguments and occurrence-distinct list output shapes`() {
        val applications = mutableListOf<Pair<Int, Int>>()
        var renderedApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Entry { raw: Int!, rendered: String! }
                    type Group { seed: Int!, entries(count: Int!): [Entry!]! }
                    type Query { groups: [Group!]! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val groups = schema.field("Query", "groups")
                    val groupElement =
                        (groups.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val entries = schema.field("Group", "entries")
                    val entryElement =
                        (entries.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val seedKey = schema.contractKey("Group", "seed")
                    val rawKey = schema.contractKey("Entry", "raw")
                    mapOf(
                        groups to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.OutputList.of(
                                    groupElement,
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
                        entries to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Group { seed }",
                                ),
                            ) { input, arguments ->
                                val seed =
                                    (input.fieldValues.getValue(seedKey) as Value.Int).intValue
                                val count =
                                    (arguments.fieldValues.getValue("count") as Value.Int).intValue
                                applications += seed to count
                                Value.OutputList.of(
                                    entryElement,
                                    (0 until count).map { offset ->
                                        schema.objectOf("Entry") {
                                            "raw" setTo seed + offset
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
                                val raw =
                                    input.fieldValues.getValue(rawKey) as Value.Int
                                Value.String.of("entry-${raw.intValue}")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  groups {
                    one: entries(count: 1) { rendered }
                    three: entries(count: 3) { rendered }
                  }
                }
                """.trimIndent(),
            )

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val groups =
            assertIs<EngineResult.List>(
                result.getValue(world.schema.contractKey("Query", "groups")).get(),
            )

        listOf(10, 20).forEachIndexed { groupIndex, seed ->
            val group = assertIs<EngineResult.Object>(groups[groupIndex])
            listOf(1, 3).forEach { count ->
                val entries =
                    assertIs<EngineResult.List>(
                        group.getValue(
                            Value.GroundKey.of(
                                world.schema.objectField("Group", "entries"),
                                mapOf("count" to count),
                            ),
                        ).get(),
                    )
                assertEquals(count, entries.size)
                entries.forEachIndexed { offset, value ->
                    val entry = assertIs<EngineResult.Object>(value)
                    assertEquals(
                        Value.String.of("entry-${seed + offset}"),
                        entry.getValue(world.schema.contractKey("Entry", "rendered")).get(),
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
            applications.groupingBy { it }.eachCount(),
        )
        assertEquals(8, renderedApplications)
    }
}
