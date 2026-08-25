package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.engineObjectDataOf
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectKey
import model.requireQueryTypeDef
import model.requireField
import model.requireObjectField
import model.requireType
import model.SourceSchemaAdapter
import model.lowering.VIADUCT_IGNORE_SYMBOL
import model.schemaType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.graphql.schema.graphqljava.gjDef

class NodeBridgeLoweringTest {
    @Test
    fun `retains the source GraphQL schema while lowering only the model schema`() {
        val schema =
            TestWorld.fromSDL(
                schemaSDL =
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
                    """.trimIndent(),
            ).schema as GJSchema

        assertNotNull(schema.graphQLSchema.queryType.getFieldDefinition("user"))
        assertNull(schema.graphQLSchema.queryType.getFieldDefinition("user_V_A_node"))
        assertNull(schema.graphQLSchema.getType("User_V_A_Bridge"))

        assertFailsWith<IllegalStateException> {
            schema.requireField("Query", "user")
        }
        val producer = schema.requireField("Query", "user_V_A_node")
        val bridge = schema.requireType("User_V_A_Bridge") as ViaductSchema.Object
        val nodeBridge = schema.requireType("Node_V_A_Bridge") as ViaductSchema.Interface
        assertEquals(bridge, producer.type.baseTypeDef)
        assertEquals(
            setOf("id", "node"),
            bridge.fields.mapTo(linkedSetOf(), ViaductSchema.Field::name),
        )
        assertEquals(setOf(bridge), bridge.possibleObjectTypes)
        assertEquals(setOf(bridge), nodeBridge.possibleObjectTypes)

        val query = schema.requireQueryTypeDef()
        val sourceQuery = schema.graphQLSchema.queryType
        assertSame(sourceQuery, query.gjDef)
        assertNotNull(query.gjDef.getFieldDefinition("user"))
        assertNull(query.gjDef.getFieldDefinition("user_V_A_node"))

        val bridgeWitness = engineObjectDataOf(bridge).type
        assertSame(bridgeWitness, engineObjectDataOf(bridge).type)
        assertEquals(
            setOf("id", "node"),
            bridgeWitness.fieldDefinitions.mapTo(linkedSetOf()) { it.name },
        )

        val user = schema.requireType("User") as ViaductSchema.Object
        assertSame(schema.graphQLSchema.getObjectType("User"), user.gjDef)
        assertNull(user.gjDef.getFieldDefinition("V_A_typename"))
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

        schemasByInvalidName.forEach { (invalidName, source) ->
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    TestWorld.fromSDL(source.trimIndent())
                }
            assertContains(exception.message.orEmpty(), invalidName)
            assertTrue(exception.message.orEmpty().contains("reserved token V_A"))
        }
    }

    @Test
    fun `rejects the shared GraphQL relation ignore symbol`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    """
                    type VIADUCT_IGNORE {
                      value: String
                    }

                    type Query {
                      ignored: VIADUCT_IGNORE
                    }
                    """.trimIndent(),
                )
            }

        assertContains(exception.message.orEmpty(), VIADUCT_IGNORE_SYMBOL)
        assertContains(exception.message.orEmpty(), "reserved symbol")
    }

    @Test
    fun `lowers every Node record and transfers its interface hierarchy`() {
        val schema =
            TestWorld.fromSDL(
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
                """.trimIndent(),
            ).schema as GJSchema

        val nodeBridge = schema.requireType("Node_V_A_Bridge") as ViaductSchema.Interface
        val namedBridge = schema.requireType("NamedNode_V_A_Bridge") as ViaductSchema.Interface
        val unusedBridge = schema.requireType("UnusedNode_V_A_Bridge") as ViaductSchema.Interface
        val userBridge = schema.requireType("User_V_A_Bridge") as ViaductSchema.Object
        val adminBridge = schema.requireType("Admin_V_A_Bridge") as ViaductSchema.Object

        assertEquals(setOf(userBridge, adminBridge), nodeBridge.possibleObjectTypes)
        assertEquals(setOf(userBridge), namedBridge.possibleObjectTypes)
        assertTrue(unusedBridge.possibleObjectTypes.isEmpty())
        listOf(nodeBridge, namedBridge, unusedBridge, userBridge, adminBridge).forEach { bridge ->
            assertEquals(
                setOf("id", "node"),
                bridge.fields.mapTo(linkedSetOf(), ViaductSchema.Field::name),
            )
            assertEquals(
                bridge.name.removeSuffix("_V_A_Bridge"),
                bridge.requireField("node").type.baseTypeDef.name,
            )
        }

        val containerField = schema.requireField("Container", "item_V_A_node")
        val shelfField = schema.requireField("Shelf", "item_V_A_node")
        assertEquals(nodeBridge, containerField.type.baseTypeDef)
        assertEquals(userBridge, shelfField.type.baseTypeDef)

        val fragment = schema.fragmentFrom("fragment F on Container { item { id } }")
        val abstractProducer = fragment.subselections.single()
        assertEquals(containerField, abstractProducer.key.field)
        assertEquals(
            shelfField,
            abstractProducer
                .objectKey(schema.requireType("Shelf") as ViaductSchema.Object)
                .field,
        )
        val payload = abstractProducer.subselections.merge(userBridge).single()
        assertEquals(userBridge.requireField("node"), payload.key.field)

        val user = schema.requireType("User") as ViaductSchema.Object
        val lowered =
            schema.lowerSourceOutput(
                containerField,
                engineObjectDataOf(user, mapOf("id" to "user-1")),
            )
        assertEquals(userBridge, assertIs<EngineObjectData.Sync>(lowered).schemaType)
    }

