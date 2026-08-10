package semantics.contract

import io.kotest.property.PropertyTesting
import kotlinx.coroutines.runBlocking
import model.fragmentFrom
import model.objectOf
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NodeObjectWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.NodeResolversEnabled
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
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in high-volume coverage for deep dependency-heavy generated worlds. */
interface DeepResolverStressContract : ResolverContract {
    val resolverName: String
    val objectPathVariablesEnabled: Boolean
        get() = false
    val nodeResolversEnabled: Boolean
        get() = true
    val mixedVariableCoverageRequired: Boolean
        get() = false

    @Test
    fun `deep dependency-heavy arbitrary worlds resolve correctly`(): Unit =
        runBlocking {
            val requestedCases =
                configured(stressCasesProperty, stressCasesEnvironment)
                    .toIntOrNull()
                    ?.also { cases ->
                        require(cases >= MINIMUM_STRESS_CASES && cases % CASES_PER_SCHEMA == 0) {
                            "$stressCasesProperty/$stressCasesEnvironment must be at least " +
                                "$MINIMUM_STRESS_CASES and a multiple of $CASES_PER_SCHEMA"
                        }
                    }
                    ?: error("$stressCasesProperty/$stressCasesEnvironment must be an integer")
            val seed =
                configured(stressSeedProperty, stressSeedEnvironment)
                    .toLongOrNull()
                    ?: error("$stressSeedProperty/$stressSeedEnvironment must be a Long")
            val counts =
                TestCaseCount(
                    schemas = requestedCases / CASES_PER_SCHEMA,
                    registriesPerSchema = REGISTRIES_PER_SCHEMA,
                    queriesPerSchema = QUERIES_PER_SCHEMA,
                )
            val config =
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
                    (NodeResolversEnabled to nodeResolversEnabled) +
                    // Static tests exhaustively cover dispatch; stress samples interactions cheaply.
                    (NodeObjectWeight to 0.05) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariablesEnabled to objectPathVariablesEnabled)
            var attemptedCases = 0
            var verifiedCases = 0
            var resolverApplications = 0
            var generatedNodeResolvers = 0
            var nodeLoaderApplications = 0
            var argumentBearingNodeBridgeProducerApplications = 0
            var polymorphicNodeLoaderApplications = 0
            var generatedFromArgumentVariables = 0
            var generatedObjectPathVariables = 0
            var activatedFromArgumentApplications = 0
            var activatedObjectPathApplications = 0
            var coactivatedMixedVariableCases = 0
            val previousSeed = PropertyTesting.defaultSeed
            PropertyTesting.defaultSeed = seed

