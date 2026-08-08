package semantics.resolver03

import model.Schema
import model.SelectionForest
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.mergeToGround
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverSelectiveDemandWitnessTest {
    @Test
    fun `producer witness captures exact successor demand supplied by Resolver03`() {
        var producerDemand: SelectionForest? = null
        val testWorld =
            TestWorld.fromSDL(
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
                                        Value.GroundKey.of(
                                            schema.objectField("Item", "base"),
                                            emptyMap(),
                                        ),
                                    ) as Value.String
                                Value.String.of("computed:${base.stringValue}")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { item { computed } }")
        val itemType = world.schema.type("Item") as Schema.ObjectType

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertEquals(
            setOf("base", "computed"),
            context(world) {
                requireNotNull(producerDemand)
                    .mergeToGround(itemType)
                    .keys()
                    .mapTo(linkedSetOf()) { key -> key.field.fieldName }
            },
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }
}
