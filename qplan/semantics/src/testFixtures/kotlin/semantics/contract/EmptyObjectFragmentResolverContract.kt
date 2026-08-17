package semantics.contract

import model.EngineResult
import model.Schema
import model.Value
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
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      items: [Item!]!
                        @resolver(
                          result: [
                            {selected: 1, extra: 2},
                            {selected: 3}
                          ]
                        )
                    }

                    type Item {
                      selected: Int!
                      extra: Int
                    }
                    """.trimIndent(),
                applicationObserver = { _, input, _, _ ->
                    require(input.hasExactlyFields())
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
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      items: [Item!]!
                        @resolver(
                          result: [
                            {__typename: "A"},
                            {__typename: "B"}
                          ]
                        )
                    }

                    interface Item {
                      computed: Int!
                    }

                    type A implements Item {
                      computed(factor: Int = 2): Int!
                        @resolver(result: "sum(${'$'}factor)")
                    }

                    type B implements Item {
                      computed: Int! @resolver(result: 3)
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    require(input.hasExactlyFields())
                    if (field.fieldName == "computed") {
                        applications += field.containingType.typeName
                    }
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
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(result: {__typename: "ConcreteItem"})
                    }

                    interface Item {
                      computed: Int!
                    }

                    type ConcreteItem implements Item {
                      computed(factor: Int = 7): Int!
                        @resolver(result: "sum(${'$'}factor)")
                    }
                    """.trimIndent(),
                applicationObserver = { _, input, _, _ ->
                    require(input.hasExactlyFields())
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