            try {
                checkResolverTestCases(
                    counts,
                    config,
                    profile = "$resolverName-stress",
                ) { testWorld, testCase ->
                    attemptedCases += 1
                    generatedNodeResolvers += testCase.registry.nodeResolverTypes.size
                    generatedFromArgumentVariables +=
                        testCase.registry.features.fromArgumentVariableCount
                    generatedObjectPathVariables +=
                        testCase.registry.features.fromObjectFieldVariableCount
                    assertTrue(testCase.query.selectionDepth >= 4)
                    val world = testWorld.newAssumptions()
                    val fragment = world.fragmentFrom(testCase.query.source)
                    testCase.registry.clearResolutionWitness()
                    val result =
                        resolve(
                            world,
                            world.objectOf("Query"),
                            fragment.subselections,
                        )
                    val witness = testCase.registry.resolutionWitness()
                    assertEquals(
                        context(world) {
                            result.registeredResolverApplicationIdentityCounts()
                        },
                        witness.applicationIdentityCounts(),
                    )
                    assertTrue(context(world) { result.correctResolution(fragment) })
                    resolverApplications += witness.applications.size
                    var activatedFromArgument = false
                    var activatedObjectPath = false
                    witness.applications.forEach { application ->
                        if (
                            testCase.registry.sourceResolverHasFromArgumentVariables(
                                application.key.field,
                            )
                        ) {
                            activatedFromArgumentApplications += 1
                            activatedFromArgument = true
                        }
                        if (
                            testCase.registry.sourceResolverHasFromObjectFieldVariables(
                                application.key.field,
                            )
                        ) {
                            activatedObjectPathApplications += 1
                            activatedObjectPath = true
                        }
                        if (
                            application.key.field.fieldName.endsWith("\$bridge") &&
                            application.key.arguments.type.fields.isNotEmpty()
                        ) {
                            argumentBearingNodeBridgeProducerApplications += 1
                        }
                        val possibleTypes =
                            testCase.registry.nodeLoaderPossibleTypes(
                                testCase.schema,
                                application.key.field,
                        )
                        if (possibleTypes.isNotEmpty()) {
                            nodeLoaderApplications += 1
                            if (possibleTypes.size > 1) {
                                polymorphicNodeLoaderApplications += 1
                            }
                        }
                    }
                    if (activatedFromArgument && activatedObjectPath) {
                        coactivatedMixedVariableCases += 1
                    }
                    verifiedCases += 1
                }
            } finally {
                PropertyTesting.defaultSeed = previousSeed
                println(
                    "$displayName stress: seed=$seed, requestedCases=$requestedCases, " +
                        "attemptedCases=$attemptedCases, verifiedCases=$verifiedCases, " +
                        "resolverApplications=$resolverApplications, " +
                        "generatedNodeResolvers=$generatedNodeResolvers, " +
                        "nodeLoaderApplications=$nodeLoaderApplications, " +
                        "argumentBearingNodeBridgeProducerApplications=" +
                        "$argumentBearingNodeBridgeProducerApplications, " +
                        "polymorphicNodeLoaderApplications=" +
                        "$polymorphicNodeLoaderApplications, " +
                        "generatedFromArgumentVariables=$generatedFromArgumentVariables, " +
                        "generatedObjectPathVariables=$generatedObjectPathVariables, " +
                        "activatedFromArgumentApplications=$activatedFromArgumentApplications, " +
                        "activatedObjectPathApplications=$activatedObjectPathApplications, " +
                        "coactivatedMixedVariableCases=$coactivatedMixedVariableCases, " +
                        "minimumDepth=4",
                )
            }

            assertEquals(requestedCases, attemptedCases)
            assertEquals(requestedCases, verifiedCases)
            assertTrue(resolverApplications >= requestedCases)
            if (nodeResolversEnabled) {
                assertTrue(generatedNodeResolvers >= requestedCases / 100)
                assertTrue(nodeLoaderApplications >= requestedCases / 1_000)
                assertTrue(
                    argumentBearingNodeBridgeProducerApplications >= requestedCases / 1_000,
                )
            } else {
                assertEquals(0, generatedNodeResolvers)
                assertEquals(0, nodeLoaderApplications)
                assertEquals(0, argumentBearingNodeBridgeProducerApplications)
            }
            if (objectPathVariablesEnabled) {
                assertTrue(generatedObjectPathVariables > 0)
                assertTrue(activatedObjectPathApplications > 0)
            }
            if (mixedVariableCoverageRequired) {
                assertTrue(generatedFromArgumentVariables > 0)
                assertTrue(generatedObjectPathVariables > 0)
                assertTrue(activatedFromArgumentApplications > 0)
                assertTrue(activatedObjectPathApplications > 0)
                assertTrue(coactivatedMixedVariableCases > 0)
            }
        }

    private fun configured(
        property: String,
        environment: String,
    ): String =
        System.getProperty(property)
            ?: System.getenv(environment)
            ?: error("Set $property or $environment; use the :semantics:${resolverName}Stress task")

    private val displayName: String
        get() = resolverName.replaceFirstChar(Char::uppercase)

    private val stressCasesEnvironment: String
        get() = "${resolverName.uppercase()}_STRESS_CASES"

    private val stressCasesProperty: String
        get() = "$resolverName.stress.cases"

    private val stressSeedEnvironment: String
        get() = "${resolverName.uppercase()}_STRESS_SEED"

    private val stressSeedProperty: String
        get() = "$resolverName.stress.seed"

    private companion object {
        const val REGISTRIES_PER_SCHEMA = 2
        const val QUERIES_PER_SCHEMA = 5
        const val CASES_PER_SCHEMA = REGISTRIES_PER_SCHEMA * QUERIES_PER_SCHEMA
        const val MINIMUM_STRESS_CASES = 10_000
    }
}
