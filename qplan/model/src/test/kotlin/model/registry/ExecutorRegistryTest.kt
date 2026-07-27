package model.registry

import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.selectionForestOf
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
                    mapOf<Schema.ObjectType, NodeResolver>(
                        user to
                            NodeResolver { id ->
                                assertEquals("42", id.idValue)
                                schema.objectValue(
                                    user,
                                    mapOf(schema.key(user, "id") to id),
                                )
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    val userField = schema.field("Query", "user")
                    val queryFragment =
                        object : Fragment {
                            override val nominalType = schema.query
                            override val subselections = selectionForestOf()
                        }
                    mapOf<Schema.OutputField, FieldResolver>(
                        userField to
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
                                        mapOf(
                                            schema.key(user, "id") to schema.idValue("42"),
                                        ),
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
                mapOf(schema.key(userType, "id") to schema.idValue("42")),
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
        assertTrue(objectFragment.subselections.isEmpty())
        assertEquals(
            user,
            fieldResolverFunction(
                query,
                schema.argumentsValue(userField, emptyMap()),
            ),
        )
    }

    @Test
    fun `field resolvers return the complete nullable output-value algebra`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      scalar: String!
                      list: [String]
                      nullable: String
                      failed: String
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val fragment =
                        object : Fragment {
                            override val nominalType = schema.query
                            override val subselections = selectionForestOf()
                        }
                    mapOf<Schema.OutputField, FieldResolver>(
                        schema.field("Query", "scalar") to
                            FieldResolver(fragment) { _, _ -> schema.stringValue("value") },
                        schema.field("Query", "list") to
                            FieldResolver(fragment) { _, _ ->
                                schema.outputListValue(
                                    listOf(schema.stringValue("value"), null),
                                )
                            },
                        schema.field("Query", "nullable") to
                            FieldResolver(fragment) { _, _ -> null },
                        schema.field("Query", "failed") to
                            FieldResolver(fragment) { _, _ -> Schema.ErrorValue },
                    )
                },
            )
        val schema = world.schema
        val parent = schema.objectValue(schema.query, emptyMap())
        val outputs =
            listOf("scalar", "list", "nullable", "failed").associateWith { fieldName ->
                val field = schema.field("Query", fieldName)
                world.executorRegistry
                    .fieldResolver(field)
                    .function(parent, schema.argumentsValue(field, emptyMap()))
            }

        assertEquals(schema.stringValue("value"), outputs.getValue("scalar"))
        assertEquals(
            schema.outputListValue(listOf(schema.stringValue("value"), null)),
            outputs.getValue("list"),
        )
        assertEquals(null, outputs.getValue("nullable"))
        assertEquals(Schema.ErrorValue, outputs.getValue("failed"))

        outputs.forEach { (fieldName, output) ->
            val projection =
                with(world.assumptions) {
                    schema.field("Query", fieldName).snip(output, selectionForestOf())
                }
            assertEquals(output, projection)
        }
    }

    @Test
    fun `distinguishes missing executors from foreign schema definitions`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val registry = world.executorRegistry
        val userType = schema.type("User") as Schema.ObjectType
        val userField = schema.field("User", "name")

        assertFalse(registry.hasNodeResolver(schema.query))
        assertFalse(registry.hasFieldResolver(schema.field("Node", "name")))

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
        assertEquals("User", missingField.typeName)
        assertEquals("name", missingField.fieldName)

        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
        assertFailsWith<IllegalArgumentException> {
            registry.nodeResolver(
                foreignSchema.type("User") as Schema.ObjectType,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.fieldResolver(foreignSchema.field("User", "name"))
        }
    }

    @Test
    fun `rejects a field resolver whose object fragment is not its canonical parent type`() {
        assertFailsWith<IllegalArgumentException> {
            worldWithFragmentType { schema ->
                schema.type("User") as Schema.ObjectType
            }
        }

        val foreignQuery = TestWorld.fromSDL(SCHEMA_SDL).schema.query
        assertFailsWith<IllegalArgumentException> {
            worldWithFragmentType { foreignQuery }
        }
    }

    @Test
    fun `rejects foreign resolver coordinate definitions`() {
        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val foreignUser = foreignSchema.type("User") as Schema.ObjectType
        val foreignUserField = foreignSchema.field("Query", "user")

        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = {
                    mapOf(foreignUser to NodeResolver { error("Not invoked") })
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val queryFragment =
                        object : Fragment {
                            override val nominalType = schema.query
                            override val subselections = selectionForestOf()
                        }
                    mapOf(
                        foreignUserField to
                            FieldResolver(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        }
    }

    @Test
    fun `node resolver registration requires a canonical Node interface`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      id: ID!
                    }

                    type Query {
                      user: User
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    mapOf(user to NodeResolver { error("Not invoked") })
                },
            )
        }
    }

    @Test
    fun `rejects node resolvers for object types that do not implement Node`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Other {
                      id: ID!
                    }

                    type Query {
                      user: User
                      other: Other
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val other = schema.type("Other") as Schema.ObjectType
                    mapOf(other to NodeResolver { error("Not invoked") })
                },
            )
        }
    }

    @Test
    fun `rejects field resolvers registered at abstract coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val queryFragment =
                        object : Fragment {
                            override val nominalType = schema.query
                            override val subselections = selectionForestOf()
                        }
                    val node = schema.type("Node") as Schema.InterfaceType
                    val nodeFragment =
                        object : Fragment {
                            override val nominalType = node
                            override val subselections = selectionForestOf()
                        }
                    mapOf(
                        schema.field("Query", "user") to
                            FieldResolver(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.field("Node", "name") to
                            FieldResolver(
                                objectFragment = nodeFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        }
    }

    @Test
    fun `requires a field resolver for every Query field`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                fieldResolvers = { emptyMap() },
            )
        }
    }

    @Test
    fun `rejects field resolvers for node id and typename engine fields`() {
        listOf("id", "__typename").forEach { fieldName ->
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA_SDL,
                    nodeResolvers = { schema ->
                        val user = schema.type("User") as Schema.ObjectType
                        mapOf(user to NodeResolver { error("Not invoked") })
                    },
                    fieldResolvers = { schema ->
                        val user = schema.type("User") as Schema.ObjectType
                        val fragment =
                            object : Fragment {
                                override val nominalType = user
                                override val subselections = selectionForestOf()
                            }
                        mapOf(
                            schema.field("User", fieldName) to
                                FieldResolver(
                                    objectFragment = fragment,
                                    function = { _, _ -> error("Not invoked") },
                                ),
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `public snip functions require a behavioral field or node resolver coordinate`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val user = schema.type("User") as Schema.ObjectType
        val result = schema.objectValue(user, emptyMap())

        with(world.assumptions) {
            assertFailsWith<IllegalArgumentException> {
                schema.field("User", "name").snip(result, selectionForestOf())
            }
            assertFailsWith<IllegalArgumentException> {
                user.snip(result, selectionForestOf())
            }
        }
    }

    @Test
    fun `snips selected fields recursively through objects and lists`() {
        val fixture = Fixture()
        val friend =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    fixture.key("id") to fixture.schema.idValue("friend"),
                    fixture.key("name") to fixture.schema.stringValue("Friend"),
                ),
            )
        val peer =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    fixture.key("id") to fixture.schema.idValue("peer"),
                    fixture.key("name") to fixture.schema.stringValue("Peer"),
                ),
            )
        val source =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    fixture.key("id") to fixture.schema.idValue("target"),
                    fixture.key("name") to fixture.schema.stringValue("Target"),
                    fixture.key("friend") to friend,
                    fixture.key("peers") to fixture.schema.outputListValue(listOf(peer, null)),
                ),
            )
        val selections =
            selectionForestOf(
                fixture.selection("Node", "id"),
                fixture.selection(
                    typeName = "User",
                    fieldName = "friend",
                    subselections = selectionForestOf(fixture.selection("Node", "id")),
                ),
                fixture.selection(
                    typeName = "User",
                    fieldName = "peers",
                    subselections = selectionForestOf(fixture.selection("Node", "name")),
                ),
                fixture.selection(
                    typeName = "Node",
                    fieldName = "name",
                    possibleTypes = emptySet(),
                ),
            )

        val result =
            assertIs<Schema.ObjectValue>(
                with(fixture.assumptions) {
                    fixture.userField.snip(source, selections)
                },
            )

        assertEquals(
            setOf(fixture.key("id"), fixture.key("friend"), fixture.key("peers")),
            result.fieldValues.keys,
        )
        assertEquals(
            "target",
            assertIs<Schema.IDValue>(result.fieldValues[fixture.key("id")]).idValue,
        )
        val snippedFriend =
            assertIs<Schema.ObjectValue>(result.fieldValues[fixture.key("friend")])
        assertEquals(setOf(fixture.key("id")), snippedFriend.fieldValues.keys)
        val peers = assertIs<Schema.ListValue>(result.fieldValues[fixture.key("peers")])
        val snippedPeer = assertIs<Schema.ObjectValue>(peers.outputListValues.first())
        assertEquals(setOf(fixture.key("name")), snippedPeer.fieldValues.keys)
        assertEquals(null, peers.outputListValues.last())
    }

    @Test
    fun `snip omits selections conditioned on another concrete type`() {
        val fixture = Fixture()
        val source =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(fixture.key("id") to fixture.schema.idValue("target")),
            )

        val result =
            assertIs<Schema.ObjectValue>(
                with(fixture.assumptions) {
                    fixture.userField.snip(
                        source,
                        selectionForestOf(fixture.selection("Admin", "level")),
                    )
                },
            )

        assertEquals(emptySet(), result.fieldValues.keys)
    }

    @Test
    fun `field-resolver snip stops before an argument-bearing field resolver`() {
        val fixture = Fixture()
        val source =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    fixture.key("id") to fixture.schema.idValue("target"),
                ),
            )

        val result =
            assertIs<Schema.ObjectValue>(
                with(fixture.assumptions) {
                    fixture.userField.snip(
                        source,
                        selectionForestOf(fixture.selection("User", "search")),
                    )
                },
            )

        assertEquals(emptySet(), result.fieldValues.keys)
    }

    @Test
    fun `behavioral is defined by concrete field and node resolvers`() {
        val fieldFixture = Fixture()
        val nodeFixture = Fixture(withNodeResolver = true)

        assertFalse(fieldFixture.assumptions.behavioral(fieldFixture.schema.field("User", "id")))
        assertFalse(fieldFixture.assumptions.behavioral(fieldFixture.schema.field("User", "name")))
        assertTrue(fieldFixture.assumptions.behavioral(fieldFixture.schema.field("User", "search")))
        assertTrue(
            fieldFixture.assumptions.behavioral(
                fieldFixture.schema.field("User", "__typename"),
            ),
        )
        assertFalse(
            nodeFixture.assumptions.behavioral(nodeFixture.schema.field("User", "id")),
        )
        assertTrue(
            nodeFixture.assumptions.behavioral(nodeFixture.schema.field("User", "__typename")),
        )
        assertTrue(
            nodeFixture.assumptions.behavioral(nodeFixture.schema.field("User", "name")),
        )
        assertTrue(
            nodeFixture.assumptions.behavioral(nodeFixture.schema.field("User", "search")),
        )

        assertFailsWith<IllegalArgumentException> {
            nodeFixture.assumptions.behavioral(nodeFixture.schema.field("Node", "name"))
        }
        assertFailsWith<IllegalArgumentException> {
            nodeFixture.assumptions.behavioral(
                fieldFixture.schema.field("User", "name"),
            )
        }
    }

    @Test
    fun `field-resolver snip retains only a nested node reference`() {
        val fixture = Fixture(withNodeResolver = true)
        val source =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    fixture.key("id") to fixture.schema.idValue("target"),
                    fixture.key("name") to fixture.schema.stringValue("Target"),
                ),
            )

        val result =
            assertIs<Schema.ObjectValue>(
                with(fixture.assumptions) {
                    fixture.userField.snip(
                        source,
                        selectionForestOf(
                            fixture.selection("Node", "id"),
                            fixture.selection("Node", "name"),
                        ),
                    )
                },
            )

        assertEquals(setOf(fixture.key("id")), result.fieldValues.keys)
    }

    @Test
    fun `node-resolver snip retains its fields and stops at nested boundaries`() {
        val fixture = Fixture(withNodeResolver = true)
        val friend =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    fixture.key("id") to fixture.schema.idValue("friend"),
                    fixture.key("name") to fixture.schema.stringValue("Friend"),
                ),
            )
        val source =
            fixture.schema.objectValue(
                fixture.user,
                mapOf(
                    fixture.key("id") to fixture.schema.idValue("target"),
                    fixture.key("name") to fixture.schema.stringValue("Target"),
                    fixture.key("friend") to friend,
                ),
            )
        val selections =
            selectionForestOf(
                fixture.selection("Node", "id"),
                fixture.selection("Node", "name"),
                fixture.selection("User", "__typename"),
                fixture.selection("User", "search"),
                fixture.selection(
                    typeName = "User",
                    fieldName = "friend",
                    subselections =
                        selectionForestOf(
                            fixture.selection("Node", "id"),
                            fixture.selection("Node", "name"),
                        ),
                ),
            )

        val result =
            with(fixture.assumptions) {
                fixture.user.snip(source, selections)
            }

        assertEquals(
            setOf(fixture.key("name"), fixture.key("friend")),
            result.fieldValues.keys,
        )
        val snippedFriend =
            assertIs<Schema.ObjectValue>(result.fieldValues[fixture.key("friend")])
        assertEquals(setOf(fixture.key("id")), snippedFriend.fieldValues.keys)
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
                subselections = selectionForestOf(emptyComposite),
            )
        }
    }

    private fun worldWithFragmentType(
        fragmentType: (Schema) -> Schema.CompositeType,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val fragment =
                    object : Fragment {
                        override val nominalType = fragmentType(schema)
                        override val subselections = selectionForestOf()
                    }
                val user = schema.type("User") as Schema.ObjectType
                val userField = schema.field("Query", "user")
                mapOf(
                    userField to
                        FieldResolver(
                            objectFragment = fragment,
                            function = { _, _ -> schema.objectValue(user, emptyMap()) },
                        ),
                )
            },
        )

    private class Fixture(
        withNodeResolver: Boolean = false,
    ) {
        private val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = { schema ->
                    if (withNodeResolver) {
                        val user = schema.type("User") as Schema.ObjectType
                        mapOf(user to NodeResolver { error("Not invoked") })
                    } else {
                        emptyMap()
                    }
                },
                fieldResolvers = { schema ->
                    val query = schema.query
                    val user = schema.type("User") as Schema.ObjectType
                    val queryFragment =
                        object : Fragment {
                            override val nominalType = query
                            override val subselections = selectionForestOf()
                        }
                    val userFragment =
                        object : Fragment {
                            override val nominalType = user
                            override val subselections = selectionForestOf()
                        }
                    mapOf(
                        schema.field("Query", "user") to
                            FieldResolver(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.field("User", "search") to
                            FieldResolver(
                                objectFragment = userFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val schema = world.schema
        val assumptions = world.assumptions
        val user = schema.type("User") as Schema.ObjectType
        val userField = schema.field("Query", "user")

        fun key(fieldName: String): Schema.ObjectKey =
            schema.objectKey(
                field = schema.field(user.typeName, fieldName),
                arguments = emptyMap(),
            )

        fun selection(
            typeName: String,
            fieldName: String,
            possibleTypes: Set<Schema.ObjectType> =
                (schema.type(typeName) as Schema.CompositeType).possibleTypes,
            subselections: SelectionForest = selectionForestOf(),
        ): model.Selection {
            val nominalType = schema.type(typeName) as Schema.CompositeType
            return Selection.of(
                key =
                    schema.objectKey(
                        field = schema.field(typeName, fieldName),
                        arguments = emptyMap(),
                    ),
                nominalType = nominalType,
                possibleTypes = possibleTypes,
                subselections = subselections,
            )
        }
    }

    private fun Schema.key(
        type: Schema.ObjectType,
        fieldName: String,
    ): Schema.ObjectKey =
        objectKey(
            field = field(type.typeName, fieldName),
            arguments = emptyMap(),
        )

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

            type Admin implements Node {
              id: ID!
              name: String!
              level: Int!
            }

            type Query {
              user: User
            }
            """.trimIndent()
    }
}
