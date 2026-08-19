package model.registry

import model.Arguments

import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ResolverApplicationObserverTest {
    @Test
    fun `application observer preserves complete and selective boundaries through composition`() {
        val observed = mutableListOf<SelectionForest?>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      value: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val fragment = schema.emptyFragmentOf("Query")
                    mapOf(
                        schema.field("Query", "value") to
                            fieldResolverOf(fragment) { _, _ -> 7 }
                                .observeApplications { _, _, selections ->
                                    observed += selections
                                }.mapOutput { output -> output }
                                .mapDemand { selectionForestOf() },
                    )
                },
            )
        val world = testWorld.assumptions
        val field = world.schema.objectField("Query", "value")
        val suppliedDemand =
            world.fragmentFrom("fragment ignored on Query { value }").subselections
        val resolver = world.resolverRegistry.resolver(field)
        val input = world.schema.objectOf("Query")
        val arguments = Arguments.Resolved.of(field, emptyMap())

        context(world) {
            resolver(input, arguments)
            resolver(input, arguments, suppliedDemand)
        }

        assertEquals(2, observed.size)
        assertNull(observed[0])
        assertSame(suppliedDemand, observed[1])
    }
}
