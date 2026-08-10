package semantics.contract

import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.fragmentFrom
import model.objectOf
import model.sameCompletedResultAs
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import semantics.arbitrary.ArbitraryRegistry
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ImplementationArgumentDefaultWeight
import semantics.arbitrary.NodeObjectWeight
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverApplicationRecord
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverTestCase
import semantics.arbitrary.ResolverTestRun
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.RootQueryFieldCount
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generated contract for user-declared resolvers with empty object fragments and no variables.
 */
interface EmptyObjectFragmentGeneratedResolverContract : ResolverContract {
    @Test
    fun `generated empty object fragment worlds resolve correctly`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false)

            checkGeneratedProfile("empty-object-fragment", config) { testWorld, testCase ->
                assertTrue(testCase.registry.nodeResolverTypes.isEmpty())
                assertTrue(testCase.registry.objectFragmentSources.values.all(String::isEmpty))
                assertEquals(0, testCase.registry.features.variableCount)
                assertGeneratedResolutionParity(testWorld, testCase)
            }
        }
}

/**
 * Generated contract for source-level node resolution through fixture-lowered loaders.
 */
interface NodeGeneratedResolverContract : ResolverContract {
    @Test
    fun `generated node worlds resolve correctly`(): Unit =
        runBlocking {
            var generatedNodeResolvers = 0
            var nodeLoaderApplications = 0
            var generatedMixedTopologyCases = 0
            var activatedMixedTopologyCases = 0
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to true) +
                    (NodeObjectWeight to 0.35) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false)

            val run = checkGeneratedProfile("node", config) { testWorld, testCase ->
                assertTrue(testCase.registry.objectFragmentSources.values.all(String::isEmpty))
                assertEquals(0, testCase.registry.features.variableCount)
                generatedNodeResolvers += testCase.registry.nodeResolverTypes.size
                val nonNodeTypes =
                    testCase.schema.domainObjectTypeNames -
                        testCase.registry.nodeResolverTypes
                if (
                    testCase.registry.nodeResolverTypes.isNotEmpty() &&
                    nonNodeTypes.isNotEmpty()
                ) {
                    generatedMixedTopologyCases += 1
                }

                val resolution = assertGeneratedResolutionParity(testWorld, testCase)
                val activatedNodeLoader =
                    resolution.applications.any { application ->
                        testCase.registry
                            .nodeLoaderPossibleTypes(
                                testCase.schema,
                                application.key.field,
                            ).isNotEmpty()
                    }
                if (activatedNodeLoader) {
                    nodeLoaderApplications += 1
                }
                if (
                    activatedNodeLoader &&
                    testCase.registry.nodeResolverTypes.isNotEmpty() &&
                    nonNodeTypes.isNotEmpty()
                ) {
                    activatedMixedTopologyCases += 1
                }
            }

            run.assertAggregate(
                generatedNodeResolvers > 0,
                "Generated node profile produced no node resolvers",
            )
            run.assertAggregate(
                nodeLoaderApplications > 0,
                "Generated node profile activated no fixture-lowered node loaders",
            )
            run.assertAggregate(
                generatedMixedTopologyCases > 0,
                "Generated node profile produced no mixed node/non-node schemas",
            )
            run.assertAggregate(
                activatedMixedTopologyCases > 0,
                "Generated node profile activated no node loaders in mixed schemas",
            )
        }
}

/**
 * Generated contract for nonempty object fragments without variables.
 */
interface ObjectFragmentGeneratedResolverContract : ResolverContract {
    @Test
    fun `generated object fragment worlds without variables resolve correctly`(): Unit =
        runBlocking {
            var generatedNonemptyFragments = 0
            var activatedNonemptyFragments = 0
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false)

            val run = checkGeneratedProfile("object-fragment", config) { testWorld, testCase ->
                assertTrue(testCase.registry.nodeResolverTypes.isEmpty())
                assertEquals(0, testCase.registry.features.variableCount)
                generatedNonemptyFragments +=
                    testCase.registry.objectFragmentSources.values.count(String::isNotEmpty)

                val resolution = assertGeneratedResolutionParity(testWorld, testCase)
                activatedNonemptyFragments +=
                    resolution.applications.count { application ->
                        testCase.registry.hasNonemptyObjectFragment(application)
                    }
            }

