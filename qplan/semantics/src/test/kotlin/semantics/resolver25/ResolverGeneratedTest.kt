package semantics.resolver25

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.InputObjectsEnabled
import semantics.arbitrary.InputScalarValueRange
import semantics.arbitrary.InterfacesEnabled
import semantics.arbitrary.ListsEnabled
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromObjectFieldPassiveUseWeight
import semantics.arbitrary.ResolverFromObjectFieldProviderPathLength
import semantics.arbitrary.ResolverFromObjectFieldVariableUseDepth
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerLimit
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverLiteralVariableConvergenceWeight
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.ResolverVariablesOnNonQueryFieldsOnly
import semantics.arbitrary.ResolverVariablesOnQueryFieldsOnly
import semantics.arbitrary.RootQueryFieldCount
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.UnionsEnabled
import semantics.arbitrary.checkResolverTestCases
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.GeneratedCaseAssertions
import semantics.contract.MixedVariableGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentFromObjectPathGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.ResolverResolutionObservation
import semantics.contract.assertAll
import semantics.contract.observeGeneratedCase
import semantics.contract.resolver25StructuralSignatures

class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    ObjectFragmentFromObjectPathGeneratedResolverContract,
    MixedVariableGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract {
    override val alwaysGeneratesTypename: Boolean
        get() = true

    override val selectiveResolvers: Boolean
        get() = true

    override val generatedCaseAssertions =
        GeneratedCaseAssertions.defaultGeneratedContract +
            GeneratedCaseAssertions.exactOrdinaryApplicationCounts +
            GeneratedCaseAssertions.objectPathBindings

    @Test
    fun `generated literal and variable selections converge at runtime`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (SchemaObjectCount to 4..6) +
                    (ObjectFieldCount to 5..7) +
                    (QueryFieldCount to 8..10) +
                    (RootQueryFieldCount to 8..10) +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (InputObjectsEnabled to false) +
                    (InputScalarValueRange to 0..2) +
                    (InterfacesEnabled to false) +
                    (ListsEnabled to false) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFragmentDepth to 3) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverLiteralVariableConvergenceWeight to 1.0) +
                    (ResolverVariableCount to 1..1) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to false) +
                    (UnionsEnabled to false)

            suspend fun runProfile(
                coverage: ConvergenceCoverage,
                seed: Long? = null,
            ) = checkResolverTestCases(
                counts = CONVERGENCE_COVERAGE_COUNTS,
                config = config,
                profile = "resolver25-literal-variable-convergence",
                seed = seed,
            ) { testWorld, testCase ->
                coverage.generated +=
                    testCase.registry.features.literalVariableConvergenceCount
                val observation =
                    observeGeneratedCase(testWorld, testCase)
                        .assertAll(
                            generatedCaseAssertions +
                                GeneratedCaseAssertions.exactOrdinaryApplicationCounts,
                        )
                if (
                    Resolver25StructuralSignature.LITERAL_VARIABLE_KEY_CONVERGENCE in
                    observation.ordinary.lifecycleEvents.resolver25StructuralSignatures()
                ) {
                    coverage.activated += 1
                }
            }

            val sampledCoverage = ConvergenceCoverage()
            val sampledRun = runProfile(sampledCoverage)
            if (sampledRun.selectedCase != null) return@runBlocking
            val (activationRun, activationCoverage) =
                if (sampledRun.seed == STRUCTURAL_ACTIVATION_SEED) {
                    sampledRun to sampledCoverage
                } else {
                    val coverage = ConvergenceCoverage()
                    runProfile(coverage, STRUCTURAL_ACTIVATION_SEED) to coverage
                }
            activationRun.assertAggregate(
                activationCoverage.generated > 0,
                "Resolver25 convergence profile generated no literal/variable candidates",
            )
            activationRun.assertAggregate(
                activationCoverage.activated > 0,
                "Resolver25 convergence profile activated no equal grounded key",
            )
        }

    @Test
    fun `generated path variables activate below passive branches`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (SchemaObjectCount to 5..7) +
                    (ObjectFieldCount to 5..7) +
                    (QueryFieldCount to 6..8) +
                    (RootQueryFieldCount to 6..8) +
                    (FieldArgumentWeight to 0.8) +
                    (ExplicitFieldResolverWeight to 0.35) +
                    (ErrorValueWeight to 0.0) +
                    (InterfacesEnabled to false) +
                    (ListsEnabled to false) +
                    (NodeResolversEnabled to false) +
                    (NullableTypeWeight to 0.0) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFragmentDepth to 3) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverFromObjectFieldPassiveUseWeight to 1.0) +
                    (ResolverFromObjectFieldProviderPathLength to 1..1) +
                    (ResolverFromObjectFieldVariableOwnerLimit to 1) +
                    (ResolverFromObjectFieldVariableUseDepth to 2..3) +
                    (ResolverVariableCount to 1..1) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to true) +
                    (ResolverVariablesOnNonQueryFieldsOnly to true) +
                    (UnionsEnabled to false)

            suspend fun runProfile(
                coverage: PassiveUseCoverage,
                seed: Long? = null,
            ) = checkResolverTestCases(
                counts = PASSIVE_USE_COVERAGE_COUNTS,
                config = config,
                profile = "resolver25-passive-variable-use",
                seed = seed,
            ) { testWorld, testCase ->
                val passiveOwners =
                    testCase.registry.passiveTopLevelFromObjectFieldVariableUseOwnerFields
                coverage.generated += passiveOwners.size
                val observation =
                    observeGeneratedCase(testWorld, testCase)
                        .assertAll(
                            generatedCaseAssertions +
                                GeneratedCaseAssertions.exactOrdinaryApplicationCounts +
                                GeneratedCaseAssertions.objectPathBindings,
                        )
                val events = observation.ordinary.lifecycleEvents
                val activatedOwner =
                    events
                        .filterIsInstance<Resolver25LifecycleEvent.BindingDeclared>()
                        .any { event ->
                            event.source is Resolver25BindingSource.FromObjectField &&
                                passiveOwners.any { owner ->
                                    owner.typeName == event.variable.field.containingType.typeName &&
                                        owner.fieldName == event.variable.field.fieldName
                                }
                        }
                if (
                    activatedOwner &&
                    Resolver25StructuralSignature.NESTED_VARIABLE_USE in
                    events.resolver25StructuralSignatures()
                ) {
                    coverage.activated += 1
                }
            }

            val sampledCoverage = PassiveUseCoverage()
            val sampledRun = runProfile(sampledCoverage)
            if (sampledRun.selectedCase != null) return@runBlocking
            val (activationRun, activationCoverage) =
                if (sampledRun.seed == PASSIVE_USE_ACTIVATION_SEED) {
                    sampledRun to sampledCoverage
                } else {
                    val coverage = PassiveUseCoverage()
                    runProfile(coverage, PASSIVE_USE_ACTIVATION_SEED) to coverage
                }
            activationRun.assertAggregate(
                activationCoverage.generated > 0,
                "Resolver25 passive-use profile generated no passive variable branches",
            )
            activationRun.assertAggregate(
                activationCoverage.activated > 0,
                "Resolver25 passive-use profile activated no passive variable branch",
            )
        }

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        resolveWithLifecycleValidation(world, root, selections)

    override fun observeResolution(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ResolverResolutionObservation =
        observeWithLifecycleValidation(world, root, selections)

    private val semantics.contract.GeneratedResolutionObservation.lifecycleEvents:
        List<Resolver25LifecycleEvent>
        get() =
            (subject as Resolver25ResolutionObservation).lifecycleEvents

    private companion object {
        const val STRUCTURAL_ACTIVATION_SEED = 1L
        const val PASSIVE_USE_ACTIVATION_SEED = 3L

        val CONVERGENCE_COVERAGE_COUNTS =
            TestCaseCount(
                schemas = 2,
                registriesPerSchema = 1,
                queriesPerSchema = 3,
            )

        val PASSIVE_USE_COVERAGE_COUNTS =
            TestCaseCount(
                schemas = 10,
                registriesPerSchema = 3,
                queriesPerSchema = 1,
            )
    }
}

private data class ConvergenceCoverage(
    var generated: Int = 0,
    var activated: Int = 0,
)

private data class PassiveUseCoverage(
    var generated: Int = 0,
    var activated: Int = 0,
)
