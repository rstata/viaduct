package model.registry

import model.SelectionForest
import model.Value
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
        val testWorld =
            TestWorld.fromSDL(
                """
                type Query {
                  value: Int!
                }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val field = world.schema.field("Query", "value")
        val fragment = world.schema.emptyFragmentOf("Query")
        val suppliedDemand =
            world.fragmentFrom("fragment ignored on Query { value }").subselections
        val observed = mutableListOf<SelectionForest?>()
        val resolver =
            fieldResolverOf(fragment) { _, _ -> Value.Int.of(7) }
                .observeApplications { _, _, selections -> observed += selections }
                .mapOutput { output -> output }
                .mapDemand { selectionForestOf() }
                .withPredecessorDemand(
                    predecessorDemand = fragment,
                    predecessorDemandFunction = { fragment },
                )
        val input = world.schema.objectOf("Query")
        val arguments = Value.Arguments.of(field, emptyMap())

        resolver(input, arguments)
        context(world) {
            resolver(input, arguments, suppliedDemand)
            resolver.resolveWithSource(
                input = input,
                arguments = arguments,
                selections = suppliedDemand,
                speculativeDemand = selectionForestOf(),
            )
        }

        assertEquals(3, observed.size)
        assertNull(observed[0])
        assertSame(suppliedDemand, observed[1])
        assertSame(suppliedDemand, observed[2])
    }
}
