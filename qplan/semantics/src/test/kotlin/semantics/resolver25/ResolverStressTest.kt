package semantics.resolver25

import java.time.Duration
import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.fragmentFrom
import model.objectOf
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import semantics.arbitrary.ArbitraryRegistry
import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.InterfacesEnabled
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.QueryScalarFieldWeight
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromFieldProviderPathLength
import semantics.arbitrary.ResolverFromFieldVariableOwnerLimit
import semantics.arbitrary.ResolverFromFieldVariableOwnerUseWeight
import semantics.arbitrary.ResolverFromFieldVariableUseDepth
import semantics.arbitrary.ResolverNestedProviderPathWeight
import semantics.arbitrary.ResolverTestCase
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.ResolverVariablesOnNonQueryFieldsOnly
import semantics.arbitrary.ResolverVariablesOnQueryFieldsOnly
import semantics.arbitrary.RootQueryFieldCount
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.SometimesPassiveFieldWeight
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.UnionsEnabled
import semantics.arbitrary.allowedResolverClosure
import semantics.arbitrary.checkResolverTestCases
import semantics.arbitrary.registeredResolverOccurrenceCounts
import semantics.arbitrary.registeredResolverOccurrences
import semantics.contract.registeredResolverApplicationIdentityCounts
import semantics.contract.validateFromFieldBindings
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Resolver25-only stress profiles for the shapes excluded from its ordinary generated contract.
 *
 * The configured case budget is split evenly across these independent tests so one failure does
 * not prevent JUnit from exercising the other profiles.
 */
class ResolverStressTest {
    @Test
    fun `nested provider with null intermediate is stressed`(): Unit =
        runProfile(
            profile = "nested-provider-null",
            config =
                directUseConfig +
                    (NullableTypeWeight to 1.0) +
                    (NullValueWeight to 0.7) +
                    (ErrorValueWeight to 0.0) +
                    (ResolverNestedProviderPathWeight to 1.0) +
                    (ResolverFromFieldProviderPathLength to 2..3),
            targetFields = ArbitraryRegistry::nullIntermediateFromObjectFieldVariableOwnerFields,
            preemptiveTimeout = true,
        )

    @Test
    fun `nested provider with error intermediate is stressed`(): Unit =
        runProfile(
            profile = "nested-provider-error",
            config =
                directUseConfig +
                    (NullableTypeWeight to 0.0) +
                    (NullValueWeight to 0.0) +
                    (ErrorValueWeight to 1.0) +
                    (ResolverNestedProviderPathWeight to 1.0) +
                    (ResolverFromFieldProviderPathLength to 2..3),
            targetFields = ArbitraryRegistry::errorIntermediateFromObjectFieldVariableOwnerFields,
            preemptiveTimeout = true,
        )

    @Test
    fun `path variable owner in a descendant list element is stressed`(): Unit =
        runProfile(
            profile = "descendant-list-owner",
            config =
                directUseConfig +
                    (ListTypeWeight to 1.0) +
                    (ListValueSize to 1..2) +
                    (ResolverVariablesOnQueryFieldsOnly to false) +
                    (ResolverVariablesOnNonQueryFieldsOnly to true),
            targetFields = { registry ->
                registry.fromObjectFieldVariableOwnerFields.filterTo(linkedSetOf()) { field ->
                    field.typeName != "Query"
                }
            },
            successfulActivation = { world, result, _, targets ->
                context(world) {
                    result
                        .registeredResolverOccurrences(world.resolverRegistry)
                        .any { occurrence ->
                            occurrence.canonicalField in targets &&
                                occurrence.occurrencePath.any { component ->
                                    component is ListEngineResult.Index
                                }
                        }
                }
            },
        )

