package model.testing

import model.requireQueryTypeDef
import model.requireField
import model.requireType
import model.Schema
import model.SourceSchemaAdapter
import model.gjDef
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

        assertFailsWith<Schema.MissingSchemaElementException> {
            schema.requireField("Query", "user")
        }
        val producer = schema.requireField("Query", "user_V_A_node")
        val bridge = schema.requireType("User_V_A_Bridge") as Schema.Object
        assertEquals(bridge, producer.type.baseType)
        assertEquals(
            setOf("id", "node"),
            bridge.fields.mapTo(linkedSetOf(), Schema.Field::name),
        )
        assertEquals(setOf(bridge), bridge.possibleObjectTypes)

        val query = schema.requireQueryTypeDef()
        val sourceQuery = schema.graphQLSchema.queryType
        assertSame(query.gjDef, query.gjDef)
        assertTrue(query.gjDef !== sourceQuery)
        assertNotNull(query.gjDef.getFieldDefinition("user_V_A_node"))
        assertNull(query.gjDef.getFieldDefinition("user"))

        assertSame(bridge.gjDef, bridge.gjDef)
        assertEquals(
            setOf("id", "node"),
            bridge.gjDef.fieldDefinitions.mapTo(linkedSetOf()) { it.name },
        )

        val user = schema.requireType("User") as Schema.Object
        assertTrue(schema.graphQLSchema.getObjectType("User") !== user.gjDef)
        assertNotNull(user.gjDef.getFieldDefinition("V_I_typename"))
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
    fun `rejects node return covariance that would require a bridge hierarchy`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    """
                    interface Node {
                      id: ID!
                    }

                    interface Container {
                      item: Node
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Shelf implements Container {
                      item: User
                    }

                    type Query {
                      container: Container
                    }
                    """.trimIndent(),
                )
            }

        assertContains(exception.message.orEmpty(), "bridge hierarchy")
        assertContains(exception.message.orEmpty(), "Container.item")
        assertContains(exception.message.orEmpty(), "Shelf.item")
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
                    val user = schema.requireType("User") as Schema.Object
                    mapOf(user to nodeResolverOf { error("Not invoked") })
                },
            )
        val schema = world.schema as GJSchema
        val bridge = SourceSchemaAdapter(schema).field("Query", "user")

        assertFailsWith<Schema.MissingSchemaElementException> {
            schema.requireField("Query", "user")
        }
        assertSame(bridge, schema.nodeBridgeFieldOrNull(bridge))
        assertNull(schema.nodeBridgeFieldOrNull(schema.requireField("Query", "seed")))
    }
}
