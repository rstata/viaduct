package semantics.resolver25

import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals

class CrossKeyRecursiveDemandRegressionTest {
    @Test
    fun `does not copy recursive demand between different grounded keys`() {
        var childrenApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      children(depth: Int!): [Item!]!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val children = schema.objectField("Item", "children")
                    val childType =
                        (children.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item")
                            },
                        children to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ ->
                                childrenApplications += 1
                                check(childrenApplications <= 12) {
                                    "recursive demand crossed grounded keys"
                                }
                                Value.OutputList.of(
                                    typeExpr = childType,
                                    values = listOf(schema.objectOf("Item")),
                                )
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        context(world) {
            world.objectOf("Query").resolve(
                world.fragmentFrom(
                    """
                    fragment ignored on Query {
                      item {
                        children(depth: 1) {
                          children(depth: 2) { __typename }
                        }
                        children(depth: 2) {
                          children(depth: 1) { __typename }
                        }
                      }
                    }
                    """.trimIndent(),
                ).subselections,
            )
        }

        assertEquals(4, childrenApplications)
    }
}
