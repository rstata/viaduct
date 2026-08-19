package semantics

import viaduct.engine.api.EngineObjectData

import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Schema
import model.TypeExpr
import model.EngineObjectDataEntry
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResolveValueTest {
    @Test
    fun `leaves demanded active typename unresolved and retains exact resolver objects`() {
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
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.field("User", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> "computed" },
                        schema.field("Profile", "rendered") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> "rendered" },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val userType = schema.type("User") as Schema.ObjectType
        val profileType = schema.type("Profile") as Schema.ObjectType
        val typeNameKey =
            ObjectEngineResult.GroundKey.of(
                schema.objectField("User", "V_I_typename"),
                emptyMap(),
            )
        val computedKey = ObjectEngineResult.GroundKey.of(schema.objectField("User", "computed"), emptyMap())
        val profileKey = ObjectEngineResult.GroundKey.of(schema.objectField("User", "profile"), emptyMap())
        val rawKey = ObjectEngineResult.GroundKey.of(schema.objectField("Profile", "raw"), emptyMap())
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
                    expectedType = world.schema.objectField("Query", "user").typeExpr,
                    path = emptyList(),
                    resolverDemand = selections,
                )
            }

        val result = assertIs<ObjectEngineResult>(resolved.engineResult)
        assertTrue(typeNameKey !in result.keys)
        assertTrue(computedKey !in result.keys)

        val profile = assertIs<ObjectEngineResult>(result.getCell(profileKey).getValue().get())
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
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.field("User", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> "computed" },
                        schema.field("Profile", "rendered") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> "rendered" },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val nameKey = ObjectEngineResult.GroundKey.of(schema.objectField("User", "name"), emptyMap())
        val profileKey = ObjectEngineResult.GroundKey.of(schema.objectField("User", "profile"), emptyMap())
        val rawKey = ObjectEngineResult.GroundKey.of(schema.objectField("Profile", "raw"), emptyMap())
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
                    expectedType = world.schema.objectField("Query", "user").typeExpr,
                    path = emptyList(),
                    resolverDemand = resolverDemand,
                )
            }

        val result = assertIs<ObjectEngineResult>(resolved.engineResult)
        assertEquals(setOf(nameKey, profileKey), result.keys)
        val profile = assertIs<ObjectEngineResult>(result.getCell(profileKey).getValue().get())
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
                    expectedType = world.schema.objectField("Query", "user").typeExpr,
                    path = emptyList(),
                    resolverDemand = selections,
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
        val selectedKey = ObjectEngineResult.GroundKey.of(world.schema.objectField("User", "selected"), emptyMap())
        val extraKey = ObjectEngineResult.GroundKey.of(world.schema.objectField("User", "extra"), emptyMap())
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
                    expectedType = world.schema.objectField("Query", "user").typeExpr,
                    path = emptyList(),
                    resolverDemand = selections,
                )
            }

        val result = assertIs<ObjectEngineResult>(resolved.engineResult)
        assertEquals(setOf(selectedKey, extraKey), result.keys)
    }

    @Test
    fun `rejects an argument-bearing passive object field`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Item {
                      value(index: Int): String
                    }

                    type Query {
                      item: Item
                    }
                    """.trimIndent(),
                ).assumptions
        val itemType = world.schema.type("Item") as Schema.ObjectType
        val field = world.schema.objectField("Item", "value")
        val value =
            engineObjectDataOf(
                schemaType = itemType,
                fields =
                    listOf(
                        EngineObjectDataEntry.of(
                            selection = field.fieldName,
                            field = field,
                            value = "one",
                        ),
                    ),
            )
        val selections =
            world.fragmentFrom(
                "fragment ignored on Item { value(index: 1) }",
            ).subselections

        assertFailsWith<IllegalArgumentException> {
            context(world) {
                value.resolveValue(
                    expectedType = world.schema.objectField("Query", "item").typeExpr,
                    path = emptyList(),
                    resolverDemand = selections,
                )
            }
        }
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
                            fieldResolverOf(emptyQuery) { _, _ ->
                                error("Not invoked")
                            },
                        schema.field("Item", "computed") to
                            fieldResolverOf(emptyItem) { _, _ ->
                                error("Not invoked")
                            },
                        schema.field("Nested", "rendered") to
                            fieldResolverOf(emptyNested) { _, _ ->
                                error("Not invoked")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val itemsField = schema.objectField("Query", "items")
        val output =
            listOf(
                        schema.objectOf("Item") {
                            "nested" setTo schema.objectOf("Nested")
                        },
                        schema.objectOf("Item") {
                            "nested" setTo schema.objectOf("Nested")
                        },
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
        val itemsKey = ObjectEngineResult.GroundKey.of(itemsField, emptyMap())
        val nestedKey = ObjectEngineResult.GroundKey.of(schema.objectField("Item", "nested"), emptyMap())
        val computedKey = ObjectEngineResult.GroundKey.of(schema.objectField("Item", "computed"), emptyMap())
        val renderedKey = ObjectEngineResult.GroundKey.of(schema.objectField("Nested", "rendered"), emptyMap())
        val rootPath = listOf<PathComponent>(itemsKey)
        val expectedPaths =
            setOf(
                rootPath + ListEngineResult.Index.of(0),
                rootPath + ListEngineResult.Index.of(0) + nestedKey,
                rootPath + ListEngineResult.Index.of(1),
                rootPath + ListEngineResult.Index.of(1) + nestedKey,
            )

        val resolvedValue =
            context(world) {
                output.resolveValue(
                    expectedType = itemsField.typeExpr,
                    path = rootPath,
                    resolverDemand = selections,
                )
            }
        val callbackPaths = mutableListOf<List<PathComponent>>()
        val replayed =
            resolvedValue.resolveObjects { objectResolution ->
                callbackPaths += objectResolution.path
                when (objectResolution.target.type.typeName) {
                    "Item" ->
                        objectResolution.target.reserveCell(computedKey).also { cell ->
                            cell.setValue(1)
                            cell.setAccessResult(true)
                        }

                    "Nested" ->
                        objectResolution.target.reserveCell(renderedKey).also { cell ->
                            cell.setValue(2)
                            cell.setAccessResult(true)
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

        val result = assertIs<ListEngineResult>(replayed)
        result.forEachIndexed { index, cell ->
            val item = assertIs<ObjectEngineResult>(cell.getValue().get())
            val itemPath = rootPath + ListEngineResult.Index.of(index)
            assertSame(item, resolutionsByPath.getValue(itemPath).target)
            assertEquals(1, item.getCell(computedKey).getValue().get())

            val nested = assertIs<ObjectEngineResult>(item.getCell(nestedKey).getValue().get())
            assertSame(
                nested,
                resolutionsByPath.getValue(itemPath + nestedKey).target,
            )
            assertEquals(2, nested.getCell(renderedKey).getValue().get())
        }
    }
}
