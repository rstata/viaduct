package semantics.contract

import model.IntEngineResult
import model.Value
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface NestedFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `retains passive demand below an ungrounded nested resolver key`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      holder: Item! @resolver(result: {passive: 7})
                      result(value: Int!): Int!
                        @resolver(
                          of: "holder { consume(value: ${'$'}value) }"
                          result: "sum(holder.consume)"
                        )
                    }

                    type Item {
                      consume(value: Int!): Int!
                        @resolver(of: "passive", result: "sum(passive)")
                      passive: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                """
                fragment ignored on Query {
                  holder { __typename }
                  result(value: 7)
                }
                """.trimIndent(),
            )

        assertEquals(
            IntEngineResult.of(7),
            resolved
                .getCell(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "result"),
                        mapOf("value" to 7),
                    ),
                ).get(),
        )
        testWorld.applicationArguments.assertApplications(
            mapOf(
                world.schema.objectField("Query", "holder") to listOf(emptyMap()),
                world.schema.objectField("Query", "result") to
                    listOf(mapOf("value" to 7)),
                world.schema.objectField("Item", "consume") to
                    listOf(mapOf("value" to 7)),
            ),
        )
    }
}
