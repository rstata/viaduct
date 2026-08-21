package semantics.resolver26

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
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType
import semantics.benchmark.CurrentProfileBenchmarkSupport
import semantics.benchmark.DEFAULT_OVERHEAD_LOOP_COUNT
import semantics.benchmark.DEFAULT_OVERHEAD_QUERY_COUNT
import semantics.benchmark.DEFAULT_OVERHEAD_QUERY_SEED
import semantics.benchmark.ObservedResolverBenchmarkSubject
import semantics.benchmark.ResolverBenchmarkApplicationObservation
import semantics.benchmark.ResolverBenchmarkSubject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import jdk.jfr.Configuration
import jdk.jfr.Recording

private const val PROFILE_OUTPUT_PROPERTY = "resolver26OverheadProfileOutput"
private const val PROFILE_RECORDING_NAME = "resolver26-overhead-measurement"

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
                    context(world) {
                        resolveObserved(selections) { observation ->
                            applicationObserver(
                                ResolverBenchmarkApplicationObservation(
                                    occurrencePath = observation.occurrencePath,
                                    occurrenceStamp = observation.occurrenceStamp,
                                    variableArgumentCount =
                                        observation.variableArgumentCount,
                                    variableSourceOccurrencePaths = emptySet(),
                                    variableSourceSelectionStamps =
                                        observation.variableSourceSelectionStamps,
                                ),
                            )
                        }
                    }
                },
        )

    private var profileRecording: Recording? = null

    @Setup(Level.Invocation)
    fun prepareOverheadInvocation(
        parameters: BenchmarkParams,
        iterationParams: IterationParams,
    ) {
        if (parameters.benchmark.endsWith(".overhead")) {
            support.prepareOverheadInvocation(queryCount, querySeed, loopCount)
            startOverheadProfile(iterationParams)
        }
    }

    @TearDown(Level.Invocation)
    fun stopOverheadProfile() {
        profileRecording?.let { recording ->
            profileRecording = null
            recording.stop()
            recording.close()
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

    private fun startOverheadProfile(iterationParams: IterationParams) {
        val output = System.getProperty(PROFILE_OUTPUT_PROPERTY) ?: return
        if (iterationParams.type != IterationType.MEASUREMENT) {
            return
        }

        val destination = Path.of(output).toAbsolutePath()
        destination.parent?.let(Files::createDirectories)
        Files.deleteIfExists(destination)
        profileRecording =
            Recording(Configuration.getConfiguration("profile")).apply {
                name = PROFILE_RECORDING_NAME
                setDestination(destination)
                start()
            }
    }
}
