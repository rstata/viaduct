package semantics.shared

import semantics.contract.get
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.operationSelectionsFrom
import model.requireObjectField
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import semantics.resolvers.resolver01.resolve as resolve01
import semantics.resolvers.resolver02.resolve as resolve02
import semantics.resolvers.resolver03.resolve as resolve03
import semantics.resolvers.resolver06.resolve as resolve06
import semantics.resolvers.resolver07.resolve as resolve07
import semantics.resolvers.resolver08.resolve as resolve08
import semantics.resolvers.resolver21.resolve as resolve21
import semantics.resolvers.resolver22.resolve as resolve22
import semantics.resolvers.resolver23.resolve as resolve23
import semantics.resolver26.resolve as resolve26

class ResolverObservationTest {
    private class Subject(
        val name: String,
        val selective: Boolean,
        val queryFragments: Boolean,
        val resolve: SharedOperationContext<*>.(SelectionForest) -> ObjectEngineResult,
    )

    private val depthFirstSubjects = listOf(
        Subject("Resolver01", false, false) { resolve01(it) },
        Subject("Resolver02", false, true) { resolve02(it) },
        Subject("Resolver03", true, true) { resolve03(it) },
        Subject("Resolver06", false, false) { resolve06(it) },
        Subject("Resolver07", false, true) { resolve07(it) },
        Subject("Resolver08", true, true) { resolve08(it) },
    )

    private val subjects = depthFirstSubjects + listOf(
        Subject("Resolver21", false, false) { resolve21(it) },
        Subject("Resolver22", false, true) { resolve22(it) },
        Subject("Resolver23", true, true) { resolve23(it) },
        Subject("Resolver26", true, true) { resolve26(it) },
    )

