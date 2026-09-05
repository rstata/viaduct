package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.operationSelectionsFrom
import model.outputValue
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.contract.contractKey
import semantics.contract.get
import semantics.shared.OperationContext
import viaduct.engine.api.EngineObjectData

class ParentAdversarialTest {
    @Test
    fun `recursive same-type parent chain retains immediate occurrence identity`() {
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { root: Link }
                    type Link { name: String, child: Link, parent: Link @parent }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "root") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Link") { "name" setTo "root" }
                            },
                        schema.requireObjectField("Link", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Link")) { _, _ ->
                                schema.objectOf("Link") { "name" setTo "child" }
                            },
                    )
                },
            ).assumptions
        val result =
            context(OperationContext(world)) {
                resolve(
                    world.operationSelectionsFrom(
                        "query { root { child { child { parent { parent { name } } } } } }",
                    ),
                )
            }
        val root =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "root")).get(),
            )
        val firstChild =
            assertIs<ObjectEngineResult>(
                root.getCell(world.schema.contractKey("Link", "child")).get(),
            )
        val secondChild =
            assertIs<ObjectEngineResult>(
                firstChild.getCell(world.schema.contractKey("Link", "child")).get(),
            )
        val parentKey = world.schema.contractKey("Link", "parent")

        assertSame(firstChild, secondChild.getCell(parentKey).get())
        assertSame(root, firstChild.getCell(parentKey).get())
    }

    @Test
    fun `resolver produced parent field cannot replace the structural parent`() {
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { company: Company }
                    type Company { name: String, user: User }
                    type User { parent: Company @parent, companyName: String }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val unrelatedCompany =
                        schema.objectOf("Company") {
                            "name" setTo "unrelated"
                        }
                    mapOf(
                        schema.requireObjectField("Query", "company") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Company") {
                                    "name" setTo "structural"
                                    "user" setTo
                                        schema.objectOf("User") {
                                            "parent" setTo unrelatedCompany
                                        }
                                }
                            },
                        schema.requireObjectField("User", "companyName") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { parent { name } }",
                                ),
                            ) { input, _ ->
                                val parent =
                                    assertIs<EngineObjectData.Sync>(
                                        input.outputValue("parent"),
                                    )
                                parent.outputValue("name")
                            },
                    )
                },
            ).assumptions
        val operation = OperationContext(world)
        val result =
            context(operation) {
                resolve(
                    world.operationSelectionsFrom(
                        "query { company { user { companyName } } }",
                    ),
                )
            }
        val company =
            assertIs<ObjectEngineResult>(
                result
                    .getCell(world.schema.contractKey("Query", "company"))
                    .get(),
            )
        val user =
            assertIs<ObjectEngineResult>(
                company
                    .getCell(world.schema.contractKey("Company", "user"))
                    .get(),
            )

        assertSame(
            company,
            user.getCell(world.schema.contractKey("User", "parent")).get(),
        )
        assertEquals(
            "structural",
            user.getCell(world.schema.contractKey("User", "companyName")).get(),
        )
    }
}
