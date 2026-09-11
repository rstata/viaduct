package semantics.contract

import model.requireField
import model.requireObjectField
import model.EngineErrorData
import model.EngineResult
import model.EngineIDResult
import model.EngineOutputListData
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import viaduct.graphql.schema.ViaductSchema
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.requireOutputType
import model.requireType
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.toTypeExpr
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract for source fields whose node outputs resolve through root references to `Query.node`.
 */
interface NodeResolverContract : ResolverContract {
    @Test
    fun `awaits completion for node in required selection set`() {
        val failedNodeCompleted = AtomicBoolean()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }

                    type Query { baz: Baz! }

                    type Baz implements Node {
                      id: ID!
                      name: String!
                      anotherBaz: Baz!
                      z: Int!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    mapOf(
                        schema.contractObjectType("Baz") to
                            nodeResolverOf { id ->
                                when (id) {
                                    "1" ->
                                        schema.objectOf("Baz") {
                                            "id" setTo id
                                        }
                                    "2" -> {
                                        failedNodeCompleted.set(true)
                                        EngineErrorData.of()
                                    }
                                    else -> error("Unexpected Baz ID: $id")
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val baz = schema.requireObjectField("Query", "baz")
                    val anotherBaz = schema.requireObjectField("Baz", "anotherBaz")
                    val z = schema.requireObjectField("Baz", "z")
                    mapOf(
                        baz to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Baz") {
                                    "id" setTo "1"
                                }
                            },
                        anotherBaz to
                            fieldResolverOf(schema.emptyFragmentOf("Baz")) { _, _ ->
                                schema.objectOf("Baz") {
                                    "id" setTo "2"
                                }
                            },
                        z to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Z on Baz { anotherBaz { name } }",
                                ),
                            ) { input, _ ->
                                input.get("anotherBaz")
                                5
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val result = resolveAndValidate(world, "query { baz { z } }")
        val baz =
            assertIs<ObjectEngineResult>(
                result.getCell(schema.contractKey("Query", "baz")).get(),
            )

        assertTrue(failedNodeCompleted.get())
        assertIs<ErrorEngineResult>(
            baz.getCell(schema.contractKey("Baz", "z")).get(),
        )
    }

    @Test
    fun `resolves an empty query through field and node resolvers`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      viewer(id: ID!): User!
                        @resolver(result: {id: "idFrom(${'$'}id)"})
                    }

