package semantics.resolver01

import kotlinx.coroutines.runBlocking
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.registry.Resolver
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
                val world = testWorld.assumptions
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

                val permutedFragment =
                    world.fragmentFrom(testCase.query.permutationEquivalentSource)
                val permutedResult =
                    context(world) {
                        world.objectOf("Query").resolve(permutedFragment.subselections)
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
                    mapOf<Schema.OutputField, Resolver.Field>(
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
): Value.Key =
    Value.Key.of(
        field = field(type.typeName, fieldName),
        arguments = emptyMap(),
    )
