package semantics

import model.EngineResult
import model.Value
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PartialOERTest {
    @Test
    fun `parent reference freezes after child cells are written`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      name: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
            ).assumptions
        val schema = world.schema
        val userKey = Value.GroundKey.of(schema.objectField("Query", "user"), emptyMap())
        val nameKey = Value.GroundKey.of(schema.objectField("User", "name"), emptyMap())
        val user = schema.objectOf("User")
        val root =
            PartialOER(
                path = emptyList(),
                source =
                    schema.objectOf("Query") {
                        "user" setTo user
                    },
            )
        val child = PartialOER(path = listOf(userKey), source = user)

        root.write(
            userKey,
            PartialCell(PartialValue.ObjectReference(child)),
        )
        child.write(
            nameKey,
            PartialCell(PartialValue.Terminal(Value.String.of("Ada"))),
        )

        val frozenChild = assertIs<EngineResult.Object>(root.freeze().fetch(userKey).value)
        assertEquals(Value.String.of("Ada"), frozenChild.fetch(nameKey).value)
    }

    @Test
    fun `an exact OER cell can be written only once`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      greeting: String!
                    }
                    """.trimIndent(),
            ).assumptions
        val schema = world.schema
        val key = Value.GroundKey.of(schema.objectField("Query", "greeting"), emptyMap())
        val oer = PartialOER(emptyList(), schema.objectOf("Query"))

        oer.write(
            key,
            PartialCell(PartialValue.Terminal(Value.String.of("first"))),
        )

        assertFailsWith<IllegalStateException> {
            oer.write(
                key,
                PartialCell(PartialValue.Terminal(Value.String.of("second"))),
            )
        }
        assertEquals(Value.String.of("first"), oer.freeze().fetch(key).value)
    }
}
