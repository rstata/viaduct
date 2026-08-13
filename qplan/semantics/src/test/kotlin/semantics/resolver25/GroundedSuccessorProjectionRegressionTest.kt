package semantics.resolver25

import model.fragmentFrom
import model.emptyFragmentOf
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals

class GroundedSuccessorProjectionRegressionTest {
    @Test
    fun `projects passive predecessors beneath a grounded resolver selection`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      passive: Int!
                      successor: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "passive" setTo 1
                                }
                            },
                        schema.objectField("Item", "successor") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Successor on Item { passive }",
                                ),
                            ) { _, _ ->
                                model.Value.Int.of(2)
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val projected =
            context(world) {
                world.fragmentFrom(
                    "fragment Demand on Item { successor }",
                ).subselections.projectionDemandDeferringTemplates()
            }

        assertEquals(
            setOf("passive", "successor"),
            buildSet {
                projected.forEach { selection ->
                    add(selection.key.field.fieldName)
                }
            },
        )
    }
}
