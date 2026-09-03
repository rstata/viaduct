package semantics.contract

import model.requireField
import viaduct.graphql.schema.ViaductSchema
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import semantics.shared.instantiateBindings
import model.merge
import model.objectOf
import model.requireType
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
                    val item = schema.requireField("Query", "item")
                    val computed = schema.requireField("Item", "computed")
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
                                    input.selectionValues().getValue(
                                        "base",
                                    ) as String
                                "computed:$base"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { item { computed } }")
        val itemType = world.schema.requireType("Item") as ViaductSchema.Object

        val resolution =
            observeResolution(
                world,
                world.objectOf("Query"),
                fragment.subselections,
            )
        val result = resolution.result

        assertEquals(
            setOf("base", "computed"),
            context(resolution.operation) {
                requireNotNull(producerDemand)
                    .merge(itemType)
                    .instantiateBindings()
                    .groundKeys()
                    .mapTo(linkedSetOf()) { key -> key.field.name }
            },
        )
        assertTrue(
            context(resolution.operation) {
                result.correctResolution(fragment)
            },
        )
    }
}
