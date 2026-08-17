package semantics.contract

import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Contract for source fields whose node outputs are resolved through fixture-lowered loaders.
 */
interface NodeResolverContract : ResolverContract {
    @Test
    fun `resolves an empty query through field and node resolvers`() {
        var viewerId: Value.Input? = null
        var greetingPrefix: Value.Input? = null
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      viewer(id: ID!): User! @resolver(result: {id: "1"})
                    }

                    type User implements Node
                      @nodeResolver(result: [{id: "1", result: {name: 7}}]) {
                      id: ID!
                      name: Int!
                      greeting(prefix: Int!): Int!
                        @resolver(result: "sumplus1(${'$'}prefix)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, arguments, _ ->
                    when {
                        field.containingType.typeName == "Query" &&
                            field.fieldName.startsWith("viewer") -> {
                            require(input.hasExactlyFields())
                            viewerId = arguments.fieldValues.getValue("id")
                        }
                        field.containingType.typeName == "User" &&
                            field.fieldName == "greeting" -> {
                            require(input.hasExactlyFields())
                            greetingPrefix = arguments.fieldValues.getValue("prefix")
                        }
                    }
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  viewer(id: "1") {
                    id
                    name
                    greeting(prefix: 5)
                  }
                }
                """.trimIndent(),
            )

        resolveAndValidate(world, world.objectOf("Query"), fragment)
        assertEquals(Value.ID.of("1"), viewerId)
        assertEquals(Value.Int.of(5), greetingPrefix)
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
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("Profile") {
                                    "id" setTo id
                                    "name" setTo "Ada"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
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
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { viewer { card { profile { id name } } } }",
            )

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val viewer =
            assertIs<EngineResult.Object>(
                result.getCell(schema.contractKey("Query", "viewer")).get(),
            )
        val card =
            assertIs<EngineResult.Object>(
                viewer.getCell(schema.contractKey("Viewer", "card")).get(),
            )
        val bridgeKey = schema.contractKey("Card", "profile_V_A_node")
        val bridge = assertIs<EngineResult.Object>(card.getCell(bridgeKey).get())
        val profile =
            assertIs<EngineResult.Object>(
                bridge.getCell(schema.contractKey("Profile_V_A_Bridge", "node")).get(),
            )

        assertEquals(expectedPassiveResultKeys(card.type, setOf(bridgeKey)), card.keys)
        assertEquals(
            "\$node:7:Profileprofile-1",
            assertIs<Value.ID>(
                bridge.getCell(schema.contractKey("Profile_V_A_Bridge", "id")).get(),
            ).idValue,
        )
        assertEquals(
            "profile-1",
            assertIs<Value.ID>(
                profile.getCell(schema.contractKey("Profile", "id")).get(),
            ).idValue,
        )
        assertEquals(
            "Ada",
            assertIs<Value.String>(
                profile.getCell(schema.contractKey("Profile", "name")).get(),
            ).stringValue,
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
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-${id.idValue}"
                                }
                            },
                        schema.contractObjectType("Admin") to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("Admin") {
                                    "id" setTo id
                                    "level" setTo 7
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val nodes = schema.field("Query", "nodes_V_A_node")
                    val elementType =
                        TypeExpr.List.of(
                            TypeExpr.Named.of(
                                schema.type("Node") as Schema.OutputType,
                                isNullable = false,
                            ),
                            isNullable = false,
                        ).elementType
                    mapOf(
                        nodes to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { input, arguments ->
                                require(input.hasExactlyFields())
                                val group =
                                    arguments.fieldValues.getValue("group") as Value.String
                                Value.OutputList.of(
                                    elementType,
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
                    )
                },
            )
        val world = testWorld.newAssumptions()
        val schema = world.schema
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  first: nodes(group: "first") {
                    id
                    ... on User { name }
                    ... on Admin { level }
                  }
                  second: nodes(group: "second") { id }
                }
                """.trimIndent(),
            )

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val bridgeField = schema.objectField("Query", "nodes_V_A_node")
        val bridgeType = schema.contractObjectType("Node_V_A_Bridge")
        val payloadKey = schema.contractKey("Node_V_A_Bridge", "node")
        val firstKey = Value.GroundKey.of(bridgeField, mapOf("group" to "first"))
        val secondKey = Value.GroundKey.of(bridgeField, mapOf("group" to "second"))

        assertEquals(
            setOf(firstKey, secondKey),
            result.keys,
        )
        val first = assertIs<EngineResult.List>(result.getCell(firstKey).get())
        assertEquals(
            listOf("User", "Admin"),
            first.map { cell ->
                val bridge = assertIs<EngineResult.Object>(cell.get())
                assertEquals(bridgeType, bridge.type)
                assertIs<EngineResult.Object>(
                    bridge.getCell(payloadKey).get(),
                ).type.typeName
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
                    observedFields += field.fieldName
                },
                nodeResolvers = { schema ->
                    mapOf(
                        schema.contractObjectType("User") to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-${id.idValue}"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val matrix = schema.field("Query", "matrix_V_A_node")
                    val outer =
                        TypeExpr.List.of(
                            TypeExpr.List.of(
                                TypeExpr.Named.of(
                                    schema.type("User") as Schema.OutputType,
                                    isNullable = false,
                                ),
                                isNullable = false,
                            ),
                            isNullable = false,
                        )
                    val inner = outer.elementType as TypeExpr.List<Schema.OutputType>
                    fun row(vararg ids: String): Value.OutputList =
                        Value.OutputList.of(
                            typeExpr = inner.elementType,
                            values =
                                ids.map { id ->
                                    schema.objectOf("User") {
                                        "id" setTo id
                                    }
                                },
                        )
                    mapOf(
                        matrix to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    Value.OutputList.of(
                                        typeExpr = outer.elementType,
                                        values = listOf(row("a", "b"), row("c")),
                                    )
                                },
                            ),
                    )
                },
            )
        val world = testWorld.newAssumptions()
        val schema = world.schema
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { matrix { id name } }",
            )

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val matrix =
            assertIs<EngineResult.List>(
                result.getCell(schema.contractKey("Query", "matrix_V_A_node")).get(),
            )
        val payloadTypes =
            matrix.map { row ->
                assertIs<EngineResult.List>(row.get()).map { bridgeCell ->
                    val bridge = assertIs<EngineResult.Object>(bridgeCell.get())
                    assertIs<EngineResult.Object>(
                        bridge.getCell(schema.contractKey("User_V_A_Bridge", "node")).get(),
                    ).type.typeName
                }
            }.flatten()

        assertEquals(listOf("User", "User", "User"), payloadTypes)
        assertEquals(3, observedFields.count { it == "node" })
    }
}
