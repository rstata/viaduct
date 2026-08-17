package semantics.resolver26

import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FromObjectFieldSingletonCoercionRegressionTest {
    @Test
    fun `singleton coerces a scalar object-field value through two input-list layers`() {
        var consumedArgument: Value.Input? = null
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
                applicationObserver = { field, _, arguments, _ ->
                    if (
                        field.containingType.typeName == "Query" &&
                        field.fieldName == "consume"
                    ) {
                        consumedArgument = arguments.fieldValues.getValue("value")
                    }
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "result"),
                emptyMap(),
            )
        val fragment =
            world.fragmentFrom("fragment QueryResult on Query { result }")

        val resolved =
            context(world) {
                resolve(fragment.subselections)
            }
        val outer = assertIs<Value.InputList>(consumedArgument)
        val inner = assertIs<Value.InputList>(outer.values.single())

        assertEquals(listOf(Value.Int.of(7)), inner.values)
        assertEquals(Value.Int.of(14), resolved.getCell(resultKey).getValue().get())
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }
}
