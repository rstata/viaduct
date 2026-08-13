package semantics.resolver26

import model.EngineResult
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals

class TypenameSynthesisTest {
    @Test
    fun `every created object result contains its concrete typename`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Parent {
                      value: Int!
                    }

                    type Query {
                      parent: Parent!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.objectField("Query", "parent") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Parent") {
                                    "value" setTo 1
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val resolved =
            context(world) {
                world.objectOf("Query").resolve(
                    world.fragmentFrom(
                        "fragment ignored on Query { parent { value } }",
                    ).subselections,
                )
            }
        val parent =
            resolved.getValue(
                Value.GroundKey.of(
                    world.schema.objectField("Query", "parent"),
                    emptyMap(),
                ),
            ).get() as EngineResult.Object

        assertEquals(
            Value.String.of("Query"),
            resolved.getValue(
                Value.GroundKey.of(
                    world.schema.objectField("Query", "__typename"),
                    emptyMap(),
                ),
            ).get(),
        )
        assertEquals(
            Value.String.of("Parent"),
            parent.getValue(
                Value.GroundKey.of(
                    world.schema.objectField("Parent", "__typename"),
                    emptyMap(),
                ),
            ).get(),
        )
    }
}
