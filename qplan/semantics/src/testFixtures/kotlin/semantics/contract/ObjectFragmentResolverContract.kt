package semantics.contract

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.IntEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.StringEngineResult
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.sameCompletedResultAs
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
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      viewer: User! @resolver(result: {first: 2, last: 3})
                    }

                    type User {
                      first: Int!
                      last: Int!
                      display: Int!
                        @resolver(of: "first last", result: "sum(first, last)")
                      greeting: Int!
                        @resolver(of: "display", result: "sumplus1(display)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    val actualFields =
                        input.fieldValues.keys.mapTo(linkedSetOf()) { key ->
                            key.field.fieldName
                        }
                    when (field.containingType.typeName to field.fieldName) {
                        "User" to "display" ->
                            require(actualFields == setOf("first", "last"))
                        "User" to "greeting" ->
                            require(actualFields == setOf("display"))
                    }
                },
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "fragment ignored on Query { viewer { greeting } }")
        val viewer =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "viewer")).get(),
            )

        assertEquals(
            expectedPassiveResultFieldNames("first", "last", "display", "greeting"),
            viewer.keys.map { it.field.fieldName }.toSet(),
        )
    }

    @Test
    fun `resolves descendant demand before its consuming sibling`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      viewer: User! @resolver(result: {profile: {raw: 2}})
                    }

                    type User {
                      profile: Profile!
                      message: Int!
                        @resolver(
                          of: "profile { rendered }"
                          result: "sum(profile.rendered)"
                        )
                    }

                    type Profile {
                      raw: Int!
                      rendered: Int!
                        @resolver(of: "raw", result: "sumplus1(raw)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    when (field.containingType.typeName to field.fieldName) {
                        "Profile" to "rendered" ->
                            require(
                                input.fieldValues.keys
                                    .mapTo(linkedSetOf()) { key -> key.field.fieldName } ==
                                    setOf("raw"),
                            )
                        "User" to "message" -> {
                            require(
                                input.fieldValues.keys
                                    .mapTo(linkedSetOf()) { key -> key.field.fieldName } ==
                                    setOf("profile"),
                            )
                            val profile = input.fieldValues.values.single() as Value.Object
                            require(
                                profile.fieldValues.keys
                                    .mapTo(linkedSetOf()) { key -> key.field.fieldName } ==
                                    setOf("rendered"),
                            )
                        }
                    }
                },
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "fragment ignored on Query { viewer { message } }")
        val viewer =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "viewer")).get(),
            )
        val profile =
            assertIs<ObjectEngineResult>(
                viewer.getCell(world.schema.contractKey("User", "profile")).get(),
            )

        assertEquals(
            expectedPassiveResultFieldNames("raw", "rendered"),
            profile.keys.map { it.field.fieldName }.toSet(),
        )
    }

    @Test
    fun `resolves recursive demand introduced by an object fragment`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      chain: Chain!
                        @resolver(
                          result: {
                            label: 1
                            next: {label: 2, next: null}
                          }
                        )
                    }

                    type Chain {
                      label: Int!
                      next: Chain
                      computed: Int!
                        @resolver(
                          of: "next { label }"
                          result: "sum(next.label)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "fragment ignored on Query { chain { computed } }")
        val chain =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "chain")).get(),
            )
        val next =
            assertIs<ObjectEngineResult>(
                chain.getCell(world.schema.contractKey("Chain", "next")).get(),
            )

        assertTrue("label" in next.keys.map { it.field.fieldName })
        assertEquals(
            IntEngineResult.of(2),
            chain.getCell(world.schema.contractKey("Chain", "computed")).get(),
        )
    }

    @Test
    fun `applies concrete implementation defaults in transitive demand`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      holder: Holder! @resolver(result: {item: {__typename: "ConcreteItem"}})
                    }

                    type Holder {
                      item: Item!
                      result: Int!
                        @resolver(of: "item { computed }", result: "sum(item.computed)")
                    }

                    interface Item {
                      computed: Int!
                    }

                    type ConcreteItem implements Item {
                      computed(factor: Int = 7): Int!
                        @resolver(result: "sum(${'$'}factor)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "fragment ignored on Query { holder { result } }")
        val holder =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "holder")).get(),
            )

        assertEquals(
            IntEngineResult.of(7),
            holder.getCell(world.schema.contractKey("Holder", "result")).get(),
        )
    }

    @Test
    fun `list null and error elements preserve position and skip descendants`() {
        var itemsApplications = 0
        var computedApplications = 0
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      items: [Item]
                        @resolver(result: [null, "ERROR", {seed: 3}])
                    }

                    type Item {
                      seed: Int!
                      computed: Int!
                        @resolver(of: "seed", result: "sum(seed, seed)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    when (field.containingType.typeName to field.fieldName) {
                        "Query" to "items" -> {
                            require(input.hasExactlyFields())
                            itemsApplications += 1
                        }
                        "Item" to "computed" -> computedApplications += 1
                    }
                },
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "fragment ignored on Query { items { computed } }")
        val items =
            assertIs<ListEngineResult>(
                result.getCell(world.schema.contractKey("Query", "items")).get(),
            )

        assertEquals(3, items.size)
        assertEquals(null, items[0].get())
        assertEquals(ErrorEngineResult, items[1].get())
        val item = assertIs<ObjectEngineResult>(items[2].get())
        assertEquals(
            IntEngineResult.of(6),
            item.getCell(world.schema.contractKey("Item", "computed")).get(),
        )
        assertEquals(1, itemsApplications)
        assertEquals(1, computedApplications)
    }

    @Test
    fun `error-valued resolver argument does not import transitive demand`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "dependency(arg: \"ERROR\")"
                          result: 1
                        )
                      dependency(arg: Int!): Int!
                        @resolver(
                          of: "container { helper }"
                          result: 2
                        )
                      container: Container! @resolver(result: {})
                    }

                    type Container {
                      helper: Int!
                    }
                    """.trimIndent(),
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
                resolveWithResolver01(fragment.subselections)
            }
        val result = resolveAndValidate(world, fragment)

        assertEquals(
            expected.keys.mapTo(linkedSetOf()) { key -> key.visibleIdentity() },
            result.keys
                .mapTo(linkedSetOf()) { key -> key.visibleIdentity() },
        )
        expected.keys.forEach { expectedKey ->
            val resultKey =
                result.keys.single { key ->
                    key.visibleIdentity() == expectedKey.visibleIdentity()
                }
            assertTrue(
                expected
                    .getCell(expectedKey)
                    .get()
                    .sameCompletedResultAs(result.getCell(resultKey).get()),
            )
        }
        assertEquals(
            IntEngineResult.of(1),
            result.getCell(world.schema.contractKey("Query", "result")).get(),
        )
        testWorld.applicationArguments.assertApplicationCount(
            world.schema.objectField("Query", "dependency"),
            0,
        )
    }

    private fun Value.GroundKey.visibleIdentity() =
        field to arguments.fieldValues

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
        val result =
            resolveAndValidate(
                world,
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
        val groups =
            assertIs<ListEngineResult>(
                result.getCell(world.schema.contractKey("Query", "groups")).get(),
            )

        listOf(1, 2).forEachIndexed { groupIndex, seed ->
            val group = assertIs<ObjectEngineResult>(groups[groupIndex].get())
            listOf(2, 3).forEach { factor ->
                val product =
                    assertIs<ObjectEngineResult>(
                        group.getCell(
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
                    StringEngineResult.of("${seed}x$factor"),
                    product.getCell(world.schema.contractKey(typeName, "label")).get(),
                )
                assertEquals(
                    IntEngineResult.of(base * if (typeName == "EvenProduct") 10 else 100),
                    product.getCell(world.schema.contractKey(typeName, "computed")).get(),
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
        val result =
            resolveAndValidate(
                world,
                """
                fragment ignored on Query {
                  groups {
                    one: entries(count: 1) { rendered }
                    three: entries(count: 3) { rendered }
                  }
                }
                """.trimIndent(),
            )
        val groups =
            assertIs<ListEngineResult>(
                result.getCell(world.schema.contractKey("Query", "groups")).get(),
            )

        listOf(10, 20).forEachIndexed { groupIndex, seed ->
            val group = assertIs<ObjectEngineResult>(groups[groupIndex].get())
            listOf(1, 3).forEach { count ->
                val entries =
                    assertIs<ListEngineResult>(
                        group.getCell(
                            Value.GroundKey.of(
                                world.schema.objectField("Group", "entries"),
                                mapOf("count" to count),
                            ),
                        ).get(),
                    )
                assertEquals(count, entries.size)
                entries.forEachIndexed { offset, cell ->
                    val entry = assertIs<ObjectEngineResult>(cell.get())
                    assertEquals(
                        StringEngineResult.of("entry-${seed + offset}"),
                        entry.getCell(world.schema.contractKey("Entry", "rendered")).get(),
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
