package model

import graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import model.testing.TestWorld

class SelectionDocumentTest {
    @Test
    fun `accepts nested named fragments at the model fixture boundary`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  value: Int!
                }
                """.trimIndent(),
            )

        val fragment =
            world.schema.fragmentFromDocument(
                Parser.parse(
                    """
                    fragment Value on Query {
                      renamed: value
                    }
                    fragment Outer on Query {
                      ...Value
                    }
                    fragment Main on Query {
                      ...Outer
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals("value", fragment.subselections.single().key.field.name)
        assertEquals("renamed", fragment.materializeSelections.single().responseKey)
    }
}
