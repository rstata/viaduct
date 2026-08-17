package model.invariants

import model.ObjectEngineResult

import model.Schema
import model.Value
import model.engineResultOf
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaConformanceTest {
    @Test
    fun `factory-constructed values conform to schema`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val value =
            world.objectOf("User") {
                "name" setTo "Ada"
            }

        assertTrue(
            context(world) {
                value.conformsToSchema() &&
                    value.conformsToSchema(schema.field("Query", "user").typeExpr)
            },
        )
    }

    @Test
    fun `factory-constructed engine results conform to schema`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val result =
            world.engineResultOf("User") {
                "name" resolvesTo "Ada"
            }

        assertTrue(
            context(world) {
                result.conformsToSchema()
            },
        )
    }

    @Test
    fun `object value factory rejects a field value with the wrong type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = schema.type("User") as Schema.ObjectType

        assertFailsWith<IllegalArgumentException> {
            Value.Object.of(
                type = user,
                fields =
                    mapOf(
                        schema.key(user, "name") to Value.Int.of(1),
                    ),
            )
        }
    }

    private fun Schema.key(
        type: Schema.ObjectType,
        fieldName: String,
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(
            field = objectField(type.typeName, fieldName),
            arguments = emptyMap(),
        )

    private companion object {
        val SCHEMA_SDL =
            """
            type User {
              name: String!
            }

            type Query {
              user: User
            }
            """.trimIndent()
    }
}
