package semantics.contract

import model.EngineResult
import model.ObjectEngineResult
import model.Value
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface PassiveObjectPathProviderResolverContract : ResolverContract {
    @Test
    fun `installs a resolver promise below a passive provider field`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {provider: {}})
                    }

                    type Item {
                      result: Int!
                        @resolver(
                          of: "provider { value } consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["provider", "value"]}]
                          result: "sum(consume)"
                        )
                      provider: Provider!
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Provider {
                      value: Int! @resolver(result: 11)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(world, "fragment ignored on Query { item { result } }")
        val item =
            resolved.getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.objectField("Query", "item"),
                    emptyMap(),
                ),
            ).get() as ObjectEngineResult

        assertEquals(
            11,
            item.getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.objectField("Item", "result"),
                    emptyMap(),
                ),
            ).get(),
        )
    }
}
