package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class VariableValuesTest {
    @Test
    fun `instantiates bound variables recursively and preserves unbound variables`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                variableValues = { schema ->
                    mapOf(
                        "count" to schema.intValue(7),
                        "nothing" to null,
                    )
                },
            )
        val schema = world.schema
        val variablesType = schema.type("Variables") as Schema.InputObjectType
        val unresolved = schema.variableValue("unresolved")
        val other = schema.variableValue("other")
        val variableValues = world.assumptions.variableValues
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
        assertEquals(
            Schema.ErrorValue,
            variableValues.instantiateAllVariables(Schema.ErrorValue),
        )
    }

    @Test
    fun `binding validation reports every nested variable once`() {
        lateinit var first: Schema.VariableValue
        lateinit var second: Schema.VariableValue
        lateinit var third: Schema.VariableValue

        val exception =
            assertFailsWith<MissingVariablesException> {
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA_SDL,
                    variableValues = { schema ->
                        val inputType = schema.type("Input") as Schema.InputObjectType
                        first = schema.variableValue("first")
                        second = schema.variableValue("second")
                        third = schema.variableValue("third")
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
                        )
                    },
                )
            }

        assertEquals(
            setOf(first, second, third),
            exception.variableValues.toSet(),
        )
        assertEquals(3, exception.variableValues.size)
    }

    private companion object {
        val SCHEMA_SDL =
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
            """.trimIndent()
    }
}
