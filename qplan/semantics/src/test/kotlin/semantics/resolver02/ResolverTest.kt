package semantics.resolver02

import model.EngineResult
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.selectionsFrom
import model.selectionForestOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import semantics.spec.flatten
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolverTest {
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
                Value.Object.of(schema.query).resolve(selections)
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
                    val query = schema.query
                    val user = schema.objectType("User")
                    val firstNameKey = schema.key(user, "firstName")
                    val lastNameKey = schema.key(user, "lastName")
                    val displayNameKey = schema.key(user, "displayName")

                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.fragment(query),
                                function = { input, _ ->
                                    require(input.fieldValues.isEmpty())
                                    Value.Object.of(
                                        type = user,
                                        fields =
                                            mapOf(
                                                firstNameKey to Value.String.of("Ada"),
                                                lastNameKey to Value.String.of("Lovelace"),
                                            ),
                                    )
                                },
                            ),
                        schema.field("User", "displayName") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragment(
                                        user,
                                        schema.selection(user, "firstName"),
                                        schema.selection(user, "lastName"),
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
                                    schema.fragment(
                                        user,
                                        schema.selection(user, "displayName"),
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
                Value.Object.of(schema.query).resolve(selections)
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
                    val query = schema.query
                    val user = schema.objectType("User")
                    val profile = schema.objectType("Profile")
                    val profileKey = schema.key(user, "profile")
                    val rawKey = schema.key(profile, "raw")
                    val renderedKey = schema.key(profile, "rendered")

                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.fragment(query),
                                function = { _, _ ->
                                    Value.Object.of(
                                        type = user,
                                        fields =
                                            mapOf(
                                                profileKey to
                                                    Value.Object.of(
                                                        type = profile,
                                                        fields =
                                                            mapOf(
                                                                rawKey to
                                                                    Value.String.of("engineer"),
                                                            ),
                                                    ),
                                            ),
                                    )
                                },
                            ),
                        schema.field("Profile", "rendered") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragment(
                                        profile,
                                        schema.selection(profile, "raw"),
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
                                    schema.fragment(
                                        user,
                                        schema.selection(
                                            user,
                                            "profile",
                                            selectionForestOf(
                                                schema.selection(profile, "rendered"),
                                            ),
                                        ),
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
                Value.Object.of(schema.query).resolve(selections)
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
    private fun parsedFragment(source: String): Pair<Fragment, SelectionForest> {
        val (nominalType, specSelections) = world.selectionsFrom(source)
        val selections = flatten(nominalType, specSelections)
        return Fragment.of(nominalType, selections) to selections
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

private fun Schema.fragment(
    type: Schema.ObjectType,
    vararg selections: Selection,
): Fragment =
    Fragment.of(
        nominalType = type,
        subselections = selectionForestOf(*selections),
    )

private fun Schema.selection(
    type: Schema.ObjectType,
    fieldName: String,
    subselections: SelectionForest = selectionForestOf(),
): Selection =
    Selection.of(
        key = key(type, fieldName),
        nominalType = type,
        possibleTypes = setOf(type),
        subselections = subselections,
    )

private fun Schema.key(
    type: Schema.ObjectType,
    fieldName: String,
): Value.Key =
    Value.Key.of(
        field = field(type.typeName, fieldName),
        arguments = emptyMap(),
    )
