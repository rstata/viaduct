package semantics.resolver26

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.ErrorEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.operationSelectionsFrom
import model.requireField
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.SharedOperationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResolverStartTest {
    @Test
    fun `startResolve publishes root shape before resolver values complete`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val world = delayedWorld(providerStarted, gate)
            val selections = world.assumptions.operationSelectionsFrom("query { fast slow }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolver26CoroutineContext() + requestJob)

            try {
                val root =
                    context(SharedOperationContext(world.assumptions)) {
                        startResolve(selections, requestScope)
                    }
                val fast = root.cell("fast")
                val slow = root.cell("slow")

                assertFalse(slow.getValue().isCompleted)
                withTimeout(5_000) { providerStarted.await() }
                assertEquals(1, withTimeout(5_000) { fast.getValue().await() })
                assertFalse(slow.getValue().isCompleted)

                gate.complete(Unit)
                assertEquals(2, withTimeout(5_000) { slow.getValue().await() })
                assertTrue(slow.getValue().isCompleted)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `cancelling the request scope cancels resolver work`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val providerStopped = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val world = delayedWorld(providerStarted, gate, providerStopped)
            val selections = world.assumptions.operationSelectionsFrom("query { slow }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolver26CoroutineContext() + requestJob)

            val root =
                context(SharedOperationContext(world.assumptions)) {
                    startResolve(selections, requestScope)
                }
            withTimeout(5_000) { providerStarted.await() }

            requestJob.cancelAndJoin()

            withTimeout(5_000) { providerStopped.await() }
            assertIs<CancellationException>(root.cell("slow").getValue().awaitFailure())
            assertFalse(gate.isCompleted)
        }

    @Test
    fun `task-local cancellation becomes the affected field error`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val world =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA,
                    fieldResolvers = { schema ->
                        val slow = schema.requireObjectField("Query", "slow")
                        mapOf(
                            slow to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment Slow on Query { " +
                                            "dependency @include(if: ${'$'}ready) }",
                                        variableField = slow,
                                    ),
                                ) { _, _ -> 2 }
                                    .withVariablesProvider(setOf("ready")) {
                                        providerStarted.complete(Unit)
                                        throw CancellationException("provider cancelled")
                                    },
                        )
                    },
                )
            val selections = world.assumptions.operationSelectionsFrom("query { slow }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolver26CoroutineContext() + requestJob)

            try {
                val root =
                    context(SharedOperationContext(world.assumptions)) {
                        startResolve(selections, requestScope)
                    }
                val slow = root.cell("slow")

                withTimeout(5_000) { providerStarted.await() }
                assertTrue(requestJob.isActive)
                val error = assertIs<ErrorEngineResult>(slow.getValue().await())

                assertTrue(slow.getValue().isCompleted)
                assertEquals(
                    "provider cancelled",
                    assertIs<CancellationException>(error.errorData.cause).message,
                )
                assertTrue(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `task-local cancellation becomes field errors without cancelling the request`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val world =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA,
                    fieldResolvers = { schema ->
                        val slow = schema.requireObjectField("Query", "slow")
                        val dependent = schema.requireObjectField("Query", "dependent")
                        mapOf(
                            slow to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment Slow on Query { " +
                                            "dependency @include(if: ${'$'}ready) }",
                                        variableField = slow,
                                    ),
                                ) { _, _ -> 2 }
                                    .withVariablesProvider(setOf("ready")) {
                                        providerStarted.complete(Unit)
                                        throw CancellationException("provider cancelled")
                                    },
                            dependent to
                                fieldResolverOf(
                                    schema.fragmentFrom("fragment Dependent on Query { slow }"),
                                ) { _, _ -> error("dependent resolver should not execute") },
                        )
                    },
                )
            val selections = world.assumptions.operationSelectionsFrom("query { dependent }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolver26CoroutineContext() + requestJob)

            try {
                val root =
                    context(SharedOperationContext(world.assumptions)) {
                        startResolve(selections, requestScope)
                    }

                withTimeout(5_000) { providerStarted.await() }
                val slowError =
                    assertIs<ErrorEngineResult>(
                        withTimeout(5_000) { root.cell("slow").getValue().await() },
                    )
                assertIs<CancellationException>(slowError.errorData.cause)
                assertIs<ErrorEngineResult>(
                    withTimeout(5_000) { root.cell("dependent").getValue().await() },
                )
                assertTrue(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `provider contract exception becomes a field error`() =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA,
                    fieldResolvers = { schema ->
                        val slow = schema.requireObjectField("Query", "slow")
                        mapOf(
                            slow to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment Slow on Query { " +
                                            "dependency @include(if: ${'$'}ready) }",
                                        variableField = slow,
                                    ),
                                ) { _, _ -> 2 }
                                    .withVariablesProvider(setOf("ready")) { emptyMap() },
                        )
                    },
                )
            val selections = world.assumptions.operationSelectionsFrom("query { slow }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolver26CoroutineContext() + requestJob)

            try {
                val root =
                    context(SharedOperationContext(world.assumptions)) {
                        startResolve(selections, requestScope)
                    }
                val error =
                    assertIs<ErrorEngineResult>(
                        withTimeout(5_000) { root.cell("slow").getValue().await() },
                    )
                val publicationFailure = assertIs<IllegalStateException>(error.errorData.cause)

                assertTrue(
                    publicationFailure.message.orEmpty().contains(
                        "VariablesProvider returned invalid variables. Missing keys: ready",
                    ),
                )
                assertTrue(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `field exception does not cancel queued sibling producers`() =
        runBlocking {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val siblingStarted = CompletableDeferred<Unit>()
                val world =
                    TestWorld.fromSDL(
                        schemaSDL = SCHEMA,
                        fieldResolvers = { schema ->
                            val fast = schema.requireObjectField("Query", "fast")
                            val slow = schema.requireObjectField("Query", "slow")
                            mapOf(
                                fast to
                                    fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                        throw NullPointerException("tenant bug")
                                    },
                                slow to
                                    fieldResolverOf(
                                        schema.fragmentFrom(
                                            "fragment Slow on Query { " +
                                                "dependency @include(if: ${'$'}ready) }",
                                            variableField = slow,
                                        ),
                                    ) { _, _ -> 2 }
                                        .withVariablesProvider(setOf("ready")) {
                                            siblingStarted.complete(Unit)
                                            mapOf("ready" to false)
                                        },
                            )
                        },
                    )
                val selections =
                    world.assumptions.operationSelectionsFrom("query { fast slow }")
                val requestJob = Job()
                val requestScope = CoroutineScope(dispatcher + requestJob)

                try {
                    val root =
                        context(SharedOperationContext(world.assumptions)) {
                            startResolve(selections, requestScope)
                        }
                    val fastError =
                        assertIs<ErrorEngineResult>(
                            withTimeout(5_000) { root.cell("fast").getValue().await() },
                        )
                    val slowValue = withTimeout(5_000) { root.cell("slow").getValue().await() }

                    assertEquals(
                        "tenant bug",
                        assertIs<NullPointerException>(fastError.errorData.cause).message,
                    )
                    assertEquals(2, slowValue)
                    withTimeout(5_000) { siblingStarted.await() }
                    assertTrue(requestJob.isActive)
                } finally {
                    requestJob.cancelAndJoin()
                }
            }
        }

    @Test
    fun `request cancellation terminates field promises before coroutine entry`() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor()
            executor.asCoroutineDispatcher().use { dispatcher ->
                val blockerStarted = CountDownLatch(1)
                val releaseBlocker = CountDownLatch(1)
                val resolverStarted = AtomicBoolean()
                executor.submit {
                    blockerStarted.countDown()
                    releaseBlocker.await()
                }
                assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

                val world =
                    TestWorld.fromSDL(
                        schemaSDL = SCHEMA,
                        fieldResolvers = { schema ->
                            val fast = schema.requireObjectField("Query", "fast")
                            mapOf(
                                fast to
                                    fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                        resolverStarted.set(true)
                                        1
                                    },
                            )
                        },
                    )
                val requestJob = Job()
                val requestScope = CoroutineScope(dispatcher + requestJob)

                try {
                    val root =
                        context(SharedOperationContext(world.assumptions)) {
                            startResolve(
                                world.assumptions.operationSelectionsFrom("query { fast }"),
                                requestScope,
                            )
                        }
                    requestJob.cancel(CancellationException("cancelled before dispatch"))
                    releaseBlocker.countDown()

                    val cancellation =
                        withTimeout(5_000) { root.cell("fast").getValue().awaitFailure() }
                    assertTrue(cancellation.message.orEmpty().contains("cancelled before dispatch"))
                    assertIs<CancellationException>(cancellation)
                    assertFalse(resolverStarted.get())
                } finally {
                    releaseBlocker.countDown()
                    requestJob.cancelAndJoin()
                }
            }
        }

    private fun delayedWorld(
        providerStarted: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
        providerStopped: CompletableDeferred<Unit>? = null,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA,
            fieldResolvers = { schema ->
                val fast = schema.requireObjectField("Query", "fast")
                val slow = schema.requireObjectField("Query", "slow")
                mapOf(
                    fast to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 },
                    slow to
                        fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment Slow on Query { " +
                                    "dependency @include(if: ${'$'}ready) }",
                                variableField = slow,
                            ),
                        ) { _, _ -> 2 }
                            .withVariablesProvider(setOf("ready")) {
                                providerStarted.complete(Unit)
                                try {
                                    gate.await()
                                    mapOf("ready" to false)
                                } finally {
                                    providerStopped?.complete(Unit)
                                }
                            },
                )
            },
        )

    private fun ObjectEngineResult.cell(fieldName: String) =
        getCell(
            ObjectEngineResult.GroundKey.of(
                type.requireField(fieldName),
                emptyMap(),
            ),
        )

    private suspend fun <T> model.Promise<T>.awaitFailure(): Exception =
        try {
            await()
            error("Promise completed successfully")
        } catch (failure: Exception) {
            failure
        }

    private companion object {
        val SCHEMA =
            """
            type Query {
              fast: Int!
              slow: Int!
              dependent: Int!
              dependency: Int!
            }
            """.trimIndent()
    }
}
