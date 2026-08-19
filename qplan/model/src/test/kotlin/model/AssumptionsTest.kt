package model

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AssumptionsTest {
    @Test
    fun `constructs a resolver fragment variable with its defining field`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val variableField = assumptions.schema.objectField("Query", "node_V_A_node")

        val fragment =
            assumptions.schema.fragmentFrom(
                source =
                    """
                fragment ignored on Query {
                  node(filter: ${'$'}filter) {
                    id
                  }
                }
                    """.trimIndent(),
                variableField = variableField,
            )

        val node = fragment.subselections.single()
        assertEquals(
            Arguments.Variable.of(variableField, "filter"),
            node.key.arguments.fieldExpressions().getValue("filter"),
        )
        assertNotEquals(
            Arguments.Variable.of(variableField, "other"),
            node.key.arguments.fieldExpressions().getValue("filter"),
        )
    }

    @Test
    fun `parses and validates a named fragment against the retained schema`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        val fragment =
            assumptions.fragmentFrom(
                """
                fragment ignored on Query {
                  result: node(filter: {tags: "one", role: ADMIN}) {
                    id
                  }
                }
                """.trimIndent(),
            )

        assertEquals(assumptions.schema.query, fragment.nominalType)
        val node = fragment.subselections.single()
        assertEquals("node_V_A_node", node.key.field.fieldName)

        val filter =
            assertIs<EngineInputObjectData>(
                node.key.arguments.fieldExpressions().getValue("filter"),
            )
        val role = assertIs<String>(filter["role"])
        assertEquals("ADMIN", role)
        val limit = assertIs<Int>(filter["limit"])
        assertEquals(10, limit)
        val tags = assertIs<EngineInputListData>(filter["tags"])
        assertEquals(
            "one",
            assertIs<String>(tags.single()),
        )

        val payload = node.subselections.single()
        assertEquals("node", payload.key.field.fieldName)
        val id = payload.subselections.single()
        assertEquals("id", id.key.field.fieldName)
    }

    @Test
    fun `rejects a named fragment that is invalid for the retained schema`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        val exception =
            assertFailsWith<IllegalArgumentException> {
                assumptions.fragmentFrom(
                    """
                    fragment ignored on Query {
                      missing
                    }
                    """.trimIndent(),
                )
            }

        assertContains(exception.message.orEmpty(), "missing")
    }

    @Test
    fun `parses a fragment from a schema with explicit variable bindings`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val filter =
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("limit" to 5),
            )

        val fragment =
            schema.fragmentFrom(
                source =
                    """
                    fragment ignored on Query {
                      node(filter: ${'$'}filter) {
                        id
                      }
                    }
                    """.trimIndent(),
                bindings = mapOf("filter" to filter),
            )

        assertEquals(
            filter,
            fragment.subselections.single().key.arguments
                .fieldExpressions()
                .getValue("filter"),
        )
    }

    @Test
    fun `ground input object construction rejects unresolved variables`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val variableField = schema.objectField("Query", "node_V_A_node")

        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value =
                    mapOf(
                        "limit" to Arguments.Variable.of(variableField, "nested"),
                    ),
            )
        }
    }

    @Test
    fun `constructs empty fragments that GraphQL text cannot express`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        val worldFragment = assumptions.emptyFragmentOf("Query")
        val schemaFragment = assumptions.schema.emptyFragmentOf("Query")

        assertEquals(assumptions.schema.query, worldFragment.nominalType)
        assertEquals(assumptions.schema.query, schemaFragment.nominalType)
        assertTrue(worldFragment.subselections.isEmpty())
        assertTrue(schemaFragment.subselections.isEmpty())
    }

    @Test
    fun `constructs assumptions from a schema and resolver registry`() {
        val world = TestWorld.fromSDL(schemaSDL = SCHEMA_SDL)
        val assumptions = world.assumptions

        assertEquals(world.schema, assumptions.schema)

        val schema = assumptions.schema
        val query = schema.query
        val node = assertIs<Schema.InterfaceType>(schema.type("Node"))
        val user = assertIs<Schema.ObjectType>(schema.type("User"))
        val admin = assertIs<Schema.ObjectType>(schema.type("Admin"))
        val actor = assertIs<Schema.UnionType>(schema.type("Actor"))
        assertEquals(query, schema.type("Query"))
        assertEquals(Schema.IntType, schema.type("Int"))
        assertEquals(Schema.FloatType, schema.type("Float"))
        assertEquals(Schema.StringType, schema.type("String"))
        assertEquals(Schema.BooleanType, schema.type("Boolean"))
        assertEquals(Schema.IDType, schema.type("ID"))
        assertEquals(setOf(user, admin), node.possibleTypes)
        assertEquals(setOf(user, admin), actor.possibleTypes)
        val typeName = schema.field("Node", "V_I_typename")
        assertEquals(node, typeName.containingType)
        assertEquals(Schema.NoArguments, typeName.arguments)
        assertEquals(emptyMap(), Schema.NoArguments.fields)
        assertEquals(
            TypeExpr.Named.of(Schema.StringType, isNullable = false),
            typeName.typeExpr,
        )

        val actors = schema.field("Query", "actors")
        listOf(
            schema.field("Node", "id"),
            schema.field("User", "name"),
            schema.field("Admin", "level"),
            actors,
        ).forEach { field ->
            assertEquals(Schema.NoArguments, field.arguments)
        }
        val emptyArguments = Arguments.Resolved.of(actors, emptyMap())
        val otherEmptyArguments = Arguments.Resolved.of(typeName, emptyMap())
        assertEquals(emptyArguments, otherEmptyArguments)
        assertEquals(
            TypeExpr.List.of(
                elementType = TypeExpr.Named.of(actor, isNullable = false),
                isNullable = false,
            ),
            actors.typeExpr,
        )

        val nodeField = schema.field("Query", "node_V_A_node")
        assertEquals(query, nodeField.containingType)
        assertFalse(nodeField.arguments == Schema.NoArguments)
        val filterArgument = nodeField.arguments.fields.getValue("filter")
        assertEquals(nodeField.arguments, filterArgument.containingType)
        assertEquals("filter", filterArgument.name)
        assertFalse(filterArgument.isRequired)
        val filterDefault =
            assertIs<CoercedDefaultValue.Present>(filterArgument.defaultValue)
        val filterValue = assertIs<EngineInputObjectData>(filterDefault.value)
        val nodeArguments =
            Arguments.Resolved.of(
                field = nodeField,
                fields = mapOf("filter" to filterValue),
            )
        assertEquals(filterValue, nodeArguments.fieldValues.getValue("filter"))
        val defaultRole = assertIs<String>(filterValue["role"])
        assertEquals("MEMBER", defaultRole)
        val defaultLimit = assertIs<Int>(filterValue["limit"])
        assertEquals(10, defaultLimit)
        val tags =
            assertIs<EngineInputListData>(
                filterValue["tags"],
            )
        assertEquals(
            "a",
            assertIs<String>(tags.single()),
        )
        val nodeKey =
            ObjectEngineResult.Key.of(
                field = nodeField,
                arguments = mapOf("filter" to filterValue),
            )
        assertEquals(nodeField, nodeKey.field)
        assertEquals(
            filterValue,
            nodeKey.arguments.fieldExpressions()["filter"],
        )
        assertEquals(
            nodeKey,
            ObjectEngineResult.Key.of(
                field = nodeField,
                arguments = mapOf("filter" to filterValue),
            ),
        )
        val interfaceIdKey =
            ObjectEngineResult.Key.of(
                field = schema.field("Node", "id"),
                arguments = emptyMap(),
            )
        val objectIdKey =
            ObjectEngineResult.Key.of(
                field = schema.field("User", "id"),
                arguments = emptyMap(),
            )
        assertFalse(interfaceIdKey == objectIdKey)

        val friendField = schema.field("User", "friend_V_A_node")
        assertFalse(friendField.arguments == Schema.NoArguments)
        val limitArgument: Schema.InputLikeField =
            friendField.arguments.fields.getValue("limit")
        assertEquals(friendField.arguments, limitArgument.containingType)
        assertEquals("limit", limitArgument.name)
        assertFalse(limitArgument.isRequired)

        val filter = assertIs<Schema.InputObjectType>(schema.type("Filter"))
        val limitInputField: Schema.InputLikeField = filter.fields.getValue("limit")
        assertEquals(filter, limitInputField.containingType)
        assertEquals("limit", limitInputField.name)
        assertFalse(limitInputField.isRequired)
        val tagsTypeExpr =
            assertIs<TypeExpr.List<Schema.InputType>>(
                filter.fields.getValue("tags").typeExpr,
            )
        assertFalse(tagsTypeExpr.elementType.isNullable)
    }

    @Test
    fun `variable bindings distinguish undeclared incomplete and bound to null`(): Unit =
        runBlocking {
            val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
            val field = assumptions.schema.objectField("Query", "node_V_A_node")
            val variable = Arguments.Variable.of(field, "value").stamp(emptyList())

            assertFalse(assumptions.isBound(variable))
            assertFailsWith<IllegalStateException> {
                assumptions.getBinding(variable)
            }

            assumptions.declareBinding(variable)
            val fetched = async { assumptions.fetchBinding(variable) }

            assertFalse(assumptions.isBound(variable))
            assertFalse(fetched.isCompleted)
            assertFailsWith<UncompletedPromiseException> {
                assumptions.getBinding(variable)
            }

            assumptions.completeBinding(variable, null)

            assertTrue(assumptions.isBound(variable))
            assertEquals(VariableBinding.of(null), assumptions.getBinding(variable))
            assertEquals(VariableBinding.of(null), fetched.await())
            assertFalse(
                assumptions.isBound(
                    Arguments.Variable.of(field, "value")
                        .stamp(listOf(ListEngineResult.Index.of(0))),
                ),
            )
        }

    @Test
    fun `variable bindings are written once`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val field = assumptions.schema.objectField("Query", "node_V_A_node")
        val variable = Arguments.Variable.of(field, "value").stamp(emptyList())
        val first = 1

        assumptions.declareBinding(variable)
        assertFailsWith<IllegalStateException> {
            assumptions.declareBinding(
                Arguments.Variable.of(field, "value").stamp(emptyList()),
            )
        }
        assumptions.completeBinding(variable, first)
        assertFailsWith<IllegalStateException> {
            assumptions.completeBinding(variable, 2)
        }

        assertEquals(VariableBinding.of(first), assumptions.getBinding(variable))
    }

    @Test
    fun `variables can be bound immediately exactly once`(): Unit =
        runBlocking {
            val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
            val field = assumptions.schema.objectField("Query", "node_V_A_node")
            val variable = Arguments.Variable.of(field, "value").stamp(emptyList())
            val value = 1

            assumptions.bindVariable(variable, value)

            assertTrue(assumptions.isBound(variable))
            assertEquals(VariableBinding.of(value), assumptions.getBinding(variable))
            assertEquals(VariableBinding.of(value), assumptions.fetchBinding(variable))
            assertFailsWith<IllegalStateException> {
                assumptions.bindVariable(variable, 2)
            }
            assertFailsWith<IllegalStateException> {
                assumptions.declareBinding(variable)
            }
        }

    @Test
    fun `immediate binding rejects a previously declared variable`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val field = assumptions.schema.objectField("Query", "node_V_A_node")
        val variable = Arguments.Variable.of(field, "value").stamp(emptyList())

        assumptions.declareBinding(variable)

        assertFailsWith<IllegalStateException> {
            assumptions.bindVariable(variable, null)
        }
    }

    @Test
    fun `binding values are ground by type`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val field = assumptions.schema.objectField("Query", "node_V_A_node")
        val binding = Arguments.Variable.of(field, "binding").stamp(emptyList())
        val filter =
            toEngineInputObjectData(
                expectedType = assumptions.schema.type("Filter") as Schema.InputObjectType,
                value = mapOf("limit" to 1),
            )

        assumptions.declareBinding(binding)
        assumptions.completeBinding(binding, filter)

        assertEquals(VariableBinding.of(filter), assumptions.getBinding(binding))
    }

    @Test
    fun `open arguments instantiate recursively from bindings`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val node = assumptions.schema.objectField("Query", "node_V_A_node")
        val variable = Arguments.Variable.of(node, "filter").stamp(emptyList())
        val filter =
            toEngineInputObjectData(
                expectedType = assumptions.schema.type("Filter") as Schema.InputObjectType,
                value = mapOf("limit" to 3),
            )
        val arguments =
            Arguments.of(
                field = node,
                fields = mapOf("filter" to variable),
            )
        assumptions.declareBinding(variable)
        assumptions.completeBinding(variable, filter)

        val instantiated =
            context(assumptions) {
                arguments.instantiateBindings(node.arguments)
            }

        val grounded = assertIs<Arguments.Resolved>(instantiated)
        assertEquals(
            filter,
            grounded.fieldValues.getValue("filter"),
        )
    }

    @Test
    fun `argument instantiation preserves the argument error`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val node = assumptions.schema.objectField("Query", "node_V_A_node")
        val arguments = Arguments.of(node, mapOf("filter" to ArgumentResolutionError))

        val instantiated =
            context(assumptions) {
                arguments.instantiateBindings(node.arguments)
            }

        assertSame(Arguments.Error, instantiated)
    }

    @Test
    fun `nested input errors become an argument error`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val node = schema.objectField("Query", "node_V_A_node")
        listOf(
            mapOf(
                "tags" to listOf(ArgumentResolutionError),
            ),
        ).forEach { filter ->
            val arguments =
                Arguments.of(
                    node,
                    mapOf(
                        "filter" to filter,
                    ),
                )

            assertSame(Arguments.Error, arguments)
        }
    }

    @Test
    fun `nested erroneous variable bindings become an argument error`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val node = assumptions.schema.objectField("Query", "node_V_A_node")
        val variable = Arguments.Variable.of(node, "tag").stamp(emptyList())
        val arguments =
            Arguments.of(
                node,
                mapOf(
                    "filter" to
                        mapOf(
                            "tags" to listOf(variable),
                        ),
                ),
            )
        assumptions.bindVariable(variable, VariableBinding.Error)

        val instantiated =
            context(assumptions) {
                arguments.instantiateBindings(node.arguments)
            }

        assertSame(Arguments.Error, instantiated)
    }

    @Test
    fun `object values reject argument-bearing passive fields`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val friendValue = schema.objectOf("Profile")

        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("Profile") {
                field("friend", "limit" to 1) setTo friendValue
            }
        }
    }

    @Test
    fun `input-like factories convert host values according to the schema`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val filter =
            toEngineInputObjectData(
                expectedType = filterType,
                value =
                    mapOf(
                        "limit" to 20,
                        "tags" to listOf("one", "two"),
                        "role" to "ADMIN",
                    ),
            )

        assertEquals(
            20,
            filter.getValue("limit"),
        )
        assertEquals(
            listOf("one", "two"),
            filter.getValue("tags"),
        )
        assertEquals(
            "ADMIN",
            assertIs<String>(filter.getValue("role")),
        )

        val nodeField = schema.field("Query", "node_V_A_node")
        val arguments =
            Arguments.Resolved.of(
                field = nodeField,
                fields =
                    mapOf(
                        "filter" to
                            mapOf(
                                "limit" to 30,
                                "tags" to listOf("nested"),
                            ),
                    ),
            )
        val nestedFilter =
            assertIs<EngineInputObjectData>(arguments.fieldValues.getValue("filter"))
        assertEquals(
            30,
            nestedFilter["limit"],
        )
    }

    @Test
    fun `input-like factories apply declared defaults unless explicitly overridden`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val friendField = schema.field("User", "friend_V_A_node")
        val defaultFriendArguments = Arguments.Resolved.of(friendField, emptyMap())

        assertEquals(
            3,
            defaultFriendArguments.fieldValues.getValue("limit"),
        )

        val nodeField = schema.field("Query", "node_V_A_node")
        val defaultArguments = Arguments.Resolved.of(nodeField, emptyMap())
        val defaultFilter =
            assertIs<EngineInputObjectData>(defaultArguments.fieldValues.getValue("filter"))

        assertEquals(
            10,
            defaultFilter["limit"],
        )
        assertEquals(
            "MEMBER",
            assertIs<String>(defaultFilter["role"]),
        )

        val explicitFilter =
            toEngineInputObjectData(
                expectedType = schema.type("Filter") as Schema.InputObjectType,
                value = mapOf("limit" to 20, "role" to null),
            )
        assertEquals(
            20,
            explicitFilter.getValue("limit"),
        )
        assertTrue(explicitFilter.containsKey("role"))
        assertEquals(null, explicitFilter.getValue("role"))

        val explicitNullFriendArguments =
            Arguments.Resolved.of(
                field = friendField,
                fields = mapOf("limit" to null),
            )
        assertTrue(explicitNullFriendArguments.fieldValues.containsKey("limit"))
        assertEquals(null, explicitNullFriendArguments.fieldValues.getValue("limit"))
    }

    @Test
    fun `input-like factories reject values that do not match the schema`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType

        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("limit" to "twenty"),
            )
        }
        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("tags" to listOf(null)),
            )
        }
        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("missing" to 1),
            )
        }
        assertFailsWith<ClassCastException> {
            Arguments.Resolved.of(
                field = schema.field("Query", "node_V_A_node"),
                fields = mapOf("filter" to 1),
            )
        }
        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("role" to "MISSING"),
            )
        }
    }

    @Test
    fun `rejects non-standard scalar declarations`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        scalar Date

                        type Query {
                          today: Date
                        }
                        """.trimIndent(),
                )
            }

        assertContains(exception.message.orEmpty(), "Date")
    }

    @Test
    fun `rejects non-standard directive definitions`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        directive @authenticated on FIELD_DEFINITION

                        type Query {
                          secret: String @authenticated
                        }
                        """.trimIndent(),
                )
            }

        assertContains(exception.message.orEmpty(), "authenticated")
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
              role: Role!
              friend(limit: Int = 3): User
            }

            type Admin implements Node {
              id: ID!
              level: Int!
            }

            type Profile {
              friend(limit: Int = 3): Profile
            }

            union Actor = User | Admin

            enum Role {
              MEMBER
              ADMIN
            }

            enum OtherRole {
              MEMBER
            }

            input Filter {
              limit: Int = 10
              tags: [String!]
              role: Role = MEMBER
            }

            type Query {
              node(filter: Filter = {tags: ["a"]}): Node!
              actors: [Actor!]!
            }
            """.trimIndent()
    }
}
