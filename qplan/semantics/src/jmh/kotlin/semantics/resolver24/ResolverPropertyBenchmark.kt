package semantics.resolver24

import kotlinx.coroutines.runBlocking
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.objectOf
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Timeout
import org.openjdk.jmh.annotations.Warmup
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NodeObjectWeight
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.contract.registeredResolverApplicationIdentityCounts
import semantics.contract.validateObjectPathBindings
import semantics.correctresolution.correctResolution
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
@Timeout(time = 10, timeUnit = TimeUnit.MINUTES)
open class ResolverPropertyBenchmark {
    @Benchmark
    fun propertyTest100x2x5(): Int =
        runBlocking {
            var verifiedCases = 0
            val run =
                checkResolverTestCases(
                    counts = COUNTS,
                    config = CONFIG,
                    profile = "resolver24-benchmark",
                    seed = 1L,
                ) { testWorld, testCase ->
                    check(testCase.query.selectionDepth >= 4)
                    val world = testWorld.newAssumptions()
                    val fragment = world.fragmentFrom(testCase.query.source)
                    testCase.registry.clearResolutionWitness()
                    val result =
                        context(world) {
                            world.objectOf("Query").resolve(fragment.subselections)
                        }
                    val witness = testCase.registry.resolutionWitness()
                    check(
                        context(world) {
                            result.registeredResolverApplicationIdentityCounts()
                        } == witness.applicationIdentityCounts(),
                    )
                    check(
                        context(world) {
                            result.correctResolution(
                                fragment.subselections
                                    .merge(world.schema.query)
                                    .instantiateBindings(),
                            )
                        },
                    )
                    context(world) {
                        result.validateObjectPathBindings()
                    }
                    verifiedCases += 1
                }
            check(run.attemptedCases == EXPECTED_CASES)
            check(verifiedCases == EXPECTED_CASES)
            verifiedCases
        }

    private companion object {
        const val EXPECTED_CASES = 1_000

        val COUNTS =
            TestCaseCount(
                schemas = 100,
                registriesPerSchema = 2,
                queriesPerSchema = 5,
            )

        val CONFIG =
            Config.default +
                (MinimumSelectionDepth to 4) +
                (MaxSelectionDepth to 6) +
                (SchemaObjectCount to 4..5) +
                (ObjectFieldCount to 3..5) +
                (QueryFieldCount to 2..4) +
                (FieldArgumentWeight to 0.65) +
                (ExplicitFieldResolverWeight to 0.7) +
                (NullableTypeWeight to 0.15) +
                (NullValueWeight to 0.05) +
                (ErrorValueWeight to 0.02) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 0.85) +
                (ResolverFragmentDepth to 3) +
                (NodeResolversEnabled to true) +
                (NodeObjectWeight to 0.05) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariablesEnabled to true)
    }
}
