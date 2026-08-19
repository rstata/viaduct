package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeExprTest {
    private val schema = TestWorld.fromSDL(SCHEMA_SDL).schema

    @Test
    fun `outer nullability controls compatibility`() {
        val nullable = TypeExpr.Named.of(Schema.StringType)
        val nonNull = TypeExpr.Named.of(Schema.StringType, isNullable = false)

        assertTrue(nullable.canContainPure(nullable))
        assertTrue(nullable.canContainPure(nonNull))
        assertFalse(nonNull.canContainPure(nullable))
        assertTrue(nonNull.canContainPure(nonNull))
    }

    @Test
    fun `list wrappers recurse and named input types match exactly`() {
        val strings =
            TypeExpr.List.of(
                TypeExpr.Named.of(Schema.StringType, isNullable = false),
            )
        val nullableStrings =
            TypeExpr.List.of(TypeExpr.Named.of(Schema.StringType))
        val ints = TypeExpr.List.of(TypeExpr.Named.of(Schema.IntType))

        assertTrue(nullableStrings.canContainPure(strings))
        assertFalse(strings.canContainPure(nullableStrings))
        assertFalse(strings.canContainPure(ints))
        assertFalse(strings.canContainPure(TypeExpr.Named.of(Schema.StringType)))
    }

    @Test
    fun `output composites contain canonical narrower types`() {
        val node = schema.requireType("Node") as Schema.Interface
        val actor = schema.requireType("Actor") as Schema.Union
        val user = schema.requireType("User") as Schema.Object
        val admin = schema.requireType("Admin") as Schema.Object
        val nodeExpr = TypeExpr.Named.of(node)
        val actorExpr = TypeExpr.Named.of(actor)
        val userExpr = TypeExpr.Named.of(user, isNullable = false)
        val adminExpr = TypeExpr.Named.of(admin)

        assertTrue(nodeExpr.canContainPure(userExpr))
        assertTrue(actorExpr.canContainPure(userExpr))
        assertFalse(userExpr.canContainPure(nodeExpr))
        assertFalse(userExpr.canContainPure(adminExpr))
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

            type Admin implements Node {
              id: ID!
            }

            union Actor = User | Admin

            type Query {
              node: Node
            }
            """.trimIndent()
    }
}
