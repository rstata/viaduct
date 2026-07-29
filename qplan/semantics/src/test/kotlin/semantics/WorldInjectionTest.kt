package semantics

import model.Assumptions
import model.Fragment
import model.Schema
import model.Value
import model.selectionsFrom
import model.registry.ExecutorRegistry
import model.registry.MissingExecutorException
import model.registry.Resolver
import model.selectionForestOf
import model.testing.TestWorld
import semantics.spec.flatten
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
                variableValues = { schema ->
                    mapOf("requestedId" to Value.ID.of("bound"))
                },
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    mapOf<Schema.ObjectType, Resolver.Node>(
                        user to
                            model.testing.nodeResolverOf { id ->
                                Value.Object.of(
                                    type = user,
                                    fields = mapOf(schema.key(user, "id") to id),
                                )
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    val userField = schema.field("Query", "user")
                    val queryFragment =
                        Fragment.of(schema.query, selectionForestOf())
                    mapOf<Schema.OutputField, Resolver.Field>(
                        userField to
                            model.testing.fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ ->
                                    Value.Object.of(
                                        type = user,
                                        fields =
                                            mapOf(
                                                schema.key(user, "id") to
                                                    Value.ID.of("field"),
                                            ),
                                    )
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
        assertEquals(
            "bound",
            assertIs<Value.ID>(
                world.variableValues["requestedId"],
            ).idValue,
        )

        val user = schema.type("User") as Schema.ObjectType
        val node =
            registry.resolver(user).function(
                Value.ID.of("node"),
            )
        assertEquals(
            "node",
            assertIs<Value.ID>(
                node.fieldValues[schema.key(user, "id")],
            ).idValue,
        )

        val userField = schema.field("Query", "user")
        val (_, fieldResolverFunction) = registry.resolver(userField)
        val field =
            assertIs<Value.Object>(
                fieldResolverFunction(
                    Value.Object.of(schema.query, emptyMap()),
                    Value.Arguments.of(userField, emptyMap()),
                ),
            )
        assertEquals(
            "field",
            assertIs<Value.ID>(
                field.fieldValues[schema.key(user, "id")],
            ).idValue,
        )

        val (typeInScope, specSelections) =
            world.selectionsFrom(
                """
                fragment ignored on Query {
                  user {
                    id
                  }
                }
                """.trimIndent(),
            )
        val selections =
            context(world) {
                flatten(typeInScope, specSelections)
            }
        assertEquals(userField, selections.single().key.field)
        assertEquals(
            schema.field("User", "id"),
            selections.single().subselections.single().key.field,
        )
    }

    @Test
    fun `guice supplies required query resolvers when resolver inputs are omitted`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val user = world.schema.type("User") as Schema.ObjectType

        assertFalse(world.variableValues.containsKey("requestedId"))
        assertFailsWith<MissingExecutorException> {
            world.executorRegistry.resolver(user)
        }
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
