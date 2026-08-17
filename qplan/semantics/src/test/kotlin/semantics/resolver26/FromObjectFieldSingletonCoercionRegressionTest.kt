package semantics.resolver26

import model.ObjectEngineResult

import model.IntEngineResult
import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.contract.assertArguments
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FromObjectFieldSingletonCoercionRegressionTest {
    @Test
    fun `singleton coerces a scalar object-field value through two input-list layers`() {
        val testWorld =
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
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "result"),
                emptyMap(),
            )
        val fragment =
            world.fragmentFrom("fragment QueryResult on Query { result }")

        val resolved =
            context(world) {
                resolve(fragment.subselections)
            }

        testWorld.applicationArguments.assertArguments(
            world.schema.objectField("Query", "consume"),
            mapOf("value" to listOf(listOf(7))),
        )
        assertEquals(IntEngineResult.of(14), resolved.getCell(resultKey).getValue().get())
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }
}
