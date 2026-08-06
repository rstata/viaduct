package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ValueVariableTest {
    @Test
    fun `variable identity contains its name defining field and nullable path`() {
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
        val path = listOf(Value.ListIndex.of(0))
        val variable = Value.Variable.of("value", first, path)

        assertEquals(Value.Variable.of("value", first, path), variable)
        assertNotEquals(Value.Variable.of("other", first, path), variable)
        assertNotEquals(Value.Variable.of("value", second, path), variable)
        assertNotEquals(Value.Variable.of("value", first, null), variable)
        assertNotEquals(Value.Variable.of("value", first, emptyList()), variable)
        assertEquals("Variable(name=value, field=Query/first, path=[index=0])", "$variable")
    }
}
