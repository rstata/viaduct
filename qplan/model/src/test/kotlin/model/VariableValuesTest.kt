package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class VariableValuesTest {
    private val schema =
        GJSchema.fromSDL(
            """
            input Variables {
              count: Int
              nothing: Int
              nested: [Int]
            }

            input Input {
              value: Int
            }

            type Query {
              value: Int
            }
            """.trimIndent(),
        )
    private val variablesType = schema.type("Variables") as Schema.InputObjectType
    private val inputType = schema.type("Input") as Schema.InputObjectType

    @Test
    fun `instantiates bound variables recursively and preserves unbound variables`() {
        val unresolved = schema.variableValue("unresolved")
        val other = schema.variableValue("other")
        val variableValues =
            Assumptions
                .of(
                    schema,
                    mapOf(
                        "count" to schema.intValue(7),
                        "nothing" to null,
                    ),
                ).variableValues
        val value =
            schema.inputObjectValue(
                type = variablesType,
                fields =
                    mapOf(
                        "count" to schema.variableValue("count"),
                        "nothing" to schema.variableValue("nothing"),
                        "nested" to
                            schema.inputListValue(
                                listOf(
                                    unresolved,
                                    other,
                                    schema.variableValue("unresolved"),
                                ),
                            ),
                    ),
            )

        val instantiated =
            assertIs<Schema.InputObjectValue>(variableValues.instantiateVariables(value))
        assertEquals(
            7,
            assertIs<Schema.IntValue>(
                instantiated.fieldValues["count"],
            ).intValue,
        )
        assertNull(instantiated.fieldValues["nothing"])
        assertEquals(
            listOf(unresolved, other, unresolved),
            assertIs<Schema.InputListValue>(
                instantiated.fieldValues["nested"],
            ).inputListValues,
        )

        val exception =
            assertFailsWith<MissingVariablesException> {
                variableValues.instantiateAllVariables(value)
            }
        assertEquals(listOf(unresolved, other), exception.variableValues)

        assertNull(
            variableValues.instantiateAllVariables(
                schema.variableValue("nothing"),
            ),
        )
        assertSame(
            Schema.ErrorValue,
            variableValues.instantiateAllVariables(Schema.ErrorValue),
        )
    }

    @Test
    fun `binding validation reports every nested variable once`() {
        val first = schema.variableValue("first")
        val second = schema.variableValue("second")
        val third = schema.variableValue("third")

        val exception =
            assertFailsWith<MissingVariablesException> {
                Assumptions.of(
                    schema,
                    mapOf(
                        "list" to
                            schema.inputListValue(
                                listOf(first, second, schema.variableValue("first")),
                            ),
                        "object" to
                            schema.inputObjectValue(
                                type = inputType,
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
