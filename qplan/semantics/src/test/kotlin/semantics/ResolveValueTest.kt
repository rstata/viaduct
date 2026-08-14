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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResolveValueTest {
    @Test
    fun `constructs typename directly and retains exact resolver objects`() {
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
        val typeNameKey = Value.GroundKey.of(schema.objectField("User", "__typename"), emptyMap())
        val computedKey = Value.GroundKey.of(schema.objectField("User", "computed"), emptyMap())
        val profileKey = Value.GroundKey.of(schema.objectField("User", "profile"), emptyMap())
        val rawKey = Value.GroundKey.of(schema.objectField("Profile", "raw"), emptyMap())
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
                    retainCompleteOutput = false,
                )
            }

        val result = assertIs<EngineResult.Object>(resolved.engineResult)
        val typeName =
            assertIs<Value.String>(
                result.getCell(typeNameKey).getValue().get(),
            )
        assertEquals("User", typeName.stringValue)
        assertTrue(computedKey !in result.keys)

        val profile = assertIs<EngineResult.Object>(result.getCell(profileKey).getValue().get())
        assertEquals(userType, result.type)
        assertEquals(profileType, profile.type)
        assertEquals(setOf(rawKey), profile.keys)
        val resolutionsByPath =
            resolved.objectsNeedingResolution.associateBy { objectResolution ->
                objectResolution.path
            }
        assertEquals(
            setOf(emptyList(), listOf(profileKey)),
            resolutionsByPath.keys,
        )
        assertSame(result, resolutionsByPath.getValue(emptyList()).target)
        assertSame(profile, resolutionsByPath.getValue(listOf(profileKey)).target)
        assertEquals(4, resolutionsByPath.getValue(emptyList()).selections.size)
        assertEquals(2, resolutionsByPath.getValue(listOf(profileKey)).selections.size)
    }

    @Test
    fun `non-selective traversal unpacks every provided passive field but only demanded resolver paths`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = false,
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
        val nameKey = Value.GroundKey.of(schema.objectField("User", "name"), emptyMap())
        val profileKey = Value.GroundKey.of(schema.objectField("User", "profile"), emptyMap())
        val rawKey = Value.GroundKey.of(schema.objectField("Profile", "raw"), emptyMap())
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
                    retainCompleteOutput = false,
                )
            }

        val result = assertIs<EngineResult.Object>(resolved.engineResult)
        assertEquals(setOf(nameKey, profileKey), result.keys)
        val profile = assertIs<EngineResult.Object>(result.getCell(profileKey).getValue().get())
        assertEquals(setOf(rawKey), profile.keys)
        assertEquals(
            setOf(emptyList()),
            resolved.objectsNeedingResolution.map { it.path }.toSet(),
        )
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
                    retainCompleteOutput = false,
                )
            }
        }
    }

    @Test
    fun `non-selective worlds retain output fields outside selections`() {
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
        val selectedKey = Value.GroundKey.of(world.schema.objectField("User", "selected"), emptyMap())
        val extraKey = Value.GroundKey.of(world.schema.objectField("User", "extra"), emptyMap())
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
                    retainCompleteOutput = false,
                )
            }

        val result = assertIs<EngineResult.Object>(resolved.engineResult)
        assertEquals(setOf(selectedKey, extraKey), result.keys)
    }

    @Test
    fun `list traversal populates exact object occurrences without rebuilding paths`() {
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
        val itemsKey = Value.GroundKey.of(itemsField, emptyMap())
        val nestedKey = Value.GroundKey.of(schema.objectField("Item", "nested"), emptyMap())
        val computedKey = Value.GroundKey.of(schema.objectField("Item", "computed"), emptyMap())
        val renderedKey = Value.GroundKey.of(schema.objectField("Nested", "rendered"), emptyMap())
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
                    retainCompleteOutput = false,
                )
            }
        val callbackPaths = mutableListOf<List<PathComponent>>()
        val replayed =
            resolvedValue.resolveObjects { objectResolution ->
                callbackPaths += objectResolution.path
                when (objectResolution.target.type.typeName) {
                    "Item" ->
                        objectResolution.target.reserveCell(computedKey).also { cell ->
                            cell.setValue(Value.Int.of(1))
                            cell.setAccessAccepted(Value.Boolean.of(true))
                        }

                    "Nested" ->
                        objectResolution.target.reserveCell(renderedKey).also { cell ->
                            cell.setValue(Value.Int.of(2))
                            cell.setAccessAccepted(Value.Boolean.of(true))
                        }

                    else -> error("Unexpected object type")
                }
            }

        val resolutionsByPath =
            resolvedValue.objectsNeedingResolution.associateBy { objectResolution ->
                objectResolution.path
            }
        assertEquals(expectedPaths, resolutionsByPath.keys)
        assertEquals(expectedPaths, callbackPaths.toSet())
        assertEquals(expectedPaths.size, callbackPaths.size)
        assertTrue(
            callbackPaths.zipWithNext().all { (left, right) -> left.size >= right.size },
        )
        assertSame(resolvedValue.engineResult, replayed)

        val result = assertIs<EngineResult.List>(replayed)
        result.forEachIndexed { index, cell ->
            val item = assertIs<EngineResult.Object>(cell.getValue().get())
            val itemPath = rootPath + Value.ListIndex.of(index)
            assertSame(item, resolutionsByPath.getValue(itemPath).target)
            assertEquals(Value.Int.of(1), item.getCell(computedKey).getValue().get())

            val nested = assertIs<EngineResult.Object>(item.getCell(nestedKey).getValue().get())
            assertSame(
                nested,
                resolutionsByPath.getValue(itemPath + nestedKey).target,
            )
            assertEquals(Value.Int.of(2), nested.getCell(renderedKey).getValue().get())
        }
    }
}
