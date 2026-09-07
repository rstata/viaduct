package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InclusionConditionTest {
    private val schema =
        TestWorld.fromSDL("type Query { value: Boolean! }").schema
    private val field = schema.requireObjectField("Query", "value")
    private val x = Arguments.Variable.of(field, "x")
    private val y = Arguments.Variable.of(field, "y")

    @Test
    fun `conjunction combines requirements and rejects conflicts`() {
        val condition =
            InclusionCondition.requires(mapOf(x to true))
                .and(InclusionCondition.requires(mapOf(y to false)))

        assertTrue(condition.include(mapOf(x to true, y to false)))
        assertFalse(condition.include(mapOf(x to true, y to true)))
        assertSame(
            InclusionCondition.Never,
            condition.and(InclusionCondition.requires(mapOf(x to false))),
        )
    }

    @Test
    fun `disjunction includes when any alternative permits inclusion`() {
        val condition =
            InclusionCondition.anyOf(
                listOf(
                    InclusionCondition.requires(mapOf(x to true)),
                    InclusionCondition.requires(mapOf(y to false)),
                ),
            )

        assertTrue(condition.include(mapOf(x to true, y to true)))
        assertTrue(condition.include(mapOf(x to false, y to false)))
        assertFalse(condition.include(mapOf(x to false, y to true)))
    }

    @Test
    fun `mapping variables preserves conflicts introduced by the mapping`() {
        val condition =
            InclusionCondition.requires(mapOf(x to true, y to false))
                .mapVariables { x }

        assertSame(InclusionCondition.Never, condition)
    }
}
