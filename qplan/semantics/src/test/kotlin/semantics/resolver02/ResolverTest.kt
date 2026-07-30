package semantics.resolver02

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.arbitrary.resolverTestBatch
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
                    (ResolverFragmentsEnabled to true)

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                assertTrue(generatedResolutionIsCorrect(testWorld, testCase.query.source))
            }
        }

    @Test
    fun `generated property detects missing transitive demand closure`() {
        val counts =
            TestCaseCount(
                schemas = 2,
                registriesPerSchema = 3,
                queriesPerSchema = 5,
            )
        val config =
            Config.default +
                (ResolverFragmentsEnabled to true)
        val random = RandomSource.seeded(-2028282154048352130L)
        val batches =
            List(counts.schemas) {
                Arb.resolverTestBatch(counts, config).next(random)
            }
        var passingCases = 0
        var failingCases = 0

        batches.forEach { batch ->
            batch.registries.forEach { registry ->
                val ordinaryWorld = registry.world(batch.schema)
                val mutantWorld =
                    registry.world(
                        schema = batch.schema,
                        noTransitiveDemand = true,
                    )
                batch.queries.forEach { query ->
                    assertTrue(
                        generatedResolutionIsCorrect(ordinaryWorld, query.source),
                        "The mutation-control corpus must pass ordinary resolver02",
                    )
                    val correct =
                        runCatching {
                            generatedResolutionIsCorrect(mutantWorld, query.source)
                        }.getOrDefault(false)
                    if (correct) passingCases += 1 else failingCases += 1
                }
            }
        }

        assertTrue(passingCases > 0, "The mutant should not reject every generated case")
        assertTrue(failingCases > 0, "Generated cases did not detect the transitive-demand mutant")
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

    context(world: model.Assumptions)
    private fun parsedFragment(source: String) =
        world.fragmentFrom(source).let { it to it.subselections }

    private fun generatedResolutionIsCorrect(
        testWorld: TestWorld,
        querySource: String,
    ): Boolean {
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom(querySource)
        val selections = fragment.subselections
        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }
        return context(world) {
            result.correctResolution(fragment)
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
    }
}

private fun Schema.objectType(typeName: String): Schema.ObjectType =
    type(typeName) as Schema.ObjectType

private fun Schema.key(
    type: Schema.ObjectType,
    fieldName: String,
): Value.Key =
    Value.Key.of(
        field = field(type.typeName, fieldName),
        arguments = emptyMap(),
    )
