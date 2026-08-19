package semantics.contract

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.requireObjectField
import model.requireType
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract for resolvers whose user-declared object fragments are empty.
 */
interface EmptyObjectFragmentResolverContract :
    ResolverContract,
    CrossKeyRecursiveDemandResolverContract {
    @Test
    fun `resolves an externally typename-only query to an empty root`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = selectiveResolvers,
            ).assumptions
        val result = resolveAndValidate(world, "query { __typename }")

        assertTrue(result.keys.isEmpty())
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
        resolveAndValidate(world, "query { items { selected } }")
    }

    @Test
    fun `specializes shared list continuation and concrete argument defaults`() {
        val applications = ConcurrentLinkedQueue<String>()
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
                    if (field.name == "computed") {
                        applications += field.containingDef.name
                    }
                },
            )
        val world = testWorld.assumptions
        val result = resolveAndValidate(world, "query { items { computed } }")
        val items =
            assertIs<ListEngineResult>(
                result.getCell(world.schema.contractKey("Query", "items")).get(),
            )
        val a = assertIs<ObjectEngineResult>(items[0].get())
        val b = assertIs<ObjectEngineResult>(items[1].get())

        assertEquals(
            2,
            a.getCell(
                world.schema.contractKey("A", "computed", mapOf("factor" to 2)),
            ).get(),
        )
        assertEquals(
            3,
            b.getCell(world.schema.contractKey("B", "computed")).get(),
        )
        applications.toList().shouldContainExactlyInAnyOrder("A", "B")
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
        val result =
            resolveAndValidate(world, "query { item { computed } }")
        val item =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "item")).get(),
            )
        val concreteDefaultKey =
            world.schema.contractKey("ConcreteItem", "computed", mapOf("factor" to 7))

        assertEquals(7, item.getCell(concreteDefaultKey).get())
        assertEquals(
            expectedPassiveResultKeys(item.type, setOf(concreteDefaultKey)),
            item.keys,
        )
    }
}

internal fun Schema.contractObjectType(typeName: String): Schema.Object =
    requireType(typeName) as Schema.Object

internal fun Schema.contractKey(
    typeName: String,
    fieldName: String,
    arguments: Map<String, Any?> = emptyMap(),
): ObjectEngineResult.GroundKey =
    ObjectEngineResult.GroundKey.of(requireObjectField(typeName, fieldName), arguments)
