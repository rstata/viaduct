package semantics.resolver25

import model.ObjectEngineResult

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
import model.Stamp
import model.variableArgumentNames
import model.variableSourceSelectionStamps
import semantics.benchmark.CurrentProfileBenchmarkSupport
import semantics.benchmark.DEFAULT_OVERHEAD_LOOP_COUNT
import semantics.benchmark.DEFAULT_OVERHEAD_QUERY_COUNT
import semantics.benchmark.DEFAULT_OVERHEAD_QUERY_SEED
import semantics.benchmark.ObservedResolverBenchmarkSubject
import semantics.benchmark.ResolverBenchmarkApplicationObservation
import semantics.benchmark.ResolverBenchmarkSubject
import java.util.concurrent.ConcurrentHashMap
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
    @Param("$DEFAULT_OVERHEAD_QUERY_COUNT")
    var queryCount: Int = DEFAULT_OVERHEAD_QUERY_COUNT

    @JvmField
    @Param("$DEFAULT_OVERHEAD_QUERY_SEED")
    var querySeed: Long = DEFAULT_OVERHEAD_QUERY_SEED

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
                    context(world) {
                        resolveObserved(selections) { event ->
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
                                            sourceSelectionStamps =
                                                event.selection.key.arguments
                                                    .variableSourceSelectionStamps(),
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
                                    (
                                        variableUseByCoordinate[event.coordinate]
                                            ?: VariableUse.EMPTY
                                    )
                                        .let { variableUse ->
                                            applicationObserver(
                                                ResolverBenchmarkApplicationObservation(
                                                    occurrencePath = event.coordinate,
                                                    occurrenceStamp =
                                                        (
                                                            event.coordinate.lastOrNull()
                                                                as? ObjectEngineResult.GroundKey
                                                        )?.stamp as? Stamp.Occurrence,
                                                    variableArgumentCount =
                                                        variableUse.argumentNames.size,
                                                    variableSourceOccurrencePaths =
                                                        variableUse.sourceOccurrencePaths,
                                                    variableSourceSelectionStamps =
                                                        variableUse.sourceSelectionStamps,
                                                ),
                                            )
                                        }
                                }
                                else -> Unit
                            }
                        }
                    }
                },
        )

    private data class VariableUse(
        val argumentNames: Set<String>,
        val sourceOccurrencePaths: Set<List<PathComponent>>,
        val sourceSelectionStamps: Set<Stamp.Occurrence>,
    ) {
        operator fun plus(other: VariableUse): VariableUse =
            VariableUse(
                argumentNames = argumentNames + other.argumentNames,
                sourceOccurrencePaths =
                    sourceOccurrencePaths + other.sourceOccurrencePaths,
                sourceSelectionStamps =
                    sourceSelectionStamps + other.sourceSelectionStamps,
            )

        companion object {
            val EMPTY = VariableUse(emptySet(), emptySet(), emptySet())
        }
    }

    @Setup(Level.Invocation)
    fun prepareOverheadInvocation(parameters: BenchmarkParams) {
        if (parameters.benchmark.endsWith(".overhead")) {
            support.prepareOverheadInvocation(queryCount, querySeed, loopCount)
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
