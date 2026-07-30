package semantics.correctresolution

import model.engineResultOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertTrue

class IsClosedUnderResolverDemandTest {
    @Test
    fun `non-node object does not require an id field`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val result =
            world.engineResultOf("Profile") {
                "name" resolvesTo "Ada"
            }

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
