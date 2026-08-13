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
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL = FIELD_AND_NODE_SCHEMA,
                nodeResolvers = { schema ->
                    val user = schema.contractObjectType("User")
                    mapOf(
                        user to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "Ada"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val viewer = schema.field("Query", "viewer")
                    val greeting = schema.field("User", "greeting")
                    mapOf<Schema.OutputField, FieldResolverDefinition>(
                        viewer to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { input, arguments ->
                                require(input.hasExactlyFields())
                                schema.objectOf("User") {
                                    "id" setTo arguments.fieldValues.getValue("id")
                                }
                            },
                        greeting to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { input, arguments ->
                                require(input.hasExactlyFields())
                                val prefix =
                                    arguments.fieldValues.getValue("prefix") as Value.String
                                Value.String.of("${prefix.stringValue}, Ada")
                            },
                    )
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
                    greeting(prefix: "Hello")
                  }
                }
                """.trimIndent(),
            )

        resolveAndValidate(world, world.objectOf("Query"), fragment)
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
                result.getValue(schema.contractKey("Query", "viewer")).get(),
            )
        val card =
            assertIs<EngineResult.Object>(
                viewer.getValue(schema.contractKey("Viewer", "card")).get(),
            )
        val bridgeKey = schema.contractKey("Card", "profile\$bridge")
        val bridge = assertIs<EngineResult.Object>(card.getValue(bridgeKey).get())
        val profile =
            assertIs<EngineResult.Object>(
                bridge.getValue(schema.contractKey("Profile\$Bridge", "\$node")).get(),
            )

        assertEquals(setOf(bridgeKey), card.keys)
        assertEquals(
            "\$node:7:Profileprofile-1",
            assertIs<Value.ID>(
                bridge.getValue(schema.contractKey("Profile\$Bridge", "\$id")).get(),
            ).idValue,
        )
        assertEquals(
            "profile-1",
            assertIs<Value.ID>(
                profile.getValue(schema.contractKey("Profile", "id")).get(),
            ).idValue,
        )
        assertEquals(
            "Ada",
            assertIs<Value.String>(
                profile.getValue(schema.contractKey("Profile", "name")).get(),
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
                    val nodes = schema.field("Query", "nodes")
                    val elementType =
                        (nodes.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
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
        val bridgeField = schema.objectField("Query", "nodes\$bridge")
        val bridgeType = schema.contractObjectType("Node\$Bridge")
        val payloadKey = schema.contractKey("Node\$Bridge", "\$node")
        val firstKey = Value.GroundKey.of(bridgeField, mapOf("group" to "first"))
        val secondKey = Value.GroundKey.of(bridgeField, mapOf("group" to "second"))

        assertEquals(
            setOf(firstKey, secondKey),
            result.keys,
        )
        val first = assertIs<EngineResult.List>(result.getValue(firstKey).get())
        assertEquals(
            listOf("User", "Admin"),
            first.map { value ->
                val bridge = assertIs<EngineResult.Object>(value)
                assertEquals(bridgeType, bridge.type)
                assertIs<EngineResult.Object>(
                    bridge.getValue(payloadKey).get(),
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
                    val matrix = schema.field("Query", "matrix")
                    val outer = matrix.typeExpr as TypeExpr.List<Schema.OutputType>
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
                result.getValue(schema.contractKey("Query", "matrix\$bridge")).get(),
            )
        val payloadTypes =
            matrix.map { row ->
                assertIs<EngineResult.List>(row).map { bridgeValue ->
                    val bridge = assertIs<EngineResult.Object>(bridgeValue)
                    assertIs<EngineResult.Object>(
                        bridge.getValue(schema.contractKey("User\$Bridge", "\$node")).get(),
                    ).type.typeName
                }
            }.flatten()

        assertEquals(listOf("User", "User", "User"), payloadTypes)
        assertEquals(3, observedFields.count { it == "\$node" })
    }

    private companion object {
        val FIELD_AND_NODE_SCHEMA =
            """
            interface Node { id: ID! }
            type User implements Node {
              id: ID!
              name: String!
              greeting(prefix: String!): String!
            }
            type Query { viewer(id: ID!): User! }
            """.trimIndent()
    }
}
