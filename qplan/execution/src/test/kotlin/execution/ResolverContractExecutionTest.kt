package execution

import execution.testing.ExecutionTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * GraphQL-boundary copies of deterministic Resolver26 contract scenarios.
 *
 * These tests intentionally assert only behavior observable through GraphQL.execute.
 */
class ResolverContractExecutionTest {
    @Test
    fun `specializes shared list continuation and concrete argument defaults`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    interface Item {
                      computed: Int!
                    }

                    type Query {
                      items: [Item!]!
                    }

                    type A implements Item {
                      computed(factor: Int = 2): Int!
                    }

                    type B implements Item {
                      computed: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      items: [Item!]!
                        @resolver(
                          result: [
                            {__typename: "A"},
                            {__typename: "B"}
                          ]
                        )
                    }

                    interface Item {
                      computed: Int!
                    }

                    type A implements Item {
                      computed(factor: Int = 2): Int!
                        @resolver(result: "sum(${'$'}factor)")
                    }

                    type B implements Item {
                      computed: Int! @resolver(result: 3)
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { items { computed } }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "items" to
                    listOf(
                        mapOf("computed" to 2),
                        mapOf("computed" to 3),
                    ),
            ),
            result.getData(),
        )
    }

    @Test
    fun `resolves an empty query through field and node resolvers`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type Query {
                      viewer(id: ID!): User!
                    }

                    type User implements Node {
                      id: ID!
                      name: Int!
                      greeting(prefix: Int!): Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      viewer(id: ID!): User!
                        @resolver(result: {id: "idFrom(${'$'}id)"})
                    }

                    type User implements Node
                      @nodeResolver(result: [{id: "1", result: {name: 7}}]) {
                      id: ID!
                      name: Int!
                      greeting(prefix: Int!): Int!
                        @resolver(result: "sumplus1(${'$'}prefix)")
                    }
                    """.trimIndent(),
            )

        val result =
            fixture.runQuery(
                """
                query {
                  viewer(id: "1") {
                    id
                    name
                    greeting(prefix: 5)
                  }
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "viewer" to
                    mapOf(
                        "id" to "1",
                        "name" to 7,
                        "greeting" to 6,
                    ),
            ),
            result.getData(),
        )
    }

    @Test
    fun `materializes argumentless and argument-bearing aliases by response key`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      viewer: User!
                    }

                    type User {
                      plain: Int!
                      scaled(factor: Int!): Int!
                      total: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
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
            )

        val result = fixture.runQuery("query { viewer { total } }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf("viewer" to mapOf("total" to 10)),
            result.getData(),
        )
    }

    @Test
    fun `collects one alias across non-overlapping concrete types`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      holders: [Holder!]!
                    }

                    type Holder {
                      item: Choice!
                      chosen: Int!
                    }

                    union Choice = Alpha | Beta

                    type Alpha {
                      alpha: Int!
                    }

                    type Beta {
                      beta: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
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
            )

        val result = fixture.runQuery("query { holders { chosen } }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "holders" to
                    listOf(
                        mapOf("chosen" to 4),
                        mapOf("chosen" to 7),
                    ),
            ),
            result.getData(),
        )
    }

    @Test
    fun `closes and orders transitive sibling resolver demand`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      viewer: User!
                    }

                    type User {
                      first: Int!
                      last: Int!
                      display: Int!
                      greeting: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
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
            )

        val result = fixture.runQuery("query { viewer { greeting } }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf("viewer" to mapOf("greeting" to 6)),
            result.getData(),
        )
    }

    @Test
    fun `resolves descendant demand before its consuming sibling`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      viewer: User!
                    }

                    type User {
                      profile: Profile!
                      message: Int!
                    }

                    type Profile {
                      raw: Int!
                      rendered: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
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
            )

        val result = fixture.runQuery("query { viewer { message } }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf("viewer" to mapOf("message" to 3)),
            result.getData(),
        )
    }

    @Test
    fun `resolves recursive demand introduced by an object fragment`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      chain: Chain!
                    }

                    type Chain {
                      label: Int!
                      next: Chain
                      computed: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
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

        val result = fixture.runQuery("query { chain { computed } }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf("chain" to mapOf("computed" to 2)),
            result.getData(),
        )
    }

    @Test
    fun `resolves input selected with a fromArgument variable`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int!): Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!): Int!
                        @resolver(
                          of: "consume(value: ${'$'}seed)"
                          result: "sum(consume)"
                        )
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                    }
                    """.trimIndent(),
            )

        val result =
            fixture.runQuery(
                """
                query Resolve(${'$'}first: Int!, ${'$'}second: Int!) {
                  first: result(seed: ${'$'}first)
                  second: result(seed: ${'$'}second)
                }
                """.trimIndent(),
                variables = mapOf("first" to 7, "second" to 8),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "first" to 14,
                "second" to 16,
            ),
            result.getData(),
        )
    }

    @Test
    fun `retains passive demand below an ungrounded nested resolver key`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      holder: Item!
                      result(value: Int!): Int!
                    }

                    type Item {
                      consume(value: Int!): Int!
                      passive: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      holder: Item! @resolver(result: {passive: 7})
                      result(value: Int!): Int!
                        @resolver(
                          of: "holder { consume(value: ${'$'}value) }"
                          result: "sum(holder.consume)"
                        )
                    }

                    type Item {
                      consume(value: Int!): Int!
                        @resolver(of: "passive", result: "sum(passive)")
                      passive: Int!
                    }
                    """.trimIndent(),
            )

        val result =
            fixture.runQuery(
                """
                query {
                  holder { __typename }
                  result(value: 7)
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "holder" to mapOf("__typename" to "Item"),
                "result" to 7,
            ),
            result.getData(),
        )
    }

    @Test
    fun `binds a variable from a direct active scalar provider`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      source: Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: Int! @resolver(result: 7)
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { result }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(mapOf("result" to 14), result.getData())
    }

    @Test
    fun `reads a nested provider after its active ancestor publishes passive content`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      box: Box!
                      consume(value: Int!): Int!
                    }

                    type Box {
                      value: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "box { value } consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["box", "value"]}]
                          result: "sum(consume)"
                        )
                      box: Box! @resolver(result: {value: 9})
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Box {
                      value: Int!
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { result }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(mapOf("result" to 9), result.getData())
    }

    @Test
    fun `converts a terminal scalar list to a ground input list`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      source: [Int!]!
                      consume(values: [Int!]!): Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source consume(values: ${'$'}values)"
                          pathVars: [{name: "values", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: [Int!]! @resolver(result: [2, 3, 5])
                      consume(values: [Int!]!): Int! @resolver(result: 10)
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { result }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(mapOf("result" to 10), result.getData())
    }

    @Test
    fun `waits for a provider value before expanding a nested variable use`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      source: Int!
                      holder: Holder!
                      delay: Int!
                    }

                    type Holder {
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source holder { consume(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(holder.consume)"
                        )
                      source: Int! @resolver(of: "delay", result: "sum(delay)")
                      holder: Holder! @resolver(result: {})
                      delay: Int! @resolver(result: 7)
                    }

                    type Holder {
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { result }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(mapOf("result" to 7), result.getData())
    }

    @Test
    fun `installs a resolver promise below a passive provider field`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      item: Item!
                    }

                    type Item {
                      result: Int!
                      provider: Provider!
                      consume(value: Int!): Int!
                    }

                    type Provider {
                      value: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {provider: {}})
                    }

                    type Item {
                      result: Int!
                        @resolver(
                          of: "provider { value } consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["provider", "value"]}]
                          result: "sum(consume)"
                        )
                      provider: Provider!
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Provider {
                      value: Int! @resolver(result: 11)
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { item { result } }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf("item" to mapOf("result" to 11)),
            result.getData(),
        )
    }

    @Test
    fun `accepts an acyclic mixed-variable dependency chain`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      outer: Int!
                      q1(value: Int!): Int!
                      q2(value: Int!): Int!
                      q5: Int!
                      q7(value: Int!): Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      outer: Int!
                        @resolver(
                          of: "q5 q2(value: ${'$'}fromQ5)"
                          pathVars: [{name: "fromQ5", path: ["q5"]}]
                          result: "sum(q2)"
                        )
                      q1(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      q2(value: Int!): Int!
                        @resolver(
                          of: "q7(value: ${'$'}value)"
                          result: "sum(q7)"
                        )
                      q5: Int! @resolver(of: "q1(value: 1)", result: "sum(q1)")
                      q7(value: Int!): Int!
                        @resolver(
                          of: "q1(value: ${'$'}value)"
                          result: "sum(q1)"
                        )
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { outer }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(mapOf("outer" to 1), result.getData())
    }

    @Test
    fun `applies the configured identity policy after variable selections ground equally`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int!, other: Int!): Int!
                      payload(arg: Int!): Payload!
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!, other: Int!): Int!
                        @resolver(
                          of: "ground: payload(arg: 1) { one } seedValue: payload(arg: ${'$'}seed) { two } seedValue: payload(arg: ${'$'}seed) { two } otherValue: payload(arg: ${'$'}other) { one }"
                          result: "sum(ground.one, seedValue.two)"
                        )
                      payload(arg: Int!): Payload!
                        @resolver(result: {one: 3, two: 5})
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { result(seed: 1, other: 1) }")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(mapOf("result" to 8), result.getData())
    }

    @Test
    fun `list null and error elements preserve position and skip descendants`() {
        val fixture =
            ExecutionTestFixture.fromResolverDSL(
                schemaSDL =
                    """
                    type Query {
                      items: [Item]
                    }

                    type Item {
                      seed: Int!
                      computed: Int!
                    }
                    """.trimIndent(),
                resolverSchemaSDL =
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
            )

        val result = fixture.runQuery("query { items { computed } }")

        assertEquals(
            mapOf(
                "items" to
                    listOf(
                        null,
                        null,
                        mapOf("computed" to 6),
                    ),
            ),
            result.getData(),
        )
        assertEquals(1, result.errors.size)
        assertEquals(
            listOf("items", 1),
            assertNotNull(result.errors.single().path),
        )
        assertTrue(result.errors.single().message.contains("QPlan field resolution failed"))
    }
}
