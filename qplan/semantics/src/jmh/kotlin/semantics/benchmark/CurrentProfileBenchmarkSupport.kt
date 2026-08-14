package semantics.benchmark

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.Fragment
import model.SelectionForest
import model.SelectionStamp
import model.Value
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.objectOf
import model.ownerResolverStamp
import org.openjdk.jmh.infra.Blackhole
import semantics.arbitrary.ResolverBenchmarkCorpus
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.arbitrary.resolverBenchmarkFullConfig
import semantics.arbitrary.resolverBenchmarkOverheadQueryConfig
import semantics.contract.registeredResolverApplicationIdentityCounts
import semantics.contract.validateObjectPathBindings
import semantics.correctresolution.correctResolution
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.ceil

internal const val DEFAULT_OVERHEAD_LOOP_COUNT = 1
internal const val DEFAULT_OVERHEAD_QUERY_COUNT = 100
internal const val DEFAULT_OVERHEAD_QUERY_SEED = 1L

private const val SCHEMA_RESOURCE =
    "semantics/benchmark/current-profile/schema.graphqls"
private const val REGISTRY_RESOURCE =
    "semantics/benchmark/current-profile/registry.json"
private const val REPORT_FILE_PROPERTY = "resolverBenchmarkReportFile"

internal fun interface ResolverBenchmarkSubject {
    fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object
}

internal fun interface ObservedResolverBenchmarkSubject {
    fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
        applicationObserver: (ResolverBenchmarkApplicationObservation) -> Unit,
    ): EngineResult.Object
}

internal data class ResolverBenchmarkApplicationObservation(
    val occurrencePath: List<model.PathComponent>,
    val occurrenceStamp: SelectionStamp?,
    val variableArgumentCount: Int,
    val variableSourceOccurrencePaths: Set<List<model.PathComponent>>,
    val variableSourceSelectionStamps: Set<SelectionStamp>,
)

