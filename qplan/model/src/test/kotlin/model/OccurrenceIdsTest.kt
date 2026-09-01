package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import model.testing.TestWorld

class OccurrenceIdsTest {
    @Test
    fun `resolver occurrence identity includes root identity and path`() {
        val queryType =
            TestWorld.fromSDL("type Query { value: Int }").schema.requireQueryTypeDef()
        val firstRoot = ObjectEngineResult.of(queryType, values = emptyMap())
        val secondRoot = ObjectEngineResult.of(queryType, values = emptyMap())
        val firstPath = listOf(ListEngineResult.Index.of(1))
        val secondPath = listOf(ListEngineResult.Index.of(2))

        val first = ResolverOccurrenceId.at(firstRoot, firstPath)
        val equalFirst = ResolverOccurrenceId.at(firstRoot, firstPath.toList())
        val second = ResolverOccurrenceId.at(firstRoot, secondPath)

        assertEquals(first, equalFirst)
        assertEquals(first.hashCode(), equalFirst.hashCode())
        assertNotEquals(first, second)
        assertNotEquals(first, ResolverOccurrenceId.at(secondRoot, firstPath))
        assertEquals(
            "ResolverOccurrenceId(root=${System.identityHashCode(firstRoot)}, path=[index=1])",
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
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), values = emptyMap())
        val firstOccurrence =
            ResolverOccurrenceId.at(root, listOf(ListEngineResult.Index.of(1)))
        val equalFirstOccurrence =
            ResolverOccurrenceId.at(root, listOf(ListEngineResult.Index.of(1)))
        val secondOccurrence =
            ResolverOccurrenceId.at(root, listOf(ListEngineResult.Index.of(2)))

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
                "resolver=ResolverOccurrenceId(" +
                "root=${System.identityHashCode(root)}, path=[index=1]), " +
                "variable=Query/first:value)",
            first.toString(),
        )
    }
}
