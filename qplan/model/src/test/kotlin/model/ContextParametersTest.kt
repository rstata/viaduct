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
            assertIs<Schema.ObjectValue>(
                context(world) {
                    world.schema.field("Query", "self").snip(
                        source,
                        selectionForestOf(),
                    )
                },
            )

        assertEquals(world.schema.query, result.type)
        assertEquals(emptyMap(), result.fieldValues)
    }

    context(world: Assumptions)
    private fun Schema.ObjectValue.copyInWorld(): Schema.ObjectValue = world.run {
        val copiedFields = fieldValues.toMap()
        Schema.ObjectValue.of(type, copiedFields)
    }

    context(world: Assumptions)
    private fun Schema.ObjectValue.copyTwiceInWorld(): Schema.ObjectValue =
        copyInWorld().copyInWorld()

    private fun assumptions(): Assumptions =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val fragment = Fragment.of(schema.query, selectionForestOf())
                schema.query.fields.values
                    .filter { it.fieldName != "__typename" }
                    .associateWith {
                        model.testing.fieldResolverOf(
                            objectFragment = fragment,
                            function = { _, _ -> error("Not invoked") },
                        )
                    }
            },
        ).assumptions

    private fun Assumptions.sourceObject(): Schema.ObjectValue =
        Schema.ObjectValue.of(
            type = schema.query,
            fields =
                mapOf(
                    schema.key("id") to Schema.IDValue.of("query"),
                    schema.key("name") to Schema.StringValue.of("Query"),
                ),
        )

    private fun Schema.key(fieldName: String): Schema.ObjectKey =
        Schema.ObjectKey.of(
            field = field("Query", fieldName),
            arguments = emptyMap(),
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
