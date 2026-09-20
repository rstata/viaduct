package semantics.resolvers.resolver21

import kotlinx.coroutines.runBlocking
import model.ObjectEngineResult
import model.requireQueryTypeDef
import model.selectionForestOf
import model.testing.TestWorld
import semantics.shared.CycleCheckState
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoroutineOrchestrationTaskTest {
    @Test
    fun `duplicate orchestration dispatch remains an illegal state`(): Unit =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        schemaSDL = "type Query { value: Int }",
                        selectiveResolvers = false,
                    ).assumptions
            val operation =
                CoroutineOperationContext(
                    base = SharedOperationContext.create(world),
                    requestScope = this,
                    complete = { it },
                    supportsParentFields = false,
                    cycleChecker = CycleCheckState.create(),
                )
            val root =
                ObjectEngineResult.of(
                    world.schema.requireQueryTypeDef(),
                    mutable = true,
                )
            val task =
                CoroutineOrchestrationTask.create(
                    operation = operation,
                    occurrence = OEROccurrence(root, emptyList(), root),
                    source = world.resolverRegistry.createRootQueryInput(),
                    initialDemand = selectionForestOf(),
                )

            operation.dispatcher.dispatchOrchestrator(task)

            val failure =
                assertFailsWith<IllegalStateException> {
                    operation.dispatcher.dispatchOrchestrator(task)
                }
            assertEquals("Object orchestrated twice: []", failure.message)
        }
}
