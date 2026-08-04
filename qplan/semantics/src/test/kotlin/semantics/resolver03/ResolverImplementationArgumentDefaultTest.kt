package semantics.resolver03

import model.EngineResult
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Concrete implementation argument-default behavior after abstract-field dispatch.
 *
 * Keep tests here focused on specialization and transitive demand for implementation defaults.
 */
class ResolverImplementationArgumentDefaultTest {
    @Test
    fun `applies a concrete implementation argument default after interface dispatch`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = DIRECT_SCHEMA,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "item") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("ConcreteItem")
                            },
                        schema.field("ConcreteItem", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("ConcreteItem"),
                            ) { _, arguments ->
                                arguments.fieldValues.getValue("factor") as Value.Int
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  item {
                    computed
                  }
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val item =
            assertIs<EngineResult.Object>(
                result.fetch(Value.Key.of(world.schema.field("Query", "item"), emptyMap())).value,
            )

        assertEquals(
            Value.Int.of(7),
            item.fetch(
                Value.Key.of(
                    world.schema.field("ConcreteItem", "computed"),
                    mapOf("factor" to 7),
                ),
            ).value,
        )
    }

    @Test
    fun `specializes an interface selection to the concrete defaulted argument key`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = DIRECT_SCHEMA,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "item") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("ConcreteItem")
                            },
                        schema.field("ConcreteItem", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("ConcreteItem"),
                            ) { _, _ ->
                                Value.Int.of(41)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  item {
                    computed
                  }
                }
                """.trimIndent(),
            )
        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val item =
            assertIs<EngineResult.Object>(
                result.fetch(Value.Key.of(world.schema.field("Query", "item"), emptyMap())).value,
            )
        val concreteDefaultKey =
            Value.Key.of(
                world.schema.field("ConcreteItem", "computed"),
                mapOf("factor" to 7),
            )

        assertTrue(context(world) { result.correctResolution(fragment) })
        assertTrue(
            concreteDefaultKey in item.keys,
            "The concrete field default must be part of its fully coerced OER key",
        )
    }

    @Test
    fun `applies a concrete implementation default in transitive resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = TRANSITIVE_SCHEMA,
                fieldResolvers = { schema ->
                    val itemKey =
                        Value.Key.of(schema.field("Holder", "item"), emptyMap())
                    val computedKey =
                        Value.Key.of(
                            schema.field("ConcreteItem", "computed"),
                            mapOf("factor" to 7),
                        )
                    mapOf(
                        schema.field("Query", "holder") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Holder") {
                                    "item" setTo schema.objectOf("ConcreteItem")
                                }
                            },
                        schema.field("Holder", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Holder { item { computed } }",
                                ),
                            ) { input, _ ->
                                val item =
                                    input.fieldValues.getValue(itemKey) as Value.Object
                                item.fieldValues.getValue(computedKey) as Value.Int
                            },
                        schema.field("ConcreteItem", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("ConcreteItem"),
                            ) { _, arguments ->
                                arguments.fieldValues.getValue("factor") as Value.Int
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  holder {
                    result
                  }
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val holder =
            assertIs<EngineResult.Object>(
                result.fetch(Value.Key.of(world.schema.field("Query", "holder"), emptyMap())).value,
            )

        assertEquals(
            Value.Int.of(7),
            holder.fetch(
                Value.Key.of(world.schema.field("Holder", "result"), emptyMap()),
            ).value,
        )
    }

    private companion object {
        val DIRECT_SCHEMA =
            """
            interface Item {
              computed: Int!
            }

            type ConcreteItem implements Item {
              computed(factor: Int = 7): Int!
            }

            type Query {
              item: Item!
            }
            """.trimIndent()

        val TRANSITIVE_SCHEMA =
            """
            interface Item {
              computed: Int!
            }

            type ConcreteItem implements Item {
              computed(factor: Int = 7): Int!
            }

            type Holder {
              item: Item!
              result: Int!
            }

            type Query {
              holder: Holder!
            }
            """.trimIndent()
    }
}
