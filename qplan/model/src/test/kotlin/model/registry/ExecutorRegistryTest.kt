package model.registry

import model.Fragment
import model.Schema
import model.Selection
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
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
                    mapOf<Schema.ObjectType, Resolver.Node>(
                        user to
                            model.testing.nodeResolverOf { id ->
                                assertEquals("42", id.idValue)
                                schema.objectOf("User") {
                                    "id" setTo id
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val userField = schema.field("Query", "user")
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf<Schema.OutputField, Resolver.Field>(
                        userField to
                            model.testing.fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { parent, arguments ->
                                    assertEquals(
                                        schema.objectOf("Query"),
                                        parent,
                                    )
                                    assertEquals(Schema.NoArguments, arguments.type)
                                    schema.objectOf("User") {
                                        "id" setTo "42"
                                    }
                                },
                            ),
                    )
                },
            )
        val schema = world.schema
        val query = schema.objectOf("Query")
        val userType = schema.type("User") as Schema.ObjectType
        val user =
            schema.objectOf("User") {
                "id" setTo "42"
            }
        val userField = schema.field("Query", "user")
        val registry = world.executorRegistry
        val assumptions = world.assumptions

        assertEquals(registry, assumptions.executorRegistry)
        val nodeResult =
            context(assumptions) {
                registry
                    .resolver(userType)
                    .resolve(
                        type = userType,
                        id = Value.ID.of("42"),
                        transitiveDemand = selectionForestOf(),
                    )
            }
        assertEquals(
            schema.objectOf("User"),
            nodeResult,
        )
        val fieldResolver = registry.resolver(userField)
        assertEquals(schema.query, fieldResolver.objectFragment.nominalType)
        assertTrue(fieldResolver.objectFragment.subselections.isEmpty())
        assertEquals(
            user,
            context(assumptions) {
                fieldResolver.resolve(
                    input = query,
                    arguments = Value.Arguments.of(userField, emptyMap()),
                    transitiveDemand = selectionForestOf(),
                )
            },
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
                    val fragment = schema.emptyFragmentOf("Query")
                    mapOf<Schema.OutputField, Resolver.Field>(
                        schema.field("Query", "scalar") to
                            model.testing.fieldResolverOf(fragment) { _, _ -> Value.String.of("value") },
                        schema.field("Query", "list") to
                            model.testing.fieldResolverOf(fragment) { _, _ ->
                                Value.OutputList.of(
                                    typeExpr =
                                        (
                                            schema.field("Query", "list").typeExpr as
                                                TypeExpr.List<Schema.OutputType>
                                        ).elementType,
                                    values = listOf(Value.String.of("value"), null),
                                )
                            },
                        schema.field("Query", "nullable") to
                            model.testing.fieldResolverOf(fragment) { _, _ -> null },
                        schema.field("Query", "failed") to
                            model.testing.fieldResolverOf(fragment) { _, _ -> Value.Error },
                    )
                },
            )
        val schema = world.schema
        val parent = schema.objectOf("Query")
        val outputs =
            listOf("scalar", "list", "nullable", "failed").associateWith { fieldName ->
                val field = schema.field("Query", fieldName)
                context(world.assumptions) {
                    world.executorRegistry
                        .resolver(field)
                        .resolve(
                            input = parent,
                            arguments = Value.Arguments.of(field, emptyMap()),
                            transitiveDemand = selectionForestOf(),
                        )
                }
            }

        assertEquals(Value.String.of("value"), outputs.getValue("scalar"))
        assertEquals(
            Value.OutputList.of(
                typeExpr =
                    (
                        schema.field("Query", "list").typeExpr as
                            TypeExpr.List<Schema.OutputType>
                    ).elementType,
                values = listOf(Value.String.of("value"), null),
            ),
            outputs.getValue("list"),
        )
        assertEquals(null, outputs.getValue("nullable"))
        assertEquals(Value.Error, outputs.getValue("failed"))

        outputs.forEach { (_, output) ->
            val projection =
                with(world.assumptions) {
                    output.snipToDemand(selectionForestOf())
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

        assertFalse(schema.query in registry)
        assertFalse(schema.field("Node", "name") in registry)

        val missingNode =
            assertFailsWith<MissingExecutorException> {
                registry.resolver(userType)
            }
        assertEquals("User", missingNode.typeName)
        assertEquals(null, missingNode.fieldName)

        val missingField =
            assertFailsWith<MissingExecutorException> {
                registry.resolver(userField)
            }
        assertEquals("User", missingField.typeName)
        assertEquals("name", missingField.fieldName)

        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
        assertFailsWith<IllegalArgumentException> {
            registry.resolver(
                foreignSchema.type("User") as Schema.ObjectType,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.resolver(foreignSchema.field("User", "name"))
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
                    mapOf(foreignUser to model.testing.nodeResolverOf { error("Not invoked") })
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf(
                        foreignUserField to
                            model.testing.fieldResolverOf(
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
                    mapOf(user to model.testing.nodeResolverOf { error("Not invoked") })
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
                    mapOf(other to model.testing.nodeResolverOf { error("Not invoked") })
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
                    val queryFragment = schema.emptyFragmentOf("Query")
                    val nodeFragment = schema.emptyFragmentOf("Node")
                    mapOf(
                        schema.field("Query", "user") to
                            model.testing.fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.field("Node", "name") to
                            model.testing.fieldResolverOf(
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
                        mapOf(user to model.testing.nodeResolverOf { error("Not invoked") })
                    },
                    fieldResolvers = { schema ->
                        val fragment = schema.emptyFragmentOf("User")
                        mapOf(
                            schema.field("User", fieldName) to
                                model.testing.fieldResolverOf(
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
    fun `node snipToDemand requires a node resolver coordinate`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val user = schema.type("User") as Schema.ObjectType
        val result = world.assumptions.objectOf("User")

        with(world.assumptions) {
            assertFailsWith<IllegalArgumentException> {
                user.snipToDemand(result, selectionForestOf())
            }
        }
    }

    @Test
    fun `snips selected fields recursively through objects and lists`() {
        val fixture = Fixture()
        val friend =
            fixture.assumptions.objectOf("User") {
                "id" setTo "friend"
                "name" setTo "Friend"
            }
        val peer =
            fixture.assumptions.objectOf("User") {
                "id" setTo "peer"
                "name" setTo "Peer"
            }
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
                "name" setTo "Target"
                "friend" setTo friend
                "peers" setTo listOf(peer, null)
            }
        val selections =
            fixture.schema.fragmentFrom(
                """
                fragment ignored on Node {
                  id
                  ... on User {
                    friend {
                      ... on Node {
                        id
                      }
                    }
                    peers {
                      ... on Node {
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
            ).subselections +
                selectionForestOf(
                    fixture.selection(
                        typeName = "Node",
                        fieldName = "name",
                        possibleTypes = emptySet(),
                    ),
                )

        val result =
            assertIs<Value.Object>(
                with(fixture.assumptions) {
                    source.snipToDemand(selections)
                },
            )

        assertEquals(
            setOf(fixture.key("id"), fixture.key("friend"), fixture.key("peers")),
            result.fieldValues.keys,
        )
        assertEquals(
            "target",
            assertIs<Value.ID>(result.fieldValues[fixture.key("id")]).idValue,
        )
        val snippedFriend =
            assertIs<Value.Object>(result.fieldValues[fixture.key("friend")])
        assertEquals(setOf(fixture.key("id")), snippedFriend.fieldValues.keys)
        val peers = assertIs<Value.OutputList>(result.fieldValues[fixture.key("peers")])
        val snippedPeer = assertIs<Value.Object>(peers.values.first())
        assertEquals(setOf(fixture.key("name")), snippedPeer.fieldValues.keys)
        assertEquals(null, peers.values.last())
    }

    @Test
    fun `snipToDemand omits selections conditioned on another concrete type`() {
        val fixture = Fixture()
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
            }

        val result =
            assertIs<Value.Object>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on Admin {
                              level
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(emptySet(), result.fieldValues.keys)
    }

    @Test
    fun `field-resolver snipToDemand stops before an argument-bearing field resolver`() {
        val fixture = Fixture()
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
            }

        val result =
            assertIs<Value.Object>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on User {
                              search {
                                id
                              }
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(emptySet(), result.fieldValues.keys)
    }

    @Test
    fun `snipToDemand does not expand resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      firstName: String!
                      lastName: String!
                      greeting: String!
                    }

                    type Query {
                      viewer: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.field("User", "greeting") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          firstName
                                          lastName
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val source =
            world.objectOf("User") {
                "firstName" setTo "Ada"
                "lastName" setTo "Lovelace"
            }
        val demand =
            world.fragmentFrom(
                """
                fragment ignored on User {
                  greeting
                }
                """.trimIndent(),
            ).subselections

        val result =
            assertIs<Value.Object>(
                context(world) {
                    source.snipToDemand(demand)
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
    fun `field-resolver snipToDemand retains only a nested node reference`() {
        val fixture = Fixture(withNodeResolver = true)
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
                "name" setTo "Target"
            }

        val result =
            assertIs<Value.Object>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on Node {
                              name
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(setOf(fixture.key("id")), result.fieldValues.keys)
    }

    @Test
    fun `node-resolver snipToDemand retains its fields and stops at nested boundaries`() {
        val fixture = Fixture(withNodeResolver = true)
        val friend =
            fixture.assumptions.objectOf("User") {
                "id" setTo "friend"
                "name" setTo "Friend"
            }
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
                "name" setTo "Target"
                "friend" setTo friend
            }
        val selections =
            fixture.schema.fragmentFrom(
                """
                fragment ignored on Node {
                  id
                  name
                  ... on User {
                    __typename
                    search {
                      id
                    }
                    friend {
                      ... on Node {
                        id
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
            ).subselections

        val result =
            with(fixture.assumptions) {
                fixture.user.snipToDemand(source, selections)
            }

        assertEquals(
            setOf(fixture.key("name"), fixture.key("friend")),
            result.fieldValues.keys,
        )
        val snippedFriend =
            assertIs<Value.Object>(result.fieldValues[fixture.key("friend")])
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
                    Fragment.of(fragmentType(schema), selectionForestOf())
                val userField = schema.field("Query", "user")
                mapOf(
                    userField to
                        model.testing.fieldResolverOf(
                            objectFragment = fragment,
                            function = { _, _ -> schema.objectOf("User") },
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
                        mapOf(user to model.testing.nodeResolverOf { error("Not invoked") })
                    } else {
                        emptyMap()
                    }
                },
                fieldResolvers = { schema ->
                    val queryFragment = schema.emptyFragmentOf("Query")
                    val userFragment = schema.emptyFragmentOf("User")
                    mapOf(
                        schema.field("Query", "user") to
                            model.testing.fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.field("User", "search") to
                            model.testing.fieldResolverOf(
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

        fun key(fieldName: String): Value.Key =
            Value.Key.of(
                field = schema.field(user.typeName, fieldName),
                arguments = emptyMap(),
            )

        fun selection(
            typeName: String,
            fieldName: String,
            possibleTypes: Set<Schema.ObjectType> =
                (schema.type(typeName) as Schema.CompositeType).possibleTypes,
        ): model.Selection {
            val nominalType = schema.type(typeName) as Schema.CompositeType
            return Selection.of(
                key =
                    Value.Key.of(
                        field = schema.field(typeName, fieldName),
                        arguments = emptyMap(),
                    ),
                nominalType = nominalType,
                possibleTypes = possibleTypes,
                subselections = selectionForestOf(),
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
