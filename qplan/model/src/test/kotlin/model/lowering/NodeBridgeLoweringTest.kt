package model.lowering

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.viaductSchema

class NodeBridgeLoweringTest {
    @Test
    fun `retains the source schema while rewriting Node fields in the lowered schema`() {
        val source =
            graphQLSchema(
                """
                interface Node {
                  id: ID!
                }

                type User implements Node {
                  id: ID!
                }

                type Query {
                  user: User!
                }
                """,
            )

        val lowered = lowerSchema(source)

        assertTrue(source.queryType.getFieldDefinition("user") != null)
        assertNull(source.queryType.getFieldDefinition("user_V_A_node"))
        assertNull(source.getType("User_V_A_Bridge"))

        assertNull(lowered.requireRecord("Query").field("user"))
        val loweredQuery = assertIs<ViaductSchema.Object>(lowered.requireType("Query"))
        val producer = lowered.requireField("Query", "user_V_A_node")
        val bridge = assertIs<ViaductSchema.Object>(lowered.requireType("User_V_A_Bridge"))
        val nodeBridge =
            assertIs<ViaductSchema.Interface>(lowered.requireType("Node_V_A_Bridge"))
        assertSame(source.queryType, loweredQuery.sourceGraphQLJavaDefinitionOrNull)
        assertTrue(
            loweredQuery.sourceGraphQLJavaDefinitionOrNull
                ?.getFieldDefinition("user") != null,
        )
        assertNull(
            loweredQuery.sourceGraphQLJavaDefinitionOrNull
                ?.getFieldDefinition("user_V_A_node"),
        )
        assertNull(bridge.sourceGraphQLJavaDefinitionOrNull)
        assertSame(bridge, producer.type.baseTypeDef)
        assertEquals(setOf("id", "node"), bridge.fields.mapTo(linkedSetOf()) { it.name })
        assertEquals(setOf(bridge), nodeBridge.possibleObjectTypes)
    }

    @Test
    fun `rejects reserved names throughout the source schema`() {
        val schemasByInvalidName =
            mapOf(
                "V_A_Type" to
                    """
                    type V_A_Type {
                      value: String
                    }
                    type Query {
                      value: V_A_Type
                    }
                    """,
                "value_V_A_hidden" to
                    """
                    type Query {
                      value_V_A_hidden: String
                    }
                    """,
                "argument_V_A_hidden" to
                    """
                    type Query {
                      value(argument_V_A_hidden: String): String
                    }
                    """,
                "V_A_directive" to
                    """
                    directive @V_A_directive on FIELD_DEFINITION
                    type Query {
                      value: String
                    }
                    """,
                "argument_V_A_directive" to
                    """
                    directive @tag(argument_V_A_directive: String) on FIELD_DEFINITION
                    type Query {
                      value: String
                    }
                    """,
                "VALUE_V_A_HIDDEN" to
                    """
                    enum Choice {
                      VALUE_V_A_HIDDEN
                    }
                    type Query {
                      value: Choice
                    }
                    """,
                "value_V_A_hidden_input" to
                    """
                    input Filter {
                      value_V_A_hidden_input: String
                    }
                    type Query {
                      value(filter: Filter): String
                    }
                    """,
            )

        schemasByInvalidName.forEach { (invalidName, sdl) ->
            val exception =
                assertFailsWith<IllegalArgumentException>(invalidName) {
                    lowerSchema(graphQLSchema(sdl))
                }
            assertContains(exception.message.orEmpty(), invalidName)
            assertContains(exception.message.orEmpty(), "reserved token V_A")
        }
    }

