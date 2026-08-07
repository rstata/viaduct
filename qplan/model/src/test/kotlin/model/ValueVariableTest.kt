package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
