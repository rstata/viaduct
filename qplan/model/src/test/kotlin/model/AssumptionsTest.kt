package model

import model.spec.SpecSelection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class AssumptionsTest {
    @Test
    fun `preserves an unbound fragment variable as a variable value`() {
        val assumptions = Assumptions.of(GJSchema.fromSDL(SCHEMA_SDL), emptyMap())

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
        val schema = GJSchema.fromSDL(SCHEMA_SDL)
        val filterType = schema.type("Filter") as Schema.InputObjectType
        val filter =
            schema.inputObjectValue(
                type = filterType,
                fields = mapOf("limit" to schema.intValue(5)),
            )
        val assumptions =
            Assumptions.of(
                schema = schema,
                bindings = mapOf("filter" to filter),
            )

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
        assertSame(filter, node.arguments.getValue("filter"))
        assertSame(filterType, filter.type)
        assertSame(assumptions.schema.type("Filter"), filter.type)
    }

    @Test
    fun `parses and validates a named fragment against the retained schema`() {
        val assumptions = Assumptions.of(GJSchema.fromSDL(SCHEMA_SDL), emptyMap())

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

        assertSame(assumptions.schema.query, typeCondition)
        val node = assertIs<SpecSelection.Field>(selections.single())
        assertEquals("result", node.alias)
        assertEquals("node", node.fieldName)

        val filter = assertIs<Schema.InputObjectValue>(node.arguments.getValue("filter"))
        assertSame(assumptions.schema.type("Filter"), filter.type)
        val role = assertIs<Schema.EnumValue>(filter.fieldValues["role"])
        assertSame(assumptions.schema.type("Role"), role.type)
        assertEquals("ADMIN", role.enumValue)
        val limit = assertIs<Schema.IntValue>(filter.fieldValues["limit"])
        assertSame(assumptions.schema.type("Int"), limit.type)
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
        val assumptions = Assumptions.of(GJSchema.fromSDL(SCHEMA_SDL), emptyMap())

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
        val suppliedSchema = GJSchema.fromSDL(SCHEMA_SDL)
        val assumptions =
            Assumptions.of(
                schema = suppliedSchema,
                bindings = mapOf("failed" to Schema.ErrorValue),
            )

        assertSame(suppliedSchema, assumptions.schema)
        assertSame(Schema.ErrorValue, assumptions.variableValues["failed"])

        val schema = assumptions.schema
        val query = schema.query
        val node = assertIs<Schema.InterfaceType>(schema.type("Node"))
        val user = assertIs<Schema.ObjectType>(schema.type("User"))
        val admin = assertIs<Schema.ObjectType>(schema.type("Admin"))
        val actor = assertIs<Schema.UnionType>(schema.type("Actor"))

        assertSame(query, schema.type("Query"))
        assertEquals(setOf(user, admin), node.possibleTypes)
        assertEquals(setOf(user, admin), actor.possibleTypes)
        assertEquals(Schema.TypeRelation.WIDER_THAN, schema.relation("Node", "User"))
        assertEquals(Schema.TypeRelation.NARROWER_THAN, schema.relation("User", "Node"))
        assertEquals(Schema.TypeRelation.WIDER_THAN, schema.relation("Actor", "Admin"))
        assertEquals(Schema.TypeRelation.COPARENT, schema.relation("Node", "Actor"))
        assertEquals(true, schema.isSpreadable("Node", "Actor"))

        val typeName = schema.field("Node", "__typename")
        assertSame(node, typeName.containingType)
        assertSame(Schema.NoArguments, typeName.arguments)
        assertEquals(emptyMap(), Schema.NoArguments.fields)
        assertEquals(
            Schema.TypeExpr.Named(Schema.ScalarType.String, isNullable = false),
            typeName.type,
        )

        val actors = schema.field("Query", "actors")
        listOf(
            schema.field("Node", "id"),
            schema.field("User", "name"),
            schema.field("Admin", "level"),
            actors,
        ).forEach { field ->
            assertSame(Schema.NoArguments, field.arguments)
        }
        val emptyArguments = schema.argumentsValue(actors, emptyMap())
        val otherEmptyArguments = schema.argumentsValue(typeName, emptyMap())
        assertEquals(emptyArguments, otherEmptyArguments)
        assertFalse(emptyArguments === otherEmptyArguments)
        assertSame(Schema.NoArguments, emptyArguments.type)
        assertSame(emptyArguments.type, emptyArguments.fieldValues.containingType)
        assertEquals(
            Schema.TypeExpr.List(
                elementType = Schema.TypeExpr.Named(actor, isNullable = false),
                isNullable = false,
            ),
            actors.type,
        )

        val nodeField = schema.field("Query", "node")
        assertSame(query, nodeField.containingType)
        assertFalse(nodeField.arguments == Schema.NoArguments)
        val filterArgument = nodeField.arguments.fields.getValue("filter")
        assertSame(nodeField.arguments, filterArgument.containingType)
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
        assertSame(nodeField.arguments, nodeArguments.type)
        assertSame(nodeArguments.type, nodeArguments.fieldValues.containingType)
        assertSame(filterValue, nodeArguments.fieldValues["filter"])
        assertSame(schema.type("Filter"), filterValue.type)
        val defaultRole = assertIs<Schema.EnumValue>(filterValue.fieldValues["role"])
        assertSame(schema.type("Role"), defaultRole.type)
        assertEquals("MEMBER", defaultRole.enumValue)
        val defaultLimit =
            assertIs<Schema.IntValue>(
                filterValue.fieldValues["limit"],
            )
        assertSame(schema.type("Int"), defaultLimit.type)
        assertEquals(10, defaultLimit.intValue)
        val tags =
            assertIs<Schema.InputListValue>(
                filterValue.fieldValues["tags"],
            )
        assertEquals(
            "a",
            assertIs<Schema.StringValue>(tags.inputListValues.single()).stringValue,
        )

        val friendField = schema.field("User", "friend")
        assertFalse(friendField.arguments == Schema.NoArguments)
        val limitArgument: Schema.InputLikeField =
            friendField.arguments.fields.getValue("limit")
        assertSame(friendField.arguments, limitArgument.containingType)
        assertEquals("limit", limitArgument.name)
        assertFalse(limitArgument.isRequired)

        val filter = assertIs<Schema.InputObjectType>(schema.type("Filter"))
        val limitInputField: Schema.InputLikeField = filter.fields.getValue("limit")
        assertSame(filter, limitInputField.containingType)
        assertEquals("limit", limitInputField.name)
        assertFalse(limitInputField.isRequired)
        assertFalse(filter.fields.getValue("tags").type.isBaseTypeNullable)
    }

    @Test
    fun `value factories reject definitions from another schema instance`() {
        val schemaA = GJSchema.fromSDL(SCHEMA_SDL)
        val schemaB = GJSchema.fromSDL(SCHEMA_SDL)
        val foreignRole =
            schemaA.enumValue(
                schemaA.type("Role") as Schema.EnumType,
                "MEMBER",
            )

        assertFailsWith<IllegalArgumentException> {
            schemaB.inputObjectValue(
                type = schemaA.type("Filter") as Schema.InputObjectType,
                fields = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            schemaB.inputObjectValue(
                type = schemaB.type("Filter") as Schema.InputObjectType,
                fields = mapOf("role" to foreignRole),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            schemaB.argumentsValue(
                field = schemaA.field("Query", "node"),
                fields = emptyMap(),
            )
        }
    }

    @Test
    fun `rejects non-standard scalar declarations`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                GJSchema.fromSDL(
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
                GJSchema.fromSDL(
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