internal class CurrentProfileBenchmarkSupport(
    private val subject: ResolverBenchmarkSubject,
    private val observedSubject: ObservedResolverBenchmarkSubject,
) {
    private val corpus: ResolverBenchmarkCorpus =
        ResolverBenchmarkCorpus.load(SCHEMA_RESOURCE, REGISTRY_RESOURCE)

    private var overheadCases: Array<PreparedResolution> = emptyArray()
    private var overheadQuerySources: Array<String> = emptyArray()

    fun prepareOverheadInvocation(
        queryCount: Int,
        querySeed: Long,
        loopCount: Int,
    ) {
        require(loopCount > 0) { "Resolver benchmark loop count must be positive" }
        val testWorld = corpus.world()
        overheadQuerySources =
            corpus
                .generateQueries(
                    count = queryCount,
                    config = resolverBenchmarkOverheadQueryConfig(),
                    seed = querySeed,
                ).map { query -> query.source }
                .toTypedArray()
        val parsedQueries =
            overheadQuerySources.map { source ->
                testWorld.assumptions.fragmentFrom(source).subselections
            }
        overheadCases =
            Array(loopCount * parsedQueries.size) { index ->
                val selections = parsedQueries[index % parsedQueries.size]
                val world = testWorld.newAssumptions(selectiveResolvers = true)
                PreparedResolution(
                    world = world,
                    root = world.objectOf("Query"),
                    selections = selections,
                )
            }
    }

    fun overhead(blackhole: Blackhole): Int {
        check(overheadCases.isNotEmpty()) {
            "Overhead invocation was not prepared"
        }
        overheadCases.forEach { prepared ->
            blackhole.consume(
                subject.resolve(
                    world = prepared.world,
                    root = prepared.root,
                    selections = prepared.selections,
                ),
            )
        }
        return overheadCases.size
    }

    fun reportOverheadStatistics() {
        check(overheadQuerySources.isNotEmpty()) {
            "Overhead query corpus was not prepared"
        }
        val testWorld = corpus.world(captureResolutionWitness = true)
        val variableArgumentCounts = mutableListOf<Long>()
        val samples =
            overheadQuerySources.map { source ->
                val world = testWorld.newAssumptions(selectiveResolvers = true)
                val selections = world.fragmentFrom(source).subselections
                corpus.registry.clearResolutionWitness()
                val applicationObservations =
                    java.util.Collections.synchronizedList(
                        mutableListOf<ResolverBenchmarkApplicationObservation>(),
                    )
                val result =
                    observedSubject.resolve(
                        world = world,
                        root = world.objectOf("Query"),
                        selections = selections,
                        applicationObserver = applicationObservations::add,
                    )
                val witness = corpus.registry.resolutionWitness()
                check(applicationObservations.size == witness.applications.size) {
                    "Observed ${applicationObservations.size} variable-argument counts " +
                        "for ${witness.applications.size} resolver executions"
                }
                val queryVariableArgumentCounts =
                    applicationObservations.map { observation ->
                        observation.variableArgumentCount.toLong()
                    }
                variableArgumentCounts += queryVariableArgumentCounts.filter { count -> count > 0 }
                val shape = result.shape()
                OverheadSample(
                    fields = shape.fields,
                    resolverExecutions = witness.applications.size.toLong(),
                    variableBearingResolverExecutions =
                        queryVariableArgumentCounts.count { count -> count > 0 }.toLong(),
                    variableStackDepth = applicationObservations.maximumVariableStackDepth(),
                    depth = shape.depth.toLong(),
                )
            }
        val report =
            buildString {
                appendLine(
                    "Resolver overhead corpus statistics " +
                        "(${overheadQuerySources.size} queries):",
                )
                appendLine(
                    "  fields returned: " +
                        samples.statistics(OverheadSample::fields),
                )
                appendLine(
                    "  resolvers executed: " +
                        samples.statistics(OverheadSample::resolverExecutions),
                )
                appendLine(
                    "  resolver executions with variable-bearing arguments: " +
                        samples.statistics(
                            value = OverheadSample::variableBearingResolverExecutions,
                            percentile = 0.5,
                            percentileName = "p50",
                        ),
                )
                appendLine(
                    "  variable-bearing arguments per such resolver execution: " +
                        variableArgumentCounts.statistics(
                            value = { count -> count },
                        ),
                )
                appendLine(
                    "  maximum variable stack depth: " +
                        samples.statistics(
                            value = OverheadSample::variableStackDepth,
                            percentile = 0.5,
                            percentileName = "p50",
                        ),
                )
                append(
                    "  result depth: " +
                        samples.statistics(OverheadSample::depth),
                )
            }
        val reportFile = System.getProperty(REPORT_FILE_PROPERTY)
        if (reportFile == null) {
            println()
            println(report)
        } else {
            val path = Path.of(reportFile)
            path.parent?.let { parent -> Files.createDirectories(parent) }
            Files.writeString(path, report + System.lineSeparator())
        }
    }

    fun full(): Int =
        runBlocking {
            var verifiedCases = 0
            val run =
                checkResolverTestCases(
                    counts = FULL_COUNTS,
                    config = resolverBenchmarkFullConfig(),
                    profile = "resolver-benchmark-full",
                    seed = 1L,
                ) { testWorld, testCase ->
                    check(testCase.query.selectionDepth >= 4)
                    val world = testWorld.newAssumptions(selectiveResolvers = true)
                    val fragment = world.fragmentFrom(testCase.query.source)
                    testCase.registry.clearResolutionWitness()
                    val result =
                        subject.resolve(
                            world = world,
                            root = world.objectOf("Query"),
                            selections = fragment.subselections,
                        )
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
            check(run.attemptedCases == FULL_CASE_COUNT)
            check(verifiedCases == FULL_CASE_COUNT)
            verifiedCases
        }

    private data class PreparedResolution(
        val world: Assumptions,
        val root: Value.Object,
        val selections: SelectionForest,
    )

    private data class ResultShape(
        val fields: Long,
        val depth: Int,
    )

    private data class OverheadSample(
        val fields: Long,
        val resolverExecutions: Long,
        val variableBearingResolverExecutions: Long,
        val variableStackDepth: Long,
        val depth: Long,
    )

    private fun EngineResult?.shape(depth: Int = 0): ResultShape =
        when (this) {
            null, is Value.Simple -> ResultShape(fields = 0, depth = depth)
            is EngineResult.List ->
                indices
                    .map { index -> get(index).getValue().get().shape(depth) }
                    .fold(ResultShape(fields = 0, depth = depth)) { result, child ->
                        result.combine(child)
                    }
            is EngineResult.Object ->
                keys
                    .map { key ->
                        val child = getCell(key).getValue().get().shape(depth + 1)
                        child.copy(fields = child.fields + 1)
                    }.fold(ResultShape(fields = 0, depth = depth)) { result, child ->
                        result.combine(child)
                    }
        }

    private fun ResultShape.combine(other: ResultShape): ResultShape =
        ResultShape(
            fields = fields + other.fields,
            depth = maxOf(depth, other.depth),
        )

    private fun <T> List<T>.statistics(
        value: (T) -> Long,
        percentile: Double = 0.9,
        percentileName: String = "p90",
    ): String {
        val values = map(value).sorted()
        val average = values.average()
        val percentileIndex =
            ceil(values.size * percentile).toInt().coerceAtLeast(1) - 1
        return "average=%.2f, $percentileName=%d, max=%d".format(
            Locale.ROOT,
            average,
            values[percentileIndex],
            values.last(),
        )
    }

    private fun List<ResolverBenchmarkApplicationObservation>.maximumVariableStackDepth(): Long {
        val executedOccurrences = mapTo(linkedSetOf()) { observation -> observation.identity() }
        val childrenBySource =
            buildMap<ResolverOccurrenceIdentity, MutableSet<ResolverOccurrenceIdentity>> {
                this@maximumVariableStackDepth.forEach { observation ->
                    observation
                        .sourceIdentities()
                        .filter(executedOccurrences::contains)
                        .forEach { sourceIdentity ->
                            getOrPut(sourceIdentity, ::linkedSetOf)
                                .add(observation.identity())
                        }
                }
            }
        val depthByOccurrence = mutableMapOf<ResolverOccurrenceIdentity, Long>()
        val visiting = mutableSetOf<ResolverOccurrenceIdentity>()
        fun depth(identity: ResolverOccurrenceIdentity): Long {
            depthByOccurrence[identity]?.let { return it }
            check(visiting.add(identity)) {
                "Variable resolver dependency cycle at $identity"
            }
            val depth =
                childrenBySource[identity]
                    .orEmpty()
                    .maxOfOrNull { child -> 1L + depth(child) }
                    ?: 0
            visiting.remove(identity)
            depthByOccurrence[identity] = depth
            return depth
        }
        return executedOccurrences.maxOfOrNull(::depth) ?: 0
    }

    private sealed interface ResolverOccurrenceIdentity {
        data class Ordinary(
            val path: List<model.PathComponent>,
        ) : ResolverOccurrenceIdentity

        data class Stamped(
            val stamp: SelectionStamp,
        ) : ResolverOccurrenceIdentity
    }

    private fun ResolverBenchmarkApplicationObservation.identity(): ResolverOccurrenceIdentity =
        occurrenceStamp
            ?.let(ResolverOccurrenceIdentity::Stamped)
            ?: ResolverOccurrenceIdentity.Ordinary(occurrencePath)

    private fun ResolverBenchmarkApplicationObservation.sourceIdentities():
        Set<ResolverOccurrenceIdentity> =
        buildSet {
            variableSourceOccurrencePaths.mapTo(this, ResolverOccurrenceIdentity::Ordinary)
            variableSourceSelectionStamps.mapTo(this) { sourceStamp ->
                sourceStamp.ownerResolverStamp()
                    ?.let(ResolverOccurrenceIdentity::Stamped)
                    ?: ResolverOccurrenceIdentity.Ordinary(sourceStamp.resolverPath)
            }
        }

    private companion object {
        const val FULL_CASE_COUNT = 1_000

        val FULL_COUNTS =
            TestCaseCount(
                schemas = 100,
                registriesPerSchema = 2,
                queriesPerSchema = 5,
            )
    }
}