    @Test
    fun `nested path variable use is stressed`(): Unit =
        runProfile(
            profile = "nested-variable-use",
            config =
                baseConfig +
                    (NullableTypeWeight to 0.0) +
                    (NullValueWeight to 0.0) +
                    (ErrorValueWeight to 0.0) +
                    (QueryScalarFieldWeight to 0.45) +
                    (ResolverFragmentDepth to 3) +
                    (ResolverNestedProviderPathWeight to 0.0) +
                    (ResolverFromFieldProviderPathLength to 1..1) +
                    (ResolverFromFieldVariableUseDepth to 2..3) +
                    (ResolverFromFieldVariableOwnerLimit to 1) +
                    (ResolverVariablesOnQueryFieldsOnly to true) +
                    (SometimesPassiveFieldWeight to 0.25),
            targetFields = ArbitraryRegistry::nestedFromObjectFieldVariableUseOwnerFields,
        )

    @Test
    fun `multiple path variable owners with acyclic generated order are stressed`(): Unit =
        runProfile(
            profile = "multiple-variable-owners",
            config =
                directUseConfig +
                    (RootQueryFieldCount to 6..8) +
                    (ResolverVariableCount to 1..1) +
                    (ResolverFromFieldVariableOwnerLimit to 4) +
                    (ResolverFromFieldVariableOwnerUseWeight to 1.0),
            targetFields = { registry ->
                registry.fromObjectFieldVariableOwnerDependencies
                    .flatMapTo(linkedSetOf()) { dependency ->
                        listOf(dependency.first, dependency.second)
                    }
            },
            minimumQueryTargets = 2,
        )

