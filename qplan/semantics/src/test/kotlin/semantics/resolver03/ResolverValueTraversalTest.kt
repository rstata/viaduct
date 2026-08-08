package semantics.resolver03

import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Structural traversal of nullable and error-valued resolver outputs.
 *
 * Keep deterministic list-position and descendant-skipping behavior for output values here.
 */
class ResolverValueTraversalTest {
    @Test
    fun `heterogeneous list elements specialize their shared continuation independently`() {
        val applications = mutableListOf<String>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Item {
                      computed: Int!
                    }

                    type A implements Item {
                      computed(factor: Int = 2): Int!
                    }

                    type B implements Item {
                      computed: Int!
                    }

                    type Query {
                      items: [Item!]!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val items = schema.field("Query", "items")
                    val elementType =
                        (items.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    mapOf(
                        items to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.OutputList.of(
                                    elementType,
                                    listOf(
                                        schema.objectOf("A"),
                                        schema.objectOf("B"),
                                    ),
                                )
                            },
                        schema.field("A", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("A"),
                            ) { _, arguments ->
                                applications += "A"
                                arguments.fieldValues.getValue("factor") as Value.Int
                            },
                        schema.field("B", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("B"),
                            ) { _, _ ->
                                applications += "B"
                                Value.Int.of(3)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { items { computed } }",
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        val items =
            assertIs<EngineResult.List>(
                result.fetch(
                    Value.GroundKey.of(world.schema.objectField("Query", "items"), emptyMap()),
                ).value,
            )
        val a = assertIs<EngineResult.Object>(items[0].value)
        val b = assertIs<EngineResult.Object>(items[1].value)
        assertEquals(
            Value.Int.of(2),
            a.fetch(
                Value.GroundKey.of(
                    world.schema.objectField("A", "computed"),
                    mapOf("factor" to 2),
                ),
            ).value,
        )
        assertEquals(
            Value.Int.of(3),
            b.fetch(Value.GroundKey.of(world.schema.objectField("B", "computed"), emptyMap())).value,
        )
        assertEquals(listOf("A", "B"), applications)
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `list null and error elements preserve position and skip descendant resolvers`() {
        var itemsApplications = 0
        var computedApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      seed: Int!
                      computed: Int!
                    }

                    type Query {
                      items: [Item]
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val items = schema.field("Query", "items")
                    val elementType =
                        (items.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    mapOf(
                        items to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                itemsApplications += 1
                                Value.OutputList.of(
                                    elementType,
                                    listOf(
                                        null,
                                        Value.Error,
                                        schema.objectOf("Item") {
                                            "seed" setTo 3
                                        },
                                    ),
                                )
                            },
                        schema.field("Item", "computed") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Item { seed }",
                                ),
                            ) { input, _ ->
                                computedApplications += 1
                                val seed =
                                    input.fieldValues.getValue(
                                        Value.GroundKey.of(
                                            schema.objectField("Item", "seed"),
                                            emptyMap(),
                                        ),
                                    ) as Value.Int
                                Value.Int.of(seed.intValue * 2)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { items { computed } }",
            )
        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val items =
            assertIs<EngineResult.List>(
                result.fetch(
                    Value.GroundKey.of(world.schema.objectField("Query", "items"), emptyMap()),
                ).value,
            )

        assertEquals(3, items.size)
        assertEquals(null, items[0].value)
        assertEquals(Value.Error, items[1].value)
        val item = assertIs<EngineResult.Object>(items[2].value)
        assertEquals(
            Value.Int.of(6),
            item.fetch(
                Value.GroundKey.of(world.schema.objectField("Item", "computed"), emptyMap()),
            ).value,
        )
        assertEquals(1, itemsApplications)
        assertEquals(1, computedApplications)
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

}
