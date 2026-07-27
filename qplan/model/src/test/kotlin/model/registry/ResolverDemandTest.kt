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
import kotlin.test.assertTrue

class ResolverDemandTest {
    @Test
    fun `derives resolver demand from all reachable selections and their possible types`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = DEMAND_SCHEMA,
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    val admin = schema.type("Admin") as Schema.ObjectType
                    mapOf(
                        user to NodeResolver { error("Not invoked") },
                        admin to NodeResolver { error("Not invoked") },
                    )
                },
                fieldResolvers = { schema ->
                    val query = schema.query
                    val node = schema.type("Node") as Schema.InterfaceType
                    val user = schema.type("User") as Schema.ObjectType
                    val admin = schema.type("Admin") as Schema.ObjectType
                    val result = schema.type("Result") as Schema.ObjectType
                    val resolvedSelection =
                        selection(
                            schema = schema,
                            nominalType = node,
                            fieldName = "resolved",
                            possibleTypes = setOf(user, admin),
                            subselections =
                                selectionForestOf(
                                    selection(
                                        schema = schema,
                                        nominalType = result,
                                        fieldName = "value",
                                    ),
                                ),
                        )
                    val consumerFragment =
                        fragment(
                            nominalType = query,
                            selections =
                                selectionForestOf(
                                    selection(
                                        schema = schema,
                                        nominalType = query,
                                        fieldName = "node",
                                        subselections = selectionForestOf(resolvedSelection),
                                    ),
                                ),
                        )
                    val outerFragment =
                        fragment(
                            nominalType = query,
                            selections =
                                selectionForestOf(
                                    selection(
                                        schema = schema,
                                        nominalType = query,
                                        fieldName = "consumer",
                                    ),
                                ),
                        )
                    mapOf(
                        schema.field("Query", "node") to resolver(fragment(query)),
                        schema.field("Query", "consumer") to
                            resolver(consumerFragment),
                        schema.field("Query", "outer") to resolver(outerFragment),
                        schema.field("User", "resolved") to
                            resolver(fragment(user)),
                        schema.field("Admin", "resolved") to
                            resolver(fragment(admin)),
                    )
                },
            )
        val schema = world.schema
        val registry = world.executorRegistry
        val user = schema.type("User") as Schema.ObjectType
        val admin = schema.type("Admin") as Schema.ObjectType
        val queryNode = registry.fieldResolver(schema.field("Query", "node"))
        val consumer = registry.fieldResolver(schema.field("Query", "consumer"))
        val outer = registry.fieldResolver(schema.field("Query", "outer"))
        val userResolved = registry.fieldResolver(schema.field("User", "resolved"))
        val adminResolved = registry.fieldResolver(schema.field("Admin", "resolved"))
        val userNode = registry.nodeResolver(user)
        val adminNode = registry.nodeResolver(admin)

        assertEquals(
            setOf(queryNode, userNode, adminNode, userResolved, adminResolved),
            consumer.mayDemandFrom,
        )
        assertEquals(setOf(consumer), outer.mayDemandFrom)
        assertTrue(queryNode.mayDemandFrom.isEmpty())
        assertTrue(userNode.mayDemandFrom.isEmpty())
        assertTrue(adminNode.mayDemandFrom.isEmpty())
        assertTrue(userResolved.mayDemandFrom.isEmpty())
        assertTrue(adminResolved.mayDemandFrom.isEmpty())

        assertEquals(setOf(outer), consumer.mayBeDemandedBy)
        assertEquals(setOf(consumer), queryNode.mayBeDemandedBy)
        assertEquals(setOf(consumer), userNode.mayBeDemandedBy)
        assertEquals(setOf(consumer), adminNode.mayBeDemandedBy)
        assertEquals(setOf(consumer), userResolved.mayBeDemandedBy)
        assertEquals(setOf(consumer), adminResolved.mayBeDemandedBy)
        assertTrue(outer.mayBeDemandedBy.isEmpty())
    }

    @Test
    fun `rejects cyclic resolver demand`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = CYCLE_SCHEMA,
                    fieldResolvers = { schema ->
                        val query = schema.query
                        mapOf(
                            schema.field("Query", "a") to
                                resolver(
                                    fragment(
                                        nominalType = query,
                                        selections =
                                            selectionForestOf(
                                                selection(
                                                    schema = schema,
                                                    nominalType = query,
                                                    fieldName = "b",
                                                ),
                                            ),
                                    ),
                                ),
                            schema.field("Query", "b") to
                                resolver(
                                    fragment(
                                        nominalType = query,
                                        selections =
                                            selectionForestOf(
                                                selection(
                                                    schema = schema,
                                                    nominalType = query,
                                                    fieldName = "a",
                                                ),
                                            ),
                                    ),
                                ),
                        )
                    },
                )
            }

        assertTrue(exception.message!!.contains("demand cycle"))
    }

    private companion object {
        val DEMAND_SCHEMA =
            """
            interface Node {
              id: ID!
              resolved: Result
            }

            type User implements Node {
              id: ID!
              resolved: Result
            }

            type Admin implements Node {
              id: ID!
              resolved: Result
            }

            type Result {
              value: String
            }

            type Query {
              node: Node
              consumer: Result
              outer: Result
            }
            """.trimIndent()

        val CYCLE_SCHEMA =
            """
            type Result {
              value: String
            }

            type Query {
              a: Result
              b: Result
            }
            """.trimIndent()

        fun resolver(fragment: Fragment): FieldResolver =
            FieldResolver(
                objectFragment = fragment,
                function = { _, _ -> error("Not invoked") },
            )

        fun fragment(
            nominalType: Schema.CompositeType,
            selections: SelectionForest = selectionForestOf(),
        ): Fragment =
            object : Fragment {
                override val nominalType = nominalType
                override val subselections = selections
            }

        fun selection(
            schema: Schema,
            nominalType: Schema.CompositeType,
            fieldName: String,
            possibleTypes: Set<Schema.ObjectType> = nominalType.possibleTypes,
            subselections: SelectionForest = selectionForestOf(),
        ): Selection =
            Selection.of(
                key =
                    schema.objectKey(
                        field = schema.field(nominalType.typeName, fieldName),
                        arguments = emptyMap(),
                    ),
                nominalType = nominalType,
                possibleTypes = possibleTypes,
                subselections = subselections,
            )
    }
}
