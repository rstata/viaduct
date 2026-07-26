package semantics

import model.Assumptions
import model.Fragment
import model.GJSchema
import model.Schema
import model.registry.ExecutorRegistry
import model.registry.FieldCoordinate
import model.registry.FieldResolver
import model.registry.MissingExecutorException
import model.registry.NodeResolver
import model.testing.TestWorld
import semantics.spec.SpecSelectionFlattener
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
                    mapOf<String, NodeResolver>(
                        "User" to
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
                    val queryFragment =
                        object : Fragment {
                            override val nominalType = schema.query
                            override val subselections = emptyList<model.Selection>()
                        }
                    mapOf<FieldCoordinate, FieldResolver>(
                        FieldCoordinate("Query", "user") to
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
        val flattener = testWorld.instance(SpecSelectionFlattener::class.java)

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
            fieldResolverFunction(
                schema.objectValue(schema.query, emptyMap()),
                schema.argumentsValue(userField, emptyMap()),
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
        val selections = flattener.flatten(typeInScope, specSelections)
        assertEquals(userField, selections.single().key.field)
        assertEquals(
            schema.field("User", "id"),
            selections.single().subselections.single().key.field,
        )
    }

    @Test
    fun `guice explicitly binds empty world input tables`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val user = world.schema.type("User") as Schema.ObjectType

        assertFalse(world.variableValues.containsKey("requestedId"))
        assertFailsWith<MissingExecutorException> {
            world.executorRegistry.nodeResolver(user)
        }
        assertFailsWith<MissingExecutorException> {
            world.executorRegistry.fieldResolver(
                world.schema.field("Query", "user"),
            )
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type User {
              id: ID!
            }

            type Query {
              user: User
            }
            """.trimIndent()
    }
}
