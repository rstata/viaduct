package semantics.resolver26

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineOutputData
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.Fragment
import model.fragmentFrom
import model.outputValue
import model.schemaType
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MaxOutputListDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.ParentFieldsEnabled
import semantics.arbitrary.RandomParentFieldsEnabled
import semantics.arbitrary.FieldCoordinate
import semantics.contract.RegisteredResolverOccurrence
import semantics.arbitrary.ResolutionOccurrenceApplicationLog
import semantics.arbitrary.ResolutionWitness
import semantics.arbitrary.ResolverFromQueryFieldVariablesEnabled
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverTestExecution
import semantics.arbitrary.ResolverTestRun
import semantics.arbitrary.SometimesPassiveFieldWeight
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.configuredResolverTestExecution
import semantics.arbitrary.executeResolverTestCases
import semantics.arbitrary.isGeneratedRandomParentField
import semantics.contract.registeredResolverOccurrences
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCountsFor
import semantics.contract.registeredResolverOccurrenceApplicationKeyCounts
import semantics.contract.validateFromFieldBindings
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import semantics.shared.OperationContext
import semantics.shared.RecordingResolverObserver
import viaduct.engine.api.EngineObjectData

/**
 * Unfiltered Resolver26 stress: every generated registry/query product is resolved and validated.
 */
class ResolverBroadStressTest {
    @Test
    fun `generated great-grandparent demand resolves across randomized worlds`(): Unit =
        runBlocking {
            val counts = TestCaseCount(schemas = 10, registriesPerSchema = 5, queriesPerSchema = 5)
            val completed =
                runResolver26BroadStress(
                    requiredSignatures =
                        setOf(
                            Resolver26StructuralSignature.GREAT_GRANDPARENT_PARENT_DEMAND,
                        ),
                    propertyProfile = "resolver26-parent-fields",
                    counts = counts,
                    config =
                        Config.default +
                            (ParentFieldsEnabled to true) +
                            (MinimumSelectionDepth to 2) +
                            (MaxSelectionDepth to 6) +
                            (RandomParentFieldsEnabled to true) +
                            (ResolverFragmentsEnabled to true) +
                            (ResolverFragmentWeight to 1.0) +
                            (ResolverFragmentDepth to 3) +
                            (NodeResolversEnabled to false) +
                            (MaxOutputListDepth to 2) +
                            (ListValueSize to 1..2) +
                            (NullValueWeight to 0.0) +
                            (ErrorValueWeight to 0.0),
                    seed = 2026090403L,
                    execution = ResolverTestExecution(counts),
                )

            assertEquals(250, completed)
        }

    @Test
    fun `broad full-feature worlds resolve correctly`(): Unit =
        runBlocking {
            val broadProfile: Resolver26BroadStressProfile = configuredProfile()
            runResolver26BroadStress(
                requiredSignatures = broadProfile.requiredSignatures,
                propertyProfile = broadProfile.propertyProfile,
                counts = configuredCounts(broadProfile),
                config = broadProfile.config,
                seed = configuredSeed(),
            )
        }

