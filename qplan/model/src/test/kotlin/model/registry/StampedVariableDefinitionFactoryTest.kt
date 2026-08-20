package model.registry

import model.requireObjectField
import model.requireField
import model.Arguments
import model.ObjectEngineResult
import model.SelectionOccurrenceId
import model.Stamp
import model.requireArg
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class StampedVariableDefinitionFactoryTest {
    private val world =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  result(seed: Int): Int
                  source: Int
                }
                """.trimIndent(),
        )
    private val result = world.schema.requireObjectField("Query", "result")
    private val source = world.schema.requireObjectField("Query", "source")
    private val template = Arguments.Variable.of(result, "seed")
    private val path =
        listOf(ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 1)))

    @Test
    fun `stamped object path definitions have structural equality`() {
        val variable = template.stamp(path)
        val providerPath =
            listOf(ObjectEngineResult.GroundKey.of(source, emptyMap()))

        assertEquals(
            StampedObjectPathDefinition.of(variable, providerPath),
            StampedObjectPathDefinition.of(
                variable,
                listOf(ObjectEngineResult.GroundKey.of(source, emptyMap())),
            ),
        )
        assertNotEquals(
            StampedObjectPathDefinition.of(variable, providerPath),
            StampedObjectPathDefinition.of(template.stamp(emptyList()), providerPath),
        )
    }

    @Test
    fun `object path definition requires a stamped variable`() {
        assertFailsWith<IllegalArgumentException> {
            StampedObjectPathDefinition.of(
                variable = template,
                path = listOf(ObjectEngineResult.GroundKey.of(source, emptyMap())),
            )
        }
    }

    @Test
    fun `selection stamped definitions have structural equality`() {
        val occurrence =
            Stamp.Occurrence.of(
                resolverPath = path,
                occurrenceLineage =
                    listOf(
                        SelectionOccurrenceId(
                            ObjectEngineResult.Key.of(source, emptyMap()),
                        ),
                    ),
            )
        val variable = template.stamp(occurrence)
        val definition =
            VariableDefinition.FromArgument.of(
                result.requireArg("seed"),
            )

        assertEquals(
            SelectionStampedVariableDefinition.of(variable, definition),
            SelectionStampedVariableDefinition.of(
                variable,
                VariableDefinition.FromArgument.of(
                    result.requireArg("seed"),
                ),
            ),
        )
        assertNotEquals(
            SelectionStampedVariableDefinition.of(variable, definition),
            SelectionStampedVariableDefinition.of(
                variable,
                VariableDefinition.FromObjectField.of(
                    listOf(ObjectEngineResult.Key.of(source, emptyMap())),
                ),
            ),
        )
    }

    @Test
    fun `selection stamped definition requires selection lineage`() {
        assertFailsWith<IllegalArgumentException> {
            SelectionStampedVariableDefinition.of(
                variable = template.stamp(path),
                definition =
                    VariableDefinition.FromArgument.of(
                        result.requireArg("seed"),
                    ),
            )
        }
    }
}
