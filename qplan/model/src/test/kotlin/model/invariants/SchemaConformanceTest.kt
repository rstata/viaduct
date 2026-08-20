package model.invariants

import viaduct.graphql.schema.ViaductSchema

import model.engineResultOf
import model.engineObjectDataOf
import model.objectOf
import model.outputType
import model.requireField
import model.requireType
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
                    value.conformsToOutputSchema(schema.requireField("Query", "user").outputType)
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
        val user = schema.requireType("User") as ViaductSchema.Object

        assertFailsWith<IllegalArgumentException> {
            engineObjectDataOf(
                schemaType = user,
                fields =
                    mapOf(
                        "name" to 1,
                    ),
            )
        }
    }

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
