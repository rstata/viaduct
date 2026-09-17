package semantics.resolvers.resolver06

import semantics.shared.SharedOperationContext
import semantics.shared.OEROccurrenceContext
import semantics.resolvers.resolver01.DepthFirstFieldResolverTask
import semantics.resolvers.resolver01.DepthFirstOperationContext
import semantics.resolvers.resolver01.DepthFirstOrchestrationTask
import semantics.resolvers.resolver01.DepthFirstTaskDispatcher
import model.ObjectEngineResult
import model.fragmentFrom
import model.merge
import model.schemaType
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import java.util.PriorityQueue
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DepthFirstReactorTest {
    @Test
    fun `equal-depth resolvers precede orchestrators and preserve insertion order`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = false,
            ).assumptions
        val source = world.resolverRegistry.createRootQueryInput()
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { __typename }")
                .subselections
        val sourceType = source.schemaType
        val selection = selections.merge(sourceType).byGroundKey().values.single()
        val target = ObjectEngineResult.of(sourceType, emptyMap(), mutable = true)
        val operation = DepthFirstOperationContext(SharedOperationContext(world), { it }, DepthFirstTaskDispatcher())
        val occurrence = OEROccurrenceContext(target, emptyList(), target)
        val firstResolver =
            DepthFirstFieldResolverTask(operation, occurrence, selection, target.reserveCell(selection.key))
        val secondResolver =
            DepthFirstFieldResolverTask(
                operation, occurrence, selection,
                ObjectEngineResult.of(sourceType, mutable = true).reserveCell(selection.key),
            )
        val orchestrator =
            DepthFirstOrchestrationTask.create(operation, occurrence, source, selections)
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
        val reactor =
            DepthFirstReactor(
                operation = SharedOperationContext(world),
                complete = { demand -> demand },
                source = world.resolverRegistry.createRootQueryInput(),
                selections = selections,
            )

        reactor.resolve()

        assertFailsWith<IllegalStateException> {
            reactor.resolve()
        }
    }
}