    private fun runProfile(
        profile: String,
        config: Config,
        targetFields: (ArbitraryRegistry) -> Set<FieldCoordinate>,
        minimumQueryTargets: Int = 1,
        preemptiveTimeout: Boolean = false,
        successfulActivation: (
            Assumptions,
            ObjectEngineResult,
            ResolverTestCase,
            Set<FieldCoordinate>,
        ) -> Boolean = { _, _, _, _ -> true },
    ): Unit =
        runBlocking {
            val requestedCases = configuredCases()
            val casesPerProfile = requestedCases / PROFILE_COUNT
            val counts =
                TestCaseCount(
                    schemas = casesPerProfile / CASES_PER_SCHEMA,
                    registriesPerSchema = REGISTRIES_PER_SCHEMA,
                    queriesPerSchema = QUERIES_PER_SCHEMA,
                )
            val seed = configuredSeed()
            var generatedArgumentVariables = 0
            var generatedObjectPathVariables = 0
            var generatedMixedVariableOwnerCases = 0
            var generatedSometimesPassiveFields = 0
            var maximumProviderPathLength = 0
            var maximumVariableUseDepth = 0
            var shapeQualifiedCases = 0
            var queryActivatedCases = 0
            var argumentVariableActivatedCases = 0
            var mixedVariableOwnerActivatedCases = 0
            var activatedSometimesPassiveOccurrences = 0
            var runtimeActivatedCases = 0
            var completedCases = 0

            try {
                val run =
                    checkResolverTestCases(
                        counts = counts,
                        config = config,
                        profile = "resolver25-stress-$profile",
                        seed = seed,
                    ) { testWorld, testCase ->
                        generatedArgumentVariables +=
                            testCase.registry.features.fromArgumentVariableCount
                        generatedObjectPathVariables +=
                            testCase.registry.features.fromObjectFieldVariableCount
                        generatedSometimesPassiveFields +=
                            testCase.registry.features.sometimesPassiveFieldCount
                        val mixedVariableOwners =
                            testCase.registry.fromArgumentVariableOwnerFields.intersect(
                                testCase.registry.fromObjectFieldVariableOwnerFields,
                            )
                        if (mixedVariableOwners.isNotEmpty()) {
                            generatedMixedVariableOwnerCases += 1
                        }
                        maximumProviderPathLength =
                            maxOf(
                                maximumProviderPathLength,
                                testCase.registry.features.maximumFromObjectFieldPathLength,
                            )
                        maximumVariableUseDepth =
                            maxOf(
                                maximumVariableUseDepth,
                                testCase.registry.features.maximumFromObjectFieldVariableUseDepth,
                            )
                        val targets = targetFields(testCase.registry)
                        if (targets.isEmpty()) return@checkResolverTestCases
                        shapeQualifiedCases += 1

                        val world = testWorld.newAssumptions(selectiveResolvers = true)
                        val fragment = world.fragmentFrom(testCase.query.source)
                        val queryTargets =
                            targets.intersect(
                                fragment.subselections
                                    .allowedResolverClosure(world.resolverRegistry)
                                    .canonicalFields,
                            )
                        if (queryTargets.size < minimumQueryTargets) {
                            return@checkResolverTestCases
                        }
                        queryActivatedCases += 1

                        testCase.registry.clearResolutionWitness()
                        var completedObservation: Resolver25ResolutionObservation? = null
                        if (preemptiveTimeout) {
                            assertTimeoutPreemptively(RESOLUTION_TIMEOUT) {
                                completedObservation =
                                    observeWithLifecycleValidation(
                                        world = world,
                                        root = world.objectOf("Query"),
                                        selections = fragment.subselections,
                                    )
                            }
                        } else {
                            completedObservation =
                                observeWithLifecycleValidation(
                                    world = world,
                                    root = world.objectOf("Query"),
                                    selections = fragment.subselections,
                                )
                        }
                        val observation = requireNotNull(completedObservation)
                        val result = observation.result
                        val witness = testCase.registry.resolutionWitness()
                        val activatedFields =
                            witness.applications.mapTo(linkedSetOf()) { application ->
                                testCase.registry.sourceResolverCoordinate(application.key.field)
                            }
                        val activatedTargets = activatedFields.intersect(queryTargets)
                        if (activatedTargets.isNotEmpty()) {
                            runtimeActivatedCases += 1
                        }
                        if (
                            activatedFields.any(
                                testCase.registry.fromArgumentVariableOwnerFields::contains,
                            )
                        ) {
                            argumentVariableActivatedCases += 1
                        }
                        if (activatedFields.any(mixedVariableOwners::contains)) {
                            mixedVariableOwnerActivatedCases += 1
                        }

                        if (profile in SOMETIMES_PASSIVE_PROFILES) {
                            val resultOccurrenceCounts =
                                context(world) {
                                    result.registeredResolverOccurrenceCounts(
                                        world.resolverRegistry,
                                    )
                                }
                            witness.applicationCounts().forEach { (key, count) ->
                                assertTrue(
                                    count <= resultOccurrenceCounts.getOrDefault(key, 0),
                                    "Observed $count applications of $key but result contains only " +
                                        "${resultOccurrenceCounts.getOrDefault(key, 0)} occurrences",
                                )
                            }
                            activatedSometimesPassiveOccurrences +=
                                resultOccurrenceCounts.values.sum() - witness.applications.size
                        } else {
                            assertEquals(
                                context(world) {
                                    result.registeredResolverApplicationIdentityCounts()
                                },
                                witness.applicationIdentityCounts(),
                            )
                        }
                        assertTrue(context(world) { result.correctResolution(fragment) })
                        context(world) {
                            result.validateFromFieldBindings(
                                observation.appliedResolverOccurrences,
                            )
                        }
                        if (successfulActivation(world, result, testCase, queryTargets)) {
                            completedCases += 1
                        }
                    }

                run.assertAggregate(
                    shapeQualifiedCases > 0,
                    "Resolver25 $profile stress generated no qualifying shape",
                )
                if (resolver25StressRequiresArgumentVariableActivation(profile)) {
                    run.assertAggregate(
                        generatedArgumentVariables > 0,
                        "Resolver25 $profile stress generated no argument variables",
                    )
                }
                run.assertAggregate(
                    queryActivatedCases > 0,
                    "Resolver25 $profile stress activated no qualifying shape from a query",
                )
                if (resolver25StressRequiresArgumentVariableActivation(profile)) {
                    run.assertAggregate(
                        argumentVariableActivatedCases > 0,
                        "Resolver25 $profile stress activated no argument-variable resolver",
                    )
                }
                run.assertAggregate(
                    runtimeActivatedCases > 0,
                    "Resolver25 $profile stress invoked no qualifying resolver",
                )
                run.assertAggregate(
                    completedCases > 0,
                    "Resolver25 $profile stress completed no qualifying case",
                )
                if (profile in SOMETIMES_PASSIVE_PROFILES) {
                    run.assertAggregate(
                        generatedSometimesPassiveFields > 0,
                        "Resolver25 $profile stress generated no sometimes-passive fields",
                    )
                    run.assertAggregate(
                        activatedSometimesPassiveOccurrences > 0,
                        "Resolver25 $profile stress activated no sometimes-passive fields",
                    )
                }
            } finally {
                println(
                    "Resolver25 $profile stress: seed=$seed, requestedCases=$casesPerProfile, " +
                        "generatedArgumentVariables=$generatedArgumentVariables, " +
                        "generatedObjectPathVariables=$generatedObjectPathVariables, " +
                        "generatedMixedVariableOwnerCases=$generatedMixedVariableOwnerCases, " +
                        "generatedSometimesPassiveFields=$generatedSometimesPassiveFields, " +
                        "maximumProviderPathLength=$maximumProviderPathLength, " +
                        "maximumVariableUseDepth=$maximumVariableUseDepth, " +
                        "shapeQualifiedCases=$shapeQualifiedCases, " +
                        "queryActivatedCases=$queryActivatedCases, " +
                        "argumentVariableActivatedCases=$argumentVariableActivatedCases, " +
                        "mixedVariableOwnerActivatedCases=$mixedVariableOwnerActivatedCases, " +
                        "activatedSometimesPassiveOccurrences=" +
                        "$activatedSometimesPassiveOccurrences, " +
                        "runtimeActivatedCases=$runtimeActivatedCases, " +
                        "completedCases=$completedCases",
                )
            }
        }

