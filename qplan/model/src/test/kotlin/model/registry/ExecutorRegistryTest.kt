package model.registry

import model.Assumptions
import model.GJSchema
import model.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ExecutorRegistryTest {
    @Test
    fun `looks up and invokes node and field resolvers by schema coordinate`() {
        val schema = GJSchema.fromSDL(SCHEMA_SDL)
        val query = schema.objectValue(schema.query, emptyMap())
        val userType = schema.type("User") as Schema.ObjectType
        val user =
            schema.objectValue(
                userType,
                mapOf("id" to schema.idValue("42")),
            )
        val userField = schema.field("Query", "user")
        val registry =
            ExecutorRegistry.of(
                schema = schema,
                nodeResolvers =
                    mapOf(
                        "User" to { id ->
                            assertEquals("42", id.idValue)
                            user
                        },
                    ),
                fieldResolvers =
                    mapOf(
                        FieldCoordinate("Query", "user") to { parent, arguments ->
                            assertEquals(query, parent)
                            assertEquals(Schema.NoArguments, arguments.type)
                            user
                        },
                    ),
            )
        val assumptions =
            Assumptions.of(
                schema = schema,
                bindings = emptyMap(),
                executorRegistry = registry,
            )

        assertEquals(registry, assumptions.executorRegistry)
        assertEquals(
            user,
            registry.nodeResolver(userType)(schema.idValue("42")),
        )
        assertEquals(
            user,
            registry.fieldResolver(userField)(
                query,
                schema.argumentsValue(userField, emptyMap()),
            ),
        )
    }

    @Test
    fun `distinguishes missing executors from foreign schema definitions`() {
        val schema = GJSchema.fromSDL(SCHEMA_SDL)
        val registry = ExecutorRegistry.empty(schema)
        val userType = schema.type("User") as Schema.ObjectType
        val userField = schema.field("Query", "user")

        val missingNode =
            assertFailsWith<MissingExecutorException> {
                registry.nodeResolver(userType)
            }
        assertEquals("User", missingNode.typeName)
        assertEquals(null, missingNode.fieldName)

        val missingField =
            assertFailsWith<MissingExecutorException> {
                registry.fieldResolver(userField)
            }
        assertEquals("Query", missingField.typeName)
        assertEquals("user", missingField.fieldName)

        val foreignSchema = GJSchema.fromSDL(SCHEMA_SDL)
        assertFailsWith<IllegalArgumentException> {
            registry.nodeResolver(
                foreignSchema.type("User") as Schema.ObjectType,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.fieldResolver(foreignSchema.field("Query", "user"))
        }
    }

    @Test
    fun `snips selected fields recursively through objects and lists`() {
        val fixture = Fixture()
        val friend =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    "id" to fixture.schema.idValue("friend"),
                    "name" to fixture.schema.stringValue("Friend"),
                ),
            )
        val peer =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    "id" to fixture.schema.idValue("peer"),
                    "name" to fixture.schema.stringValue("Peer"),
                ),
            )
        val source =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    "id" to fixture.schema.idValue("target"),
                    "name" to fixture.schema.stringValue("Target"),
                    "friend" to friend,
                    "peers" to fixture.schema.outputListValue(listOf(peer, null)),
                ),
            )
        val selections =
            listOf(
                fixture.selection("Node", "id"),
                fixture.selection(
                    typeName = "User",
                    fieldName = "friend",
                    subselections = listOf(fixture.selection("Node", "id")),
                ),
                fixture.selection(
                    typeName = "User",
                    fieldName = "peers",
                    subselections = listOf(fixture.selection("Node", "name")),
                ),
                fixture.selection(
                    typeName = "Node",
                    fieldName = "name",
                    possibleTypes = emptySet(),
                ),
            )

        val result =
            with(fixture.assumptions) {
                source.snip(selections)
            }

        assertEquals(setOf("id", "friend", "peers"), result.outputObjectFields.keys)
        assertEquals(
            "target",
            assertIs<Schema.IDValue>(result.outputObjectFields["id"]).idValue,
        )
        val snippedFriend = assertIs<Schema.ObjectValue>(result.outputObjectFields["friend"])
        assertEquals(setOf("id"), snippedFriend.outputObjectFields.keys)
        val peers = assertIs<Schema.ListValue>(result.outputObjectFields["peers"])
        val snippedPeer = assertIs<Schema.ObjectValue>(peers.outputListValues.first())
        assertEquals(setOf("name"), snippedPeer.outputObjectFields.keys)
        assertEquals(null, peers.outputListValues.last())
    }

    @Test
    fun `snip rejects selections on unrelated types and argument-taking fields`() {
        val fixture = Fixture()
        val source =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    "id" to fixture.schema.idValue("target"),
                    "search" to null,
                ),
            )

        with(fixture.assumptions) {
            assertFailsWith<IllegalArgumentException> {
                source.snip(listOf(fixture.selection("Query", "user")))
            }
            assertFailsWith<IllegalArgumentException> {
                source.snip(listOf(fixture.selection("User", "search")))
            }
        }
    }

    private class Fixture {
        val schema = GJSchema.fromSDL(SCHEMA_SDL)
        val assumptions = Assumptions.of(schema, emptyMap())
        val user = schema.type("User") as Schema.ObjectType

        fun selection(
            typeName: String,
            fieldName: String,
            possibleTypes: Set<Schema.ObjectType> =
                (schema.type(typeName) as Schema.CompositeType).possibleTypes,
            subselections: List<model.Selection>? = null,
        ): model.Selection {
            val nominalType = schema.type(typeName) as Schema.CompositeType
            return TestSelection(
                key =
                    schema.objectEngineResultKey(
                        field = schema.field(typeName, fieldName),
                        arguments = emptyMap(),
                    ),
                nominalType = nominalType,
                possibleTypes = possibleTypes,
                subselections = subselections,
            )
        }
    }

    private class TestSelection(
        override val key: model.ObjectEngineResult.Key,
        override val nominalType: Schema.CompositeType,
        override val possibleTypes: Set<Schema.ObjectType>,
        override val subselections: List<model.Selection>?,
    ) : model.Selection

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
              name: String!
            }

            type User implements Node {
              id: ID!
              name: String!
              friend: User
              peers: [User]
              search(limit: Int): User
            }

            type Query {
              user: User
            }
            """.trimIndent()
    }
}
