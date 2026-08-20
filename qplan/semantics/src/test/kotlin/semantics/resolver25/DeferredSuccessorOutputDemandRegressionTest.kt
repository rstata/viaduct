package semantics.resolver25

import model.requireType
import model.requireObjectField
import viaduct.graphql.schema.ViaductSchema
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals

class DeferredSuccessorOutputDemandRegressionTest {
    @Test
    fun `retains output demand beneath a grounded successor in a fixed fragment`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      late: Int!
                    }

                    type Branch {
                      target: Payload!
                    }

                    type Item {
                      branch: Branch!
                      driver: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item")
                            },
                        schema.requireObjectField("Item", "driver") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment Driver on Item {
                                      branch {
                                        target { late }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                1
                            },
                        schema.requireObjectField("Branch", "target") to
                            fieldResolverOf(schema.emptyFragmentOf("Branch")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "late" setTo 2
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val projected =
            context(world) {
                world.fragmentFrom(
                    "fragment Demand on Item { driver }",
                ).subselections.projectionDemandDeferringTemplates()
            }
        val item = world.schema.requireType("Item") as ViaductSchema.Object
        val branch = world.schema.requireType("Branch") as ViaductSchema.Object
        val payload = world.schema.requireType("Payload") as ViaductSchema.Object
        val branchSelection =
            projected
                .merge(item)
                .byKey()
                .values
                .single { selection -> selection.key.field.name == "branch" }
        val targetSelection =
            branchSelection.subselections
                .merge(branch)
                .byKey()
                .values
                .single { selection -> selection.key.field.name == "target" }

        assertEquals(
            setOf("late"),
            targetSelection.subselections
                .merge(payload)
                .byKey()
                .values
                .mapTo(linkedSetOf()) { selection ->
                    selection.key.field.name
                },
        )
    }
}
