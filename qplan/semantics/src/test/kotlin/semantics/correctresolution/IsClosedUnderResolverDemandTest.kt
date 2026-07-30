package semantics.correctresolution

import model.EngineResult
import model.Schema
import model.Value
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertTrue

class IsClosedUnderResolverDemandTest {
    @Test
    fun `non-node object does not require an id field`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val profile = schema.type("Profile") as Schema.ObjectType
        val nameKey =
            Value.Key.of(
                field = schema.field("Profile", "name"),
                arguments = emptyMap(),
            )
        val result =
            EngineResult.Object.of(
                type = profile,
                cells =
                    mapOf(
                        nameKey to EngineResult.Cell.of(Value.String.of("Ada")),
                    ),
            )

        assertTrue(context(world) { result.isClosedUnderResolverDemand() })
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type Profile {
              name: String!
            }

            type Query {
              profile: Profile!
            }
            """.trimIndent()
    }
}
