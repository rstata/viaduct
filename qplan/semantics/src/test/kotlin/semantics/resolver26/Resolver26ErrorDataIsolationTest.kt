package semantics.resolver26

import model.ErrorEngineResult
import model.ObjectEngineResult
import model.operationSelectionsFrom
import model.requireObjectField
import model.testing.TestWorld
import semantics.contract.get
import kotlin.test.Test
import kotlin.test.assertIs

class Resolver26ErrorDataIsolationTest {
    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:908
    // The source test is also tagged MechAdapt; this is its single isolation counterpart.
    @Test
    fun `selective dependency propagates ErrorData`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      container: Container!
                        @resolver(result: {})
                    }

                    type Container {
                      details: Details
                        @resolver(result: {a: 1})
                      summary: Int!
                        @resolver(
                          of: "details { b }"
                          result: "sum(details.b)"
                        )
                    }

                    type Details {
                      a: Int
                      b: Int
                        @resolver(result: "ERROR")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            context(world) {
                resolve(world.operationSelectionsFrom("query { container { summary } }"))
            }
        val containerKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "container"),
                emptyMap(),
            )
        val container = assertIs<ObjectEngineResult>(result.getCell(containerKey).get())
        val summaryKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Container", "summary"),
                emptyMap(),
            )

        assertIs<ErrorEngineResult>(container.getCell(summaryKey).get())
    }
}