    @TestFactory
    fun `thread interruption does not cause discrepancy between DFS resolver observer invocation count and actual invocation count`() =
        depthFirstSubjects.flatMap { subject ->
            listOf(false, true).flatMap { reference ->
                listOf(false, true).map { suspendAfterEntry ->
                    dynamicTest("${subject.name} reference=$reference suspendAfterEntry=$suspendAfterEntry") {
                        var entered = 0
                        val events = CopyOnWriteArrayList<ResolverInvocationObservation>()
                        val observer = object : RecordingResolverObserver() {
                            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                                super.onResolverInvocation(observation)
                                if (observation.field.name == "value") {
                                    events += observation
                                    // Force interruption between recording and the resolver call.
                                    Thread.currentThread().interrupt()
                                }
                            }
                        }
                        val testWorld = TestWorld.fromSDL(
                            selectiveResolvers = subject.selective,
                            schemaSDL = "type Query { value: Int reference: Int }",
                            fieldResolvers = { schema ->
                                val value = schema.requireObjectField("Query", "value")
                                mapOf(
                                    schema.requireObjectField("Query", "reference") to
                                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                            RootFieldReferenceData.of(listOf(value), emptyMap())
                                        },
                                    value to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                        entered += 1
                                        if (suspendAfterEntry) awaitCancellation()
                                        7
                                    },
                                )
                            },
                        )
                        val operation = SharedOperationContext.create(testWorld.assumptions, resolverObserver = observer)
                        val selections = operation.world.operationSelectionsFrom(if (reference) "{ reference }" else "{ value }")
                        try {
                            val attempt = runCatching { subject.resolve(operation, selections) }
                            assertEquals(1, entered, "The recorded resolver must actually be entered")
                            assertEquals(entered, events.size, "Events must represent actual entry, not a pending runBlocking")
                            if (suspendAfterEntry) {
                                assertIs<InterruptedException>(attempt.exceptionOrNull())
                            } else {
                                // A synchronous body completes before runBlocking checks interruption again.
                                val result = attempt.getOrThrow()
                                assertEquals(7, result.getCell(result.keys.single()).get())
                                assertTrue(Thread.currentThread().isInterrupted)
                            }
                        } finally {
                            Thread.interrupted()
                        }
                    }
                }
            }
        }

    @TestFactory
    fun `ordinary and reference calls record their exact inputs before entry`() = subjects.map { subject ->
        dynamicTest(subject.name) {
            val events = CopyOnWriteArrayList<ResolverInvocationObservation>()
            val observer = object : RecordingResolverObserver() {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    events += observation
                }
            }
            val testWorld = TestWorld.fromSDL(
                selectiveResolvers = subject.selective,
                schemaSDL = "type Query { reference: Int! value(arg: Int!): Int! }",
                fieldResolvers = { schema ->
                    val value = schema.requireObjectField("Query", "value")
                    mapOf(
                        schema.requireObjectField("Query", "reference") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { input, arguments ->
                                val event = events.last()
                                assertEquals("reference", event.field.name)
                                assertSame(input, event.input)
                                assertSame(arguments, event.arguments)
                                RootFieldReferenceData.of(listOf(value), mapOf("arg" to 7))
                            },
                        value to fieldResolverOf(schema.emptyFragmentOf("Query")) { input, arguments ->
                            val event = events.last()
                            assertEquals("value", event.field.name)
                            assertSame(input, event.input)
                            assertSame(arguments, event.arguments)
                            arguments.fieldValues.getValue("arg")
                        },
                    )
                },
            )
            val world = testWorld.assumptions
            val operation = SharedOperationContext.create(world, resolverObserver = observer)
            val result = subject.resolve(operation, world.operationSelectionsFrom("{ reference }"))
            assertEquals(listOf("reference", "value"), events.map { it.field.name })
            assertEquals(7, result.getCell(result.keys.single()).get())
            val hop = observer.rootFieldReferenceInvocations().single()
            assertEquals(ResolverOccurrenceId.at(result, hop.publicationPath), events[0].resolverOccurrenceId)
            assertEquals(ResolverOccurrenceId.at(hop.invocationRoot, hop.invocationPath), events[1].resolverOccurrenceId)
            assertEquals(hop.invocationPath, events[1].occurrencePath)
            assertEquals(events.map { it.resolverOccurrenceId }.toSet(), observer.invokedResolverOccurrences())
            assertTrue(observer.allQueryFragmentResults().isEmpty())
            events.forEach {
                if (subject.selective) assertNotNull(it.suppliedDemand) else assertNull(it.suppliedDemand)
            }
            // Full event consumers preserve duplicates even though the base recorder stores an ID set.
            observer.onResolverInvocation(events.last())
            assertEquals(3, events.size)
            assertEquals(2, observer.invokedResolverOccurrences().size)
        }
    }

    @TestFactory
    fun `failed and cancelled ordinary and reference calls remain recorded`() = subjects.flatMap { subject ->
        listOf(false, true).flatMap { reference ->
            listOf(false, true).map { cancellation ->
                dynamicTest("${subject.name} reference=$reference cancellation=$cancellation") {
                    val events = CopyOnWriteArrayList<ResolverInvocationObservation>()
                    val observer = object : RecordingResolverObserver() {
                        override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                            super.onResolverInvocation(observation)
                            events += observation
                        }
                    }
                    val failure = if (cancellation) CancellationException("resolver cancelled") else IllegalStateException("resolver failed")
                    val testWorld = TestWorld.fromSDL(
                        selectiveResolvers = subject.selective,
                        schemaSDL = "type Query { reference: Int value: Int }",
                        fieldResolvers = { schema ->
                            val value = schema.requireObjectField("Query", "value")
                            mapOf(
                                schema.requireObjectField("Query", "reference") to
                                    fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                        RootFieldReferenceData.of(listOf(value), emptyMap())
                                    },
                                value to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> throw failure },
                            )
                        },
                    )
                    val operation = SharedOperationContext.create(testWorld.assumptions, resolverObserver = observer)
                    // DFS propagates these failures; coroutine subjects publish a field error.
                    try {
                        subject.resolve(operation, operation.world.operationSelectionsFrom(if (reference) "{ reference }" else "{ value }"))
                    } catch (caught: Exception) {
                        assertSame(failure, caught)
                    }
                    assertEquals(if (reference) listOf("reference", "value") else listOf("value"), events.map { it.field.name })
                    assertEquals(events.map { it.resolverOccurrenceId }.toSet(), observer.invokedResolverOccurrences())
                }
            }
        }
    }

    @TestFactory
    fun `declared Query root is recorded before its resolver can start`() = subjects.filter { it.queryFragments }.map { subject ->
        dynamicTest(subject.name) {
            val order = CopyOnWriteArrayList<String>()
            val observer = object : RecordingResolverObserver() {
                override fun onQueryFragmentPrepared(resolverOccurrenceId: ResolverOccurrenceId, result: ObjectEngineResult) {
                    assertTrue(order.isEmpty())
                    super.onQueryFragmentPrepared(resolverOccurrenceId, result)
                    order += "prepared"
                }

                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    order += observation.field.name
                }
            }
            val testWorld = TestWorld.fromSDL(
                selectiveResolvers = subject.selective,
                schemaSDL = "type Query { dependency: Int! consumer: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "dependency") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                assertEquals(listOf("prepared", "dependency"), order.toList())
                                assertEquals(1, observer.allQueryFragmentResults().size)
                                7
                            },
                        schema.requireObjectField("Query", "consumer") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                                schema.fragmentFrom("fragment Input on Query { dependency }"),
                            ) { _, _, _ -> 7 },
                    )
                },
            )
            val operation = SharedOperationContext.create(testWorld.assumptions, resolverObserver = observer)
            val root = subject.resolve(operation, operation.world.operationSelectionsFrom("{ consumer }"))
            assertEquals(listOf("prepared", "dependency", "consumer"), order.toList())
            val owner = ResolverOccurrenceId.at(root, listOf(root.keys.single()))
            val queryRoot = observer.queryFragmentResults(owner).single()
            assertEquals(7, queryRoot.getCell(queryRoot.keys.single()).get())
        }
    }

    @TestFactory
    fun `invocation demand precedes fixture demand and output transforms`() = subjects.map { subject ->
        dynamicTest(subject.name) {
            val events = CopyOnWriteArrayList<ResolverInvocationObservation>()
            val observer = object : RecordingResolverObserver() {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    events += observation
                }
            }
            val testWorld = TestWorld.fromSDL(
                selectiveResolvers = subject.selective,
                schemaSDL = "type Query { object: Thing } type Thing { value: Int }",
                fieldResolvers = { schema ->
                    mapOf(schema.requireObjectField("Query", "object") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> null }
                            .mapOutput { it }
                            .mapDemand { selectionForestOf() })
                },
            )
            val operation = SharedOperationContext.create(testWorld.assumptions, resolverObserver = observer)
            subject.resolve(operation, operation.world.operationSelectionsFrom("{ object { value } }"))
            val event = events.single()
            if (subject.selective) assertFalse(assertNotNull(event.suppliedDemand).isEmpty()) else assertNull(event.suppliedDemand)
        }
    }
}
