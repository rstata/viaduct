package semantics.contract

import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface NestedFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `retains passive demand below an ungrounded nested resolver key`() {
        val applications = linkedMapOf<String, Int>()
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
                      holder { __typename }
                      result(value: 7)
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            Value.Int.of(7),
            resolved
                .getCell(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "result"),
                        mapOf("value" to 7),
                    ),
                ).get(),
        )
        assertEquals(
            mapOf("holder" to 1, "result" to 1, "consume" to 1),
            applications,
        )
    }
}
