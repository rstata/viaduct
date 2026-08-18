package semantics

import model.ObjectEngineResult

import model.Assumptions
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.registry.ResolverRegistry
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class WorldInjectionTest {
    @Test
    fun `guice assembles one complete reasoning world from qualified inputs`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    mapOf(
                        user to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val userField = schema.field("Query", "user_V_A_node")
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf<Schema.OutputField, FieldResolverDefinition>(
                        userField to
                            model.testing.fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ ->
                                    schema.objectOf("User") {
                                        "id" setTo "field"
                                    }
                                },
                            ),
                    )
                },
            )

        val schema = testWorld.schema
        val registry = testWorld.resolverRegistry
        val world = testWorld.assumptions

        assertEquals(schema, world.schema)
        assertEquals(registry, world.resolverRegistry)
        assertEquals(registry, testWorld.instance(ResolverRegistry::class.java))
        assertEquals(world, testWorld.instance(Assumptions::class.java))

        val bridgeField = schema.objectField("Query", "user_V_A_node")
        val payloadField = schema.objectField("User_V_A_Bridge", "node")
        val user = schema.type("User") as Schema.ObjectType
        val bridge =
            assertIs<Value.Object>(
                registry
                    .resolver(bridgeField)(
                        input = world.objectOf("Query"),
                        arguments = Value.Arguments.of(bridgeField, emptyMap()),
                    ),
            )

        val selections =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  user {
                    id
                  }
                }
                """.trimIndent(),
            ).subselections
        val field =
            assertIs<Value.Object>(
                context(world) {
                    registry
                        .resolver(payloadField)(
                            input = bridge,
                            arguments = Value.Arguments.of(payloadField, emptyMap()),
                            selections = selections.single().subselections.single().subselections,
                        )
                },
            )
        assertEquals(
            "field",
            assertIs<Value.ID>(
                field.fieldValues["id"],
            ).idValue,
        )

        kotlin.test.assertFailsWith<Schema.MissingSchemaElementException> {
            schema.objectField("Query", "user")
        }
        assertEquals(bridgeField, selections.single().key.field)
        assertEquals(
            payloadField,
            selections.single().subselections.single().key.field,
        )
        assertEquals(
            schema.field("User", "id"),
            selections.single().subselections.single().subselections.single().key.field,
        )
    }

    @Test
    fun `guice supplies required query resolvers when resolver inputs are omitted`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        assertFalse(world.schema.objectField("User", "id") in world.resolverRegistry)
        world.resolverRegistry.resolver(
            world.schema.objectField("Query", "user_V_A_node"),
        )
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

private fun Schema.key(
    type: Schema.ObjectType,
    fieldName: String,
): ObjectEngineResult.GroundKey =
    ObjectEngineResult.GroundKey.of(
        field = objectField(type.typeName, fieldName),
        arguments = emptyMap(),
    )
