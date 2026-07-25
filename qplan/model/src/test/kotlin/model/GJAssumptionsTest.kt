package model

import model.spec.SpecSelection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class GJAssumptionsTest {
    @Test
    fun `preserves an unbound fragment variable as a variable value`() {
        val assumptions = GJAssumptions(SCHEMA_SDL, emptyMap())

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
            GraphQLVariableValue.of("filter"),
            node.arguments.getValue("filter"),
        )
    }

    @Test
    fun `instantiates a bound fragment variable with its binding`() {
        val filter =
            GraphQLInputObjectValue.of(
                typeName = "Filter",
                fields = mapOf("limit" to GraphQLIntValue.of(5)),
            )
        val assumptions =
            GJAssumptions(
                schemaSDL = SCHEMA_SDL,
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
    }

    @Test
    fun `parses and validates a named fragment against the retained schema`() {
        val assumptions = GJAssumptions(SCHEMA_SDL, emptyMap())

        val (typeCondition, selections) =
            assumptions.selectionsFrom(
                """
                fragment ignored on Query {
                  result: node(filter: {tags: "one"}) {
                    id
                  }
                }
                """.trimIndent(),
            )

        assertSame(assumptions.schema.query, typeCondition)
        val node = assertIs<SpecSelection.Field>(selections.single())
        assertEquals("result", node.alias)
        assertEquals("node", node.fieldName)

        val filter = assertIs<GraphQLInputObjectValue>(node.arguments.getValue("filter"))
        assertEquals(
            10,
            assertIs<GraphQLIntValue>(filter.inputObjectFields["limit"]).intValue,
        )
        val tags = assertIs<GraphQLInputList>(filter.inputObjectFields["tags"])
        assertEquals(
            "one",
            assertIs<GraphQLStringValue>(tags.inputListValues.single()).stringValue,
        )

        val id = assertIs<SpecSelection.Field>(node.subselections.orEmpty().single())
        assertEquals("id", id.fieldName)
    }

    @Test
    fun `rejects a named fragment that is invalid for the retained schema`() {
        val assumptions = GJAssumptions(SCHEMA_SDL, emptyMap())

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
    fun `constructs global assumptions from SDL and qualified variable values`() {
        val assumptions =
            GJAssumptions(
                schemaSDL = SCHEMA_SDL,
                bindings = mapOf("failed" to GraphQLErrorValue),
            )

        assertSame(GraphQLErrorValue, assumptions.variableValues["failed"])

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
        assertEquals(
            Schema.TypeExpr.Named(Schema.ScalarType.String, isNullable = false),
            typeName.type,
        )

        val actors = schema.field("Query", "actors")
        assertEquals(
            Schema.TypeExpr.List(
                elementType = Schema.TypeExpr.Named(actor, isNullable = false),
                isNullable = false,
            ),
            actors.type,
        )

        val nodeField = schema.field("Query", "node")
        assertSame(query, nodeField.containingType)
        val filterArgument = nodeField.arguments.getValue("filter")
        assertSame(nodeField, filterArgument.containingField)
        val filterDefault =
            assertIs<Schema.DefaultValue.Present>(filterArgument.defaultValue)
        val filterValue = assertIs<GraphQLInputObjectValue>(filterDefault.value)
        assertEquals("Filter", filterValue.inputObjectTypeName)
        assertEquals(
            10,
            assertIs<GraphQLIntValue>(
                filterValue.inputObjectFields["limit"],
            ).intValue,
        )
        val tags =
            assertIs<GraphQLInputList>(
                filterValue.inputObjectFields["tags"],
            )
        assertEquals(
            "a",
            assertIs<GraphQLStringValue>(tags.inputListValues.single()).stringValue,
        )

        val filter = assertIs<Schema.InputObjectType>(schema.type("Filter"))
        assertSame(filter, filter.fields.getValue("limit").containingType)
        assertFalse(filter.fields.getValue("tags").type.isBaseTypeNullable)
    }

    @Test
    fun `rejects non-standard scalar declarations`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                GJAssumptions(
                    schemaSDL =
                        """
                        scalar Date

                        type Query {
                          today: Date
                        }
                        """.trimIndent(),
                    bindings = emptyMap(),
                )
            }

        assertContains(exception.message.orEmpty(), "Date")
    }

    @Test
    fun `rejects non-standard directive definitions`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                GJAssumptions(
                    schemaSDL =
                        """
                        directive @authenticated on FIELD_DEFINITION

                        type Query {
                          secret: String @authenticated
                        }
                        """.trimIndent(),
                    bindings = emptyMap(),
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
            }

            type Query {
              node(filter: Filter = {tags: ["a"]}): Node!
              actors: [Actor!]!
            }
            """.trimIndent()
    }
}
