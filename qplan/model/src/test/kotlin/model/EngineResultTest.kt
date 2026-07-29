package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineResultTest {
    @Test
    fun `list engine result retains its elements`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr
        val first =
            EngineResult.Cell.of(
                Schema.StringValue.of("one"),
                Schema.BooleanValue.of(true),
            )
        val second = EngineResult.Cell.of(null, Schema.BooleanValue.of(true))
        val result =
            ListEngineResult.of(
                typeExpr = elementType,
                cells = listOf(first, second),
            )

        assertEquals<List<EngineResult.Cell>>(
            listOf(first, second),
            result,
        )
        assertEquals(elementType, result.typeExpr)
    }

    @Test
    fun `typed empty list retains its intended element type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr

        val result = ListEngineResult.of(elementType, emptyList())

        assertEquals(elementType, result.typeExpr)
        assertEquals(0, result.size)
    }

    @Test
    fun `object result factory rejects values that violate field typing`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key =
            Schema.ObjectKey.of(
                schema.field("Query", "required"),
                emptyMap(),
            )
        val cell = EngineResult.Cell.of(null, Schema.BooleanValue.of(true))

        assertFailsWith<IllegalArgumentException> {
            ObjectEngineResult.of(schema.query, mapOf(key to cell))
        }
    }

    @Test
    fun `list result factory rejects incompatible element values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr
        val cell =
            EngineResult.Cell.of(
                Schema.IntValue.of(1),
                Schema.BooleanValue.of(true),
            )

        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(elementType, listOf(cell))
        }
    }

    private companion object {
        const val SCHEMA_SDL =
            """
            type Query {
              value: String
              required: String!
            }
            """
    }
}
