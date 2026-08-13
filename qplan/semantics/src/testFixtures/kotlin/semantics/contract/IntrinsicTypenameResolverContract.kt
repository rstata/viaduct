package semantics.contract

import model.EngineResult
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Contract for resolvers that synthesize `__typename` in every created object result. */
interface IntrinsicTypenameResolverContract : ResolverContract {
    @Test
    fun `synthesizes typename for every created object result`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
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
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Parent") {
                                    "value" setTo 1
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { parent { value } }")

        val resolved = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val parent =
            assertIs<EngineResult.Object>(
                resolved.getValue(world.schema.contractKey("Query", "parent")).get(),
            )

        assertEquals(
            Value.String.of("Query"),
            resolved.getValue(world.schema.contractKey("Query", "__typename")).get(),
        )
        assertEquals(
            Value.String.of("Parent"),
            parent.getValue(world.schema.contractKey("Parent", "__typename")).get(),
        )
    }
}