    @Test
    fun `bridge lookup is nullable for ordinary and synthetic source fields`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Query {
                      seed: ID!
                      user: User!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val user = schema.requireType("User") as ViaductSchema.Object
                    mapOf(user to nodeResolverOf { error("Not invoked") })
                },
            )
        val schema = world.schema as GJSchema
        val bridge = SourceSchemaAdapter(schema).field("Query", "user")

        assertFailsWith<IllegalStateException> {
            schema.requireField("Query", "user")
        }
        assertSame(bridge, schema.nodeBridgeFieldOrNull(bridge))
        assertNull(schema.nodeBridgeFieldOrNull(schema.requireField("Query", "seed")))
    }

    @Test
    fun `accepts tenant-produced Node values without a private qplan EOD downcast`() {
        val schema =
            TestWorld.fromSDL(
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
                """.trimIndent(),
            ).schema as GJSchema
        val producer = schema.requireField("Query", "user_V_A_node")
        val sourceUser = requireNotNull(schema.graphQLSchema.getObjectType("User"))
        val tenantValue =
            EngineObjectDataBuilder
                .from(sourceUser)
                .put("id", "user-1")
                .build()

        val lowered = schema.lowerSourceOutput(producer, tenantValue)

        val bridge = assertIs<EngineObjectData.Sync>(lowered)
        assertEquals("User_V_A_Bridge", bridge.schemaType.name)
        assertEquals("\$node:4:Useruser-1", bridge.get("id"))
    }

    @Test
    fun `adapts tenant-produced ordinary object output into the canonical lowered schema`() {
        lateinit var tenantValue: EngineObjectData.Sync
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      value: String!
                    }

                    type Payload {
                      items: [Item!]!
                    }

                    type Query {
                      payload: Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val sourceSchema = (schema as GJSchema).graphQLSchema
                    val item =
                        EngineObjectDataBuilder
                            .from(requireNotNull(sourceSchema.getObjectType("Item")))
                            .put("value", "one")
                            .build()
                    tenantValue =
                        EngineObjectDataBuilder
                            .from(requireNotNull(sourceSchema.getObjectType("Payload")))
                            .put("items", listOf(item))
                            .build()
                    mapOf(
                        schema.requireField("Query", "payload") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                tenantValue
                            },
                    )
                },
            )
        val field = world.schema.requireObjectField("Query", "payload")

        val lowered =
            context(world.newAssumptions(selectiveResolvers = false)) {
                world.resolverRegistry.resolver(field)(
                    world.resolverRegistry.createRootQueryInput(),
                    model.Arguments.Resolved.of(field, emptyMap()),
                )
            }

        assertTrue(lowered !== tenantValue)
        val payload = assertIs<EngineObjectData.Sync>(lowered)
        assertEquals(world.schema.requireType("Payload"), payload.schemaType)
        val items = assertIs<List<*>>(payload.get("items"))
        val item = assertIs<EngineObjectData.Sync>(items.single())
        assertEquals(world.schema.requireType("Item"), item.schemaType)
        assertEquals("one", item.get("value"))
    }
}
