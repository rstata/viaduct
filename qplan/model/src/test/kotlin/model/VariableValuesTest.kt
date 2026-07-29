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
                        "count" to Schema.IntValue.of(7),
                        "nothing" to null,
                    )
                },
            )
        val schema = world.schema
        val variablesType = schema.type("Variables") as Schema.InputObjectType
        val unresolved = Schema.VariableValue.of("unresolved")
        val other = Schema.VariableValue.of("other")
        val variableValues = world.assumptions.variableValues
        val value =
            Schema.InputObjectValue.of(
                type = variablesType,
                fields =
                    mapOf(
                        "count" to Schema.VariableValue.of("count"),
                        "nothing" to Schema.VariableValue.of("nothing"),
                        "nested" to
                            Schema.InputListValue.of(
                                typeExpr =
                                    (variablesType.fields.getValue("nested").typeExpr as
                                        Schema.TypeExpr.List).elementType,
                                values = listOf(
                                    unresolved,
                                    other,
                                    Schema.VariableValue.of("unresolved"),
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
            ).values,
        )

        val exception =
            assertFailsWith<MissingVariablesException> {
                variableValues.instantiateAllVariables(value)
            }
        assertEquals(listOf(unresolved, other), exception.variableValues)

        assertNull(
            variableValues.instantiateAllVariables(
                Schema.VariableValue.of("nothing"),
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
                        first = Schema.VariableValue.of("first")
                        second = Schema.VariableValue.of("second")
                        third = Schema.VariableValue.of("third")
                        mapOf(
                            "list" to
                                Schema.InputListValue.of(
                                    typeExpr = Schema.TypeExpr.Named.of(Schema.IntType),
                                    values =
                                        listOf(first, second, Schema.VariableValue.of("first")),
                                ),
                            "object" to
                                Schema.InputObjectValue.of(
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
