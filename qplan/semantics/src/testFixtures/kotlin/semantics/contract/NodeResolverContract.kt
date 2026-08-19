package semantics.contract

import model.requireField
import model.requireObjectField
import model.EngineResult
import model.EngineIDResult
import model.EngineOutputListData
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.TypeExpr
import model.emptyFragmentOf
import model.objectOf
import model.requireType
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Contract for source fields whose node outputs are resolved through fixture-lowered loaders.
 */
interface NodeResolverContract : ResolverContract {
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
            world.schema.requireObjectField("Query", "viewer_V_A_node"),
            mapOf("id" to "1"),
        )
        testWorld.applicationArguments.assertArguments(
            world.schema.requireObjectField("User", "greeting"),
            mapOf("prefix" to 5),
        )
    }

    @Test
    fun `resolves a nested passive node through its synthetic bridge`() {
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
        val bridgeKey = schema.contractKey("Card", "profile_V_A_node")
        val bridge = assertIs<ObjectEngineResult>(card.getCell(bridgeKey).get())
        val profile =
            assertIs<ObjectEngineResult>(
                bridge.getCell(schema.contractKey("Profile_V_A_Bridge", "node")).get(),
            )

        assertEquals(expectedPassiveResultKeys(card.type, setOf(bridgeKey)), card.keys)
        assertEquals(
            EngineIDResult.of("\$node:7:Profileprofile-1"),
            bridge.getCell(schema.contractKey("Profile_V_A_Bridge", "id")).get(),
        )
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
                    val nodes = schema.requireField("Query", "nodes_V_A_node")
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
        val bridgeField = schema.requireObjectField("Query", "nodes_V_A_node")
        val bridgeType = schema.contractObjectType("Node_V_A_Bridge")
        val payloadKey = schema.contractKey("Node_V_A_Bridge", "node")
        val firstKey = ObjectEngineResult.GroundKey.of(bridgeField, mapOf("group" to "first"))
        val secondKey = ObjectEngineResult.GroundKey.of(bridgeField, mapOf("group" to "second"))

        assertEquals(
            setOf(firstKey, secondKey),
            result.keys,
        )
        val first = assertIs<ListEngineResult>(result.getCell(firstKey).get())
        assertEquals(
            listOf("User", "Admin"),
            first.map { cell ->
                val bridge = assertIs<ObjectEngineResult>(cell.get())
                assertEquals(bridgeType, bridge.type)
                assertIs<ObjectEngineResult>(
                    bridge.getCell(payloadKey).get(),
                ).type.name
            },
        )
    }

    @Test
    fun `dispatches every nested node-list bridge occurrence`() {
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
                    val matrix = schema.requireField("Query", "matrix_V_A_node")
                    val outer =
                        TypeExpr.List.of(
                            TypeExpr.List.of(
                                TypeExpr.Named.of(
                                    schema.requireType("User") as Schema.OutputTypeDef,
                                    isNullable = false,
                                ),
                                isNullable = false,
                            ),
                            isNullable = false,
                        )
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
                result.getCell(schema.contractKey("Query", "matrix_V_A_node")).get(),
            )
        val payloadTypes =
            matrix.map { row ->
                assertIs<ListEngineResult>(row.get()).map { bridgeCell ->
                    val bridge = assertIs<ObjectEngineResult>(bridgeCell.get())
                    assertIs<ObjectEngineResult>(
                        bridge.getCell(schema.contractKey("User_V_A_Bridge", "node")).get(),
                    ).type.name
                }
            }.flatten()

        assertEquals(listOf("User", "User", "User"), payloadTypes)
        assertEquals(3, observedFields.count { it == "node" })
    }
}
