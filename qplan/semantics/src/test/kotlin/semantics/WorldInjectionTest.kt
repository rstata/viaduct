package semantics

import model.Assumptions
import model.Fragment
import model.GJSchema
import model.Schema
import model.registry.ExecutorRegistry
import model.registry.FieldResolver
import model.registry.MissingExecutorException
import model.registry.NodeResolver
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
                    mapOf("requestedId" to schema.idValue("bound"))
                },
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    mapOf<Schema.ObjectType, NodeResolver>(
                        user to
                            NodeResolver { id ->
                                schema.objectValue(
                                    type = user,
                                    fields = mapOf("id" to id),
                                )
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    val userField = schema.field("Query", "user")
                    val queryFragment =
                        object : Fragment {
                            override val nominalType = schema.query
                            override val subselections = selectionForestOf()
                        }
                    mapOf<Schema.OutputField, FieldResolver>(
                        userField to
                            FieldResolver(
                                objectFragment = queryFragment,
                                function = { _, _ ->
                                    schema.objectValue(
                                        type = user,
                                        fields = mapOf("id" to schema.idValue("field")),
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
        assertEquals(schema, testWorld.instance(GJSchema::class.java))
        assertEquals(registry, testWorld.instance(ExecutorRegistry::class.java))
        assertEquals(world, testWorld.instance(Assumptions::class.java))
        assertEquals(
            "bound",
            assertIs<Schema.IDValue>(
                world.variableValues["requestedId"],
            ).idValue,
        )

        val user = schema.type("User") as Schema.ObjectType
        val node =
            registry.nodeResolver(user).function(
                schema.idValue("node"),
            )
        assertEquals(
            "node",
            assertIs<Schema.IDValue>(node.outputObjectFields["id"]).idValue,
        )

        val userField = schema.field("Query", "user")
        val (_, fieldResolverFunction) = registry.fieldResolver(userField)
        val field =
            assertIs<Schema.ObjectValue>(
                fieldResolverFunction(
                    schema.objectValue(schema.query, emptyMap()),
                    schema.argumentsValue(userField, emptyMap()),
                ),
            )
        assertEquals(
            "field",
            assertIs<Schema.IDValue>(field.outputObjectFields["id"]).idValue,
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
            world.executorRegistry.nodeResolver(user)
        }
        world.executorRegistry.fieldResolver(
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
