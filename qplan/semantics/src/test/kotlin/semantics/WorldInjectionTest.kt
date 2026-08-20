package semantics

import model.requireType
import model.requireField
import model.requireObjectField
import model.Arguments
import model.Assumptions
import model.ObjectEngineResult
import viaduct.graphql.schema.ViaductSchema
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.registry.ResolverRegistry
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import semantics.contract.selectionValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import viaduct.engine.api.EngineObjectData

class WorldInjectionTest {
    @Test
    fun `guice assembles one complete reasoning world from qualified inputs`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = { schema ->
                    val user = schema.requireType("User") as ViaductSchema.Object
                    mapOf(
                        user to
                            nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val userField = schema.requireField("Query", "user_V_A_node")
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf<ViaductSchema.Field, FieldResolverDefinition>(
                        userField to
                            fieldResolverOf(
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

        val bridgeField = schema.requireObjectField("Query", "user_V_A_node")
        val payloadField = schema.requireObjectField("User_V_A_Bridge", "node")
        val bridge =
            assertIs<EngineObjectData.Sync>(
                registry
                    .resolver(bridgeField)(
                        input = world.objectOf("Query"),
                        arguments = Arguments.Resolved.of(bridgeField, emptyMap()),
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
            assertIs<EngineObjectData.Sync>(
                context(world) {
                    registry
                        .resolver(payloadField)(
                            input = bridge,
                            arguments = Arguments.Resolved.of(payloadField, emptyMap()),
                            selections = selections.single().subselections.single().subselections,
                        )
                },
            )
        assertEquals(
            "field",
            field.selectionValues()["id"],
        )

        assertFailsWith<IllegalStateException> {
            schema.requireObjectField("Query", "user")
        }
        assertEquals(bridgeField, selections.single().key.field)
        assertEquals(
            payloadField,
            selections.single().subselections.single().key.field,
        )
        assertEquals(
            schema.requireField("User", "id"),
            selections.single().subselections.single().subselections.single().key.field,
        )
    }

    @Test
    fun `guice supplies required query resolvers when resolver inputs are omitted`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        assertFalse(world.schema.requireObjectField("User", "id") in world.resolverRegistry)
        world.resolverRegistry.resolver(
            world.schema.requireObjectField("Query", "user_V_A_node"),
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

private fun ViaductSchema.key(
    type: ViaductSchema.Object,
    fieldName: String,
): ObjectEngineResult.GroundKey =
    ObjectEngineResult.GroundKey.of(
        field = requireObjectField(type.name, fieldName),
        arguments = emptyMap(),
    )
