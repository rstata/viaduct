package semantics.resolver26

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FromObjectFieldSingletonCoercionRegressionTest {
    @Test
    fun `scalar object-field provider is incompatible with a nested input-list location`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: Int! @resolver(result: 7)
                      consume(value: [[Int!]!]!): Int!
                        @resolver(result: 14)
                    }
                    """.trimIndent(),
            )
        }
    }
}
