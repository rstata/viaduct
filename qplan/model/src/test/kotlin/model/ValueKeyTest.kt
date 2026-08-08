package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ValueKeyTest {
    @Test
    fun `object fields always construct object keys with structural equality`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = schema.type("User") as Schema.ObjectType
        val field = user.fields.getValue("id")

        val general =
            Value.Key.of(
                field = field as Schema.OutputField,
                arguments = emptyMap(),
            )
        val precise = Value.GroundKey.of(field, emptyMap())

        assertIs<Value.GroundKey>(general)
        assertEquals(precise, general)
        assertSame(field, general.field)
    }

    @Test
    fun `abstract fields construct plain keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.field("Node", "id")

        val key = Value.Key.of(field, emptyMap())

        assertFalse(key is Value.GroundKey)
    }

    @Test
    fun `concrete fields with open arguments construct object keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.objectField("Query", "find")
        val variable = Value.Variable.of(field, "id")

        val key =
            Value.Key.of(
                field = field as Schema.OutputField,
                arguments = OpenArguments.of(field, mapOf("id" to variable)),
            )

        assertIs<Value.ObjectKey>(key)
        assertFalse(key is Value.GroundKey)
        assertSame(field, key.field)
    }

    @Test
    fun `list index rejects negative positions`() {
        Value.ListIndex.of(2)
        assertFailsWith<IllegalArgumentException> { Value.ListIndex.of(-1) }
    }

    @Test
    fun `selection paths contain only object keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = Value.GroundKey.of(schema.objectField("Query", "user"), emptyMap())
        val id = Value.GroundKey.of(schema.objectField("User", "id"), emptyMap())

        assertEquals(listOf(user, id), listOf<PathComponent>(user, id).toSelectionPath())
        assertNull(listOf<PathComponent>(user, Value.ListIndex.of(0), id).toSelectionPath())
        assertNull((null as List<PathComponent>?).toSelectionPath())
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
            }

            type Query {
              user: User
              find(id: ID!): User
            }
            """.trimIndent()
    }
}
