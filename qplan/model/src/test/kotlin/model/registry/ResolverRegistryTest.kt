package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.requireQueryTypeDef
import model.requireObjectField
import model.requireField
import model.requireType
import model.Arguments
import model.EngineErrorData
import model.Fragment
import model.ObjectEngineResult
import model.Selection
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.schemaType
import model.selectionForestOf
import model.testing.FieldResolverDefinition
import model.testing.GJSchema
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import model.testing.resolverRegistryOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData

class ResolverRegistryTest {
    @Test
    fun `lowers node and field resolvers to field coordinates`() {
        val observedFields = mutableListOf<String>()
        val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                applicationObserver = { field, _, _, _ ->
                    observedFields += field.name
                },
                nodeResolvers = { schema ->
                    val user = schema.requireType("User") as ViaductSchema.Object
                    mapOf(
                        user to
                            nodeResolverOf { id ->
                                assertEquals("42", id)
                                schema.objectOf("User") {
                                    "id" setTo "lookup-id"
                                    "name" setTo "Ada"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val userField = schema.requireField("Query", "user_V_A_node")
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf<ViaductSchema.Field, FieldResolverDefinition>(
                        userField to
                            fieldResolverOf(
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
                "name" setTo "Ada"
            }
        val bridgeField = schema.requireObjectField("Query", "user_V_A_node")
        val bridgeType = schema.requireType("User_V_A_Bridge") as ViaductSchema.Object
        val bridgeIdField = schema.requireObjectField("User_V_A_Bridge", "id")
        val payloadField = schema.requireObjectField("User_V_A_Bridge", "node")
        val registry = world.resolverRegistry
        val assumptions = world.assumptions

        assertEquals(registry, assumptions.resolverRegistry)
        assertFailsWith<IllegalStateException> {
            schema.requireObjectField("Query", "user")
        }
        assertTrue(bridgeField in registry)
        assertTrue(payloadField in registry)
        assertTrue(registry.mayDemandFrom(bridgeField).isEmpty())
        assertTrue(registry.mayDemandFrom(payloadField).isEmpty())
        val bridgeValue =
            context(assumptions) {
                registry
                    .resolver(bridgeField)(
                        input = query,
                        arguments = Arguments.Resolved.of(bridgeField, emptyMap()),
                    )
            }
        val bridgeObject = assertIs<EngineObjectData.Sync>(bridgeValue)
        assertEquals(bridgeType, bridgeObject.schemaType)
        assertIs<String>(
            bridgeObject.get(
                bridgeIdField.name,
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
                    arguments = Arguments.Resolved.of(payloadField, emptyMap()),
                    selections =
                        schema.fragmentFrom(
                            """
                            fragment ignored on User {
                              id
                              name
                            }
                            """.trimIndent(),
                        ).subselections,
                )
            },
        )
        assertEquals(listOf("user_V_A_node", "node"), observedFields)
    }

    @Test
    fun `root resolver supplies an empty query object`() {
        val fixture = Fixture()
        val root = fixture.assumptions.resolverRegistry.resolveRootQuery()

        assertEquals(fixture.schema.requireQueryTypeDef(), root.schemaType)
        assertEquals(emptySet(), root.getSelections().toSet())
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

        assertNotNull(schema.requireQueryTypeDef().field("user_V_A_node"))
        assertNotNull(schema.requireQueryTypeDef().field("users_V_A_node"))
        assertNotNull(schema.requireQueryTypeDef().field("matrix_V_A_node"))
        val userBridge = schema.requireType("User_V_A_Bridge") as ViaductSchema.Object
        val nodeBridge = schema.requireType("Node_V_A_Bridge") as ViaductSchema.Interface
        assertEquals(setOf(userBridge), nodeBridge.possibleObjectTypes)
        assertEquals(
            setOf("id", "node"),
            userBridge.fields.mapTo(linkedSetOf(), ViaductSchema.Field::name),
        )
        val matrixBridge = schema.requireField("Query", "matrix_V_A_node")
        val inner = checkNotNull(matrixBridge.type.unwrapList())
        val element = checkNotNull(inner.unwrapList())
        assertEquals(userBridge, element.baseTypeDef)
        assertFalse(matrixBridge.type.isNullable)
        assertFalse(inner.isNullable)
        assertFalse(element.isNullable)
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
                    val user = schema.requireType("User") as ViaductSchema.Object
                    mapOf(user to nodeResolverOf { error("Not invoked") })
                },
                fieldResolvers = { schema ->
                    val user = schema.requireField("Query", "user_V_A_node")
                    mapOf(
                        schema.requireField("Query", "seed") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ -> error("Not invoked") },
                            ),
                        user to
                            fieldResolverOf(
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
        val bridge = schema.requireObjectField("Query", "user_V_A_node")
        val payload = schema.requireObjectField("User_V_A_Bridge", "node")
        val bridgeId = schema.requireObjectField("User_V_A_Bridge", "id")

        assertEquals(setOf("id"), bridge.args.mapTo(linkedSetOf(), ViaductSchema.FieldArg::name))
        assertTrue(world.resolverRegistry.resolver(bridge).variables.isEmpty())
        val payloadResolver = world.resolverRegistry.resolver(payload)
        assertTrue(payloadResolver.variables.isEmpty())
        assertTrue(payload.args.isEmpty())
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
                    mapOf<ViaductSchema.Field, FieldResolverDefinition>(
                        schema.requireField("Query", "scalar") to
                            fieldResolverOf(fragment) { _, _ -> "value" },
                        schema.requireField("Query", "list") to
                            fieldResolverOf(fragment) { _, _ ->
                                listOf("value", null)
                            },
                        schema.requireField("Query", "nullable") to
                            fieldResolverOf(fragment) { _, _ -> null },
                        schema.requireField("Query", "failed") to
                            fieldResolverOf(fragment) { _, _ -> EngineErrorData },
                    )
                },
            )
        val schema = world.schema
        val parent = schema.objectOf("Query")
        val outputs =
            listOf("scalar", "list", "nullable", "failed").associateWith { fieldName ->
                val field = schema.requireObjectField("Query", fieldName)
                context(world.assumptions) {
                    world.resolverRegistry
                        .resolver(field)(
                            input = parent,
                            arguments = Arguments.Resolved.of(field, emptyMap()),
                            selections = selectionForestOf(),
                        )
                }
            }

        assertEquals("value", outputs.getValue("scalar"))
        assertEquals(
            listOf("value", null),
            outputs.getValue("list"),
        )
        assertEquals(null, outputs.getValue("nullable"))
        assertEquals(EngineErrorData, outputs.getValue("failed"))

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
        val userField = schema.requireObjectField("User", "name")

        val missingField =
            assertFailsWith<MissingResolverException> {
                registry.resolver(userField)
            }
        assertEquals("User", missingField.typeName)
        assertEquals("name", missingField.fieldName)

        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
        assertFailsWith<IllegalArgumentException> {
            registry.resolver(foreignSchema.requireObjectField("User", "name"))
        }
    }

    @Test
    fun `rejects a field resolver whose object fragment is not its canonical parent type`() {
        assertFailsWith<IllegalArgumentException> {
            worldWithFragmentType { schema ->
                schema.requireType("User") as ViaductSchema.Object
            }
        }

        val foreignQuery = TestWorld.fromSDL(SCHEMA_SDL).schema.requireQueryTypeDef()
        assertFailsWith<IllegalArgumentException> {
            worldWithFragmentType { foreignQuery }
        }
    }

    @Test
    fun `rejects foreign resolver coordinate definitions`() {
        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val foreignUser = foreignSchema.requireType("User") as ViaductSchema.Object
        val foreignUserField = foreignSchema.requireField("Query", "user_V_A_node")

        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = {
                    mapOf(foreignUser to nodeResolverOf { error("Not invoked") })
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
                            fieldResolverOf(
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
                    val user = schema.requireType("User") as ViaductSchema.Object
                    mapOf(user to nodeResolverOf { error("Not invoked") })
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
                    val other = schema.requireType("Other") as ViaductSchema.Object
                    mapOf(other to nodeResolverOf { error("Not invoked") })
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
                        schema.requireField("Query", "user_V_A_node") to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.requireField("Node", "name") to
                            fieldResolverOf(
                                objectFragment = nodeFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        }
    }

    @Test
    fun `fills missing Query resolvers with errors and preserves supplied resolvers`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { supplied: Int, missing: Int }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "supplied") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
            )
        val schema = world.schema
        val registry = world.resolverRegistry
        val query = schema.objectOf("Query")

        fun resolve(fieldName: String): Any? {
            val field = schema.requireObjectField("Query", fieldName)
            return context(world.assumptions) {
                registry.resolver(field)(
                    input = query,
                    arguments = Arguments.Resolved.of(field, emptyMap()),
                )
            }
        }

        assertEquals(7, resolve("supplied"))
        assertEquals(EngineErrorData, resolve("missing"))
    }

    @Test
    fun `requires a field resolver for every Query field in a canonical registry`() {
        val schema = GJSchema.fromSDL("type Query { missing: Int }")
        assertFailsWith<IllegalArgumentException> {
            resolverRegistryOf(
                schema = schema,
                nodeResolvers = emptyMap(),
                fieldResolvers = emptyMap(),
                variableProviders = emptyMap(),
                applicationObserver = null,
            )
        }
    }

    @Test
    fun `rejects field resolvers for node id and generated typename fields`() {
        listOf("id", "V_A_typename").forEach { fieldName ->
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA_SDL,
                    nodeResolvers = { schema ->
                        val user = schema.requireType("User") as ViaductSchema.Object
                        mapOf(user to nodeResolverOf { error("Not invoked") })
                    },
                    fieldResolvers = { schema ->
                        val fragment = schema.emptyFragmentOf("User")
                        mapOf(
                            schema.requireField("User", fieldName) to
                                fieldResolverOf(
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
        val record = schema.requireType("Record") as ViaductSchema.Object
        fun key(fieldName: String): ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Record", fieldName),
                emptyMap(),
            )
        fun selection(
            fieldName: String,
            subselections: SelectionForest = selectionForestOf(),
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
            assertIs<EngineObjectData.Sync>(
                with(world.assumptions) {
                    source.snipToDemand(selections)
                },
            )

        assertEquals(
            setOf(
                "id",
                "friend",
                "peers",
            ),
            result.getSelections().toSet(),
        )
        assertEquals(
            "target",
            result.get("id"),
        )
        val snippedFriend =
            assertIs<EngineObjectData.Sync>(result.get("friend"))
        assertEquals(
            setOf("id"),
            snippedFriend.getSelections().toSet(),
        )
        val peers = assertIs<List<*>>(result.get("peers"))
        val snippedPeer = assertIs<EngineObjectData.Sync>(peers.first())
        assertEquals(
            setOf("name"),
            snippedPeer.getSelections().toSet(),
        )
        assertEquals(null, peers.last())
    }

    @Test
    fun `snipToDemand omits selections conditioned on another concrete type`() {
        val fixture = Fixture()
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
            }

        val result =
            assertIs<EngineObjectData.Sync>(
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

        assertEquals(emptySet(), result.getSelections().toSet())
    }

    @Test
    fun `field-resolver snipToDemand stops before an argument-bearing field resolver`() {
        val fixture = Fixture()
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
            }

        val result =
            assertIs<EngineObjectData.Sync>(
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

        assertEquals(emptySet(), result.getSelections().toSet())
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
                        schema.requireField("Query", "viewer") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.requireField("User", "greeting") to
                            fieldResolverOf(
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
            assertIs<EngineObjectData.Sync>(
                context(world) {
                    source.snipToDemand(demand)
                },
            )

        assertEquals(emptySet(), result.getSelections().toSet())
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
            assertIs<EngineObjectData.Sync>(
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
            setOf("name"),
            result.getSelections().toSet(),
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
        fragmentType: (ViaductSchema) -> ViaductSchema.CompositeTypeDef,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val fragment =
                    Fragment.of(fragmentType(schema), selectionForestOf())
                val userField = schema.requireField("Query", "user_V_A_node")
                mapOf(
                    userField to
                        fieldResolverOf(
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
                        val user = schema.requireType("User") as ViaductSchema.Object
                        mapOf(user to nodeResolverOf { error("Not invoked") })
                    } else {
                        emptyMap()
                    }
                },
                fieldResolvers = { schema ->
                    val queryFragment = schema.emptyFragmentOf("Query")
                    val userFragment = schema.emptyFragmentOf("User")
                    mapOf(
                        schema.requireField("Query", "user_V_A_node") to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.requireField("User", "search_V_A_node") to
                            fieldResolverOf(
                                objectFragment = userFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val schema = world.schema
        val assumptions = world.assumptions
        val user = schema.requireType("User") as ViaductSchema.Object
        val userField = schema.requireField("Query", "user_V_A_node")

        fun key(fieldName: String): ObjectEngineResult.Key =
            ObjectEngineResult.Key.of(
                field = schema.requireField(user.name, fieldName),
                arguments = emptyMap(),
            )

        fun selection(
            typeName: String,
            fieldName: String,
            possibleTypes: Set<ViaductSchema.Object> =
                (schema.requireType(typeName) as ViaductSchema.CompositeTypeDef).possibleObjectTypes,
        ): Selection {
            return Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = schema.requireField(typeName, fieldName),
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
