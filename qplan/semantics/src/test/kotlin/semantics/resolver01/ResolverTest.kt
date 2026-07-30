package semantics.resolver01

import kotlinx.coroutines.runBlocking
import model.Fragment
import model.Schema
import model.Value
import model.registry.Resolver
import model.selectionsFrom
import model.selectionForestOf
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.correctResolution
import semantics.spec.flatten
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
                val (nominalType, specSelections) =
                    testWorld.selectionsFrom(testCase.query.source)
                val selections =
                    context(world) {
                        flatten(nominalType, specSelections)
                    }
                val fragment = Fragment.of(nominalType, selections)
                val result =
                    context(world) {
                        Value.Object.of(world.schema.query).resolve(selections)
                    }

                assertTrue(
                    context(world) {
                        result.correctResolution(fragment)
                    },
                )
            }
        }

    @Test
    fun `resolves typename as the concrete object type`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val (nominalType, specSelections) =
            world.selectionsFrom(
                """
                fragment ignored on Query {
                  __typename
                }
                """.trimIndent(),
            )
        val selections =
            context(world) {
                flatten(nominalType, specSelections)
            }

        val result =
            context(world) {
                Value.Object.of(schema.query).resolve(selections)
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
                    val idKey = schema.key(user, "id")
                    val nameKey = schema.key(user, "name")
                    mapOf<Schema.ObjectType, Resolver.Node>(
                        user to
                            model.testing.nodeResolverOf { id ->
                                Value.Object.of(
                                    type = user,
                                    fields =
                                        mapOf(
                                            idKey to id,
                                            nameKey to Value.String.of("Ada"),
                                        ),
                                )
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
                                objectFragment = schema.emptyFragment(schema.query),
                                function = { _, arguments ->
                                    val id = arguments.fieldValues.getValue("id")
                                    require(id != Value.Error && id is Value.ID)
                                    Value.Object.of(
                                        type = user,
                                        fields =
                                            mapOf(
                                                schema.key(user, "id") to id,
                                            ),
                                    )
                                },
                            ),
                        greeting to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragment(user),
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
        val (nominalType, specSelections) =
            world.selectionsFrom(
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
        val selections =
            context(world) {
                flatten(nominalType, specSelections)
            }

        val result =
            context(world) {
                Value.Object.of(schema.query, emptyMap()).resolve(selections)
            }

        assertTrue(
            context(world) {
                result.correctResolution(Fragment.of(nominalType, selections))
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

private fun Schema.emptyFragment(type: Schema.ObjectType): Fragment =
    Fragment.of(type, selectionForestOf())

private fun Schema.key(
    type: Schema.ObjectType,
    fieldName: String,
): Value.Key =
    Value.Key.of(
        field = field(type.typeName, fieldName),
        arguments = emptyMap(),
    )
