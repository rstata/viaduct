package model

import model.registry.Resolver
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

        assertIs<Value.Object>(result)
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
            assertIs<Value.Object>(
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
    private fun Value.Object.copyInWorld(): Value.Object = world.run {
        val copiedFields = fieldValues.toMap()
        Value.Object.of(type, copiedFields)
    }

    context(world: Assumptions)
    private fun Value.Object.copyTwiceInWorld(): Value.Object =
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

    private fun Assumptions.sourceObject(): Value.Object =
        Value.Object.of(
            type = schema.query,
            fields =
                mapOf(
                    schema.key("id") to Value.ID.of("query"),
                    schema.key("name") to Value.String.of("Query"),
                ),
        )

    private fun Schema.key(fieldName: String): Value.Key =
        Value.Key.of(
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