            run.assertAggregate(
                generatedNonemptyFragments > 0,
                "Generated object-fragment profile produced no nonempty fragments",
            )
            run.assertAggregate(
                activatedNonemptyFragments > 0,
                "Generated object-fragment profile activated no nonempty fragments",
            )
        }
}

/**
 * Generated contract for nonempty object fragments with variables bound from resolver arguments.
 */
interface ObjectFragmentFromArgumentGeneratedResolverContract : ResolverContract {
    @Test
    fun `generated object fragment worlds with fromArgument resolve correctly`(): Unit =
        runBlocking {
            var generatedFromArgumentVariables = 0
            var activatedFromArgumentApplications = 0
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to false)

            val run =
                checkGeneratedProfile(
                    "object-fragment-from-argument",
                    config,
                ) { testWorld, testCase ->
                    assertTrue(testCase.registry.nodeResolverTypes.isEmpty())
                    assertEquals(
                        testCase.registry.features.fromArgumentVariableCount,
                        testCase.registry.features.variableCount,
                    )
                    generatedFromArgumentVariables +=
                        testCase.registry.features.fromArgumentVariableCount

                    val resolution = assertGeneratedResolutionParity(testWorld, testCase)
                    activatedFromArgumentApplications +=
                        resolution.applications.count { application ->
                            testCase.registry.sourceResolverHasFromArgumentVariables(
                                application.key.field,
                            )
                        }
                }

            run.assertAggregate(
                generatedFromArgumentVariables > 0,
                "Generated FromArgument profile produced no FromArgument variables",
            )
            run.assertAggregate(
                activatedFromArgumentApplications > 0,
                "Generated FromArgument profile activated no variable-bearing resolvers",
            )
        }
}

/** Generated contract isolating variables read from object-fragment paths. */
interface ObjectFragmentFromObjectPathGeneratedResolverContract : ResolverContract {
    @Test
    fun `generated object fragment worlds with fromObjectField resolve correctly`(): Unit =
        runBlocking {
            var generatedVariables = 0
            var activatedApplications = 0
            val config =
                Config.default +
                    (SchemaObjectCount to 4..6) +
                    (ObjectFieldCount to 4..6) +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (QueryFieldCount to 6..6) +
                    (RootQueryFieldCount to 10..10) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to true)

            val run =
                checkGeneratedProfile(
                    "object-fragment-from-object-field",
                    config,
                ) { testWorld, testCase ->
                    val features = testCase.registry.features
                    assertEquals(
                        features.fromObjectFieldVariableCount,
                        features.variableCount,
                    )
                    generatedVariables += features.fromObjectFieldVariableCount
                    val resolution = assertGeneratedResolutionParity(testWorld, testCase)
                    activatedApplications +=
                        resolution.applications.count { application ->
                            testCase.registry.sourceResolverHasFromObjectFieldVariables(
                                application.key.field,
                            )
                        }
                }

            run.assertAggregate(
                generatedVariables > 0,
                "Generated FromObjectField profile produced no path variables",
            )
            run.assertAggregate(
                activatedApplications > 0,
                "Generated FromObjectField profile activated no variable-bearing resolvers",
            )
        }
}

