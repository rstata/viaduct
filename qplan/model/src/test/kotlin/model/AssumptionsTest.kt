package model

import model.spec.SpecSelection
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AssumptionsTest {
    @Test
    fun `preserves an unbound fragment variable as a variable value`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        val (_, selections) =
            assumptions.selectionsFrom(
                """
                fragment ignored on Query {
                  node(filter: ${'$'}filter) {
                    id
                  }
                }
                """.trimIndent(),
            )

        val node = assertIs<SpecSelection.Field>(selections.single())
        assertEquals(
            assumptions.schema.variableValue("filter"),
            node.arguments.getValue("filter"),
        )
    }

    @Test
    fun `instantiates a bound fragment variable with its binding`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                variableValues = { schema ->
                    val filterType = schema.type("Filter") as Schema.InputObjectType
                    mapOf(
                        "filter" to
                            schema.inputObjectValue(
                                type = filterType,
                                fields = mapOf("limit" to schema.intValue(5)),
                            ),
                    )
                },
            )
        val assumptions = world.assumptions
        val filter =
            assertIs<Schema.InputObjectValue>(
                assumptions.variableValues["filter"],
            )
        val filterType = world.schema.type("Filter")

        val (_, selections) =
            assumptions.selectionsFrom(
                """
                fragment ignored on Query {
                  node(filter: ${'$'}filter) {
                    id
                  }
                }
                """.trimIndent(),
            )

        val node = assertIs<SpecSelection.Field>(selections.single())
        assertEquals(filter, node.arguments.getValue("filter"))
        assertEquals(filterType, filter.type)
        assertEquals(assumptions.schema.type("Filter"), filter.type)
    }

    @Test
    fun `parses and validates a named fragment against the retained schema`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        val (typeCondition, selections) =
            assumptions.selectionsFrom(
                """
                fragment ignored on Query {
                  result: node(filter: {tags: "one", role: ADMIN}) {
                    id
                  }
                }
                """.trimIndent(),
            )

        assertEquals(assumptions.schema.query, typeCondition)
        val node = assertIs<SpecSelection.Field>(selections.single())
        assertEquals("result", node.alias)
        assertEquals("node", node.fieldName)

        val filter = assertIs<Schema.InputObjectValue>(node.arguments.getValue("filter"))
        assertEquals(assumptions.schema.type("Filter"), filter.type)
        val role = assertIs<Schema.EnumValue>(filter.fieldValues["role"])
        assertEquals(assumptions.schema.type("Role"), role.baseType)
        assertEquals(
            Schema.TypeExpr.Named(role.baseType, isNullable = false),
            role.type,
        )
        assertEquals("ADMIN", role.enumValue)
        val limit = assertIs<Schema.IntValue>(filter.fieldValues["limit"])
        assertEquals(assumptions.schema.type("Int"), limit.baseType)
        assertEquals(
            Schema.TypeExpr.Named(Schema.IntType, isNullable = false),
            limit.type,
        )
        assertEquals(10, limit.intValue)
        val tags = assertIs<Schema.InputListValue>(filter.fieldValues["tags"])
        assertEquals(
            "one",
            assertIs<Schema.StringValue>(tags.inputListValues.single()).stringValue,
        )

        val id = assertIs<SpecSelection.Field>(node.subselections.orEmpty().single())
        assertEquals("id", id.fieldName)
    }

    @Test
    fun `rejects a named fragment that is invalid for the retained schema`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        val exception =
            assertFailsWith<IllegalArgumentException> {
                assumptions.selectionsFrom(
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
    fun `constructs assumptions from a schema and qualified variable values`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                variableValues = {
                    mapOf("failed" to Schema.ErrorValue)
                },
            )
        val assumptions = world.assumptions

        assertEquals(world.schema, assumptions.schema)
        assertEquals(Schema.ErrorValue, assumptions.variableValues["failed"])

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
            Schema.TypeExpr.Named(Schema.StringType, isNullable = false),
            typeName.type,
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
        val emptyArguments = schema.argumentsValue(actors, emptyMap())
        val otherEmptyArguments = schema.argumentsValue(typeName, emptyMap())
        assertEquals(emptyArguments, otherEmptyArguments)
        assertEquals(Schema.NoArguments, emptyArguments.type)
        assertEquals(emptyArguments.type, emptyArguments.fieldValues.containingType)
        assertEquals(
            Schema.TypeExpr.List(
                elementType = Schema.TypeExpr.Named(actor, isNullable = false),
                isNullable = false,
            ),
            actors.type,
        )

        val nodeField = schema.field("Query", "node")
        assertEquals(query, nodeField.containingType)
        assertFalse(nodeField.arguments == Schema.NoArguments)
        val filterArgument = nodeField.arguments.fields.getValue("filter")
        assertEquals(nodeField.arguments, filterArgument.containingType)
        assertEquals("filter", filterArgument.name)
        assertFalse(filterArgument.isRequired)
        val filterDefault =
            assertIs<Schema.DefaultValue.Present>(filterArgument.defaultValue)
        val filterValue = assertIs<Schema.InputObjectValue>(filterDefault.value)
        val nodeArguments =
            schema.argumentsValue(
                field = nodeField,
                fields = mapOf("filter" to filterValue),
            )
        assertEquals(nodeField.arguments, nodeArguments.type)
        assertEquals(nodeArguments.type, nodeArguments.fieldValues.containingType)
        assertEquals(filterValue, nodeArguments.fieldValues["filter"])
        assertEquals(schema.type("Filter"), filterValue.type)
        val defaultRole = assertIs<Schema.EnumValue>(filterValue.fieldValues["role"])
        assertEquals(schema.type("Role"), defaultRole.baseType)
        assertEquals("MEMBER", defaultRole.enumValue)
        val defaultLimit =
            assertIs<Schema.IntValue>(
                filterValue.fieldValues["limit"],
            )
        assertEquals(schema.type("Int"), defaultLimit.baseType)
        assertEquals(10, defaultLimit.intValue)
        val tags =
            assertIs<Schema.InputListValue>(
                filterValue.fieldValues["tags"],
            )
        assertEquals(
            "a",
            assertIs<Schema.StringValue>(tags.inputListValues.single()).stringValue,
        )
        val nodeKey =
            schema.objectEngineResultKey(
                field = nodeField,
                arguments = mapOf("filter" to filterValue),
            )
        assertEquals(nodeField, nodeKey.field)
        assertEquals(nodeField.arguments, nodeKey.arguments.type)
        assertEquals(filterValue, nodeKey.arguments.fieldValues["filter"])
        assertEquals(
            nodeKey,
            schema.objectEngineResultKey(
                field = nodeField,
                arguments = mapOf("filter" to filterValue),
            ),
        )
        val interfaceIdKey =
            schema.objectEngineResultKey(
                field = schema.field("Node", "id"),
                arguments = emptyMap(),
            )
        val objectIdKey =
            schema.objectEngineResultKey(
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
        assertFalse(filter.fields.getValue("tags").type.isBaseTypeNullable)
    }

    @Test
    fun `input-like factories convert host values according to the schema`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val filter =
            schema.inputObjectValue(
                type = filterType,
                fields =
                    mapOf(
                        "limit" to 20,
                        "tags" to listOf("one", "two"),
                        "role" to "ADMIN",
                    ),
            )

        assertEquals(20, assertIs<Schema.IntValue>(filter.fieldValues["limit"]).intValue)
        assertEquals(
            listOf("one", "two"),
            assertIs<Schema.InputListValue>(filter.fieldValues["tags"])
                .inputListValues
                .map { assertIs<Schema.StringValue>(it).stringValue },
        )
        assertEquals(
            "ADMIN",
            assertIs<Schema.EnumValue>(filter.fieldValues["role"]).enumValue,
        )

        val nodeField = schema.field("Query", "node")
        val arguments =
            schema.argumentsValue(
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
            assertIs<Schema.InputObjectValue>(arguments.fieldValues["filter"])
        assertEquals(filterType, nestedFilter.type)
        assertEquals(
            30,
            assertIs<Schema.IntValue>(nestedFilter.fieldValues["limit"]).intValue,
        )
    }

    @Test
    fun `input-like factories reject values that do not match the schema`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val otherRole =
            schema.enumValue(
                schema.type("OtherRole") as Schema.EnumType,
                "MEMBER",
            )

        assertFailsWith<ClassCastException> {
            schema.inputObjectValue(
                type = filterType,
                fields = mapOf("limit" to "twenty"),
            )
        }
        assertFailsWith<ClassCastException> {
            schema.inputObjectValue(
                type = filterType,
                fields = mapOf("tags" to listOf(null)),
            )
        }
        assertFailsWith<ClassCastException> {
            schema.inputObjectValue(
                type = filterType,
                fields = mapOf("missing" to 1),
            )
        }
        assertFailsWith<ClassCastException> {
            schema.argumentsValue(
                field = schema.field("Query", "node"),
                fields = mapOf("filter" to 1),
            )
        }
        assertFailsWith<ClassCastException> {
            schema.inputObjectValue(
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
