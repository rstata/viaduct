package model.registry

import model.ObjectEngineResult

import model.Fragment
import model.Schema
import model.Selection
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.selectionForestOf
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolverRegistryTest {
    @Test
    fun `lowers node and field resolvers to field coordinates`() {
        val observedFields = mutableListOf<String>()
        val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                applicationObserver = { field, _, _, _ ->
                    observedFields += field.fieldName
                },
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    mapOf(
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
                    val userField = schema.field("Query", "user_V_A_node")
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf<Schema.OutputField, FieldResolverDefinition>(
                        userField to
                            model.testing.fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { parent, arguments ->
                                    assertEquals(
                                        schema.objectOf("Query"),
                                        parent,
                                    )
                                    assertTrue(arguments.fieldValues.isEmpty())
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
        val user =
            schema.objectOf("User") {
                "id" setTo "42"
            }
        val bridgeField = schema.objectField("Query", "user_V_A_node")
        val bridgeType = schema.type("User_V_A_Bridge") as Schema.ObjectType
        val bridgeIdField = schema.objectField("User_V_A_Bridge", "id")
        val payloadField = schema.objectField("User_V_A_Bridge", "node")
        val registry = world.resolverRegistry
        val assumptions = world.assumptions

        assertEquals(registry, assumptions.resolverRegistry)
        assertFailsWith<Schema.MissingSchemaElementException> {
            schema.objectField("Query", "user")
        }
        assertTrue(bridgeField in registry)
        assertTrue(payloadField in registry)
        assertTrue(registry.mayDemandFrom(bridgeField).isEmpty())
        assertTrue(registry.mayDemandFrom(payloadField).isEmpty())
        val bridgeValue =
            registry
                .resolver(bridgeField)(
                    input = query,
                    arguments = Value.Arguments.of(bridgeField, emptyMap()),
                )
        val bridgeObject = assertIs<Value.Object>(bridgeValue)
        assertEquals(bridgeType, bridgeObject.type)
        assertIs<Value.ID>(
            bridgeObject.fieldValues.getValue(
                ObjectEngineResult.GroundKey.of(bridgeIdField, emptyMap()),
            ),
        )
        val payloadResolver = registry.resolver(payloadField)
        val objectFragment = payloadResolver.objectFragment
        assertEquals(1, objectFragment.size)
        assertEquals(bridgeIdField, objectFragment.single().key.field)
        assertEquals(
            user,
            context(assumptions) {
                payloadResolver(
                    input = bridgeObject,
                    arguments = Value.Arguments.of(payloadField, emptyMap()),
                    selections =
                        schema.fragmentFrom(
                            """
                            fragment ignored on User {
                              id
                            }
                            """.trimIndent(),
                        ).subselections,
                )
            },
        )
        assertEquals(listOf("user_V_A_node", "node"), observedFields)
    }

    @Test
    fun `root resolver supplies only canonical query typename`() {
        val fixture = Fixture()
        val root = fixture.assumptions.resolverRegistry.resolveRootQuery()
        val typenameKey =
            ObjectEngineResult.GroundKey.of(
                fixture.schema.objectField("Query", "__typename"),
                emptyMap(),
            )

        assertEquals(fixture.schema.query, root.type)
        assertEquals(setOf(typenameKey), root.fieldValues.keys)
        assertEquals(Value.String.of("Query"), root.fieldValues.getValue(typenameKey))
    }

    @Test
    fun `reuses one bridge type while preserving every source list layer`() {
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
                      users: [User!]!
                      matrix: [[User!]!]!
                    }
                    """.trimIndent(),
            ).schema

        assertTrue("user_V_A_node" in schema.query.fields)
        assertTrue("users_V_A_node" in schema.query.fields)
        assertTrue("matrix_V_A_node" in schema.query.fields)
        assertFailsWith<Schema.MissingSchemaElementException> {
            schema.type("Node_V_A_Bridge")
        }

        val userBridge = schema.type("User_V_A_Bridge") as Schema.ObjectType
        assertEquals(setOf("__typename", "id", "node"), userBridge.fields.keys)
        val matrixBridge = schema.field("Query", "matrix_V_A_node")
        val outer = assertIs<TypeExpr.List<Schema.OutputType>>(matrixBridge.typeExpr)
        val inner = assertIs<TypeExpr.List<Schema.OutputType>>(outer.elementType)
        assertEquals(userBridge, inner.elementType.baseType)
        assertFalse(outer.isNullable)
        assertFalse(inner.isNullable)
        assertFalse(inner.elementType.isNullable)
    }

    @Test
    fun `node bridge keeps producer arguments off its payload resolver`() {
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
                      user(id: ID!): User!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    mapOf(user to model.testing.nodeResolverOf { error("Not invoked") })
                },
                fieldResolvers = { schema ->
                    val user = schema.field("Query", "user_V_A_node")
                    mapOf(
                        schema.field("Query", "seed") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ -> error("Not invoked") },
                            ),
                        user to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment ignored on Query { seed }",
                                    ),
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val schema = world.schema
        val bridge = schema.objectField("Query", "user_V_A_node")
        val payload = schema.objectField("User_V_A_Bridge", "node")
        val bridgeId = schema.objectField("User_V_A_Bridge", "id")

        assertEquals(setOf("id"), bridge.arguments.fields.keys)
        assertTrue(world.resolverRegistry.resolver(bridge).variables.isEmpty())
        val payloadResolver = world.resolverRegistry.resolver(payload)
        assertTrue(payloadResolver.variables.isEmpty())
        assertEquals(Schema.NoArguments, payload.arguments)
        assertEquals(bridgeId, payloadResolver.objectFragment.single().key.field)
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
                    mapOf<Schema.OutputField, FieldResolverDefinition>(
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
                val field = schema.objectField("Query", fieldName)
                context(world.assumptions) {
                    world.resolverRegistry
                        .resolver(field)(
                            input = parent,
                            arguments = Value.Arguments.of(field, emptyMap()),
                            selections = selectionForestOf(),
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
        val registry = world.resolverRegistry
        val userField = schema.objectField("User", "name")

        val missingField =
            assertFailsWith<MissingResolverException> {
                registry.resolver(userField)
            }
        assertEquals("User", missingField.typeName)
        assertEquals("name", missingField.fieldName)

        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
        assertFailsWith<IllegalArgumentException> {
            registry.resolver(foreignSchema.objectField("User", "name"))
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
        val foreignUserField = foreignSchema.field("Query", "user_V_A_node")

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
                        schema.field("Query", "user_V_A_node") to
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
    fun `rejects field resolvers for node id and generated typename fields`() {
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
    fun `snips selected fields recursively through objects and lists`() {
        val world =
            TestWorld.fromSDL(
                """
                type Record {
                  id: ID!
                  name: String!
                  friend: Record
                  peers: [Record]
                }

                type Query {
                  record: Record
                }
                """.trimIndent(),
            )
        val schema = world.schema
        val record = schema.type("Record") as Schema.ObjectType
        fun key(fieldName: String): ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                schema.objectField("Record", fieldName),
                emptyMap(),
            )
        fun selection(
            fieldName: String,
            subselections: model.SelectionForest = selectionForestOf(),
        ): Selection =
            Selection.of(
                key = key(fieldName),
                possibleTypes = setOf(record),
                subselections = subselections,
            )
        val friend =
            schema.objectOf("Record") {
                "id" setTo "friend"
                "name" setTo "Friend"
            }
        val peer =
            schema.objectOf("Record") {
                "id" setTo "peer"
                "name" setTo "Peer"
            }
        val source =
            schema.objectOf("Record") {
                "id" setTo "target"
                "name" setTo "Target"
                "friend" setTo friend
                "peers" setTo listOf(peer, null)
            }
        val idSelection = selection("id")
        val nameSelection = selection("name")
        val selections =
            selectionForestOf(
                idSelection,
                selection("friend", selectionForestOf(idSelection)),
                selection("peers", selectionForestOf(nameSelection)),
            ) +
                selectionForestOf(
                    Selection.of(
                        key = key("name"),
                        possibleTypes = emptySet(),
                        subselections = selectionForestOf(),
                    ),
                )

        val result =
            assertIs<Value.Object>(
                with(world.assumptions) {
                    source.snipToDemand(selections)
                },
            )

        assertEquals(
            setOf(
                key("id"),
                key("friend"),
                key("peers"),
            ),
            result.fieldValues.keys,
        )
        assertEquals(
            "target",
            assertIs<Value.ID>(result.fieldValues[key("id")]).idValue,
        )
        val snippedFriend =
            assertIs<Value.Object>(result.fieldValues[key("friend")])
        assertEquals(
            setOf(key("id")),
            snippedFriend.fieldValues.keys,
        )
        val peers = assertIs<Value.OutputList>(result.fieldValues[key("peers")])
        val snippedPeer = assertIs<Value.Object>(peers.values.first())
        assertEquals(
            setOf(key("name")),
            snippedPeer.fieldValues.keys,
        )
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
    fun `field-resolver snipToDemand has no implicit node ownership boundary`() {
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

        assertEquals(
            setOf(fixture.key("name")),
            result.fieldValues.keys,
        )
    }

    @Test
    fun `selection factory distinguishes empty composites and rejects subselections on leaves`() {
        val fixture = Fixture()

        val leaf = fixture.selection("Node", "id")
        val emptyComposite = fixture.selection("User", "friend_V_A_node")

        assertTrue(leaf.isLeaf)
        assertTrue(leaf.subselections.isEmpty())
        assertFalse(emptyComposite.isLeaf)
        assertTrue(emptyComposite.subselections.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            Selection.of(
                key = leaf.key,
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
                val userField = schema.field("Query", "user_V_A_node")
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
                        schema.field("Query", "user_V_A_node") to
                            model.testing.fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.field("User", "search_V_A_node") to
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
        val userField = schema.field("Query", "user_V_A_node")

        fun key(fieldName: String): ObjectEngineResult.Key =
            ObjectEngineResult.Key.of(
                field = schema.field(user.typeName, fieldName),
                arguments = emptyMap(),
            )

        fun selection(
            typeName: String,
            fieldName: String,
            possibleTypes: Set<Schema.ObjectType> =
                (schema.type(typeName) as Schema.CompositeType).possibleTypes,
        ): model.Selection {
            return Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = schema.field(typeName, fieldName),
                        arguments = emptyMap(),
                    ),
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
