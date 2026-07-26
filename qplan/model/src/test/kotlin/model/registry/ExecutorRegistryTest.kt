package model.registry

import model.Fragment
import model.Schema
import model.Selection
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutorRegistryTest {
    @Test
    fun `looks up and invokes node and field resolvers by schema coordinate`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    mapOf<String, NodeResolver>(
                        "User" to
                            NodeResolver { id ->
                                assertEquals("42", id.idValue)
                                schema.objectValue(
                                    user,
                                    mapOf("id" to id),
                                )
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    val queryFragment =
                        object : Fragment {
                            override val nominalType = schema.query
                            override val subselections = emptyList<model.Selection>()
                        }
                    mapOf<FieldCoordinate, FieldResolver>(
                        FieldCoordinate("Query", "user") to
                            FieldResolver(
                                objectFragment = queryFragment,
                                function = { parent, arguments ->
                                    assertEquals(
                                        schema.objectValue(schema.query, emptyMap()),
                                        parent,
                                    )
                                    assertEquals(Schema.NoArguments, arguments.type)
                                    schema.objectValue(
                                        user,
                                        mapOf("id" to schema.idValue("42")),
                                    )
                                },
                            ),
                    )
                },
            )
        val schema = world.schema
        val query = schema.objectValue(schema.query, emptyMap())
        val userType = schema.type("User") as Schema.ObjectType
        val user =
            schema.objectValue(
                userType,
                mapOf("id" to schema.idValue("42")),
            )
        val userField = schema.field("Query", "user")
        val registry = world.executorRegistry
        val assumptions = world.assumptions

        assertEquals(registry, assumptions.executorRegistry)
        assertEquals(
            user,
            registry.nodeResolver(userType).function(schema.idValue("42")),
        )
        val (objectFragment, fieldResolverFunction) =
            registry.fieldResolver(userField)
        assertEquals(schema.query, objectFragment.nominalType)
        assertEquals(emptyList(), objectFragment.subselections)
        assertEquals(
            user,
            fieldResolverFunction(
                query,
                schema.argumentsValue(userField, emptyMap()),
            ),
        )
    }

    @Test
    fun `distinguishes missing executors from foreign schema definitions`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val registry = world.executorRegistry
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

        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
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

    @Test
    fun `selection factory distinguishes empty composites and rejects subselections on leaves`() {
        val fixture = Fixture()

        val leaf = fixture.selection("Node", "id")
        val emptyComposite = fixture.selection("User", "friend")

        assertTrue(leaf.isLeaf)
        assertTrue(leaf.subselections.isEmpty())
        assertFalse(emptyComposite.isLeaf)
        assertTrue(emptyComposite.subselections.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            Selection.of(
                key = leaf.key,
                nominalType = leaf.nominalType,
                possibleTypes = leaf.possibleTypes,
                subselections = listOf(emptyComposite),
            )
        }
    }

    private class Fixture {
        private val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val assumptions = world.assumptions
        val user = schema.type("User") as Schema.ObjectType

        fun selection(
            typeName: String,
            fieldName: String,
            possibleTypes: Set<Schema.ObjectType> =
                (schema.type(typeName) as Schema.CompositeType).possibleTypes,
            subselections: List<model.Selection> = emptyList(),
        ): model.Selection {
            val nominalType = schema.type(typeName) as Schema.CompositeType
            return Selection.of(
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
