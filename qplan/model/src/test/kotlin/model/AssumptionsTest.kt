package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AssumptionsTest {
    @Test
    fun `constructs a resolver fragment variable with its defining field`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val variableField = assumptions.schema.objectField("Query", "node")

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
            Value.Variable.of("filter", variableField, path = null),
            node.key.arguments.fieldValues.getValue("filter"),
        )
        assertNotEquals(
            Value.Variable.of("other", variableField, path = null),
            node.key.arguments.fieldValues.getValue("filter"),
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
        assertEquals("node", node.key.field.fieldName)

        val filter =
            assertIs<Value.InputObject>(
                node.key.arguments.fieldValues.getValue("filter"),
            )
        assertEquals(assumptions.schema.type("Filter"), filter.type)
        val role = assertIs<Value.Enum>(filter.fieldValues["role"])
        assertEquals(assumptions.schema.type("Role"), role.type)
        assertEquals("ADMIN", role.enumValue)
        val limit = assertIs<Value.Int>(filter.fieldValues["limit"])
        assertEquals(Schema.IntType, limit.type)
        assertEquals(10, limit.intValue)
        val tags = assertIs<Value.InputList>(filter.fieldValues["tags"])
        assertEquals(
            "one",
            assertIs<Value.String>(tags.values.single()).stringValue,
        )

        val id = node.subselections.single()
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
            Value.InputObject.of(
                type = filterType,
                fields = mapOf("limit" to Value.Int.of(5)),
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
            fragment.subselections.single().key.arguments.fieldValues.getValue("filter"),
        )
    }

    @Test
    fun `rejects operation bindings containing unresolved variables`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val variableField = schema.objectField("Query", "node")
        val filter =
            Value.InputObject.of(
                type = filterType,
                fields =
                    mapOf(
                        "limit" to Value.Variable.of("nested", variableField, path = null),
                    ),
            )

        assertFailsWith<IllegalArgumentException> {
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
        assertEquals(Schema.TypeRelation.WIDER_THAN, schema.relation(node, user))
        assertEquals(Schema.TypeRelation.NARROWER_THAN, schema.relation(user, node))
        assertEquals(Schema.TypeRelation.WIDER_THAN, schema.relation(actor, admin))
        assertEquals(Schema.TypeRelation.COPARENT, schema.relation(node, actor))
        assertEquals(setOf(node, user, admin, actor), schema.spreadableTypes(node))
        assertEquals(true, schema.isSpreadable(node, actor))

        val typeName = schema.field("Node", "__typename")
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
        val emptyArguments = Value.Arguments.of(actors, emptyMap())
        val otherEmptyArguments = Value.Arguments.of(typeName, emptyMap())
        assertEquals(emptyArguments, otherEmptyArguments)
        assertEquals(Schema.NoArguments, emptyArguments.type)
        assertEquals(emptyArguments.type, emptyArguments.fieldValues.containingType)
        assertEquals(
            TypeExpr.List.of(
                elementType = TypeExpr.Named.of(actor, isNullable = false),
                isNullable = false,
            ),
            actors.typeExpr,
        )

        val nodeField = schema.field("Query", "node")
        assertEquals(query, nodeField.containingType)
        assertFalse(nodeField.arguments == Schema.NoArguments)
        val filterArgument = nodeField.arguments.fields.getValue("filter")
        assertEquals(nodeField.arguments, filterArgument.containingType)
        assertEquals("filter", filterArgument.name)
        assertFalse(filterArgument.isRequired)
        val filterDefault =
            assertIs<Value.Default.Present>(filterArgument.defaultValue)
        val filterValue = assertIs<Value.InputObject>(filterDefault.value)
        val nodeArguments =
            Value.Arguments.of(
                field = nodeField,
                fields = mapOf("filter" to filterValue),
            )
        assertEquals(nodeField.arguments, nodeArguments.type)
        assertEquals(nodeArguments.type, nodeArguments.fieldValues.containingType)
        assertEquals(filterValue, nodeArguments.fieldValues["filter"])
        assertEquals(schema.type("Filter"), filterValue.type)
        val defaultRole = assertIs<Value.Enum>(filterValue.fieldValues["role"])
        assertEquals(schema.type("Role"), defaultRole.type)
        assertEquals("MEMBER", defaultRole.enumValue)
        val defaultLimit =
            assertIs<Value.Int>(
                filterValue.fieldValues["limit"],
            )
        assertEquals(Schema.IntType, defaultLimit.type)
        assertEquals(10, defaultLimit.intValue)
        val tags =
            assertIs<Value.InputList>(
                filterValue.fieldValues["tags"],
            )
        assertEquals(
            "a",
            assertIs<Value.String>(tags.values.single()).stringValue,
        )
        val nodeKey =
            Value.Key.of(
                field = nodeField,
                arguments = mapOf("filter" to filterValue),
            )
        assertEquals(nodeField, nodeKey.field)
        assertEquals(nodeField.arguments, nodeKey.arguments.type)
        assertEquals(filterValue, nodeKey.arguments.fieldValues["filter"])
        assertEquals(
            nodeKey,
            Value.Key.of(
                field = nodeField,
                arguments = mapOf("filter" to filterValue),
            ),
        )
        val interfaceIdKey =
            Value.Key.of(
                field = schema.field("Node", "id"),
                arguments = emptyMap(),
            )
        val objectIdKey =
            Value.Key.of(
                field = schema.field("User", "id"),
                arguments = emptyMap(),
            )
        assertFalse(interfaceIdKey == objectIdKey)

        val friendField = schema.field("User", "friend")
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
    fun `object values use concrete argument-sensitive keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = schema.type("User") as Schema.ObjectType
        val friend = schema.objectField("User", "friend")
        val firstKey = Value.ObjectKey.of(friend, mapOf("limit" to 1))
        val secondKey = Value.ObjectKey.of(friend, mapOf("limit" to 2))
        val friendValue = schema.objectOf("User")

        val value =
            schema.objectOf("User") {
                field("friend", "limit" to 1) setTo friendValue
                field("friend", "limit" to 2) setTo null
            }

        assertEquals(setOf(firstKey, secondKey), value.fieldValues.keys)
        assertEquals(friendValue, value.fieldValues[firstKey])
        assertEquals(null, value.fieldValues[secondKey])

        assertFailsWith<IllegalArgumentException> {
            Value.Object.of(
                type = user,
                fields =
                    mapOf(
                        Value.ObjectKey.of(
                            friend,
                            mapOf(
                                "limit" to
                                    Value.Variable.of(
                                        "limit",
                                        schema.objectField("Query", "node"),
                                        path = null,
                                    ),
                            ),
                        ) to friendValue,
                    ),
            )
        }
    }

    @Test
    fun `input-like factories convert host values according to the schema`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val filter =
            Value.InputObject.of(
                type = filterType,
                fields =
                    mapOf(
                        "limit" to 20,
                        "tags" to listOf("one", "two"),
                        "role" to "ADMIN",
                    ),
            )

        assertEquals(20, assertIs<Value.Int>(filter.fieldValues["limit"]).intValue)
        assertEquals(
            listOf("one", "two"),
            assertIs<Value.InputList>(filter.fieldValues["tags"])
                .values
                .map { assertIs<Value.String>(it).stringValue },
        )
        assertEquals(
            "ADMIN",
            assertIs<Value.Enum>(filter.fieldValues["role"]).enumValue,
        )

        val nodeField = schema.field("Query", "node")
        val arguments =
            Value.Arguments.of(
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
            assertIs<Value.InputObject>(arguments.fieldValues["filter"])
        assertEquals(filterType, nestedFilter.type)
        assertEquals(
            30,
            assertIs<Value.Int>(nestedFilter.fieldValues["limit"]).intValue,
        )
    }

    @Test
    fun `input-like factories apply declared defaults unless explicitly overridden`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val friendField = schema.field("User", "friend")
        val defaultFriendArguments = Value.Arguments.of(friendField, emptyMap())

        assertEquals(
            3,
            assertIs<Value.Int>(defaultFriendArguments.fieldValues["limit"]).intValue,
        )

        val nodeField = schema.field("Query", "node")
        val defaultArguments = Value.Arguments.of(nodeField, emptyMap())
        val defaultFilter =
            assertIs<Value.InputObject>(defaultArguments.fieldValues["filter"])

        assertEquals(
            10,
            assertIs<Value.Int>(defaultFilter.fieldValues["limit"]).intValue,
        )
        assertEquals(
            "MEMBER",
            assertIs<Value.Enum>(defaultFilter.fieldValues["role"]).enumValue,
        )

        val explicitFilter =
            Value.InputObject.of(
                type = schema.type("Filter") as Schema.InputObjectType,
                fields = mapOf("limit" to 20, "role" to null),
            )
        assertEquals(
            20,
            assertIs<Value.Int>(explicitFilter.fieldValues["limit"]).intValue,
        )
        assertTrue(explicitFilter.fieldValues.containsKey("role"))
        assertEquals(null, explicitFilter.fieldValues["role"])

        val explicitNullFriendArguments =
            Value.Arguments.of(
                field = friendField,
                fields = mapOf("limit" to null),
            )
        assertTrue(explicitNullFriendArguments.fieldValues.containsKey("limit"))
        assertEquals(null, explicitNullFriendArguments.fieldValues["limit"])
    }

    @Test
    fun `input-like factories reject values that do not match the schema`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val otherRole =
            Value.Enum.of(
                schema.type("OtherRole") as Schema.EnumType,
                "MEMBER",
            )

        assertFailsWith<ClassCastException> {
            Value.InputObject.of(
                type = filterType,
                fields = mapOf("limit" to "twenty"),
            )
        }
        assertFailsWith<ClassCastException> {
            Value.InputObject.of(
                type = filterType,
                fields = mapOf("tags" to listOf(null)),
            )
        }
        assertFailsWith<ClassCastException> {
            Value.InputObject.of(
                type = filterType,
                fields = mapOf("missing" to 1),
            )
        }
        assertFailsWith<ClassCastException> {
            Value.Arguments.of(
                field = schema.field("Query", "node"),
                fields = mapOf("filter" to 1),
            )
        }
        assertFailsWith<ClassCastException> {
            Value.InputObject.of(
                type = filterType,
                fields = mapOf("role" to otherRole),
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
