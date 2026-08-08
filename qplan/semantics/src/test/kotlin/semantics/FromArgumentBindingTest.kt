package semantics

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fromArgument
import semantics.resolver01.resolve as resolve01
import semantics.resolver02.resolve as resolve02
import semantics.resolver03.resolve as resolve03
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FromArgumentBindingTest {
    @Test
    fun `resolver01 binds argument variables by exact resolver occurrence`() {
        assertArgumentBindings { world, root, selections ->
            context(world) {
                root.resolve01(selections)
            }
        }
    }

    @Test
    fun `resolver02 binds argument variables by exact resolver occurrence`() {
        assertArgumentBindings { world, root, selections ->
            context(world) {
                root.resolve02(selections)
            }
        }
    }

    @Test
    fun `resolver03 binds argument variables by exact resolver occurrence`() {
        assertArgumentBindings { world, root, selections ->
            context(world) {
                root.resolve03(selections)
            }
        }
    }

    @Test
    fun `binding one resolver occurrence twice is rejected`() {
        val testWorld = argumentBindingWorld()
        val world = testWorld.assumptions
        val field = world.schema.objectField("Query", "echo")
        val key = Value.GroundKey.of(field, mapOf("value" to 1))

        context(world) {
            listOf(key).bindFromArguments(emptyList())
            assertFailsWith<IllegalStateException> {
                listOf(key).bindFromArguments(emptyList())
            }
        }
    }

    private fun assertArgumentBindings(
        resolve:
            (
                Assumptions,
                Value.Object,
                SelectionForest,
            ) -> EngineResult.Object,
    ) {
        val testWorld = argumentBindingWorld()
        val world = testWorld.assumptions
        val field = world.schema.objectField("Query", "echo")
        val variable = Value.Variable.of(field, "value")
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  one: echo(value: 1)
                  two: echo(value: 2)
                }
                """.trimIndent(),
            )

        resolve(
            world,
            world.objectOf("Query"),
            fragment.subselections,
        )

        val one = Value.GroundKey.of(field, mapOf("value" to 1))
        val two = Value.GroundKey.of(field, mapOf("value" to 2))
        assertEquals(
            Value.Int.of(1),
            world.binding(variable.stamp(listOf(one))),
        )
        assertEquals(
            Value.Int.of(2),
            world.binding(variable.stamp(listOf(two))),
        )
        assertFalse(world.isBound(variable.stamp(emptyList())))
    }

    private fun argumentBindingWorld(): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  echo(value: Int): Int
                }
                """.trimIndent(),
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
}
