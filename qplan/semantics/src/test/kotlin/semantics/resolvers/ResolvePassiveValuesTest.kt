package semantics.resolvers

import model.requireType
import model.requireField
import model.requireObjectField
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import viaduct.graphql.schema.ViaductSchema
import model.EngineObjectDataEntry
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.objectOf
import model.outputType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import semantics.shared.OperationContext

class ResolvePassiveValuesTest {
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
                        schema.requireField("Query", "user") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.requireField("User", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> "computed" },
                        schema.requireField("Profile", "rendered") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> "rendered" },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val userType = schema.requireType("User") as ViaductSchema.Object
        val profileType = schema.requireType("Profile") as ViaductSchema.Object
        val typeNameKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("User", "V_A_typename"),
                emptyMap(),
            )
        val computedKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "computed"), emptyMap())
        val profileKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "profile"), emptyMap())
        val rawKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Profile", "raw"), emptyMap())
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
            context(OperationContext(world)) {
                value.resolvePassiveValues(
                    expectedType = world.schema.requireObjectField("Query", "user").outputType,
                    path = emptyList(),
                    constructionDemand = selections,
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
            resolved.objectsNeedingResolution.associateBy { passiveObjectOccurrence ->
                passiveObjectOccurrence.path
            }
        assertEquals(setOf(emptyList()), resolutionsByPath.keys)
        assertSame(result, resolutionsByPath.getValue(emptyList()).target)
        assertEquals(4, resolutionsByPath.getValue(emptyList()).selections.size)
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
                        schema.requireField("Query", "user") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.requireField("User", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> "computed" },
                        schema.requireField("Profile", "rendered") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> "rendered" },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val nameKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "name"), emptyMap())
        val profileKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "profile"), emptyMap())
        val rawKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Profile", "raw"), emptyMap())
        val value =
            schema.objectOf("User") {
                "name" setTo "Ada"
                "profile" setTo
                    objectOf("Profile") {
                        "raw" setTo "engineer"
                    }
            }
        val constructionDemand =
            world.fragmentFrom(
                "fragment ignored on User { computed }",
            ).subselections

        val resolved =
            context(OperationContext(world)) {
                value.resolvePassiveValues(
                    expectedType = world.schema.requireObjectField("Query", "user").outputType,
                    path = emptyList(),
                    constructionDemand = constructionDemand,
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
            context(OperationContext(world)) {
                value.resolvePassiveValues(
                    expectedType = world.schema.requireObjectField("Query", "user").outputType,
                    path = emptyList(),
                    constructionDemand = selections,
                )
            }
        }
    }

    @Test
    fun `selective output permits fields in invocation demand beyond construction demand`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Item {
                      computed: Int!
                      seed: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                ).assumptions
        val computedKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Item", "computed"),
                emptyMap(),
            )
        val seedKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Item", "seed"),
                emptyMap(),
            )
        val value =
            world.schema.objectOf("Item") {
                "computed" setTo 7
                "seed" setTo 3
            }
        val constructionDemand =
            world.fragmentFrom("fragment ignored on Item { computed }").subselections
        val invocationDemand =
            world.fragmentFrom("fragment ignored on Item { computed seed }").subselections

        val resolved =
            context(OperationContext(world)) {
                value.resolvePassiveValues(
                    expectedType = world.schema.requireObjectField("Query", "item").outputType,
                    path = emptyList(),
                    constructionDemand = constructionDemand,
                    invocationDemand = invocationDemand,
                )
            }

        val result = assertIs<ObjectEngineResult>(resolved.engineResult)
        assertEquals(setOf(computedKey, seedKey), result.keys)
    }

    @Test
    fun `missing invocation-only fields do not require downstream resolution`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Item {
                      computed: Int!
                      seed: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                ).assumptions
        val value =
            world.schema.objectOf("Item") {
                "computed" setTo 7
            }
        val constructionDemand =
            world.fragmentFrom("fragment ignored on Item { computed }").subselections
        val invocationDemand =
            world.fragmentFrom("fragment ignored on Item { computed seed }").subselections

        val resolved =
            context(OperationContext(world)) {
                value.resolvePassiveValues(
                    expectedType = world.schema.requireObjectField("Query", "item").outputType,
                    path = emptyList(),
                    constructionDemand = constructionDemand,
                    invocationDemand = invocationDemand,
                )
            }

        assertEquals(emptyList(), resolved.objectsNeedingResolution)
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
        val selectedKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("User", "selected"), emptyMap())
        val extraKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("User", "extra"), emptyMap())
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
            context(OperationContext(world)) {
                value.resolvePassiveValues(
                    expectedType = world.schema.requireObjectField("Query", "user").outputType,
                    path = emptyList(),
                    constructionDemand = selections,
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
        val itemType = world.schema.requireType("Item") as ViaductSchema.Object
        val field = world.schema.requireObjectField("Item", "value")
        val value =
            engineObjectDataOf(
                schemaType = itemType,
                fields =
                    listOf(
                        EngineObjectDataEntry.of(
                            selection = field.name,
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
            context(OperationContext(world)) {
                value.resolvePassiveValues(
                    expectedType = world.schema.requireObjectField("Query", "item").outputType,
                    path = emptyList(),
                    constructionDemand = selections,
                )
            }
        }
    }

    @Test
    fun `list traversal retains exact roots requiring resolution without rebuilding paths`() {
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
                        schema.requireField("Query", "items") to
                            fieldResolverOf(emptyQuery) { _, _ ->
                                error("Not invoked")
                            },
                        schema.requireField("Item", "computed") to
                            fieldResolverOf(emptyItem) { _, _ ->
                                error("Not invoked")
                            },
                        schema.requireField("Nested", "rendered") to
                            fieldResolverOf(emptyNested) { _, _ ->
                                error("Not invoked")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val itemsField = schema.requireObjectField("Query", "items")
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
        val nestedKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Item", "nested"), emptyMap())
        val computedKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Item", "computed"), emptyMap())
        val renderedKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Nested", "rendered"), emptyMap())
        val rootPath = listOf<PathComponent>(itemsKey)
        val expectedRootPaths =
            setOf(
                rootPath + ListEngineResult.Index.of(0),
                rootPath + ListEngineResult.Index.of(1),
            )
        val passiveValuesResult =
            context(OperationContext(world)) {
                output.resolvePassiveValues(
                    expectedType = itemsField.outputType,
                    path = rootPath,
                    constructionDemand = selections,
                )
            }
        val callbackPaths = mutableListOf<List<PathComponent>>()
        val replayed =
            passiveValuesResult.resolveRetainedObjects { passiveObjectOccurrence ->
                callbackPaths += passiveObjectOccurrence.path
                passiveObjectOccurrence.target.reserveCell(computedKey).also { cell ->
                    cell.setValue(1)
                }
            }

        val resolutionsByPath =
            passiveValuesResult.objectsNeedingResolution.associateBy { passiveObjectOccurrence ->
                passiveObjectOccurrence.path
            }
        assertEquals(expectedRootPaths, resolutionsByPath.keys)
        assertEquals(expectedRootPaths, callbackPaths.toSet())
        assertEquals(expectedRootPaths.size, callbackPaths.size)
        assertTrue(
            callbackPaths.zipWithNext().all { (left, right) -> left.size >= right.size },
        )
        assertSame(passiveValuesResult.engineResult, replayed)

        val result = assertIs<ListEngineResult>(replayed)
        result.forEachIndexed { index, cell ->
            val item = assertIs<ObjectEngineResult>(cell.getValue().get())
            val itemPath = rootPath + ListEngineResult.Index.of(index)
            assertSame(item, resolutionsByPath.getValue(itemPath).target)
            assertEquals(1, item.getCell(computedKey).getValue().get())

            val nested = assertIs<ObjectEngineResult>(item.getCell(nestedKey).getValue().get())
            assertTrue(renderedKey !in nested.keys)
        }
    }
}
