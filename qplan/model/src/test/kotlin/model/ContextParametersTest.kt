package model

import model.registry.FieldResolver
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
                world.schema.field("Query", "self").snip(
                    source,
                    selectionForestOf(),
                )
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
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val fragment =
                    object : Fragment {
                        override val nominalType = schema.query
                        override val subselections = selectionForestOf()
                    }
                mapOf(
                    schema.field("Query", "self") to
                        FieldResolver(
                            objectFragment = fragment,
                            function = { _, _ -> error("Not invoked") },
                        ),
                )
            },
        ).assumptions

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
              self: Query
            }
            """.trimIndent()
    }
}
