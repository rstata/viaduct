package semantics.contract

import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Contract for resolvers whose user-declared object fragments are empty.
 */
interface EmptyObjectFragmentResolverContract :
    ResolverContract,
    CrossKeyRecursiveDemandResolverContract {
    @Test
    fun `resolves typename as the concrete object type`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = selectiveResolvers,
            ).assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { __typename }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)

        assertEquals(
            "Query",
            assertIs<Value.String>(
                result.getCell(world.schema.contractKey("Query", "__typename")).get(),
            ).stringValue,
        )
    }

    @Test
    fun `accepts position-distinct passive fields in list output`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Item { selected: String!, extra: String }
                    type Query { items: [Item!]! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val items = schema.field("Query", "items")
                    val elementType =
                        (items.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    mapOf(
                        items to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { input, _ ->
                                require(input.hasExactlyFields())
                                Value.OutputList.of(
                                    elementType,
                                    listOf(
                                        schema.objectOf("Item") {
                                            "selected" setTo "first"
                                            "extra" setTo "only-first"
                                        },
                                        schema.objectOf("Item") {
                                            "selected" setTo "second"
                                        },
                                    ),
                                )
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { items { selected } }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)

    }

    @Test
    fun `specializes shared list continuation and concrete argument defaults`() {
        val applications = mutableListOf<String>()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Item { computed: Int! }
                    type A implements Item { computed(factor: Int = 2): Int! }
                    type B implements Item { computed: Int! }
                    type Query { items: [Item!]! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val items = schema.field("Query", "items")
                    val elementType =
                        (items.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    mapOf(
                        items to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { input, _ ->
                                require(input.hasExactlyFields())
                                Value.OutputList.of(
                                    elementType,
                                    listOf(schema.objectOf("A"), schema.objectOf("B")),
                                )
                            },
                        schema.field("A", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("A"),
                            ) { input, arguments ->
                                require(input.hasExactlyFields())
                                applications += "A"
                                arguments.fieldValues.getValue("factor") as Value.Int
                            },
                        schema.field("B", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("B"),
                            ) { input, _ ->
                                require(input.hasExactlyFields())
                                applications += "B"
                                Value.Int.of(3)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { items { computed } }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val items =
            assertIs<EngineResult.List>(
                result.getCell(world.schema.contractKey("Query", "items")).get(),
            )
        val a = assertIs<EngineResult.Object>(items[0].get())
        val b = assertIs<EngineResult.Object>(items[1].get())

        assertEquals(
            Value.Int.of(2),
            a.getCell(
                Value.GroundKey.of(
                    world.schema.objectField("A", "computed"),
                    mapOf("factor" to 2),
                ),
            ).get(),
        )
        assertEquals(
            Value.Int.of(3),
            b.getCell(world.schema.contractKey("B", "computed")).get(),
        )
        assertEquals(listOf("A", "B"), applications)
    }

    @Test
    fun `applies a concrete implementation default after interface dispatch`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Item { computed: Int! }
                    type ConcreteItem implements Item {
                      computed(factor: Int = 7): Int!
                    }
                    type Query { item: Item! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "item") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { input, _ ->
                                require(input.hasExactlyFields())
                                schema.objectOf("ConcreteItem")
                            },
                        schema.field("ConcreteItem", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("ConcreteItem"),
                            ) { input, arguments ->
                                require(input.hasExactlyFields())
                                arguments.fieldValues.getValue("factor") as Value.Int
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { item { computed } }")

        val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
        val item =
            assertIs<EngineResult.Object>(
                result.getCell(world.schema.contractKey("Query", "item")).get(),
            )
        val concreteDefaultKey =
            Value.GroundKey.of(
                world.schema.objectField("ConcreteItem", "computed"),
                mapOf("factor" to 7),
            )

        assertEquals(Value.Int.of(7), item.getCell(concreteDefaultKey).get())
        assertEquals(
            expectedPassiveResultKeys(item.type, setOf(concreteDefaultKey)),
            item.keys,
        )
    }
}

internal fun Schema.contractObjectType(typeName: String): Schema.ObjectType =
    type(typeName) as Schema.ObjectType

internal fun Schema.contractKey(
    typeName: String,
    fieldName: String,
): Value.GroundKey =
    Value.GroundKey.of(
        objectField(typeName, fieldName),
        emptyMap(),
    )
