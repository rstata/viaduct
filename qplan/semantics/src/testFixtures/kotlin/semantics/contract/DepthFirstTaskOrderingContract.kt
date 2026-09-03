package semantics.contract

import model.requireField
import viaduct.engine.api.EngineObjectData
import model.ObjectEngineResult
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import semantics.shared.OperationContext

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
        operation: OperationContext,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): ObjectEngineResult

    @Test
    fun `executes the exact recursive task order across equal-depth siblings`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
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
                        schema.requireField("Query", "container") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Container") {
                                    "left" setTo objectOf("Child")
                                    "right" setTo objectOf("Child")
                                }
                            },
                        schema.requireField("Child", "nested") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ ->
                                "nested"
                            },
                        schema.requireField("Query", "after") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                "after"
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
            operation = OperationContext(world),
            root = world.objectOf("Query"),
            selections = selections,
            taskObserver = taskTrace::add,
        )

        assertEquals(
            listOf(
                ResolverTaskObservation.SlotOrchestrator("Query", emptyList()),
                ResolverTaskObservation.SlotResolver("container", emptyList()),
                ResolverTaskObservation.SlotOrchestrator(
                    "Container",
                    listOf("container"),
                ),
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
