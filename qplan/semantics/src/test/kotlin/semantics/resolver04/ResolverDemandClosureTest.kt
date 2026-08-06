package semantics.resolver04

import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.VariableCoordinate
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ImplementationArgumentDefaultWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.conformsToFragment
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToTypename
import semantics.correctresolution.conformsToVariables
import semantics.correctresolution.correctResolution
import semantics.correctresolution.isClosedUnderResolverDemand
import semantics.correctresolution.rootedAndWellTyped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Resolver03-parity examples for ordinary exact and recursively nested demand closure.
 *
 * Keep baseline construction behavior shared by Resolver03 and Resolver04 in this suite.
 */
class ResolverDemandClosureTest {
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
    fun `resolves recursive demand introduced by an object fragment`() {
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
        assertEquals(setOf("label"), next.keys.map { it.field.fieldName }.toSet())
        assertEquals(
            "second",
            assertIs<Value.String>(
                chain.fetch(schema.key(schema.objectType("Chain"), "computed")).value,
            ).stringValue,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    context(world: model.Assumptions)
    private fun parsedFragment(source: String) =
        world.fragmentFrom(source).let { it to it.subselections }

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
