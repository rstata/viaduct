package model.registry

import model.requireObjectField
import model.Arguments
import model.ObjectEngineResult
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

}
