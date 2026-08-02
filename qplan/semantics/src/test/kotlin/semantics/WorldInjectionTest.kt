package semantics

import model.Assumptions
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.registry.ExecutorRegistry
import model.registry.MissingExecutorException
import model.registry.Resolver
import model.selectionForestOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                    val userField = schema.field("Query", "user")
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf<Schema.OutputField, Resolver.Field>(
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
        val registry = testWorld.executorRegistry
        val world = testWorld.assumptions

        assertEquals(schema, world.schema)
        assertEquals(registry, world.executorRegistry)
        assertEquals(registry, testWorld.instance(ExecutorRegistry::class.java))
        assertEquals(world, testWorld.instance(Assumptions::class.java))

        val userField = schema.field("Query", "user")
        val userIdField = schema.field("Query", "user\$id")
        val user = schema.type("User") as Schema.ObjectType
        val bridge =
            context(world) {
                registry
                    .resolver(userIdField)
                    .resolve(
                        input = world.objectOf("Query"),
                        arguments = Value.Arguments.of(userIdField, emptyMap()),
                        transitiveDemand = selectionForestOf(),
                    )
            }
        assertIs<Value.ID>(bridge)

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
                        .resolver(userField)
                        .resolve(
                            input =
                                Value.Object.of(
                                    schema.query,
                                    mapOf(
                                        Value.Key.of(userIdField, emptyMap()) to bridge,
                                    ),
                                ),
                            arguments = Value.Arguments.of(userField, emptyMap()),
                            transitiveDemand = selections.single().subselections,
                        )
                },
            )
        assertEquals(
            "field",
            assertIs<Value.ID>(
                field.fieldValues[schema.key(user, "id")],
            ).idValue,
        )

        assertEquals(userField, selections.single().key.field)
        assertEquals(
            schema.field("User", "id"),
            selections.single().subselections.single().key.field,
        )
    }

    @Test
    fun `guice supplies required query resolvers when resolver inputs are omitted`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        assertFalse(world.schema.field("User", "id") in world.executorRegistry)
        world.executorRegistry.resolver(
            world.schema.field("Query", "user"),
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
): Value.Key =
    Value.Key.of(
        field = field(type.typeName, fieldName),
        arguments = emptyMap(),
    )