/** Generated contract for interactions between both resolver-variable sources. */
interface MixedVariableGeneratedResolverContract : ResolverContract {
    @Test
    fun `generated mixed resolver variable worlds resolve correctly`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (SchemaObjectCount to 3..4) +
                    (ObjectFieldCount to 3..4) +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (QueryFieldCount to 4..4) +
                    (RootQueryFieldCount to 10..10) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to true)

            fun property(
                coverage: MixedVariableCoverage,
            ): suspend (TestWorld, ResolverTestCase) -> Unit =
                { testWorld, testCase ->
                    coverage.generatedFromArgument +=
                        testCase.registry.features.fromArgumentVariableCount
                    coverage.generatedFromObjectField +=
                        testCase.registry.features.fromObjectFieldVariableCount
                    val resolution = assertGeneratedResolutionParity(testWorld, testCase)
                    val fromArgument =
                        resolution.applications.any { application ->
                            testCase.registry.sourceResolverHasFromArgumentVariables(
                                application.key.field,
                            )
                        }
                    val fromObjectField =
                        resolution.applications.any { application ->
                            testCase.registry.sourceResolverHasFromObjectFieldVariables(
                                application.key.field,
                            )
                        }
                    if (fromArgument && fromObjectField) {
                        coverage.coactivatedCases += 1
                    }
                }

            val sampledCoverage = MixedVariableCoverage()
            val run =
                checkGeneratedProfile(
                    profile = "mixed-variables",
                    config = config,
                    property = property(sampledCoverage),
                )
            if (run.selectedCase == null) {
                val activationCoverage: MixedVariableCoverage
                val activationRun: ResolverTestRun
                if (run.seed == MIXED_VARIABLE_ACTIVATION_SEED) {
                    activationCoverage = sampledCoverage
                    activationRun = run
                } else {
                    activationCoverage = MixedVariableCoverage()
                    activationRun =
                        checkGeneratedProfile(
                            profile = "mixed-variables",
                            config = config,
                            seed = MIXED_VARIABLE_ACTIVATION_SEED,
                            property = property(activationCoverage),
                        )
                }

                activationRun.assertAggregate(
                    activationCoverage.generatedFromArgument > 0 &&
                        activationCoverage.generatedFromObjectField > 0,
                    "Mixed-variable activation corpus did not generate both variable kinds",
                )
                activationRun.assertAggregate(
                    activationCoverage.coactivatedCases > 0,
                    "Mixed-variable activation corpus did not coactivate both variable kinds",
                )
            }
        }
}

/**
 * Generated interaction-depth contract for the full supported feature combination.
 *
 * The narrower contracts isolate failures by capability. This contract preserves broad randomized
 * pressure across nodes, object fragments, arguments, and `FromArgument` variables together.
 */
interface FeatureInteractionGeneratedResolverContract : ResolverContract {
    @Test
    fun `generated full feature interactions resolve correctly`(): Unit =
        runBlocking {
            var generatedFromArgumentVariables = 0
            var generatedMixedTopologyCases = 0
            var activatedMixedTopologyCases = 0
            var coactivatedNodeAndFromArgumentCases = 0
            var activatedImplementationDefaults = 0
            val config =
                Config.default +
                    (ImplementationArgumentDefaultWeight to 1.0) +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to true) +
                    (NodeObjectWeight to 0.35) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to false)

            val run = checkGeneratedFeatureInteractionProfile(config) { testWorld, testCase ->
                val registry = testCase.registry
                val nonNodeTypes =
                    testCase.schema.domainObjectTypeNames - registry.nodeResolverTypes
                generatedFromArgumentVariables +=
                    registry.features.fromArgumentVariableCount
                if (
                    registry.nodeResolverTypes.isNotEmpty() &&
                    nonNodeTypes.isNotEmpty()
                ) {
                    generatedMixedTopologyCases += 1
                }
                if (testCase.query.features.hasAbstractImplementationDefaultSelection) {
                    activatedImplementationDefaults += 1
                }

                val resolution = assertGeneratedResolutionParity(testWorld, testCase)
                val activatedNodeLoader =
                    resolution.applications.any { application ->
                        registry
                            .nodeLoaderPossibleTypes(
                                testCase.schema,
                                application.key.field,
                            ).isNotEmpty()
                    }
                val activatedFromArgument =
                    resolution.applications.any { application ->
                        registry.sourceResolverHasFromArgumentVariables(
                            application.key.field,
                        )
                    }
                val activatedNonNodeObject =
                    resolution.applications.any { application ->
                        application.key.field.typeName in nonNodeTypes
                    }

                if (activatedNodeLoader && activatedNonNodeObject) {
                    activatedMixedTopologyCases += 1
                }
                if (activatedNodeLoader && activatedFromArgument) {
                    coactivatedNodeAndFromArgumentCases += 1
                }
            }

            run.assertAggregate(
                generatedFromArgumentVariables > 0,
                "Feature-interaction profile produced no FromArgument variables",
            )
            run.assertAggregate(
                generatedMixedTopologyCases > 0,
                "Feature-interaction profile produced no mixed node/non-node schemas",
            )
            run.assertAggregate(
                activatedMixedTopologyCases > 0,
                "Feature-interaction profile activated no mixed node/non-node schemas",
            )
            run.assertAggregate(
                coactivatedNodeAndFromArgumentCases > 0,
                "Feature-interaction profile never coactivated a node loader and FromArgument",
            )
            run.assertAggregate(
                activatedImplementationDefaults > 0,
                "Feature-interaction profile activated no abstract implementation defaults",
            )
        }
}

