package semantics.contract

import model.Value
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface NestedObjectPathUseResolverContract : ResolverContract {
    @Test
    fun `waits for a provider value before expanding a nested variable use`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source holder { consume(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(holder.consume)"
                        )
                      source: Int! @resolver(of: "delay", result: "sum(delay)")
                      holder: Holder! @resolver(result: {})
                      delay: Int! @resolver(result: 7)
                    }

                    type Holder {
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(world, "fragment ignored on Query { result }")

        assertEquals(Value.Int.of(7), resolved.getCell(resultKey).get())
    }
}
