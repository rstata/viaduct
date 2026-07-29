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
                        "count" to Value.Int.of(7),
                        "nothing" to null,
                    )
                },
            )
        val schema = world.schema
        val variablesType = schema.type("Variables") as Schema.InputObjectType
        val unresolved = Value.Variable.of("unresolved")
        val other = Value.Variable.of("other")
        val variableValues = world.assumptions.variableValues
        val value =
            Value.InputObject.of(
                type = variablesType,
                fields =
                    mapOf(
                        "count" to Value.Variable.of("count"),
                        "nothing" to Value.Variable.of("nothing"),
                        "nested" to
                            Value.InputList.of(
                                typeExpr =
                                    (variablesType.fields.getValue("nested").typeExpr as
                                        TypeExpr.List).elementType,
                                values = listOf(
                                    unresolved,
                                    other,
                                    Value.Variable.of("unresolved"),
                                ),
                            ),
                    ),
            )

        val instantiated =
            assertIs<Value.InputObject>(variableValues.instantiateVariables(value))
        assertEquals(
            7,
            assertIs<Value.Int>(
                instantiated.fieldValues["count"],
            ).intValue,
        )
        assertNull(instantiated.fieldValues["nothing"])
        assertEquals(
            listOf(unresolved, other, unresolved),
            assertIs<Value.InputList>(
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
                Value.Variable.of("nothing"),
            ),
        )
        assertEquals(
            Value.Error,
            variableValues.instantiateAllVariables(Value.Error),
        )
    }

    @Test
    fun `binding validation reports every nested variable once`() {
        lateinit var first: Value.Variable
        lateinit var second: Value.Variable
        lateinit var third: Value.Variable

        val exception =
            assertFailsWith<MissingVariablesException> {
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA_SDL,
                    variableValues = { schema ->
                        val inputType = schema.type("Input") as Schema.InputObjectType
                        first = Value.Variable.of("first")
                        second = Value.Variable.of("second")
                        third = Value.Variable.of("third")
                        mapOf(
                            "list" to
                                Value.InputList.of(
                                    typeExpr = TypeExpr.Named.of(Schema.IntType),
                                    values =
                                        listOf(first, second, Value.Variable.of("first")),
                                ),
                            "object" to
                                Value.InputObject.of(
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
