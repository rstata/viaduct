package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeExprTest {
    private val world = TestWorld.fromSDL(SCHEMA_SDL)
    private val schema = world.schema

    @Test
    fun `outer nullability controls compatibility`() {
        val nullable = Schema.TypeExpr.Named.of(Schema.StringType)
        val nonNull = Schema.TypeExpr.Named.of(Schema.StringType, isNullable = false)

        context(world.assumptions) {
            assertTrue(nullable.canContain(nullable))
            assertTrue(nullable.canContain(nonNull))
            assertFalse(nonNull.canContain(nullable))
            assertTrue(nonNull.canContain(nonNull))
        }
    }

    @Test
    fun `list wrappers recurse and named input types match exactly`() {
        val strings =
            Schema.TypeExpr.List.of(
                Schema.TypeExpr.Named.of(Schema.StringType, isNullable = false),
            )
        val nullableStrings =
            Schema.TypeExpr.List.of(Schema.TypeExpr.Named.of(Schema.StringType))
        val ints = Schema.TypeExpr.List.of(Schema.TypeExpr.Named.of(Schema.IntType))

        context(world.assumptions) {
            assertTrue(nullableStrings.canContain(strings))
            assertFalse(strings.canContain(nullableStrings))
            assertFalse(strings.canContain(ints))
            assertFalse(strings.canContain(Schema.TypeExpr.Named.of(Schema.StringType)))
        }
    }

    @Test
    fun `output composites contain canonical narrower types`() {
        val node = schema.type("Node") as Schema.InterfaceType
        val actor = schema.type("Actor") as Schema.UnionType
        val user = schema.type("User") as Schema.ObjectType
        val admin = schema.type("Admin") as Schema.ObjectType
        val nodeExpr = Schema.TypeExpr.Named.of(node)
        val actorExpr = Schema.TypeExpr.Named.of(actor)
        val userExpr = Schema.TypeExpr.Named.of(user, isNullable = false)
        val adminExpr = Schema.TypeExpr.Named.of(admin)

        context(world.assumptions) {
            assertTrue(nodeExpr.canContain(userExpr))
            assertTrue(actorExpr.canContain(userExpr))
            assertFalse(userExpr.canContain(nodeExpr))
            assertFalse(userExpr.canContain(adminExpr))
        }
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
