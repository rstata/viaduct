package semantics.correctresolution

import model.ObjectSelectionForest
import model.Schema
import model.engineResultOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CorrectResolutionTest {
    @Test
    fun `selections must be rooted at Query`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val result = world.engineResultOf("Query")
        val profileSelections =
            ObjectSelectionForest.of(
                type = world.schema.type("Profile") as Schema.ObjectType,
                selections = emptyList(),
            )

        assertFailsWith<IllegalArgumentException> {
            context(world) {
                result.correctResolution(profileSelections)
            }
        }
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
