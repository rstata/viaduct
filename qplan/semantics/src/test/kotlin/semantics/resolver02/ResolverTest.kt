package semantics.resolver02

import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.TypeExpr
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverVariableWeight
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
            var generatedFromArgumentVariables = 0
            val counts =
                TestCaseCount(
                    schemas = 20,
                    registriesPerSchema = 3,
                    queriesPerSchema = 5,
                )
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0)

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                generatedFromArgumentVariables +=
                    testCase.registry.features.fromArgumentVariableCount
                val result = generatedResolution(testWorld, testCase.query.source)
                val permuted =
                    generatedResolution(
                        testWorld,
                        testCase.query.permutationEquivalentSource,
                    )
                assertEquals(result, permuted)
            }
            assertTrue(generatedFromArgumentVariables > 0)
        }

    @Test
    fun `resolves typename as the concrete object type`() {
        val world = TestWorld.fromSDL(FLAT_SCHEMA_SDL).assumptions
        val schema = world.schema
        val (fragment, selections) =
            context(world) {
                parsedFragment(
                    """
                    fragment ignored on Query {
                      __typename
                    }
                    """.trimIndent(),
                )
            }

        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val typeName =
            assertIs<Value.String>(
                result.fetch(schema.key(schema.query, "__typename")).value,
            )
        assertEquals("Query", typeName.stringValue)
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `closes and orders transitive sibling resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = FLAT_SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val user = schema.objectType("User")
                    val firstNameKey = schema.key(user, "firstName")
                    val lastNameKey = schema.key(user, "lastName")
                    val displayNameKey = schema.key(user, "displayName")

                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { input, _ ->
                                    require(input.fieldValues.isEmpty())
                                    schema.objectOf("User") {
                                        "firstName" setTo "Ada"
                                        "lastName" setTo "Lovelace"
                                    }
                                },
                            ),
                        schema.field("User", "displayName") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          firstName
                                          lastName
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    require(
                                        input.fieldValues.keys ==
                                            setOf(firstNameKey, lastNameKey),
                                    )
                                    val firstName =
                                        input.fieldValues.getValue(firstNameKey) as Value.String
                                    val lastName =
                                        input.fieldValues.getValue(lastNameKey) as Value.String
                                    Value.String.of(
                                        "${firstName.stringValue} ${lastName.stringValue}",
                                    )
                                },
                            ),
                        schema.field("User", "greeting") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          displayName
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    require(input.fieldValues.keys == setOf(displayNameKey))
                                    val displayName =
                                        input.fieldValues.getValue(displayNameKey) as Value.String
                                    Value.String.of("Hello, ${displayName.stringValue}")
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val (fragment, selections) =
            context(world) {
                parsedFragment(
                    """
                    fragment ignored on Query {
                      viewer {
                        greeting
                      }
                    }
                    """.trimIndent(),
                )
            }

        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key(schema.query, "viewer")).value,
            )
        assertEquals(
            setOf("firstName", "lastName", "displayName", "greeting"),
            viewer.keys.map { key -> key.field.fieldName }.toSet(),
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `resolves descendant demand before its consuming sibling`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = NESTED_SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val user = schema.objectType("User")
                    val profile = schema.objectType("Profile")
                    val profileKey = schema.key(user, "profile")
                    val rawKey = schema.key(profile, "raw")
                    val renderedKey = schema.key(profile, "rendered")

                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    schema.objectOf("User") {
                                        "profile" setTo
                                            objectOf("Profile") {
                                                "raw" setTo "engineer"
                                            }
                                    }
                                },
                            ),
                        schema.field("Profile", "rendered") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Profile {
                                          raw
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    require(input.fieldValues.keys == setOf(rawKey))
                                    val raw =
                                        input.fieldValues.getValue(rawKey) as Value.String
                                    Value.String.of("Role: ${raw.stringValue}")
                                },
                            ),
                        schema.field("User", "message") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          profile {
                                            rendered
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    require(input.fieldValues.keys == setOf(profileKey))
                                    val profileInput =
                                        input.fieldValues.getValue(profileKey) as Value.Object
                                    require(
                                        profileInput.fieldValues.keys == setOf(renderedKey),
                                    )
                                    val rendered =
                                        profileInput.fieldValues.getValue(renderedKey) as Value.String
                                    Value.String.of(rendered.stringValue)
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val (fragment, selections) =
            context(world) {
                parsedFragment(
                    """
                    fragment ignored on Query {
                      viewer {
                        message
                      }
                    }
                    """.trimIndent(),
                )
            }

        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key(schema.query, "viewer")).value,
            )
        val profile =
            assertIs<EngineResult.Object>(
                viewer.fetch(schema.key(schema.objectType("User"), "profile")).value,
            )
        assertEquals(
            setOf("raw", "rendered"),
            profile.keys.map { key -> key.field.fieldName }.toSet(),
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `unpacks a finite recursive resolver output from its returned value`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = RECURSIVE_SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val chain = schema.objectType("Chain")
                    val nextKey = schema.key(chain, "next")
                    val labelKey = schema.key(chain, "label")
                    mapOf(
                        schema.field("Query", "chain") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    schema.objectOf("Chain") {
                                        "label" setTo "first"
                                        "next" setTo
                                            objectOf("Chain") {
                                                "label" setTo "second"
                                                "next" setTo null
                                            }
                                    }
                                },
                            ),
                        schema.field("Chain", "computed") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Chain {
                                          next {
                                            label
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    val next =
                                        input.fieldValues.getValue(nextKey) as Value.Object
                                    val label =
                                        next.fieldValues.getValue(labelKey) as Value.String
                                    Value.String.of(label.stringValue)
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val (fragment, selections) =
            context(world) {
                parsedFragment(
                    """
                    fragment ignored on Query {
                      chain {
                        computed
                      }
                    }
                    """.trimIndent(),
                )
            }

        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val chain =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key(schema.query, "chain")).value,
            )
        val next =
            assertIs<EngineResult.Object>(
                chain.fetch(schema.key(schema.objectType("Chain"), "next")).value,
            )
        assertEquals(setOf("label", "next"), next.keys.map { it.field.fieldName }.toSet())
        assertEquals(
            null,
            next.fetch(schema.key(schema.objectType("Chain"), "next")).value,
        )
        assertEquals(
            "second",
            assertIs<Value.String>(
                chain.fetch(schema.key(schema.objectType("Chain"), "computed")).value,
            ).stringValue,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `preserves argument tuples and dispatches abstract node lists by typed ID`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = NODE_LIST_SCHEMA_SDL,
                nodeResolvers = { schema ->
                    mapOf(
                        schema.objectType("User") to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-${id.idValue}"
                                }
                            },
                        schema.objectType("Admin") to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("Admin") {
                                    "id" setTo id
                                    "level" setTo 7
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val nodes = schema.field("Query", "nodes")
                    val elementType =
                        (nodes.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    mapOf(
                        nodes to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, arguments ->
                                    val group =
                                        arguments.fieldValues.getValue("group") as Value.String
                                    Value.OutputList.of(
                                        typeExpr = elementType,
                                        values =
                                            listOf(
                                                schema.objectOf("User") {
                                                    "id" setTo "${group.stringValue}-user"
                                                },
                                                schema.objectOf("Admin") {
                                                    "id" setTo "${group.stringValue}-admin"
                                                },
                                            ),
                                    )
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
                  first: nodes(group: "first") {
                    id
                    ... on User {
                      name
                    }
                    ... on Admin {
                      level
                    }
                  }
                  second: nodes(group: "second") {
                    id
                  }
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        val nodesField = schema.objectField("Query", "nodes")
        val bridgeField = schema.objectField("Query", "nodes\$ids")
        val firstKey = Value.ObjectKey.of(nodesField, mapOf("group" to "first"))
        val secondKey = Value.ObjectKey.of(nodesField, mapOf("group" to "second"))
        val firstBridge = Value.ObjectKey.of(bridgeField, mapOf("group" to "first"))
        val secondBridge = Value.ObjectKey.of(bridgeField, mapOf("group" to "second"))
        assertEquals(
            setOf(firstKey, secondKey, firstBridge, secondBridge),
            result.keys,
        )
        val first = assertIs<EngineResult.List>(result.fetch(firstKey).value)
        assertEquals(
            listOf("User", "Admin"),
            first.map { cell -> assertIs<EngineResult.Object>(cell.value).type.typeName },
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    context(world: model.Assumptions)
    private fun parsedFragment(source: String) =
        world.fragmentFrom(source).let { it to it.subselections }

    private fun generatedResolution(
        testWorld: TestWorld,
        querySource: String,
    ): EngineResult.Object {
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom(querySource)
        val selections = fragment.subselections
        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }
        return context(world) {
            check(result.correctResolution(fragment))
            result
        }
    }

    private companion object {
        val FLAT_SCHEMA_SDL =
            """
            type User {
              firstName: String!
              lastName: String!
              displayName: String!
              greeting: String!
            }

            type Query {
              viewer: User!
            }
            """.trimIndent()

        val NESTED_SCHEMA_SDL =
            """
            type Profile {
              raw: String!
              rendered: String!
            }

            type User {
              profile: Profile!
              message: String!
            }

            type Query {
              viewer: User!
            }
            """.trimIndent()

        val RECURSIVE_SCHEMA_SDL =
            """
            type Chain {
              label: String!
              next: Chain
              computed: String!
            }

            type Query {
              chain: Chain!
            }
            """.trimIndent()

        val NODE_LIST_SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
            }

            type Admin implements Node {
              id: ID!
              level: Int!
            }

            type Query {
              nodes(group: String!): [Node!]!
            }
            """.trimIndent()
    }
}

private fun Schema.objectType(typeName: String): Schema.ObjectType =
    type(typeName) as Schema.ObjectType

private fun Schema.key(
    type: Schema.ObjectType,
    fieldName: String,
): Value.ObjectKey =
    Value.ObjectKey.of(
        field = objectField(type.typeName, fieldName),
        arguments = emptyMap(),
    )