    // Returns the named generator distribution selected for this run.
    private fun configuredProfile(): Resolver26BroadStressProfile {
        val configured: String =
            System.getProperty(PROFILE_PROPERTY)
                ?: System.getenv(PROFILE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.profile")
                ?: Resolver26BroadStressProfile.BALANCED.propertyProfile
        return Resolver26BroadStressProfile.fromConfigured(configured)
    }

    // Returns the configured S:R:Q product dimensions.
    private fun configuredCounts(
        broadProfile: Resolver26BroadStressProfile,
    ): TestCaseCount {
        val configured: String =
            System.getProperty(SIZE_PROPERTY)
                ?: System.getenv(SIZE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.size")
                ?: broadProfile.defaultSize
        val dimensions: List<String> = configured.split(':')
        require(dimensions.size == 3) {
            "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have S:R:Q form: $configured"
        }
        val parsed: List<Int> =
            dimensions.map { dimension ->
                dimension.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: error(
                        "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have positive dimensions: " +
                            configured,
                    )
            }
        return TestCaseCount(
            schemas = parsed[0],
            registriesPerSchema = parsed[1],
            queriesPerSchema = parsed[2],
        )
    }

    // Returns the explicit seed required for reproducible broad generation.
    private fun configuredSeed(): Long {
        val configured: String =
            System.getProperty(SEED_PROPERTY)
                ?: System.getenv(SEED_ENVIRONMENT)
                ?: System.getProperty("resolver.property.seed")
                ?: error(
                    "Set $SEED_PROPERTY or $SEED_ENVIRONMENT; use the " +
                        ":semantics:resolver26BroadStress task",
                )
        return configured.toLongOrNull()
            ?: error("$SEED_PROPERTY/$SEED_ENVIRONMENT must be a Long: $configured")
    }

    private companion object {
        const val PROFILE_PROPERTY = "resolver26.broad.stress.profile"
        const val PROFILE_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_PROFILE"
        const val SIZE_PROPERTY = "resolver26.broad.stress.size"
        const val SIZE_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_SIZE"
        const val SEED_PROPERTY = "resolver26.broad.stress.seed"
        const val SEED_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_SEED"
    }
}

// Resolves and independently validates every case in one Resolver26 generated product.
internal suspend fun runResolver26BroadStress(
    requiredSignatures: Set<Resolver26StructuralSignature>,
    propertyProfile: String,
    counts: TestCaseCount,
    config: Config,
    seed: Long,
    execution: ResolverTestExecution = configuredResolverTestExecution(counts, propertyProfile),
): Int {
    val startedAt: Long = System.nanoTime()
    var attemptedCases = 0
    var resolutionCalls = 0
    var completedCases = 0
    var resolverApplications = 0
    var generatedArgumentVariables = 0
    var generatedObjectPathVariables = 0
    var generatedQueryPathVariables = 0
    var activatedArgumentVariableApplications = 0
    var activatedObjectPathVariableApplications = 0
    var activatedQueryPathVariableApplications = 0
    var maximumProviderPathLength = 0
    var maximumVariableUseDepth = 0
    var generatedSometimesPassiveFields = 0
    var activatedSometimesPassiveOccurrences = 0
    var generatedQueryFragments = 0
    var activatedQueryFragmentApplications = 0
    var activatedParentDemandApplications = 0
    var materializedParentFieldActivations = 0
    var materializedRandomParentFieldActivations = 0
    val materializedParentFieldDepths: MutableMap<Int, Int> = linkedMapOf()
    val materializedParentFields: MutableSet<FieldCoordinate> = linkedSetOf()
    val parentCoverageLock = Any()
    val observedSignatures: MutableSet<Resolver26StructuralSignature> = linkedSetOf()

    try {
        val run: ResolverTestRun =
            executeResolverTestCases(
                execution = execution,
                config = config,
                profile = propertyProfile,
                seed = seed,
            ) { testWorld, testCase ->
                attemptedCases += 1
                generatedArgumentVariables +=
                    testCase.registry.features.fromArgumentVariableCount
                generatedObjectPathVariables +=
                    testCase.registry.features.fromObjectFieldVariableCount
                generatedQueryPathVariables +=
                    testCase.registry.features.fromQueryFieldVariableCount
                generatedSometimesPassiveFields +=
                    testCase.registry.features.sometimesPassiveFieldCount
                generatedQueryFragments += testCase.registry.features.queryFragmentCount
                maximumProviderPathLength =
                    maxOf(
                        maximumProviderPathLength,
                        testCase.registry.features.maximumFromObjectFieldPathLength,
                        testCase.registry.features.maximumFromQueryFieldPathLength,
                    )
                maximumVariableUseDepth =
                    maxOf(
                        maximumVariableUseDepth,
                        testCase.registry.features.maximumFromObjectFieldVariableUseDepth,
                        testCase.registry.features.maximumFromQueryFieldVariableUseDepth,
                    )

                val world: Assumptions =
                    testWorld.newAssumptions(selectiveResolvers = true)
                val fragment: Fragment = world.fragmentFrom(testCase.query.source)
                val operation =
                    OperationContext(world, resolverObserver = RecordingResolverObserver())
                testCase.registry.clearResolutionWitness()
                val occurrenceLog = ResolutionOccurrenceApplicationLog()
                resolutionCalls += 1
                val result: ObjectEngineResult =
                    context(operation) {
                        resolveObserved(fragment.subselections) { application ->
                            val parentActivations =
                                application.input.materializedParentFieldActivations(
                                    application.inputSelections,
                                )
                            synchronized(parentCoverageLock) {
                                materializedParentFieldActivations += parentActivations.size
                                materializedRandomParentFieldActivations +=
                                    parentActivations.count { activation ->
                                        activation.field.isGeneratedRandomParentField()
                                    }
                                parentActivations.forEach { activation ->
                                    materializedParentFields += activation.field
                                    materializedParentFieldDepths[activation.depth] =
                                        materializedParentFieldDepths.getOrDefault(
                                            activation.depth,
                                            0,
                                        ) + 1
                                }
                            }
                            occurrenceLog.record(
                                resolverOccurrenceId = application.resolverOccurrenceId,
                                occurrencePath = application.occurrencePath,
                                field =
                                    FieldCoordinate(
                                        application.field.containingDef.name,
                                        application.field.name,
                                    ),
                                arguments = application.arguments,
                                input = application.input,
                                suppliedDemand = application.suppliedDemand,
                            )
                        }
                    }
                val witness: ResolutionWitness = testCase.registry.resolutionWitness()
                val occurrenceWitness = occurrenceLog.snapshot()
                assertEquals(
                    witness.applicationIdentityCounts(),
                    occurrenceWitness.applications
                        .groupingBy { application -> application.application.identity }
                        .eachCount(),
                    "Resolver26 occurrence instrumentation missed an application",
                )
                val occurrences: List<RegisteredResolverOccurrence> =
                    context(operation) {
                        result.registeredResolverOccurrences(operation.resolverRegistry)
                    }
                observedSignatures +=
                    resolver26StructuralSignatures(
                        occurrences = occurrences,
                        witness = witness,
                        registry = testCase.registry,
                    )
                resolverApplications += witness.applications.size
                witness.applications.forEach { application ->
                    val sourceField =
                        testCase.registry.sourceResolverCoordinate(application.key.field)
                    if (
                        testCase.registry.parentDemandOwnerFields.getOrDefault(sourceField, 0) >= 3
                    ) {
                        activatedParentDemandApplications += 1
                    }
                    if (
                        testCase.registry.queryFragmentSources[
                            testCase.registry.sourceResolverCoordinate(application.key.field)
                        ]?.isNotEmpty() == true
                    ) {
                        activatedQueryFragmentApplications += 1
                    }
                    if (
                        testCase.registry.sourceResolverHasFromArgumentVariables(
                            application.key.field,
                        )
                    ) {
                        activatedArgumentVariableApplications += 1
                    }
                    if (
                        testCase.registry.sourceResolverHasFromObjectFieldVariables(
                            application.key.field,
                        )
                    ) {
                        activatedObjectPathVariableApplications += 1
                    }
                    if (
                        testCase.registry.sourceResolverHasFromQueryFieldVariables(
                            application.key.field,
                        )
                    ) {
                        activatedQueryPathVariableApplications += 1
                    }
                }

                val observedOccurrenceCounts = occurrenceWitness.applicationIdentityCounts()
                if (config[SometimesPassiveFieldWeight] > 0.0) {
                    val expectedOccurrenceKeyCounts =
                        context(operation) {
                            result.registeredResolverOccurrenceApplicationKeyCounts()
                        }
                    occurrenceWitness.applicationKeyCounts().forEach { (key, count) ->
                        assertTrue(
                            count <= expectedOccurrenceKeyCounts.getOrDefault(key, 0),
                            "Observed $count applications of $key but request results " +
                                "contain only " +
                                "${expectedOccurrenceKeyCounts.getOrDefault(key, 0)} " +
                                "matching occurrences",
                        )
                    }
                    val expectedObservedIdentities =
                        context(operation) {
                            result.registeredResolverOccurrenceApplicationIdentityCountsFor(
                                occurrenceWitness.applications
                                    .map { application -> application.resolverOccurrenceId }
                                    .toSet(),
                            )
                        }
                    assertEquals(expectedObservedIdentities, observedOccurrenceCounts)
                    activatedSometimesPassiveOccurrences +=
                        expectedOccurrenceKeyCounts.values.sum() -
                            occurrenceWitness.applications.size
                } else {
                    val expectedOccurrenceCounts =
                        context(operation) {
                            result.registeredResolverOccurrenceApplicationIdentityCounts()
                        }
                    assertEquals(
                        expectedOccurrenceCounts,
                        observedOccurrenceCounts,
                    )
                }
                assertTrue(
                    context(operation) {
                        result.correctResolution(fragment)
                    },
                )
                context(operation) {
                    result.validateFromFieldBindings(
                        occurrenceWitness.applications
                            .map { application -> application.resolverOccurrenceId }
                            .toSet(),
                    )
                }
                completedCases += 1
            }

        assertEquals(run.expectedCases, run.attemptedCases)
        assertEquals(run.expectedCases, attemptedCases)
        assertEquals(run.expectedCases, resolutionCalls)
        assertEquals(run.expectedCases, completedCases)
        run.assertAggregate(
            observedSignatures.containsAll(requiredSignatures),
            "Resolver26 profile $propertyProfile missed required signatures: " +
                "${requiredSignatures - observedSignatures}; " +
                "observed=$observedSignatures",
        )
        if (config[SometimesPassiveFieldWeight] > 0.0) {
            run.assertAggregate(
                generatedSometimesPassiveFields > 0 &&
                    activatedSometimesPassiveOccurrences > 0,
                "Resolver26 profile $propertyProfile did not activate sometimes-passive fields",
            )
        }
        if (config[ResolverQueryFragmentsEnabled]) {
            run.assertAggregate(
                generatedQueryFragments > 0 && activatedQueryFragmentApplications > 0,
                "Resolver26 profile $propertyProfile did not activate query fragments",
            )
        }
        if (config[ResolverFromQueryFieldVariablesEnabled]) {
            run.assertAggregate(
                generatedQueryPathVariables > 0 &&
                    activatedQueryPathVariableApplications > 0,
                "Resolver26 profile $propertyProfile did not activate FromQueryField variables",
            )
        }
        if (config[ParentFieldsEnabled]) {
            run.assertAggregate(
                activatedParentDemandApplications > 0,
                "Resolver26 profile $propertyProfile did not activate great-grandparent demand",
            )
        }
        if (config[RandomParentFieldsEnabled]) {
            run.assertAggregate(
                materializedRandomParentFieldActivations > 0,
                "Resolver26 profile $propertyProfile did not materialize a random parent field",
            )
        }
        return completedCases
    } finally {
        println(
            "Resolver26 broad stress: profile=$propertyProfile, seed=$seed, " +
                "size=${counts.summary()}, " +
                "attemptedCases=$attemptedCases, resolutionCalls=$resolutionCalls, " +
                "completedCases=$completedCases, " +
                "resolverApplications=$resolverApplications, " +
                "generatedArgumentVariables=$generatedArgumentVariables, " +
                "generatedObjectPathVariables=$generatedObjectPathVariables, " +
                "generatedQueryPathVariables=$generatedQueryPathVariables, " +
                "activatedArgumentVariableApplications=" +
                "$activatedArgumentVariableApplications, " +
                "activatedObjectPathVariableApplications=" +
                "$activatedObjectPathVariableApplications, " +
                "activatedQueryPathVariableApplications=" +
                "$activatedQueryPathVariableApplications, " +
                "maximumProviderPathLength=$maximumProviderPathLength, " +
                "maximumVariableUseDepth=$maximumVariableUseDepth, " +
                "generatedSometimesPassiveFields=$generatedSometimesPassiveFields, " +
                "activatedSometimesPassiveOccurrences=" +
                "$activatedSometimesPassiveOccurrences, " +
                "generatedQueryFragments=$generatedQueryFragments, " +
                "activatedQueryFragmentApplications=$activatedQueryFragmentApplications, " +
                "activatedParentDemandApplications=$activatedParentDemandApplications, " +
                "materializedParentFieldActivations=$materializedParentFieldActivations, " +
                "materializedRandomParentFieldActivations=" +
                "$materializedRandomParentFieldActivations, " +
                "distinctMaterializedParentFields=${materializedParentFields.size}, " +
                "materializedParentFieldDepths=$materializedParentFieldDepths, " +
                "signatures=$observedSignatures, " +
                "elapsedMillis=${(System.nanoTime() - startedAt) / 1_000_000}",
        )
    }
}

// Returns compact S:R:Q dimensions for diagnostics.
private fun TestCaseCount.summary(): String =
    "$schemas:$registriesPerSchema:$queriesPerSchema"

private data class MaterializedParentFieldActivation(
    val field: FieldCoordinate,
    val depth: Int,
)

private fun EngineOutputData?.materializedParentFieldActivations(
    selections: MaterializeSelectionForest,
    parentDepth: Int = 0,
): List<MaterializedParentFieldActivation> =
    when (this) {
        is EngineObjectData.Sync ->
            selections
                .collect(schemaType)
                .byResponseKey()
                .flatMap { (responseKey, selection) ->
                    val isParent = selection.key is ObjectEngineResult.ParentKey
                    val nextParentDepth = if (isParent) parentDepth + 1 else 0
                    buildList {
                        if (isParent) {
                            add(
                                MaterializedParentFieldActivation(
                                    field =
                                        FieldCoordinate(
                                            selection.key.field.containingDef.name,
                                            selection.key.field.name,
                                        ),
                                    depth = nextParentDepth,
                                ),
                            )
                        }
                        addAll(
                            outputValue(responseKey).materializedParentFieldActivations(
                                selections = selection.subselections,
                                parentDepth = nextParentDepth,
                            ),
                        )
                    }
                }

        is List<*> ->
            flatMap { value ->
                value.materializedParentFieldActivations(selections, parentDepth)
            }

        else -> emptyList()
    }
