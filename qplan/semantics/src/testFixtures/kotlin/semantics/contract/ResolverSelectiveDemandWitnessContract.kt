package semantics.contract

import model.ObjectEngineResult

import model.Schema
import model.SelectionForest
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

interface ResolverSelectiveDemandWitnessContract : ResolverContract {
    @Test
    fun `producer witness captures exact successor demand`() {
        var producerDemand: SelectionForest? = null
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Item {
                      base: String!
                      computed: String!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val item = schema.field("Query", "item")
                    val computed = schema.field("Item", "computed")
                    mapOf(
                        item to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "base" setTo "input"
                                }
                            }.observeApplications { _, _, suppliedDemand ->
                                if (suppliedDemand != null) {
                                    producerDemand = suppliedDemand
                                }
                            },
                        computed to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Item { base }",
                                ),
                            ) { input, _ ->
                                val base =
                                    input.fieldValues.getValue(
                                        "base",
                                    ) as String
                                "computed:$base"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { item { computed } }")
        val itemType = world.schema.type("Item") as Schema.ObjectType

        val result =
            resolve(
                world,
                world.objectOf("Query"),
                fragment.subselections,
            )

        assertEquals(
            setOf("base", "computed"),
            context(world) {
                requireNotNull(producerDemand)
                    .merge(itemType)
                    .instantiateBindings()
                    .groundKeys()
                    .mapTo(linkedSetOf()) { key -> key.field.fieldName }
            },
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }
}
