package semantics.contract

import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface RecursiveListFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `deepens an already launched recursive list with typename demand`() {
        val applications = linkedMapOf<String, Int>()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {common: 1})
                      result(value: Int!): Int!
                        @resolver(
                          of: "item { consume(value: ${'$'}value) }"
                          result: "sum(item.consume)"
                        )
                    }

                    type Item {
                      common: Int!
                      children: [Item!]!
                        @resolver(
                          of: "common"
                          result: [{common: 2}, {common: 3}]
                        )
                      consume(value: Int!): Int!
                        @resolver(
                          of: "children { __typename }"
                          result: 1
                        )
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, _ ->
                    applications.merge(field.fieldName, 1, Int::plus)
                },
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom(
                    """
                    fragment ignored on Query {
                      item {
                        children { common }
                      }
                      result(value: 7)
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            Value.Int.of(1),
            resolved.getCell(
                Value.GroundKey.of(
                    world.schema.objectField("Query", "result"),
                    mapOf("value" to 7),
                ),
            ).get(),
        )
        assertEquals(1, applications.getValue("children"))
        assertEquals(1, applications.getValue("consume"))
    }
}
