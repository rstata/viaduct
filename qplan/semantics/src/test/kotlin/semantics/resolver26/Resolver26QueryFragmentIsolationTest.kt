package semantics.resolver26

import java.util.concurrent.atomic.AtomicInteger
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.operationSelectionsFrom
import model.requireObjectField
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import semantics.contract.get
import semantics.contract.selectionValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Disabled
import viaduct.engine.api.EngineObjectData

class Resolver26QueryFragmentIsolationTest {
    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/EngineFeatureTestExample.kt:50
    @Test
    fun `simple query selections`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      one: Int
                      twoContainer: TwoContainer
                    }

                    type TwoContainer {
                      two: Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "one") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        schema.requireObjectField("Query", "twoContainer") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("TwoContainer") {}
                            },
                        schema.requireObjectField("TwoContainer", "two") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("TwoContainer"),
                                queryFragment = schema.fragmentFrom("fragment _ on Query { one }"),
                            ) { _, queryValue, _ ->
                                (queryValue.selectionValues().getValue("one") as Int) + 1
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { twoContainer { two } }")
        assertEquals(
            2,
            nestedValue(result, world, "Query", "twoContainer", "TwoContainer", "two"),
        )
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2017
    @Test
    fun `query fragment reads a sibling field`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { currentUser: String, userGreeting: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "currentUser") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "Alice" },
                        schema.requireObjectField("Query", "userGreeting") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = schema.fragmentFrom("fragment _ on Query { currentUser }"),
                            ) { _, queryValue, _ ->
                                "Hello, ${queryValue.selectionValues().getValue("currentUser") as String}!"
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { userGreeting }")
        assertEquals(rootValue(result, world, "Query", "userGreeting"), "Hello, Alice!")
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2104
    @Test
    fun `query fragment preserves aliases`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { currentUser: String, userCount: Int, summary: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "currentUser") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "Bob" },
                        schema.requireObjectField("Query", "userCount") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 42 },
                        schema.requireObjectField("Query", "summary") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment _ on Query { user: currentUser, count: userCount }",
                                    ),
                            ) { _, queryValue, _ ->
                                "${queryValue.selectionValues().getValue("user")} has " +
                                    "${queryValue.selectionValues().getValue("count")} items"
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { summary }")
        assertEquals(rootValue(result, world, "Query", "summary"), "Bob has 42 items")
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2125
    @Test
    fun `query fragment binds literal arguments`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { user(id: String!): String, userMessage: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "user") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                "User-${arguments.fieldValues.getValue("id") as String}"
                            },
                        schema.requireObjectField("Query", "userMessage") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom("fragment _ on Query { user(id: \"123\") }")
                            ) { _, queryValue, _ ->
                                "Message for: ${queryValue.selectionValues().getValue("user")}"
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { userMessage }")
        assertEquals(rootValue(result, world, "Query", "userMessage"), "Message for: User-123")
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2151
    @Test
    fun `query fragment expands named fragments`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { userName: String, userEmail: String, profile: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "userName") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "Charlie" },
                        schema.requireObjectField("Query", "userEmail") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "charlie@example.com" },
                        schema.requireObjectField("Query", "profile") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment UserInfo on Query { userName userEmail }",
                                    ),
                            ) { _, queryValue, _ ->
                                "Name: ${queryValue.selectionValues().getValue("userName")}, " +
                                    "Email: ${queryValue.selectionValues().getValue("userEmail")}"
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { profile }")
        assertEquals(
            rootValue(result, world, "Query", "profile"),
            "Name: Charlie, Email: charlie@example.com",
        )
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2172
    @Test
    fun `query and object fragments resolve together`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    "type Query { globalConfig: String, baz: Baz } type Baz { x: Int, y: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "globalConfig") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "Premium" },
                        schema.requireObjectField("Query", "baz") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Baz") { "x" setTo 100 }
                            },
                        schema.requireObjectField("Baz", "y") to
                            fieldResolverOf(
                                objectFragment = schema.fragmentFrom("fragment _ on Baz { x }"),
                                queryFragment = schema.fragmentFrom("fragment _ on Query { globalConfig }"),
                            ) { input, queryValue, _ ->
                                "${queryValue.selectionValues().getValue("globalConfig")} item with value " +
                                    "${input.selectionValues().getValue("x")}"
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { baz { y } }")
        assertEquals(
            nestedValue(result, world, "Query", "baz", "Baz", "y"),
            "Premium item with value 100",
        )
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2194
    @Test
    fun `query fragment dependencies resolve transitively`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { baseValue: Int, multipliedValue: Int, finalValue: Int }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "baseValue") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 5 },
                        schema.requireObjectField("Query", "multipliedValue") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = schema.fragmentFrom("fragment _ on Query { baseValue }"),
                            ) { _, queryValue, _ ->
                                (queryValue.selectionValues().getValue("baseValue") as Int) * 2
                            },
                        schema.requireObjectField("Query", "finalValue") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom("fragment _ on Query { multipliedValue }"),
                            ) { _, queryValue, _ ->
                                (queryValue.selectionValues().getValue("multipliedValue") as Int) + 10
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { finalValue }")
        assertEquals(rootValue(result, world, "Query", "finalValue"), 20)
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2220
    @Test
    fun `multiple query fragment selections are shared`() {
        val userCount = AtomicInteger()
        val configCount = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { currentUser: String, globalConfig: String, combined: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "currentUser") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                userCount.incrementAndGet()
                                "David"
                            },
                        schema.requireObjectField("Query", "globalConfig") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                configCount.incrementAndGet()
                                "Advanced"
                            },
                        schema.requireObjectField("Query", "combined") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom("fragment _ on Query { currentUser globalConfig }"),
                            ) { _, queryValue, _ ->
                                "${queryValue.selectionValues().getValue("currentUser")} - " +
                                    "${queryValue.selectionValues().getValue("globalConfig")} mode"
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { combined }")
        assertEquals(rootValue(result, world, "Query", "combined"), "David - Advanced mode")
        assertEquals(1, userCount.get())
        assertEquals(1, configCount.get())
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2262
    @Test
    fun `query fragment supports an untyped inline fragment`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { isEnabled: Boolean, config: String, result: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "isEnabled") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> true },
                        schema.requireObjectField("Query", "config") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "production" },
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = schema.fragmentFrom("fragment _ on Query { ... { isEnabled config } }"),
                            ) { _, queryValue, _ ->
                                if (queryValue.selectionValues().getValue("isEnabled") as Boolean) {
                                    "Running in ${queryValue.selectionValues().getValue("config")}"
                                } else {
                                    "Disabled"
                                }
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { result }")
        assertEquals(rootValue(result, world, "Query", "result"), "Running in production")
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2283
    @Test
    fun `query fragment preserves null values`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { optionalValue: String, result: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "optionalValue") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> null },
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = schema.fragmentFrom("fragment _ on Query { optionalValue }"),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("optionalValue") as? String
                                    ?: "No value provided"
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { result }")
        assertEquals(rootValue(result, world, "Query", "result"), "No value provided")
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2321
    @Test
    fun `query fragment traverses nested objects`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    "type Query { bar: Bar, baz: Baz } type Bar { value: String } type Baz { y: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "bar") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Bar") {}
                            },
                        schema.requireObjectField("Bar", "value") to
                            fieldResolverOf(schema.emptyFragmentOf("Bar")) { _, _ -> "BarValue" },
                        schema.requireObjectField("Query", "baz") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Baz") {}
                            },
                        schema.requireObjectField("Baz", "y") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Baz"),
                                queryFragment =
                                    schema.fragmentFrom("fragment _ on Query { bar { value } }")
                            ) { _, queryValue, _ ->
                                val bar =
                                    queryValue.selectionValues().getValue("bar") as EngineObjectData.Sync
                                "Baz sees bar value: ${bar.selectionValues().getValue("value")}"
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { baz { y } }")
        assertEquals(
            nestedValue(result, world, "Query", "baz", "Baz", "y"),
            "Baz sees bar value: BarValue",
        )
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:2343
    @Test
    fun `query fragment supports a typed inline fragment`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { enabled: Boolean, message: String, status: String }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "enabled") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> false },
                        schema.requireObjectField("Query", "message") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "System offline" },
                        schema.requireObjectField("Query", "status") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom("fragment _ on Query { ... on Query { enabled message } }")
                            ) { _, queryValue, _ ->
                                if (queryValue.selectionValues().getValue("enabled") as Boolean) {
                                    "OK"
                                } else {
                                    queryValue.selectionValues().getValue("message") as String
                                }
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { status }")
        assertEquals(rootValue(result, world, "Query", "status"), "System offline")
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt:511
    // Relates: semantics/src/main/kotlin/semantics/resolver26/FieldResolverTask.kt:143
    @Disabled("ISOLATION: query fragment refetches the same node; expected 1 call, observed 2")
    @Test
    fun `node resolver is reused by a query fragment on the same path`() {
        val executions = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    type Query { baz: Baz }
                    type Baz implements Node {
                      id: ID!
                      x: Int
                      x2: String
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val baz = schema.requireType("Baz") as viaduct.graphql.schema.ViaductSchema.Object
                    mapOf(
                        baz to
                            nodeResolverOf {
                                executions.incrementAndGet()
                                schema.objectOf("Baz") { "x" setTo 10 }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "baz_V_A_node") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Baz") { "id" setTo "1" }
                            },
                        schema.requireObjectField("Baz", "x2") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Baz"),
                                queryFragment =
                                    schema.fragmentFrom("fragment _ on Query { baz { x } }")
                            ) { _, queryValue, _ ->
                                val baz =
                                    queryValue.selectionValues().getValue("baz") as EngineObjectData.Sync
                                baz.selectionValues().getValue("x").toString()
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result = resolve(world, "query { baz { x x2 } }")
        val bridge =
            assertIs<ObjectEngineResult>(
                result
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Query", "baz_V_A_node"),
                            emptyMap(),
                        ),
                    ).get(),
            )
        val baz =
            assertIs<ObjectEngineResult>(
                bridge
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Baz_V_A_Bridge", "node"),
                            emptyMap(),
                        ),
                    ).get(),
            )
        assertEquals(
            baz
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Baz", "x"),
                        emptyMap(),
                    ),
                ).get(),
            10,
        )
        assertEquals(
            baz
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Baz", "x2"),
                        emptyMap(),
                    ),
                ).get(),
            "10",
        )
        assertEquals(1, executions.get())
    }

    private fun resolve(
        world: model.Assumptions,
        query: String,
    ): ObjectEngineResult =
        context(world) {
            resolve(world.operationSelectionsFrom(query))
        }

    private fun rootValue(
        result: ObjectEngineResult,
        world: model.Assumptions,
        typeName: String,
        fieldName: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): Any? =
        result
            .getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField(typeName, fieldName),
                    arguments,
                ),
            ).get()

    private fun nestedValue(
        result: ObjectEngineResult,
        world: model.Assumptions,
        rootTypeName: String,
        rootFieldName: String,
        childTypeName: String,
        childFieldName: String,
    ): Any? {
        val child =
            assertIs<ObjectEngineResult>(
                result
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField(rootTypeName, rootFieldName),
                            emptyMap(),
                        ),
                    ).get(),
            )
        return child
            .getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField(childTypeName, childFieldName),
                    emptyMap(),
                ),
            ).get()
    }
}
