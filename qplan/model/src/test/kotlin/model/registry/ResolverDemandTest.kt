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
                        user to model.testing.nodeResolverOf { error("Not invoked") },
                        admin to model.testing.nodeResolverOf { error("Not invoked") },
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
        val queryNode = schema.field("Query", "node")
        val consumer = schema.field("Query", "consumer")
        val outer = schema.field("Query", "outer")
        val userResolved = schema.field("User", "resolved")
        val adminResolved = schema.field("Admin", "resolved")

        assertEquals(
            setOf(queryNode, user, admin, userResolved, adminResolved),
            registry.mayDemandFrom(consumer),
        )
        assertEquals(setOf(consumer), registry.mayDemandFrom(outer))
        assertTrue(registry.mayDemandFrom(queryNode).isEmpty())
        assertTrue(registry.mayDemandFrom(userResolved).isEmpty())
        assertTrue(registry.mayDemandFrom(adminResolved).isEmpty())

        assertEquals(setOf(outer), registry.mayBeDemandedBy(consumer))
        assertEquals(setOf(consumer), registry.mayBeDemandedBy(queryNode))
        assertEquals(setOf(consumer), registry.mayBeDemandedBy(user))
        assertEquals(setOf(consumer), registry.mayBeDemandedBy(admin))
        assertEquals(setOf(consumer), registry.mayBeDemandedBy(userResolved))
        assertEquals(setOf(consumer), registry.mayBeDemandedBy(adminResolved))
        assertTrue(registry.mayBeDemandedBy(outer).isEmpty())
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
            model.testing.fieldResolverOf(
                objectFragment = fragment,
                function = { _, _ -> error("Not invoked") },
            )

        fun fragment(
            nominalType: Schema.CompositeType,
            selections: SelectionForest = selectionForestOf(),
        ): Fragment =
            Fragment.of(nominalType, selections)

        fun selection(
            schema: Schema,
            nominalType: Schema.CompositeType,
            fieldName: String,
            possibleTypes: Set<Schema.ObjectType> = nominalType.possibleTypes,
            subselections: SelectionForest = selectionForestOf(),
        ): Selection =
            Selection.of(
                key =
                    Schema.ObjectKey.of(
                        field = schema.field(nominalType.typeName, fieldName),
                        arguments = emptyMap(),
                    ),
                nominalType = nominalType,
                possibleTypes = possibleTypes,
                subselections = subselections,
            )
    }
}
