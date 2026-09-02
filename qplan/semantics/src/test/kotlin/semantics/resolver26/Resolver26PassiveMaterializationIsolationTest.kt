package semantics.resolver26

import model.operationSelectionsFrom
import model.testing.TestWorld
import kotlin.test.Test
import org.junit.jupiter.api.Disabled

class Resolver26PassiveMaterializationIsolationTest {
    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt:483
    // Relates: semantics/src/main/kotlin/semantics/resolver26/ObjectOrchestrationTask.kt:89
    @Disabled("ISOLATION: User.parent not materialized by resolvePassiveValues")
    @Test
    fun `passive parent field materialization`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      company: Company!
                        @resolver(result: {companyName: 7})
                    }

                    type Company {
                      companyName: Int!
                      user: User!
                        @resolver(result: {})
                    }

                    type User {
                      parent: Company!
                      parentCompanyName: Int!
                        @resolver(
                          of: "parent { companyName }"
                          result: "sum(parent.companyName)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        context(world) {
            resolve(world.operationSelectionsFrom("query { company { user { parentCompanyName } } }"))
        }
    }
}
