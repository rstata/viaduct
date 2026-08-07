package semantics

import model.EngineResult
import model.PathComponent
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolveValueTest {
    @Test
    fun `constructs typename directly and returns exact resolver paths`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Profile {
                      raw: String!
                      rendered: String!
                    }

                    type User {
                      name: String!
                      profile: Profile!
                      computed: String!
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
                            ) { _, _ -> schema.objectOf("User") },
                        schema.field("User", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> Value.String.of("computed") },
                        schema.field("Profile", "rendered") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> Value.String.of("rendered") },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val userType = schema.type("User") as Schema.ObjectType
        val profileType = schema.type("Profile") as Schema.ObjectType
        val typeNameKey = Value.ObjectKey.of(schema.objectField("User", "__typename"), emptyMap())
        val computedKey = Value.ObjectKey.of(schema.objectField("User", "computed"), emptyMap())
        val profileKey = Value.ObjectKey.of(schema.objectField("User", "profile"), emptyMap())
        val rawKey = Value.ObjectKey.of(schema.objectField("Profile", "raw"), emptyMap())
        val value =
            schema.objectOf("User") {
                "name" setTo "Ada"
                "profile" setTo
                    objectOf("Profile") {
                        "raw" setTo "engineer"
                    }
            }
        val selections =
            world.fragmentFrom(
                """
                fragment ignored on User {
                  __typename
                  name
                  computed
                  profile {
                    raw
                    rendered
                  }
                }
                """.trimIndent(),
            ).subselections

        val resolved =
            context(world) {
                value.resolveValue(
                    path = emptyList(),
                    resolverDemand = selections,
                    beSelective = true,
                )
            }

        val result = assertIs<EngineResult.Object>(resolved.engineResult)
        val typeName =
            assertIs<Value.String>(
                result.fetch(typeNameKey).value,
            )
        assertEquals("User", typeName.stringValue)
        assertTrue(computedKey !in result.keys)

        val profile = assertIs<EngineResult.Object>(result.fetch(profileKey).value)
        assertEquals(userType, result.type)
        assertEquals(profileType, profile.type)
        assertEquals(setOf(rawKey), profile.keys)
        assertEquals(
            setOf(emptyList(), listOf(profileKey)),
            resolved.pathsNeedingResolution.keys,
        )
        assertEquals(4, resolved.pathsNeedingResolution.getValue(emptyList()).size)
        assertEquals(2, resolved.pathsNeedingResolution.getValue(listOf(profileKey)).size)
    }

    @Test
    fun `non-selective traversal unpacks every provided passive field but only demanded resolver paths`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Profile {
                      raw: String!
                      rendered: String!
                    }

                    type User {
                      name: String!
                      profile: Profile!
                      computed: String!
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
                            ) { _, _ -> schema.objectOf("User") },
                        schema.field("User", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> Value.String.of("computed") },
                        schema.field("Profile", "rendered") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> Value.String.of("rendered") },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val nameKey = Value.ObjectKey.of(schema.objectField("User", "name"), emptyMap())
        val profileKey = Value.ObjectKey.of(schema.objectField("User", "profile"), emptyMap())
        val rawKey = Value.ObjectKey.of(schema.objectField("Profile", "raw"), emptyMap())
        val value =
            schema.objectOf("User") {
                "name" setTo "Ada"
                "profile" setTo
                    objectOf("Profile") {
                        "raw" setTo "engineer"
                    }
            }
        val resolverDemand =
            world.fragmentFrom(
                "fragment ignored on User { computed }",
            ).subselections

        val resolved =
            context(world) {
                value.resolveValue(
                    path = emptyList(),
                    resolverDemand = resolverDemand,
                    beSelective = false,
                )
            }

        val result = assertIs<EngineResult.Object>(resolved.engineResult)
        assertEquals(setOf(nameKey, profileKey), result.keys)
        val profile = assertIs<EngineResult.Object>(result.fetch(profileKey).value)
        assertEquals(setOf(rawKey), profile.keys)
        assertEquals(setOf(emptyList()), resolved.pathsNeedingResolution.keys)
    }

    @Test
    fun `selective traversal rejects an output field outside selections`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      selected: String!
                      extra: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val value =
            world.schema.objectOf("User") {
                "selected" setTo "kept"
                "extra" setTo "rejected"
            }
        val selections =
            world.fragmentFrom(
                "fragment ignored on User { selected }",
            ).subselections

        assertFailsWith<IllegalArgumentException> {
            context(world) {
                value.resolveValue(
                    path = emptyList(),
                    resolverDemand = selections,
                    beSelective = true,
                )
            }
        }
    }

    @Test
    fun `non-selective assumptions allow output fields outside selections`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      selected: String!
                      extra: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
                selectiveResolvers = false,
            )
        val world = testWorld.assumptions
        val selectedKey = Value.ObjectKey.of(world.schema.objectField("User", "selected"), emptyMap())
        val value =
            world.schema.objectOf("User") {
                "selected" setTo "kept"
                "extra" setTo "ignored"
            }
        val selections =
            world.fragmentFrom(
                "fragment ignored on User { selected }",
            ).subselections

        val resolved =
            context(world) {
                value.resolveValue(
                    path = emptyList(),
                    resolverDemand = selections,
                    beSelective = true,
                )
            }

        val result = assertIs<EngineResult.Object>(resolved.engineResult)
        assertEquals(setOf(selectedKey), result.keys)
    }

    @Test
    fun `list traversal records and replays exact object occurrence paths`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Nested {
                      rendered: Int!
                    }

                    type Item {
                      nested: Nested!
                      computed: Int!
                    }

                    type Query {
                      items: [Item!]!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val emptyQuery = schema.emptyFragmentOf("Query")
                    val emptyItem = schema.emptyFragmentOf("Item")
                    val emptyNested = schema.emptyFragmentOf("Nested")
                    mapOf(
                        schema.field("Query", "items") to
                            model.testing.fieldResolverOf(emptyQuery) { _, _ ->
                                error("Not invoked")
                            },
                        schema.field("Item", "computed") to
                            model.testing.fieldResolverOf(emptyItem) { _, _ ->
                                error("Not invoked")
                            },
                        schema.field("Nested", "rendered") to
                            model.testing.fieldResolverOf(emptyNested) { _, _ ->
                                error("Not invoked")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val itemsField = schema.objectField("Query", "items")
        val elementType =
            (itemsField.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
        val output =
            Value.OutputList.of(
                typeExpr = elementType,
                values =
                    listOf(
                        schema.objectOf("Item") {
                            "nested" setTo schema.objectOf("Nested")
                        },
                        schema.objectOf("Item") {
                            "nested" setTo schema.objectOf("Nested")
                        },
                    ),
            )
        val selections =
            world.fragmentFrom(
                """
                fragment ignored on Item {
                  computed
                  nested {
                    rendered
                  }
                }
                """.trimIndent(),
            ).subselections
        val itemsKey = Value.ObjectKey.of(itemsField, emptyMap())
        val nestedKey = Value.ObjectKey.of(schema.objectField("Item", "nested"), emptyMap())
        val rootPath = listOf<PathComponent>(itemsKey)
        val expectedPaths =
            setOf(
                rootPath + Value.ListIndex.of(0),
                rootPath + Value.ListIndex.of(0) + nestedKey,
                rootPath + Value.ListIndex.of(1),
                rootPath + Value.ListIndex.of(1) + nestedKey,
            )

        val resolvedValue =
            context(world) {
                output.resolveValue(
                    path = rootPath,
                    resolverDemand = selections,
                    beSelective = true,
                )
            }
        val callbackPaths = mutableListOf<List<PathComponent>>()
        val replayed =
            output.resolvePaths(
                path = rootPath,
                resolvedValue = resolvedValue,
            ) { path, _, _, resolved ->
                callbackPaths += path
                resolved
            }

        assertEquals(expectedPaths, resolvedValue.pathsNeedingResolution.keys)
        assertEquals(expectedPaths, callbackPaths.toSet())
        assertEquals(expectedPaths.size, callbackPaths.size)
        assertEquals(resolvedValue.engineResult, replayed)
    }
}
