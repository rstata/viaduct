package semantics.resolver26

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.EngineObjectOrErrorData
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.operationSelectionsFrom
import model.requireObjectField
import model.requireQueryTypeDef
import model.schemaType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.contract.selectionValues
import semantics.shared.OperationContext
import semantics.shared.ResolverObserver
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueryFragmentProducerTest {
    @Test
    fun `producer exception becomes an explicit Query error owned by the field task`() =
        runBlocking {
            val failure = IllegalStateException("Query producer failed")
            val consumerInvoked = AtomicBoolean()
            val observer =
                object : ResolverObserver {
                    override fun onQueryFragmentResult(
                        resolverOccurrenceId: ResolverOccurrenceId,
                        result: ObjectEngineResult,
                    ): Nothing = throw failure
                }
            val requestJob = Job()
            val requestScope = CoroutineScope(resolver26CoroutineContext() + requestJob)

            try {
                val resolution =
                    startQueryFragmentResolution(requestScope, observer) {
                        consumerInvoked.set(true)
                    }
                val queryValue =
                    assertIs<EngineObjectOrErrorData.Error>(
                        withTimeout(5_000) {
                            resolution.operation.queryValuesState.fetch(
                                resolution.resolverOccurrenceId,
                            )
                        },
                    )
                val fieldValue =
                    assertIs<ErrorEngineResult>(
                        withTimeout(5_000) {
                            resolution.root
                                .getCell(resolution.key)
                                .getValue()
                                .await()
                        },
                    )

                assertSame(failure, queryValue.error.cause)
                assertSame(failure, fieldValue.errorData.cause)
                assertFalse(consumerInvoked.get())
                assertTrue(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `cancellation before producer entry cancels its declared Query value`() =
        runBlocking {
            val dispatcher = QueuedDispatcher()
            val requestJob = Job()
            val requestScope = CoroutineScope(dispatcher + requestJob)
            val producerStarted = AtomicBoolean()
            val observer =
                object : ResolverObserver {
                    override fun onQueryFragmentResult(
                        resolverOccurrenceId: ResolverOccurrenceId,
                        result: ObjectEngineResult,
                    ) {
                        producerStarted.set(true)
                    }
                }
            try {
                val resolution = startQueryFragmentResolution(requestScope, observer) {}

                // Enter the field task so it queues its Query producer and suspends awaiting its value.
                dispatcher.runNext()
                requestJob.cancel(CancellationException("cancelled before Query producer entry"))
                // Give only the cancelled producer its queued turn. Its terminal callback must cancel
                // the Query value before the field task's own cancellation continuation can do so.
                dispatcher.runNext()

                kotlin.test.assertFailsWith<CancellationException> {
                    withTimeout(5_000) {
                        resolution.operation.queryValuesState.fetch(
                            resolution.resolverOccurrenceId,
                        )
                    }
                }
                assertFalse(producerStarted.get())
            } finally {
                requestJob.cancel()
                dispatcher.runUntilIdle()
                requestJob.join()
            }
        }

    private fun startQueryFragmentResolution(
        requestScope: CoroutineScope,
        observer: ResolverObserver,
        onConsumerInvocation: () -> Unit,
    ): StartedQueryFragmentResolution {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      dependency: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependency = schema.requireObjectField("Query", "dependency")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        dependency to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerQuery on Query { dependency }",
                                    ),
                            ) { _, queryValue, _ ->
                                onConsumerInvocation()
                                queryValue.selectionValues().getValue("dependency")
                            },
                    )
                },
            )
        val selections = world.assumptions.operationSelectionsFrom("query { consumer }")
        val baseOperation = OperationContext(world.assumptions, resolverObserver = observer)
        val operation =
            Resolver26OperationContext(
                base = baseOperation,
                requestScope = requestScope,
                resolverObserver = observer.withResolver26Applications {},
            )
        val source = operation.resolverRegistry.createRootQueryInput()
        val root =
            ObjectEngineResult.of(
                type = source.schemaType,
                mutable = true,
            )
        val key = selections.merge(root.type).byKey().keys.single()
        val resolverOccurrenceId = ResolverOccurrenceId.at(root, listOf(key))
        val orchestration =
            ObjectOrchestrationTask(
                operation = operation,
                occurrence =
                    OEROccurrenceContext(
                        root = root,
                        path = emptyList(),
                        target = root,
                    ),
                source = source,
                initialDemand = selections,
            )
        orchestration.prepare()
        orchestration.launch()
        return StartedQueryFragmentResolution(operation, root, key, resolverOccurrenceId)
    }

    private data class StartedQueryFragmentResolution(
        val operation: Resolver26OperationContext,
        val root: ObjectEngineResult,
        val key: ObjectEngineResult.ObjectKey,
        val resolverOccurrenceId: ResolverOccurrenceId,
    )

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "No queued coroutine to run" }
            tasks.removeFirst().run()
        }

        fun runUntilIdle() {
            while (tasks.isNotEmpty()) runNext()
        }
    }
}
