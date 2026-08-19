package semantics.contract

import model.ObjectEngineResult

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface RecursiveListFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `deepens an already launched recursive list with typename demand`() {
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
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                """
                fragment ignored on Query {
                  item {
                    children { common }
                  }
                  result(value: 7)
                }
                """.trimIndent(),
            )

        assertEquals(
            1,
            resolved.getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.objectField("Query", "result"),
                    mapOf("value" to 7),
                ),
            ).get(),
        )
        testWorld.applicationArguments.assertApplicationCount(
            world.schema.objectField("Item", "children"),
            1,
        )
        testWorld.applicationArguments.assertApplicationCount(
            world.schema.objectField("Item", "consume"),
            1,
        )
        testWorld.applicationArguments.assertArguments(
            world.schema.objectField("Item", "consume"),
            mapOf("value" to 7),
        )
    }
}
