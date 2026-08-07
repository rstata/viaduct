package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ValueVariableTest {
    @Test
    fun `template identity contains its name and defining field`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  first: Int
                  second: Int
                }
                """.trimIndent(),
            ).schema
        val first = schema.objectField("Query", "first")
        val second = schema.objectField("Query", "second")
        val template = Value.Variable.of(first, "value")

        assertEquals(Value.Variable.of(first, "value"), template)
        assertNotEquals(Value.Variable.of(first, "other"), template)
        assertNotEquals(Value.Variable.of(second, "value"), template)
        assertEquals("Variable.Template(name=value, field=Query/first)", "$template")
    }

    @Test
    fun `stamp identity contains its template and opaque occurrence path`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  first: Int
                  second: Int
                }
                """.trimIndent(),
            ).schema
        val first = schema.objectField("Query", "first")
        val second = schema.objectField("Query", "second")
        val template = Value.Variable.of(first, "value")
        val path = listOf(Value.ListIndex.of(0))
        val stamp = template.stamp(path)

        assertEquals(template.stamp(path), stamp)
        assertNotEquals(Value.Variable.of(first, "other").stamp(path), stamp)
        assertNotEquals(Value.Variable.of(second, "value").stamp(path), stamp)
        assertNotEquals<Value.Variable>(template, stamp)
        assertNotEquals(template.stamp(emptyList()), stamp)
        assertEquals(
            "Variable.Stamped(name=value, field=Query/first, path=[index=0])",
            "$stamp",
        )
    }

    @Test
    fun `input-like values and lists recursively stamp variable templates`() {
        val schema =
            TestWorld.fromSDL(
                """
                input Filter {
                  direct: Int
                  nested: [Int]
                }

                type Query {
                  first: Int
                  consume(filter: Filter, values: [Int]): Int
                }
                """.trimIndent(),
            ).schema
        val first = schema.objectField("Query", "first")
        val consume = schema.objectField("Query", "consume")
        val template = Value.Variable.of(first, "value")
        val arguments =
            Value.Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to template,
                            "nested" to listOf(template, 1),
                        ),
                    "values" to listOf(template),
                ),
            )
        val path = listOf(Value.ListIndex.of(2))

        val stamped = arguments.stamp(path)
        val filter =
            assertIs<Value.InputObject>(stamped.fieldValues.getValue("filter"))
        val nested =
            assertIs<Value.InputList>(filter.fieldValues.getValue("nested"))
        val values =
            assertIs<Value.InputList>(stamped.fieldValues.getValue("values"))

        assertEquals(template.stamp(path), filter.fieldValues.getValue("direct"))
        assertEquals(template.stamp(path), nested.values[0])
        assertEquals(Value.Int.of(1), nested.values[1])
        assertEquals(template.stamp(path), values.values.single())
        assertEquals(
            template.stamp(path),
            assertIs<Value.InputList>(
                arguments.fieldValues.getValue("values"),
            ).stamp(path).values.single(),
        )
        val originalFilter =
            assertIs<Value.InputObject>(arguments.fieldValues.getValue("filter"))
        assertEquals(template, originalFilter.fieldValues.getValue("direct"))
    }
}
