package semantics.contract

import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface CrossKeyRecursiveDemandResolverContract : ResolverContract {
    @Test
    fun `does not copy recursive demand between different grounded keys`() {
        var childrenApplications = 0
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {})
                    }

                    type Item {
                      children(depth: Int!): [Item!]! @resolver(result: [{}])
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, _ ->
                    if (field.containingType.typeName == "Item" &&
                        field.fieldName == "children"
                    ) {
                        childrenApplications += 1
                        check(childrenApplications <= 12) {
                            "recursive demand crossed grounded keys"
                        }
                    }
                },
            )
        val world = testWorld.assumptions

        resolveAndValidate(
            world,
            world.objectOf("Query"),
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
            ),
        )

        assertEquals(4, childrenApplications)
    }
}
