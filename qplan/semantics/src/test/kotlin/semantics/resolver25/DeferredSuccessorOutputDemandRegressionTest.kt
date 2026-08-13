package semantics.resolver25

import model.Schema
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
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item")
                            },
                        schema.objectField("Item", "driver") to
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
                                model.Value.Int.of(1)
                            },
                        schema.objectField("Branch", "target") to
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
        val item = world.schema.type("Item") as Schema.ObjectType
        val branch = world.schema.type("Branch") as Schema.ObjectType
        val payload = world.schema.type("Payload") as Schema.ObjectType
        val branchSelection =
            projected
                .merge(item)
                .byKey()
                .values
                .single { selection -> selection.key.field.fieldName == "branch" }
        val targetSelection =
            branchSelection.subselections
                .merge(branch)
                .byKey()
                .values
                .single { selection -> selection.key.field.fieldName == "target" }

        assertEquals(
            setOf("late"),
            targetSelection.subselections
                .merge(payload)
                .byKey()
                .values
                .mapTo(linkedSetOf()) { selection ->
                    selection.key.field.fieldName
                },
        )
    }
}
