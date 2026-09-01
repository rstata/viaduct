package semantics.resolver25

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Timeout
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.Blackhole
import model.PathComponent
import model.ResolverOccurrenceId
import model.usedVariables
import model.variableArgumentNames
import semantics.benchmark.CurrentProfileBenchmarkSupport
import semantics.benchmark.DEFAULT_OVERHEAD_LOOP_COUNT
import semantics.benchmark.ObservedResolverBenchmarkSubject
import semantics.benchmark.ResolverBenchmarkApplicationObservation
import semantics.benchmark.ResolverBenchmarkSubject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
@Timeout(time = 10, timeUnit = TimeUnit.MINUTES)
open class ResolverBenchmark {
    @JvmField
    @Param("$DEFAULT_OVERHEAD_LOOP_COUNT")
    var loopCount: Int = DEFAULT_OVERHEAD_LOOP_COUNT

    private val support =
        CurrentProfileBenchmarkSupport(
            subject = ResolverBenchmarkSubject { world, _, selections ->
                context(world) {
                    resolve(selections)
                }
            },
            observedSubject =
                ObservedResolverBenchmarkSubject {
                        world,
                        _,
                        selections,
                        applicationObserver,
                    ->
                    val variableUseByContribution =
                        ConcurrentHashMap<DemandContributionId, VariableUse>()
                    val variableUseByCoordinate =
                        ConcurrentHashMap<List<PathComponent>, VariableUse>()
                    val pendingApplications =
                        ConcurrentLinkedQueue<Pair<List<PathComponent>, VariableUse>>()
                    context(world) {
                        val result = resolveObserved(selections) { event ->
                            when (event) {
                                is Resolver25LifecycleEvent.DemandSubmitted -> {
                                    val argumentNames =
                                        event.selection.key.arguments.variableArgumentNames()
                                    variableUseByContribution[event.contributionId] =
                                        VariableUse(
                                            argumentNames = argumentNames,
                                            sourceOccurrencePaths =
                                                if (argumentNames.isEmpty()) {
                                                    emptySet()
                                                } else {
                                                    setOfNotNull(event.consumerCoordinate)
                                                },
                                            sourceOccurrenceIds =
                                                if (argumentNames.isEmpty()) {
                                                    emptySet()
                                                } else {
                                                    buildSet {
                                                        event.selection.key.arguments
                                                            .usedVariables()
                                                            .mapNotNullTo(this) { variable ->
                                                                variable.instanceId
                                                                    ?.resolverOccurrenceId
                                                            }
                                                    }
                                                },
                                        )
                                }
                                is Resolver25LifecycleEvent.DemandGrounded -> {
                                    val variableUse =
                                        variableUseByContribution
                                            .getValue(event.contributionId)
                                    variableUseByCoordinate.merge(
                                        event.coordinate,
                                        variableUse,
                                    ) { existing, incoming ->
                                        existing + incoming
                                    }
                                }
                                is Resolver25LifecycleEvent.ResolverStarted -> {
                                    pendingApplications +=
                                        event.coordinate to
                                            (variableUseByCoordinate[event.coordinate]
                                                ?: VariableUse.EMPTY)
                                }
                                else -> Unit
                            }
                        }
                        pendingApplications.forEach { (coordinate, variableUse) ->
                            applicationObserver(
                                ResolverBenchmarkApplicationObservation(
                                    occurrencePath = coordinate,
                                    resolverOccurrenceId =
                                        ResolverOccurrenceId.at(result, coordinate),
                                    variableArgumentCount = variableUse.argumentNames.size,
                                    variableSourceOccurrenceIds =
                                        variableUse.sourceOccurrenceIds +
                                            variableUse.sourceOccurrencePaths.map { path ->
                                                ResolverOccurrenceId.at(result, path)
                                            },
                                ),
                            )
                        }
                        result
                    }
                },
        )

    private data class VariableUse(
        val argumentNames: Set<String>,
        val sourceOccurrencePaths: Set<List<PathComponent>>,
        val sourceOccurrenceIds: Set<ResolverOccurrenceId>,
    ) {
        operator fun plus(other: VariableUse): VariableUse =
            VariableUse(
                argumentNames = argumentNames + other.argumentNames,
                sourceOccurrencePaths =
                    sourceOccurrencePaths + other.sourceOccurrencePaths,
                sourceOccurrenceIds =
                    sourceOccurrenceIds + other.sourceOccurrenceIds,
            )

        companion object {
            val EMPTY = VariableUse(emptySet(), emptySet(), emptySet())
        }
    }

    @Setup(Level.Invocation)
    fun prepareOverheadInvocation(parameters: BenchmarkParams) {
        if (parameters.benchmark.endsWith(".overhead")) {
            support.prepareOverheadInvocation(loopCount)
        }
    }

    @TearDown(Level.Trial)
    fun reportOverheadStatistics(parameters: BenchmarkParams) {
        if (parameters.benchmark.endsWith(".overhead")) {
            support.reportOverheadStatistics()
        }
    }

    @Benchmark
    fun full(): Int = support.full()

    @Benchmark
    fun overhead(blackhole: Blackhole): Int = support.overhead(blackhole)
}
