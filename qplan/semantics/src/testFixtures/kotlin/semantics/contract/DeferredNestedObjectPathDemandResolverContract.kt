package semantics.contract

import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface DeferredNestedObjectPathDemandResolverContract : ResolverContract {
    @Test
    fun `retains potential demand beyond a deferred nested resolver`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {})
                      source: Int! @resolver(result: 7)
                      driver: Int!
                        @resolver(
                          of: "source item { result(value: ${'$'}sourceValue) }"
                          pathVars: [{name: "sourceValue", path: ["source"]}]
                          result: "sum(item.result)"
                        )
                    }

                    type Item {
                      step: Item! @resolver(result: {passive: 7})
                      passive: Int!
                      result(value: Int!): Int!
                        @resolver(
                          of: "step { passive }"
                          result: "sum(step.passive)"
                        )
                    }
                    """.trimIndent(),
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
                        step { __typename }
                      }
                      driver
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            Value.Int.of(7),
            resolved
                .getCell(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "driver"),
                        emptyMap(),
                    ),
                ).get(),
        )
    }
}
