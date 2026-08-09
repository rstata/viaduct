package semantics.contract

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

sealed interface ResolverTaskObservation {
    val path: List<String>

    data class SlotOrchestrator(
        val objectType: String,
        override val path: List<String>,
    ) : ResolverTaskObservation

    data class SlotResolver(
        val fieldName: String,
        override val path: List<String>,
    ) : ResolverTaskObservation
}

/**
 * Contract for queue-backed resolvers that reproduce recursive depth-first task ordering.
 */
interface DepthFirstTaskOrderingContract : ResolverContract {
    fun resolveAndObserveTasks(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): EngineResult.Object

    @Test
    fun `executes the exact recursive task order across equal-depth siblings`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Child { nested: String! }
                    type Container {
                      left: Child!
                      right: Child!
                    }
                    type Query {
                      container: Container!
                      after: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "container") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Container") {
                                    "left" setTo objectOf("Child")
                                    "right" setTo objectOf("Child")
                                }
                            },
                        schema.field("Child", "nested") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ ->
                                Value.String.of("nested")
                            },
                        schema.field("Query", "after") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                Value.String.of("after")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            world
                .fragmentFrom(
                    """
                    fragment ignored on Query {
                      container {
                        left { nested }
                        right { nested }
                      }
                      after
                    }
                    """.trimIndent(),
                ).subselections
        val taskTrace = mutableListOf<ResolverTaskObservation>()

        resolveAndObserveTasks(
            world = world,
            root = world.objectOf("Query"),
            selections = selections,
            taskObserver = taskTrace::add,
        )

        assertEquals(
            listOf(
                ResolverTaskObservation.SlotOrchestrator("Query", emptyList()),
                ResolverTaskObservation.SlotResolver("container", emptyList()),
                ResolverTaskObservation.SlotOrchestrator(
                    "Child",
                    listOf("container", "left"),
                ),
                ResolverTaskObservation.SlotResolver(
                    "nested",
                    listOf("container", "left"),
                ),
                ResolverTaskObservation.SlotOrchestrator(
                    "Child",
                    listOf("container", "right"),
                ),
                ResolverTaskObservation.SlotResolver(
                    "nested",
                    listOf("container", "right"),
                ),
                ResolverTaskObservation.SlotResolver("after", emptyList()),
            ),
            taskTrace,
        )
    }
}
