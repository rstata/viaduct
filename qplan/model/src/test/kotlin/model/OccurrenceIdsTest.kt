package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import model.testing.TestWorld

class OccurrenceIdsTest {
    @Test
    fun `resolver occurrence identity is structural and opaque`() {
        val firstPath = listOf(ListEngineResult.Index.of(1))
        val secondPath = listOf(ListEngineResult.Index.of(2))

        val first = ResolverOccurrenceId.at(firstPath)
        val equalFirst = ResolverOccurrenceId.at(firstPath.toList())
        val second = ResolverOccurrenceId.at(secondPath)

        assertEquals(first, equalFirst)
        assertEquals(first.hashCode(), equalFirst.hashCode())
        assertNotEquals(first, second)
        assertEquals(
            "ResolverOccurrenceId(path=[index=1])",
            first.toString(),
        )
    }

    @Test
    fun `variable instance identity contains occurrence declaration field and name`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  first: Int
                  second: Int
                }
                """.trimIndent(),
            ).schema
        val firstField = schema.requireObjectField("Query", "first")
        val secondField = schema.requireObjectField("Query", "second")
        val firstOccurrence =
            ResolverOccurrenceId.at(listOf(ListEngineResult.Index.of(1)))
        val equalFirstOccurrence =
            ResolverOccurrenceId.at(listOf(ListEngineResult.Index.of(1)))
        val secondOccurrence =
            ResolverOccurrenceId.at(listOf(ListEngineResult.Index.of(2)))

        val first =
            VariableInstanceId.of(
                resolverOccurrenceId = firstOccurrence,
                resolverField = firstField,
                variableName = "value",
            )

        assertEquals(
            first,
            VariableInstanceId.of(
                resolverOccurrenceId = equalFirstOccurrence,
                resolverField = firstField,
                variableName = "value",
            ),
        )
        assertNotEquals(
            first,
            VariableInstanceId.of(secondOccurrence, firstField, "value"),
        )
        assertNotEquals(
            first,
            VariableInstanceId.of(firstOccurrence, secondField, "value"),
        )
        assertNotEquals(
            first,
            VariableInstanceId.of(firstOccurrence, firstField, "other"),
        )
        assertEquals(
            "VariableInstanceId(" +
                "resolver=ResolverOccurrenceId(path=[index=1]), " +
                "variable=Query/first:value)",
            first.toString(),
        )
    }
}
