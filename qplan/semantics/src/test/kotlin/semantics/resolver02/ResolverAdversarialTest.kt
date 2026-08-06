package semantics.resolver02

import model.Schema
import model.TypeExpr
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import semantics.resolver01.resolve as resolveWithResolver01
import kotlin.test.Test
import kotlin.test.assertTrue

class ResolverAdversarialTest {
    @Test
    fun `resolver02 accepts position-distinct passive fields in a complete list output`() {
        val testWorld = listWithPositionDistinctPassiveFields()
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { items { selected } }",
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `resolver01 accepts position-distinct passive fields in a complete list output`() {
        val testWorld = listWithPositionDistinctPassiveFields()
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { items { selected } }",
            )

        val result =
            context(world) {
                world.objectOf("Query").resolveWithResolver01(fragment.subselections)
            }

        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    private fun listWithPositionDistinctPassiveFields(): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Item {
                  selected: String!
                  extra: String
                }

                type Query {
                  items: [Item!]!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val items = schema.field("Query", "items")
                val elementType =
                    (items.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                mapOf(
                    items to
                        model.testing.fieldResolverOf(
                            schema.emptyFragmentOf("Query"),
                        ) { _, _ ->
                            model.Value.OutputList.of(
                                elementType,
                                listOf(
                                    schema.objectOf("Item") {
                                        "selected" setTo "first"
                                        "extra" setTo "only-first"
                                    },
                                    schema.objectOf("Item") {
                                        "selected" setTo "second"
                                    },
                                ),
                            )
                        },
                )
            },
        )
}
