package model

import model.registry.snip
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ContextParametersTest {
    @Test
    fun `run receiver preserves the object-value extension receiver and result`() {
        val world = assumptions()
        val source = world.sourceObject()

        val result =
            context(world) {
                source.copyInWorld()
            }

        assertIs<Schema.ObjectValue>(result)
        assertEquals(source, result)
        assertEquals(world.schema.query, result.type)
    }

    @Test
    fun `an assumptions context composes implicitly across calls`() {
        val world = assumptions()
        val source = world.sourceObject()

        val result =
            context(world) {
                source.copyTwiceInWorld()
            }

        assertEquals(source, result)
    }

    @Test
    fun `a call boundary supplies the real assumptions context to snip`() {
        val world = assumptions()
        val source = world.sourceObject()

        val result =
            context(world) {
                source.snip(emptyList())
            }

        assertEquals(world.schema.query, result.type)
        assertEquals(emptyMap(), result.outputObjectFields)
    }

    context(world: Assumptions)
    private fun Schema.ObjectValue.copyInWorld(): Schema.ObjectValue = world.run {
        val copiedFields = outputObjectFields.toMap()
        schema.objectValue(type, copiedFields)
    }

    context(world: Assumptions)
    private fun Schema.ObjectValue.copyTwiceInWorld(): Schema.ObjectValue =
        copyInWorld().copyInWorld()

    private fun assumptions(): Assumptions =
        TestWorld.fromSDL(SCHEMA_SDL).assumptions

    private fun Assumptions.sourceObject(): Schema.ObjectValue =
        schema.objectValue(
            type = schema.query,
            fields =
                mapOf(
                    "id" to schema.idValue("query"),
                    "name" to schema.stringValue("Query"),
                ),
        )

    private companion object {
        val SCHEMA_SDL =
            """
            type Query {
              id: ID!
              name: String!
            }
            """.trimIndent()
    }
}
