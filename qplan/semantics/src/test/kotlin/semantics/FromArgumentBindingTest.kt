package semantics

import model.ObjectEngineResult

import kotlinx.coroutines.runBlocking
import model.Value
import model.emptyFragmentOf
import model.testing.TestWorld
import model.testing.fromArgument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FromArgumentBindingTest {
    @Test
    fun `binding one resolver occurrence twice is rejected`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { echo(value: Int): Int }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "echo") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.Int.of(0)
                            },
                    )
                },
                variableProviders = { schema ->
                    val field = schema.objectField("Query", "echo")
                    mapOf(
                        Value.Variable.of(field, "value") to
                            schema.fromArgument(field, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val field = world.schema.objectField("Query", "echo")
        val key = ObjectEngineResult.GroundKey.of(field, mapOf("value" to 1))

        context(world) {
            listOf(key).bindFromArguments(emptyList())
            val variable =
                Value.Variable.of(field, "value").stamp(listOf(key))
            assertEquals(Value.Int.of(1), world.getBinding(variable))
            assertEquals(
                Value.Int.of(1),
                runBlocking { world.fetchBinding(variable) },
            )
            assertFailsWith<IllegalStateException> {
                listOf(key).bindFromArguments(emptyList())
            }
        }
    }
}
