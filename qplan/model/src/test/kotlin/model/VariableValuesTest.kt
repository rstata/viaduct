package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class VariableValuesTest {
    @Test
    fun `instantiates bound variables recursively and preserves unbound variables`() {
        val unresolved = GraphQLVariableValue.of("unresolved")
        val other = GraphQLVariableValue.of("other")
        val variableValues =
            VariableBindings.from(
                mapOf(
                    "count" to GraphQLIntValue.of(7),
                    "nothing" to null,
                ),
            )
        val value =
            GraphQLInputObjectValue.of(
                typeName = "Variables",
                fields =
                    mapOf(
                        "count" to GraphQLVariableValue.of("count"),
                        "nothing" to GraphQLVariableValue.of("nothing"),
                        "nested" to
                            GraphQLInputList.of(
                                listOf(
                                    unresolved,
                                    other,
                                    GraphQLVariableValue.of("unresolved"),
                                ),
                            ),
                    ),
            )

        val instantiated =
            assertIs<GraphQLInputObjectValue>(variableValues.instantiateVariables(value))
        assertEquals(
            7,
            assertIs<GraphQLIntValue>(
                instantiated.inputObjectFields["count"],
            ).intValue,
        )
        assertNull(instantiated.inputObjectFields["nothing"])
        assertEquals(
            listOf(unresolved, other, unresolved),
            assertIs<GraphQLInputList>(
                instantiated.inputObjectFields["nested"],
            ).inputListValues,
        )

        val exception =
            assertFailsWith<MissingVariableException> {
                variableValues.instantiateAllVariables(value)
            }
        assertEquals(listOf(unresolved, other), exception.variableValues)

        assertNull(
            variableValues.instantiateAllVariables(
                GraphQLVariableValue.of("nothing"),
            ),
        )
        assertSame(
            GraphQLErrorValue,
            variableValues.instantiateAllVariables(GraphQLErrorValue),
        )
    }

    @Test
    fun `binding validation reports every nested variable once`() {
        val first = GraphQLVariableValue.of("first")
        val second = GraphQLVariableValue.of("second")
        val third = GraphQLVariableValue.of("third")

        val exception =
            assertFailsWith<MissingVariableException> {
                VariableBindings.from(
                    mapOf(
                        "list" to
                            GraphQLInputList.of(
                                listOf(first, second, GraphQLVariableValue.of("first")),
                            ),
                        "object" to
                            GraphQLInputObjectValue.of(
                                typeName = "Input",
                                fields = mapOf("value" to third),
                            ),
                    ),
                )
            }

        assertEquals(
            setOf(first, second, third),
            exception.variableValues.toSet(),
        )
        assertEquals(3, exception.variableValues.size)
    }
}