private const val GENERATED_PROFILE_CASE_BUDGET = 150
private const val FEATURE_INTERACTION_CASE_BUDGET = 300
private const val MIXED_VARIABLE_ACTIVATION_SEED = 1L

private val GENERATED_PROFILE_COUNTS =
    TestCaseCount(
        schemas = 10,
        registriesPerSchema = 3,
        queriesPerSchema = 5,
    )

private val FEATURE_INTERACTION_PROFILE_COUNTS =
    TestCaseCount(
        schemas = 20,
        registriesPerSchema = 3,
        queriesPerSchema = 5,
    )

private data class GeneratedResolution(
    val result: EngineResult.Object,
    val applications: List<ResolverApplicationRecord>,
)

private data class MixedVariableCoverage(
    var generatedFromArgument: Int = 0,
    var generatedFromObjectField: Int = 0,
    var coactivatedCases: Int = 0,
)

private suspend fun checkGeneratedProfile(
    profile: String,
    config: Config,
    seed: Long? = null,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun =
    checkGeneratedCases(
        profile = profile,
        counts = GENERATED_PROFILE_COUNTS,
        expectedCases = GENERATED_PROFILE_CASE_BUDGET,
        config = config,
        seed = seed,
        property = property,
    )

private suspend fun checkGeneratedFeatureInteractionProfile(
    config: Config,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun =
    checkGeneratedCases(
        profile = "feature-interaction",
        counts = FEATURE_INTERACTION_PROFILE_COUNTS,
        expectedCases = FEATURE_INTERACTION_CASE_BUDGET,
        config = config,
        property = property,
    )

private suspend fun checkGeneratedCases(
    profile: String,
    counts: TestCaseCount,
    expectedCases: Int,
    config: Config,
    seed: Long? = null,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun =
    checkResolverTestCases(
        counts = counts,
        config = config,
        profile = profile,
        seed = seed,
        property = property,
    ).also { run ->
        val effectiveExpectedCases =
            if (run.sizeOverridden) {
                run.expectedCases
            } else {
                expectedCases
            }
        run.assertAggregate(
            run.attemptedCases == effectiveExpectedCases,
            "Generated $profile profile ran ${run.attemptedCases} cases; " +
                "expected $effectiveExpectedCases",
        )
    }

private fun ResolverContract.assertGeneratedResolutionParity(
    testWorld: TestWorld,
    testCase: ResolverTestCase,
): GeneratedResolution {
    val ordinary =
        generatedResolution(
            testWorld,
            testCase.registry,
            testCase.query.source,
        )
    val permuted =
        testCase.registry.withoutResolutionWitnessCapture {
            generatedResult(
                testWorld,
                testCase.registry,
                testCase.query.permutationEquivalentSource,
            )
        }
    assertTrue(ordinary.result.sameCompletedResultAs(permuted))
    return ordinary
}

private fun ResolverContract.generatedResolution(
    testWorld: TestWorld,
    registry: ArbitraryRegistry,
    querySource: String,
): GeneratedResolution {
    registry.clearResolutionWitness()
    val result = generatedResult(testWorld, registry, querySource)
    val applications = registry.resolutionWitness().applications
    return GeneratedResolution(result, applications)
}

private fun ResolverContract.generatedResult(
    testWorld: TestWorld,
    registry: ArbitraryRegistry,
    querySource: String,
): EngineResult.Object {
    val world = testWorld.newAssumptions(selectiveResolvers)
    val fragment = world.fragmentFrom(querySource)
    val result =
        resolve(
            world,
            world.objectOf("Query"),
            fragment.subselections,
        )
    assertTrue(context(world) { result.correctResolution(fragment) })
    return result
}

private fun ArbitraryRegistry.hasNonemptyObjectFragment(
    application: ResolverApplicationRecord,
): Boolean =
    objectFragmentSources[application.key.field]?.isNotEmpty() == true
