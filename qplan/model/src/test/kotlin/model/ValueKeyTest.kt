package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        val precise = Value.ObjectKey.of(field, emptyMap())

        assertIs<Value.ObjectKey>(general)
        assertEquals(precise, general)
        assertSame(field, general.field)
    }

    @Test
    fun `abstract fields construct plain keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.field("Node", "id")

        val key = Value.Key.of(field, emptyMap())

        assertFalse(key is Value.ObjectKey)
    }

    @Test
    fun `path component projections distinguish keys from list indices`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.query.fields.getValue("user")
        val key = Value.ObjectKey.of(field, emptyMap())
        val index = Value.ListIndex.of(2)

        assertSame(key, key.asKey())
        assertEquals(2, index.asIndex())
        assertFailsWith<IllegalArgumentException> { key.asIndex() }
        assertFailsWith<IllegalArgumentException> { index.asKey() }
        assertFailsWith<IllegalArgumentException> { Value.ListIndex.of(-1) }
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
            }
            """.trimIndent()
    }
}
