package semantics

import model.EngineResult
import model.PathComponent
import model.emptyFragmentOf
import model.fragmentFrom
import model.groundKey
import model.merge
import model.objectOf
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import java.util.PriorityQueue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DepthFirstReactorTest {
    @Test
    fun `reports the complete task lifecycle through reactor instrumentation`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = false,
            ).assumptions
        val source = world.objectOf("Query")
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { __typename }")
                .subselections
        val selection = selections.merge(source.type).byGroundKey().values.single()
        val coordinate = listOf<PathComponent>(selection.groundKey())
        val events = mutableListOf<ReactorEvent>()
        val runtimeSupport =
            RuntimeSupport { demand ->
                SelectionCompletion(demand)
            }

        context(world, runtimeSupport) {
            DepthFirstReactor(
                source = source,
                selections = selections,
                eventObserver = events::add,
            ).resolve()
        }

        assertEquals(
            listOf(
                ReactorEvent.OrchestratorLaunched(emptyList(), "Query"),
                ReactorEvent.OrchestratorStarted(emptyList(), "Query"),
                ReactorEvent.ResolverLaunched(
                    coordinate,
                    ReactorSlotKind.ENGINE_OWNED,
                ),
                ReactorEvent.OrchestratorFinished(emptyList(), "Query"),
                ReactorEvent.ResolverStarted(
                    coordinate,
                    ReactorSlotKind.ENGINE_OWNED,
                ),
                ReactorEvent.ResolverFinished(
                    coordinate,
                    ReactorSlotKind.ENGINE_OWNED,
                ),
            ),
            events,
        )
    }

    @Test
    fun `equal-depth resolvers precede orchestrators and preserve insertion order`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = false,
            ).assumptions
        val source = world.objectOf("Query")
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { __typename }")
                .subselections
        val selection = selections.merge(source.type).byGroundKey().values.single()
        val target = EngineResult.Object.of(source.type, emptyMap(), mutable = true)
        val path = emptyList<PathComponent>()
        val firstResolver =
            DepthFirstReactor.SlotResolver(path, source, selection, target)
        val secondResolver =
            DepthFirstReactor.SlotResolver(path, source, selection, target)
        val orchestrator =
            DepthFirstReactor.SlotOrchestrator(path, source, selections, target)
        val tasks = PriorityQueue(depthFirstTaskComparator)

        tasks += ScheduledTask(orchestrator, sequence = 0)
        tasks += ScheduledTask(firstResolver, sequence = 1)
        tasks += ScheduledTask(secondResolver, sequence = 2)

        assertSame(firstResolver, tasks.remove().task)
        assertSame(secondResolver, tasks.remove().task)
        assertSame(orchestrator, tasks.remove().task)
    }

    @Test
    fun `resolve can only be called once`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = false,
            ).assumptions
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { __typename }")
                .subselections
        val runtimeSupport =
            RuntimeSupport { demand ->
                SelectionCompletion(demand)
            }
        val reactor =
            context(world, runtimeSupport) {
                DepthFirstReactor(
                    source = world.objectOf("Query"),
                    selections = selections,
                )
            }

        context(world, runtimeSupport) {
            reactor.resolve()

            assertFailsWith<IllegalStateException> {
                reactor.resolve()
            }
        }
    }
}
