package semantics.resolvers.resolver06

import semantics.shared.OperationContext
import model.ObjectEngineResult
import model.PathComponent
import model.fragmentFrom
import model.merge
import model.requireType
import model.schemaType
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import java.util.PriorityQueue
import viaduct.graphql.schema.ViaductSchema
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DepthFirstReactorTest {
    @Test
    fun `reactor tasks validate source and target types at construction`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  item: Item
                }

                type Item {
                  value: Int
                }
                """.trimIndent(),
            ).assumptions
        val source = world.resolverRegistry.createRootQueryInput()
        val target =
            ObjectEngineResult.of(
                world.schema.requireType("Item") as ViaductSchema.Object,
                mutable = true,
            )

        assertFailsWith<IllegalArgumentException> {
            DepthFirstReactor.SlotOrchestrator(
                path = emptyList(),
                source = source,
                selections =
                    world
                        .fragmentFrom("fragment ignored on Query { __typename }")
                        .subselections,
                target = target,
            )
        }
    }

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
        val reactor =
            DepthFirstReactor(
                operation = OperationContext(world),
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
