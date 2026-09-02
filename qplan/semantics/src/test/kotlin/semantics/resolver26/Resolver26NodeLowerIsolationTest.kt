package semantics.resolver26

import model.ObjectEngineResult
import model.operationSelectionsFrom
import model.requireObjectField
import model.testing.TestWorld
import semantics.contract.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Resolver26NodeLowerIsolationTest {
    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:1107
    @Test
    fun `matching implementation required selection`() {
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
                          of: "... on Foo { id }"
                          result: 1
                        )
                    }

                    type Bar implements Node
                      @nodeResolver(result: [{id: "bar", result: {}}]) {
                      id: ID!
                      x: Int!
                        @resolver(result: 2)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            context(world) {
                resolve(world.operationSelectionsFrom("query { foo { x } }"))
            }
        val bridgeKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "foo_V_A_node"),
                emptyMap(),
            )
        val bridge = assertIs<ObjectEngineResult>(result.getCell(bridgeKey).get())
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
}
