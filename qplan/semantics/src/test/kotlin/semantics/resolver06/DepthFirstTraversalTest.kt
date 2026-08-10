package semantics.resolver06

import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DepthFirstTraversalTest {
    @Test
    fun `resolves a produced subtree before the next sibling slot`() {
        val applications = mutableListOf<String>()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = false,
                schemaSDL =
                    """
                    type Child { nested: String! }
                    type Query {
                      first: Child!
                      second: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "first") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                applications += "first"
                                schema.objectOf("Child")
                            },
                        schema.field("Child", "nested") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ ->
                                applications += "nested"
                                model.Value.String.of("nested")
                            },
                        schema.field("Query", "second") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                applications += "second"
                                model.Value.String.of("second")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            world
                .fragmentFrom(
                    "fragment ignored on Query { first { nested } second }",
                ).subselections

        context(world) {
            world.objectOf("Query").resolve(selections)
        }

        assertEquals(listOf("first", "nested", "second"), applications)
    }
}
