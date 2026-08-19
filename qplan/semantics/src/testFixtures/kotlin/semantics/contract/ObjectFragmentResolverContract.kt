package semantics.contract

import viaduct.engine.api.EngineObjectData

import java.util.concurrent.ConcurrentHashMap
import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.TypeExpr
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.operationSelectionsFrom
import model.sameCompletedResultAs
import model.testing.TestWorld
import model.testing.fieldResolverOf
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
    fun `DSL materializes argumentless and argument-bearing aliases by response key`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      viewer: User! @resolver(result: {plain: 5})
                    }

                    type User {
                      plain: Int!
                      scaled(factor: Int!): Int!
                        @resolver(result: "sum(${'$'}factor)")
                      total: Int!
                        @resolver(
                          of: "plainValue: plain byTwo: scaled(factor: 2) byThree: scaled(factor: 3)"
                          result: "sum(plainValue, byTwo, byThree)"
                        )
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    if (field.containingType.typeName == "User" && field.fieldName == "total") {
                        assertEquals(
                            mapOf(
                                "plainValue" to 5,
                                "byTwo" to 2,
                                "byThree" to 3,
                            ),
                            input.selectionValues().toMap(),
                        )
                    }
                },
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "query { viewer { total } }")
        val viewer =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "viewer")).get(),
            )

        assertEquals(
            10,
            viewer.getCell(world.schema.contractKey("User", "total")).get(),
        )
    }

    @Test
    fun `DSL collects one alias across non-overlapping concrete types`() {
        val observed = ConcurrentHashMap<String, Int>()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      holders: [Holder!]!
                        @resolver(
                          result: [
                            {item: {__typename: "Alpha", alpha: 4}}
                            {item: {__typename: "Beta", beta: 7}}
                          ]
                        )
                    }

                    type Holder {
                      item: Choice!
                      chosen: Int!
                        @resolver(
                          of: "item { ... on Alpha { value: alpha } ... on Beta { value: beta } }"
                          result: "value(item.value)"
                        )
                    }

                    union Choice = Alpha | Beta

                    type Alpha {
                      alpha: Int!
                    }

                    type Beta {
                      beta: Int!
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    if (field.containingType.typeName == "Holder" && field.fieldName == "chosen") {
                        val item = assertIs<EngineObjectData.Sync>(input.selectionValues().getValue("item"))
                        assertEquals(setOf("value"), item.selectionValues().keys)
                        observed[item.type.name] =
                            assertIs<Int>(item.selectionValues().getValue("value"))
                    }
                },
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "query { holders { chosen } }")
        val holders =
            assertIs<ListEngineResult>(
                result.getCell(world.schema.contractKey("Query", "holders")).get(),
            )

        assertEquals(
            listOf(4, 7),
            holders.indices.map { index ->
                val holder = assertIs<ObjectEngineResult>(holders[index].get())
                holder.getCell(world.schema.contractKey("Holder", "chosen")).get()
            },
        )
        assertEquals(mapOf("Alpha" to 4, "Beta" to 7), observed)
    }

    @Test
    fun `materializes aliases and present nulls by response key`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      nullable: Int
                      result: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.objectField("Query", "source")
                    val nullable = schema.objectField("Query", "nullable")
                    val result = schema.objectField("Query", "result")
                    val resultFragment =
                        schema.fragmentFrom(
                            """
                            fragment Result on Query {
                              first: source
                              second: source
                              empty: nullable
                            }
                            """.trimIndent(),
                        )
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                7
                            },
                        nullable to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                null
                            },
                        result to
                            fieldResolverOf(resultFragment) { input, _ ->
                                assertEquals(
                                    setOf("first", "second", "empty"),
                                    input.selectionValues().keys,
                                )
                                assertEquals(7, input.selectionValues().getValue("first"))
                                assertEquals(7, input.selectionValues().getValue("second"))
                                assertTrue("empty" in input.selectionValues())
                                assertEquals(null, input.selectionValues().getValue("empty"))
                                1
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val resolved = resolveAndValidate(world, "query { result }")

        assertEquals(
            1,
            resolved.getCell(world.schema.contractKey("Query", "result")).get(),
        )
    }

    @Test
    fun `duplicate response groups combine nested aliased subselections`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      result: Int!
                    }

                    type Container {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.objectField("Query", "container")
                    val result = schema.objectField("Query", "result")
                    val resultFragment =
                        schema.fragmentFrom(
                            """
                            fragment Result on Query {
                              payload: container { first: one }
                              payload: container { second: two }
                            }
                            """.trimIndent(),
                        )
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "one" setTo 2
                                    "two" setTo 3
                                }
                            },
                        result to
                            fieldResolverOf(resultFragment) { input, _ ->
                                assertEquals(setOf("payload"), input.selectionValues().keys)
                                val payload =
                                    assertIs<EngineObjectData.Sync>(
                                        input.selectionValues().getValue("payload"),
                                    )
                                assertEquals(
                                    setOf("first", "second"),
                                    payload.selectionValues().keys,
                                )
                                assertEquals(2, payload.selectionValues().getValue("first"))
                                assertEquals(3, payload.selectionValues().getValue("second"))
                                5
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val resolved = resolveAndValidate(world, "query { result }")

        assertEquals(
            5,
            resolved.getCell(world.schema.contractKey("Query", "result")).get(),
        )
    }

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
                    val actualFields = input.selectionValues().keys
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
            resolveAndValidate(world, "query { viewer { greeting } }")
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
                                input.selectionValues().keys == setOf("raw"),
                            )
                        "User" to "message" -> {
                            require(
                                input.selectionValues().keys == setOf("profile"),
                            )
                            val profile = input.selectionValues().values.single() as EngineObjectData.Sync
                            require(
                                profile.selectionValues().keys == setOf("rendered"),
                            )
                        }
                    }
                },
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "query { viewer { message } }")
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
            resolveAndValidate(world, "query { chain { computed } }")
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
            2,
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
            resolveAndValidate(world, "query { holder { result } }")
        val holder =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "holder")).get(),
            )

        assertEquals(
            7,
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
            resolveAndValidate(world, "query { items { computed } }")
        val items =
            assertIs<ListEngineResult>(
                result.getCell(world.schema.contractKey("Query", "items")).get(),
            )

        assertEquals(3, items.size)
        assertEquals(null, items[0].get())
        assertEquals(ErrorEngineResult, items[1].get())
        val item = assertIs<ObjectEngineResult>(items[2].get())
        assertEquals(
            6,
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
        val selections = world.operationSelectionsFrom("query { result }")

        val completeWorld =
            Assumptions.of(
                schema = world.schema,
                resolverRegistry = world.resolverRegistry,
                selectiveResolvers = false,
            )
        val expected =
            context(completeWorld) {
                resolveWithResolver01(selections)
            }
        val result = resolveAndValidate(world, selections)

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
            1,
            result.getCell(world.schema.contractKey("Query", "result")).get(),
        )
        testWorld.applicationArguments.assertApplicationCount(
            world.schema.objectField("Query", "dependency"),
            0,
        )
    }

    private fun ObjectEngineResult.GroundKey.visibleIdentity() =
        field to arguments

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
                        fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ignored on $typeName { base }",
                            ),
                        ) { input, _ ->
                            computedApplications += 1
                            val base = input.selectionValues().getValue("base") as Int
                            base * if (typeName == "EvenProduct") 10 else 100
                        }

                    mapOf(
                        groups to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                listOf(
                                        schema.objectOf("Group") {
                                            "seed" setTo 1
                                        },
                                        schema.objectOf("Group") {
                                            "seed" setTo 2
                                        },
                                    )
                            },
                        schema.field("Group", "product") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Group { seed }",
                                ),
                            ) { input, arguments ->
                                val seed =
                                    input.selectionValues().getValue(seedKey.field.fieldName) as Int
                                val factor =
                                    arguments.fieldValues.getValue("factor") as Int
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
                query {
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
                            ObjectEngineResult.GroundKey.of(
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
                    "${seed}x$factor",
                    product.getCell(world.schema.contractKey(typeName, "label")).get(),
                )
                assertEquals(
                    base * if (typeName == "EvenProduct") 10 else 100,
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
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                listOf(
                                        schema.objectOf("Group") {
                                            "seed" setTo 10
                                        },
                                        schema.objectOf("Group") {
                                            "seed" setTo 20
                                        },
                                    )
                            },
                        entries to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Group { seed }",
                                ),
                            ) { input, arguments ->
                                val seed =
                                    input.selectionValues().getValue(seedKey.field.fieldName) as Int
                                val count =
                                    arguments.fieldValues.getValue("count") as Int
                                applications += seed to count
                                (0 until count).map { offset ->
                                        schema.objectOf("Entry") {
                                            "raw" setTo seed + offset
                                        }
                                    }
                            },
                        schema.field("Entry", "rendered") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Entry { raw }",
                                ),
                            ) { input, _ ->
                                renderedApplications += 1
                                val raw =
                                    input.selectionValues().getValue(rawKey.field.fieldName) as Int
                                "entry-$raw"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(
                world,
                """
                query {
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
                            ObjectEngineResult.GroundKey.of(
                                world.schema.objectField("Group", "entries"),
                                mapOf("count" to count),
                            ),
                        ).get(),
                    )
                assertEquals(count, entries.size)
                entries.forEachIndexed { offset, cell ->
                    val entry = assertIs<ObjectEngineResult>(cell.get())
                    assertEquals(
                        "entry-${seed + offset}",
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
