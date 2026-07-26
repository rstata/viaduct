package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineResultTest {
    @Test
    fun `list engine result rejects null at a non-null element type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val type =
            Schema.TypeExpr.List(
                elementType =
                    Schema.TypeExpr.Named(
                        Schema.StringType,
                        isNullable = false,
                    ),
                isNullable = false,
            )

        assertFailsWith<IllegalArgumentException> {
            schema.listEngineResult(type, listOf(null))
        }
    }

    @Test
    fun `list engine result permits null at a nullable element type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val type =
            Schema.TypeExpr.List(
                elementType =
                    Schema.TypeExpr.Named(
                        Schema.StringType,
                        isNullable = true,
                    ),
                isNullable = false,
            )

        val result =
            schema.listEngineResult(
                type = type,
                values = listOf(schema.stringValue("one"), null),
            )

        assertEquals(type, result.type)
        assertEquals(Schema.StringType, result.baseType)
        assertEquals<List<EngineResult?>>(
            listOf(schema.stringValue("one"), null),
            result,
        )
    }

    private companion object {
        const val SCHEMA_SDL =
            """
            type Query {
              value: String
            }
            """
    }
}
