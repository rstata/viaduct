package semantics.resolver26

import model.ObjectEngineResult
import model.operationSelectionsFrom
import model.requireObjectField
import model.testing.TestWorld
import semantics.contract.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Disabled

class Resolver26MechAdaptIsolationTest {
    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:192
    // Relates: model/src/testFixtures/kotlin/model/testing/GJSelectionParser.kt:198
    @Disabled("ISOLATION: GJSelectionParser defers applied directives")
    @Test
    fun `child RSS prunes conditionally skipped field`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      container: Container!
                        @resolver(result: {value: 1})
                      result: Int!
                        @resolver(
                          of: "container { value rssOnly @skip(if: true) }"
                          result: "sum(container.value)"
                        )
                    }

                    type Container {
                      value: Int!
                      rssOnly: Int
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            context(world) {
                resolve(world.operationSelectionsFrom("query { result }"))
            }
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        assertEquals(1, result.getCell(resultKey).get())
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:1186
    @Test
    fun `impossible sibling implementation dependency`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      foo: Foo!
                        @resolver(result: {id: "foo"})
                    }

                    extend interface Node {
                      x: Int!
                    }

                    type Foo implements Node
                      @nodeResolver(result: [{id: "foo", result: {}}]) {
                      id: ID!
                      x: Int!
                        @resolver(
                          of: "... on Node { ... on Bar { y } }"
                          result: 1
                        )
                    }

                    type Bar implements Node
                      @nodeResolver(result: [{id: "bar", result: {}}]) {
                      id: ID!
                      x: Int!
                      y: Int!
                        @resolver(result: 2)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            context(world) {
                resolve(world.operationSelectionsFrom("query { foo { x } }"))
            }
        val bridge =
            assertIs<ObjectEngineResult>(
                result
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Query", "foo_V_A_node"),
                            emptyMap(),
                        ),
                    ).get(),
            )
        val foo =
            assertIs<ObjectEngineResult>(
                bridge
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Foo_V_A_Bridge", "node"),
                            emptyMap(),
                        ),
                    ).get(),
            )

        assertEquals(
            1,
            foo
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Foo", "x"),
                        emptyMap(),
                    ),
                ).get(),
        )
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:1286
    // Relates: model/src/testFixtures/kotlin/model/testing/TestResolverRegistry.kt:751
    @Disabled("ISOLATION: resolver-demand cycle rejected; source cycle is checker-only")
    @Test
    fun `sibling cyclic resolver requirements without checker`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      a: Int!
                        @resolver(of: "b", result: 1)
                      b: Int!
                        @resolver(of: "a", result: 2)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            context(world) {
                resolve(world.operationSelectionsFrom("query { a b }"))
            }

        assertEquals(
            1,
            result
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Query", "a"),
                        emptyMap(),
                    ),
                ).get(),
        )
        assertEquals(
            2,
            result
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Query", "b"),
                        emptyMap(),
                    ),
                ).get(),
        )
    }

    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:1983
    @Test
    fun `unscoped RSS dependency`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      foo: Int!
                        @resolver(of: "bar", result: "sumplus1(bar)")
                      bar: Int!
                        @resolver(result: 3)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            context(world) {
                resolve(world.operationSelectionsFrom("query { foo }"))
            }
        val fooKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "foo"),
                emptyMap(),
            )

        // TestWorld has no scoped executable-schema model; this only covers the underlying RSS.
        assertEquals(4, result.getCell(fooKey).get())
    }
}
