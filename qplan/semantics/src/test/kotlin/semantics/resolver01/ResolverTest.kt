package semantics.resolver01

import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolverTest {
    @Test
    fun `arbitrary valid worlds resolve correctly`(): Unit =
        runBlocking {
            val counts =
                TestCaseCount(
                    schemas = 20,
                    registriesPerSchema = 3,
                    queriesPerSchema = 5,
                )
            val config =
                Config.default +
                    (ResolverFragmentsEnabled to false)

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                val world = testWorld.newAssumptions()
                val fragment = world.fragmentFrom(testCase.query.source)
                val selections = fragment.subselections
                val result =
                    context(world) {
                        world.objectOf("Query").resolve(selections)
                    }

                assertTrue(
                    context(world) {
                        result.correctResolution(fragment)
                    },
                )

                val permutedWorld = testWorld.newAssumptions()
                val permutedFragment =
                    permutedWorld.fragmentFrom(testCase.query.permutationEquivalentSource)
                val permutedResult =
                    context(permutedWorld) {
                        permutedWorld.objectOf("Query").resolve(permutedFragment.subselections)
                    }
                assertEquals(result, permutedResult)
            }
        }

    @Test
    fun `resolves typename as the concrete object type`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val selections =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  __typename
                }
                """.trimIndent(),
            ).subselections
        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val typeName =
            assertIs<Value.String>(
                result.fetch(schema.key(schema.query, "__typename")).value,
            )
        assertEquals("Query", typeName.stringValue)
    }

    @Test
    fun `retains unselected passive fields from a non-selective resolver`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      requested: String!
                      extra: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "user") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("User") {
                                    "requested" setTo "requested"
                                    "extra" setTo "extra"
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { user { requested } }",
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        val user =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key(schema.query, "user")).value,
            )
        assertEquals(
            setOf("requested", "extra"),
            user.keys.map { key -> key.field.fieldName }.toSet(),
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `resolves an empty Query through field and node resolvers`() {
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
                                    "name" setTo "Ada"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    val viewer = schema.field("Query", "viewer")
                    val greeting = schema.field("User", "greeting")
                    mapOf<Schema.OutputField, FieldResolverDefinition>(
                        viewer to
                            model.testing.fieldResolverOf(
                            objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, arguments ->
                                    val id = arguments.fieldValues.getValue("id")
                                    require(id != Value.Error && id is Value.ID)
                                    schema.objectOf("User") {
                                        "id" setTo id
                                    }
                                },
                            ),
                        greeting to
                            model.testing.fieldResolverOf(
                            objectFragment = schema.emptyFragmentOf("User"),
                                function = { input, arguments ->
                                    require(input.fieldValues.isEmpty())
                                    val prefix =
                                        arguments.fieldValues.getValue("prefix") as Value.String
                                    Value.String.of("${prefix.stringValue}, Ada")
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  viewer(id: "1") {
                    id
                    name
                    greeting(prefix: "Hello")
                  }
                }
                """.trimIndent(),
            )
        val selections = fragment.subselections
        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        assertTrue(
            context(world) {
                result.correctResolution(fragment)
            },
        )
    }

    @Test
    fun `resolves a nested passive node through its synthetic bridge`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type Profile implements Node {
                      id: ID!
                      name: String!
                    }

                    type Card {
                      profile: Profile!
                    }

                    type Viewer {
                      card: Card!
                    }

                    type Query {
                      viewer: Viewer!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val profile = schema.type("Profile") as Schema.ObjectType
                    mapOf(
                        profile to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("Profile") {
                                    "id" setTo id
                                    "name" setTo "Ada"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Viewer") {
                                    "card" setTo
                                        objectOf("Card") {
                                            "profile" setTo
                                                objectOf("Profile") {
                                                    "id" setTo "profile-1"
                                                }
                                        }
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  viewer {
                    card {
                      profile {
                        id
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key(schema.query, "viewer")).value,
            )
        val card =
            assertIs<EngineResult.Object>(
                viewer.fetch(schema.key(viewer.type, "card")).value,
            )
        val bridgeField = schema.objectField("Card", "profile\$bridge")
        val bridgeKey = Value.GroundKey.of(bridgeField, emptyMap())
        assertEquals(setOf(bridgeKey), card.keys)
        val bridge = assertIs<EngineResult.Object>(card.fetch(bridgeKey).value)
        val bridgeId = schema.key(bridge.type, "\$id")
        assertEquals(
            "\$node:7:Profileprofile-1",
            assertIs<Value.ID>(bridge.fetch(bridgeId).value).idValue,
        )

        val profile =
            assertIs<EngineResult.Object>(
                bridge.fetch(schema.key(bridge.type, "\$node")).value,
            )
        assertEquals(
            "profile-1",
            assertIs<Value.ID>(
                profile.fetch(schema.key(profile.type, "id")).value,
            ).idValue,
        )
        assertEquals(
            "Ada",
            assertIs<Value.String>(
                profile.fetch(schema.key(profile.type, "name")).value,
            ).stringValue,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
              greeting(prefix: String!): String!
            }

            type Query {
              viewer(id: ID!): User!
            }
            """.trimIndent()
    }
}

private fun Schema.key(
    type: Schema.ObjectType,
    fieldName: String,
): Value.GroundKey =
    Value.GroundKey.of(
        field = objectField(type.typeName, fieldName),
        arguments = emptyMap(),
    )
