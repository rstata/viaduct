package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineResultTest {
    @Test
    fun `list engine result retains its elements`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val result =
            schema.listEngineResult(
                listOf(schema.stringValue("one"), null),
            )

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