    @Test
    fun `rejects the shared GraphQL relation ignore symbol`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                lowerSchema(
                    graphQLSchema(
                        """
                        type VIADUCT_IGNORE {
                          value: String
                        }

                        type Query {
                          ignored: VIADUCT_IGNORE
                        }
                        """,
                    ),
                )
            }

        assertContains(exception.message.orEmpty(), VIADUCT_IGNORE_SYMBOL)
        assertContains(exception.message.orEmpty(), "reserved symbol")
    }

    @Test
    fun `lowers every Node record and preserves its subtype hierarchy`() {
        val lowered =
            lowerSchema(
                graphQLSchema(
                    """
                    interface Node {
                      id: ID!
                    }

                    interface NamedNode implements Node {
                      id: ID!
                      name: String!
                    }

                    interface UnusedNode implements Node {
                      id: ID!
                    }

                    interface Container {
                      item: Node
                    }

                    type User implements NamedNode & Node {
                      id: ID!
                      name: String!
                    }

                    type Admin implements Node {
                      id: ID!
                    }

                    type Shelf implements Container {
                      item: User
                    }

                    type Query {
                      container: Container
                    }
                    """,
                ),
            )

        val nodeBridge =
            assertIs<ViaductSchema.Interface>(lowered.requireType("Node_V_A_Bridge"))
        val namedBridge =
            assertIs<ViaductSchema.Interface>(lowered.requireType("NamedNode_V_A_Bridge"))
        val unusedBridge =
            assertIs<ViaductSchema.Interface>(lowered.requireType("UnusedNode_V_A_Bridge"))
        val userBridge =
            assertIs<ViaductSchema.Object>(lowered.requireType("User_V_A_Bridge"))
        val adminBridge =
            assertIs<ViaductSchema.Object>(lowered.requireType("Admin_V_A_Bridge"))

        mapOf(
            nodeBridge to "Node",
            namedBridge to "NamedNode",
            unusedBridge to "UnusedNode",
            userBridge to "User",
            adminBridge to "Admin",
        ).forEach { (bridge, sourceName) ->
            assertEquals(listOf("id", "node"), bridge.fields.map { it.name })
            bridge.fields.forEach { field ->
                assertTrue(field.args.isEmpty())
                assertTrue(field.type.isNullable)
            }
            assertEquals("ID", bridge.field("id")!!.type.baseTypeDef.name)
            assertEquals(sourceName, bridge.field("node")!!.type.baseTypeDef.name)
        }
        assertEquals(setOf(userBridge, adminBridge), nodeBridge.possibleObjectTypes)
        assertEquals(setOf(userBridge), namedBridge.possibleObjectTypes)
        assertTrue(unusedBridge.possibleObjectTypes.isEmpty())
        assertEquals(setOf(nodeBridge), namedBridge.supers.toSet())
        assertEquals(setOf(namedBridge, nodeBridge), userBridge.supers.toSet())
        assertEquals(setOf(nodeBridge), adminBridge.supers.toSet())

        val containerField = lowered.requireField("Container", "item_V_A_node")
        val shelfField = lowered.requireField("Shelf", "item_V_A_node")
        assertSame(nodeBridge, containerField.type.baseTypeDef)
        assertSame(userBridge, shelfField.type.baseTypeDef)
    }

    @Test
    fun `bridge fields and rewritten producers preserve the required field semantics`() {
        val source =
            graphQLSchema(
                """
                directive @tag(value: String!) on FIELD_DEFINITION | ARGUMENT_DEFINITION

                interface Node {
                  id: ID!
                }

                type User implements Node {
                  id: ID!
                }

                type Query {
                  "Loaded users"
                  users(
                    "Maximum users"
                    limit: Int = 3 @tag(value: "argument")
                  ): [[User!]!]! @tag(value: "field")
                }
                """,
            )
        val sourceSchema = source.viaductSchema()
        val sourceField = sourceSchema.requireField("Query", "users")

        val lowered = lowerSchema(source)
        val producer = lowered.requireField("Query", "users_V_A_node")
        val producerArg = producer.args.single()
        val sourceArg = sourceField.args.single()

        assertNull(lowered.requireRecord("Query").field("users"))
        assertEquals("User_V_A_Bridge", producer.type.baseTypeDef.name)
        assertEquals(sourceField.type.listDepth, producer.type.listDepth)
        assertEquals(sourceField.type.nullabilityShape(), producer.type.nullabilityShape())
        assertEquals(sourceField.description, producer.description)
        assertEquals(
            sourceField.appliedDirectives.semanticValues(),
            producer.appliedDirectives.semanticValues(),
        )
        assertEquals(sourceArg.name, producerArg.name)
        assertEquals(sourceArg.type, producerArg.type)
        assertEquals(sourceArg.description, producerArg.description)
        assertEquals(sourceArg.hasDefault, producerArg.hasDefault)
        assertEquals(sourceArg.defaultValue, producerArg.defaultValue)
        assertEquals(
            sourceArg.appliedDirectives.semanticValues(),
            producerArg.appliedDirectives.semanticValues(),
        )

        listOf(
            "Node_V_A_Bridge" to "Node",
            "User_V_A_Bridge" to "User",
        ).forEach { (bridgeName, sourceName) ->
            val bridge = assertIs<ViaductSchema.OutputRecord>(lowered.requireType(bridgeName))
            assertEquals(listOf("id", "node"), bridge.fields.map { it.name })
            bridge.fields.forEach { field ->
                assertTrue(field.args.isEmpty())
                assertTrue(field.type.isNullable)
                assertEquals(0, field.type.listDepth)
            }
            assertEquals("ID", bridge.field("id")!!.type.baseTypeDef.name)
            assertEquals(sourceName, bridge.field("node")!!.type.baseTypeDef.name)
        }
    }

    @Test
    fun `rewritten bridge references are canonical`() {
        val lowered =
            lowerSchema(
                graphQLSchema(
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Query {
                      users: [User!]!
                    }
                    """,
                ),
            )

        val producer = lowered.requireField("Query", "users_V_A_node")
        val bridge = assertIs<ViaductSchema.Object>(producer.type.baseTypeDef)
        assertSame(lowered.requireType(bridge.name), bridge)
        assertSame(lowered.requireType("User"), bridge.field("node")!!.type.baseTypeDef)
        bridge.supers.forEach { supertype ->
            assertSame(lowered.requireType(supertype.name), supertype)
        }
        bridge.possibleObjectTypes.forEach { possibleType ->
            assertSame(lowered.requireType(possibleType.name), possibleType)
        }

        val sourceUser = sourceObject(lowered, "User")
        assertSame(sourceUser, bridge.field("node")!!.type.baseTypeDef)
    }

    private fun sourceObject(
        schema: ViaductSchema,
        name: String,
    ): ViaductSchema.Object =
        schema.requireType(name) as? ViaductSchema.Object
            ?: error("$name is not an object")
}