    private fun configuredCases(): Int {
        val requested =
            configured("resolver25.stress.cases", "RESOLVER25_STRESS_CASES")
                .toIntOrNull()
                ?: error("resolver25.stress.cases/RESOLVER25_STRESS_CASES must be an integer")
        require(requested >= PROFILE_COUNT * CASES_PER_SCHEMA)
        require(requested % (PROFILE_COUNT * CASES_PER_SCHEMA) == 0) {
            "Resolver25 stress cases must be a multiple of ${PROFILE_COUNT * CASES_PER_SCHEMA}"
        }
        return requested
    }

    private fun configuredSeed(): Long =
        configured("resolver25.stress.seed", "RESOLVER25_STRESS_SEED")
            .toLongOrNull()
            ?: error("resolver25.stress.seed/RESOLVER25_STRESS_SEED must be a Long")

    private fun configured(
        property: String,
        environment: String,
    ): String =
        System.getProperty(property)
            ?: System.getenv(environment)
            ?: error("Set $property or $environment; use the :semantics:resolver25Stress task")

    private companion object {
        const val PROFILE_COUNT = 5
        val SOMETIMES_PASSIVE_PROFILES = setOf("nested-variable-use")
        const val REGISTRIES_PER_SCHEMA = 2
        const val QUERIES_PER_SCHEMA = 5
        const val CASES_PER_SCHEMA = REGISTRIES_PER_SCHEMA * QUERIES_PER_SCHEMA
        val RESOLUTION_TIMEOUT: Duration = Duration.ofSeconds(3)

        val baseConfig: Config =
            Config.default +
                (MinimumSelectionDepth to 2) +
                (MaxSelectionDepth to 4) +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 4..6) +
                (QueryFieldCount to 6..8) +
                (RootQueryFieldCount to 3..4) +
                (DuplicateSelectionWeight to 0.0) +
                (FieldArgumentWeight to 0.65) +
                (ExplicitFieldResolverWeight to 0.9) +
                (InterfacesEnabled to false) +
                (UnionsEnabled to false) +
                (NodeResolversEnabled to false) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 2..4)

        val directUseConfig: Config =
            baseConfig +
                (ListTypeWeight to 0.0) +
                (QueryScalarFieldWeight to 0.45) +
                (ResolverFragmentDepth to 1) +
                (ResolverFromFieldProviderPathLength to 1..1) +
                (ResolverFromFieldVariableUseDepth to 1..1) +
                (ResolverFromFieldVariableOwnerLimit to 1) +
                (ResolverVariablesOnQueryFieldsOnly to true) +
                (ResolverVariablesOnNonQueryFieldsOnly to false)
    }
}

internal fun resolver25StressRequiresArgumentVariableActivation(profile: String): Boolean =
    profile != "multiple-variable-owners"
