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
                Value.String.of("one"),
                Value.Boolean.of(true),
            )
        val second = EngineResult.Cell.of(null, Value.Boolean.of(true))
        val result =
            EngineResult.List.of(
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

        val result = EngineResult.List.of(elementType, emptyList())

        assertEquals(elementType, result.typeExpr)
        assertEquals(0, result.size)
    }

    @Test
    fun `object result factory rejects values that violate field typing`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key =
            Value.Key.of(
                schema.field("Query", "required"),
                emptyMap(),
            )
        val cell = EngineResult.Cell.of(null, Value.Boolean.of(true))

        assertFailsWith<IllegalArgumentException> {
            EngineResult.Object.of(schema.query, mapOf(key to cell))
        }
    }

    @Test
    fun `list result factory rejects incompatible element values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr
        val cell =
            EngineResult.Cell.of(
                Value.Int.of(1),
                Value.Boolean.of(true),
            )

        assertFailsWith<IllegalArgumentException> {
            EngineResult.List.of(elementType, listOf(cell))
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