                    type User implements Node
                      @nodeResolver(result: [{id: "1", result: {name: 7}}]) {
                      id: ID!
                      name: Int!
                      greeting(prefix: Int!): Int!
                        @resolver(result: "sumplus1(${'$'}prefix)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    if (
                        field.containingDef.name == "Query" &&
                        field.name.startsWith("viewer") ||
                        field.containingDef.name == "User" &&
                        field.name == "greeting"
                    ) {
                        require(input.hasExactlyFields())
                    }
                },
            )
        val world = testWorld.assumptions
        resolveAndValidate(
            world,
            """
                query {
                  viewer(id: "1") {
                    id
                    name
                    greeting(prefix: 5)
                  }
                }
                """.trimIndent(),
            )
        testWorld.applicationArguments.assertArguments(
            world.schema.requireObjectField("Query", "viewer"),
            mapOf("id" to "1"),
        )
        testWorld.applicationArguments.assertArguments(
            world.schema.requireObjectField("User", "greeting"),
            mapOf("prefix" to 5),
        )
    }

    @Test
    fun `resolves a nested passive node through Query node`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    type Profile implements Node { id: ID!, name: String! }
                    type Card { profile: Profile! }
                    type Viewer { card: Card! }
                    type Query { viewer: Viewer! }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    mapOf(
                        schema.contractObjectType("Profile") to
                            nodeResolverOf { id ->
                                schema.objectOf("Profile") {
                                    "id" setTo id
                                    "name" setTo "Ada"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "viewer") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { input, _ ->
                                require(input.hasExactlyFields())
                                schema.objectOf("Viewer") {
                                    "card" setTo
                                        objectOf("Card") {
                                            "profile" setTo
                                                objectOf("Profile") {
                                                    "id" setTo "profile-1"
                                                }
                                        }
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val result =
            resolveAndValidate(
                world,
                "query { viewer { card { profile { id name } } } }",
            )
        val viewer =
            assertIs<ObjectEngineResult>(
                result.getCell(schema.contractKey("Query", "viewer")).get(),
            )
        val card =
            assertIs<ObjectEngineResult>(
                viewer.getCell(schema.contractKey("Viewer", "card")).get(),
            )
        val profileKey = schema.contractKey("Card", "profile")
        val profile =
            assertIs<ObjectEngineResult>(
                card.getCell(profileKey).get(),
            )

        assertEquals(expectedPassiveResultKeys(card.type, setOf(profileKey)), card.keys)
        assertEquals(
            EngineIDResult.of("profile-1"),
            profile.getCell(schema.contractKey("Profile", "id")).get(),
        )
        assertEquals(
            "Ada",
            profile.getCell(schema.contractKey("Profile", "name")).get(),
        )
    }

    @Test
    fun `dispatches argument-bearing abstract node lists`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    type User implements Node { id: ID!, name: String! }
                    type Admin implements Node { id: ID!, level: Int! }
                    type Query { nodes(group: String!): [Node!]! }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    mapOf(
                        schema.contractObjectType("User") to
                            nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-$id"
                                }
                            },
                        schema.contractObjectType("Admin") to
                            nodeResolverOf { id ->
                                schema.objectOf("Admin") {
                                    "id" setTo id
                                    "level" setTo 7
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val nodes = schema.requireField("Query", "nodes")
                    mapOf(
                        nodes to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { input, arguments ->
                                require(input.hasExactlyFields())
                                val group =
                                    arguments.fieldValues.getValue("group") as String
                                listOf(
                                        schema.objectOf("User") {
                                            "id" setTo "$group-user"
                                        },
                                        schema.objectOf("Admin") {
                                            "id" setTo "$group-admin"
                                        },
                                    )
                            },
                    )
                },
            )
        val world = testWorld.newAssumptions()
        val schema = world.schema
        val result =
            resolveAndValidate(
                world,
                """
                query {
                  first: nodes(group: "first") {
                    id
                    ... on User { name }
                    ... on Admin { level }
                  }
                  second: nodes(group: "second") { id }
                }
                """.trimIndent(),
        )
        val nodesField = schema.requireObjectField("Query", "nodes")
        val firstKey = ObjectEngineResult.GroundKey.of(nodesField, mapOf("group" to "first"))
        val secondKey = ObjectEngineResult.GroundKey.of(nodesField, mapOf("group" to "second"))

        assertEquals(
            setOf(firstKey, secondKey),
            result.keys,
        )
        val first = assertIs<ListEngineResult>(result.getCell(firstKey).get())
        val expectedTypes = listOf("User", "Admin")
        assertEquals(
            expectedTypes,
            first.zip(expectedTypes).map { (cell, expectedType) ->
                assertIs<ObjectEngineResult>(cell.get()).type.name
            },
        )
    }

    @Test
    fun `dispatches every nested node-list reference occurrence`() {
        val observedFields = mutableListOf<String>()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    type User implements Node { id: ID!, name: String! }
                    type Query { matrix: [[User!]!]! }
                    """.trimIndent(),
                applicationObserver = { field, _, _, _ ->
                    observedFields += field.name
                },
                nodeResolvers = { schema ->
                    mapOf(
                        schema.contractObjectType("User") to
                            nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-$id"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val matrix = schema.requireField("Query", "matrix")
                    val outer =
                        schema.toTypeExpr("!!!", "User").requireOutputType()
                    fun row(vararg ids: String): EngineOutputListData =
                        ids.map { id ->
                            schema.objectOf("User") {
                                "id" setTo id
                            }
                        }
                    mapOf(
                        matrix to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    listOf(row("a", "b"), row("c"))
                                },
                            ),
                    )
                },
            )
        val world = testWorld.newAssumptions()
        val schema = world.schema
        val result =
            resolveAndValidate(world, "query { matrix { id name } }")
        val matrix =
            assertIs<ListEngineResult>(
                result.getCell(schema.contractKey("Query", "matrix")).get(),
            )
        val resolvedTypes =
            matrix.map { row ->
                assertIs<ListEngineResult>(row.get()).map { nodeCell ->
                    assertIs<ObjectEngineResult>(nodeCell.get()).type.name
                }
            }.flatten()

        assertEquals(listOf("User", "User", "User"), resolvedTypes)
        assertEquals(3, observedFields.count { it == "node" })
    }
}
