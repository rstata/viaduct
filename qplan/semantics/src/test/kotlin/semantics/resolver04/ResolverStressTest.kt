package semantics.resolver04

import io.kotest.property.PropertyTesting
import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolutionWitness
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverProgramKind
import semantics.arbitrary.ResolverTestCase
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.conformsToFragment
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToTypename
import semantics.correctresolution.conformsToVariables
import semantics.correctresolution.concreteObjectKey
import semantics.correctresolution.correctResolution
import semantics.correctresolution.isClosedUnderResolverDemand
import semantics.correctresolution.rootedAndWellTyped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in high-volume Resolver04 coverage for deep dependency-heavy generated worlds.
 *
 * Keep expensive seeded campaigns here so ordinary static and property validation stays small.
 */
class ResolverStressTest {
    @Test
    fun `deep dependency-heavy arbitrary worlds resolve correctly`(): Unit =
        runBlocking {
            val configuredCases =
                System.getProperty(STRESS_CASES_PROPERTY)
                    ?: System.getenv(STRESS_CASES_ENV)
                    ?: error(
                        "Set $STRESS_CASES_PROPERTY or $STRESS_CASES_ENV; " +
                            "use the :semantics:resolver04Stress task",
                    )
            val requestedCases =
                requireNotNull(configuredCases.toIntOrNull()) {
                    "$STRESS_CASES_PROPERTY/$STRESS_CASES_ENV must be an integer"
                }
            val configuredSeed =
                System.getProperty(STRESS_SEED_PROPERTY)
                    ?: System.getenv(STRESS_SEED_ENV)
                    ?: error(
                        "Set $STRESS_SEED_PROPERTY or $STRESS_SEED_ENV; " +
                            "use the :semantics:resolver04Stress task",
                    )
            val seed =
                requireNotNull(configuredSeed.toLongOrNull()) {
                    "$STRESS_SEED_PROPERTY/$STRESS_SEED_ENV must be a Long"
                }
            val registriesPerSchema = 10
            val queriesPerSchema = 10
            val casesPerSchema = registriesPerSchema * queriesPerSchema
            require(requestedCases >= MINIMUM_STRESS_CASES && requestedCases % casesPerSchema == 0) {
                "$STRESS_CASES_PROPERTY/$STRESS_CASES_ENV must be at least " +
                    "$MINIMUM_STRESS_CASES and a multiple of $casesPerSchema"
            }
            val counts =
                TestCaseCount(
                    schemas = requestedCases / casesPerSchema,
                    registriesPerSchema = registriesPerSchema,
                    queriesPerSchema = queriesPerSchema,
                )
            val config =
                Config.default +
                    (MinimumSelectionDepth to 4) +
                    (MaxSelectionDepth to 6) +
                    (SchemaObjectCount to 5..7) +
                    (ObjectFieldCount to 5..7) +
                    (QueryFieldCount to 3..5) +
                    (FieldArgumentWeight to 0.75) +
                    (ExplicitFieldResolverWeight to 0.75) +
                    (NullableTypeWeight to 0.15) +
                    (NullValueWeight to 0.05) +
                    (ErrorValueWeight to 0.02) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFragmentDepth to 4) +
                    (ResolverVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0)
            var attemptedCases = 0
            var coverage = StressCoverage()
            val previousSeed = PropertyTesting.defaultSeed
            PropertyTesting.defaultSeed = seed

            try {
                checkResolverTestCases(counts, config) { testWorld, testCase ->
                    attemptedCases += 1
                    assertTrue(
                        testCase.query.selectionDepth >= 4,
                        "Stress query depth was ${testCase.query.selectionDepth}, expected at least 4",
                    )
                    val generated = generatedResolution(testWorld, testCase)
                    coverage =
                        coverage.recordResolved(
                            generated.result.bindingOutcomes(),
                            generated.applications,
                        )
                    assertCorrectResolution(testWorld, testCase, generated.result)
                    coverage = coverage.recordVerified()
                }
            } catch (failure: Throwable) {
                throw AssertionError(
                    stressReport(
                        seed = seed,
                        requestedCases = requestedCases,
                        attemptedCases = attemptedCases,
                        coverage = coverage,
                    ),
                    failure,
                )
            } finally {
                PropertyTesting.defaultSeed = previousSeed
                println(
                    stressReport(
                        seed = seed,
                        requestedCases = requestedCases,
                        attemptedCases = attemptedCases,
                        coverage = coverage,
                    ),
                )
            }

            val report =
                stressReport(
                    seed = seed,
                    requestedCases = requestedCases,
                    attemptedCases = attemptedCases,
                    coverage = coverage,
                )
            assertEquals(
                requestedCases,
                attemptedCases,
                "Stress execution did not complete every requested case.\n$report",
            )
            assertEquals(
                requestedCases,
                coverage.verifiedCases,
                "Stress execution did not verify every requested case.\n$report",
            )
            assertCoverageThresholds(requestedCases, coverage, report)
        }

    private fun generatedResolution(
        testWorld: TestWorld,
        testCase: ResolverTestCase,
    ): GeneratedResolution {
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom(testCase.query.source)
        testCase.registry.clearResolutionWitness()
        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val witness = testCase.registry.resolutionWitness()
        return GeneratedResolution(
            result = result,
            applications = witness.applicationMetrics(testCase),
        )
    }

    private fun assertCorrectResolution(
        testWorld: TestWorld,
        testCase: ResolverTestCase,
        result: EngineResult.Object,
    ) {
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom(testCase.query.source)
        context(world) {
            val checks =
                mapOf(
                    "rootedAndWellTyped" to result.rootedAndWellTyped(fragment),
                    "conformsToFragment" to result.conformsToFragment(fragment),
                    "isClosedUnderResolverDemand" to result.isClosedUnderResolverDemand(),
                    "conformsToVariables" to result.conformsToVariables(),
                    "conformsToResolvers" to result.conformsToResolvers(),
                    "conformsToTypename" to result.conformsToTypename(),
                )
            check(checks.values.all { it }) {
                "Incorrect generated resolution: " +
                    checks.filterValues { correct -> !correct }.keys.joinToString() +
                    "; missing fragment paths: " +
                    result.missingFragmentPaths(fragment.subselections).joinToString()
            }
            check(result.correctResolution(fragment))
        }
    }

    private fun EngineResult?.bindingOutcomes(): BindingOutcomes =
        when (this) {
            is EngineResult.Object ->
                variableValues.values.fold(BindingOutcomes()) { outcomes, value ->
                    outcomes + value.bindingOutcome()
                } +
                    cells.values.fold(BindingOutcomes()) { outcomes, cell ->
                        outcomes + cell.value.bindingOutcomes()
                    }
            is EngineResult.List ->
                fold(BindingOutcomes()) { outcomes, cell ->
                    outcomes + cell.value.bindingOutcomes()
                }
            null,
            Value.Error,
            is Value.Simple,
            -> BindingOutcomes()
        }

    private fun Value.Input?.bindingOutcome(): BindingOutcomes =
        when (this) {
            null -> BindingOutcomes(nulls = 1)
            Value.Error -> BindingOutcomes(errors = 1)
            is Value.InputList -> BindingOutcomes(lists = 1)
            is Value.Simple -> BindingOutcomes(simple = 1)
            is Value.InputObject -> error("Variable providers cannot terminate at an input object")
            is Value.Variable -> error("Resolved variable bindings cannot contain variables")
        }

    private fun stressReport(
        seed: Long,
        requestedCases: Int,
        attemptedCases: Int,
        coverage: StressCoverage,
    ): String =
        "Resolver04 stress: seed=$seed, requestedCases=$requestedCases, " +
            "attemptedCases=$attemptedCases, resolvedCases=${coverage.resolvedCases}, " +
            "verifiedCases=${coverage.verifiedCases}, minimumDepth=4, " +
            "bindingCases={any=${coverage.bindingCases.any}, " +
            "successful=${coverage.bindingCases.successful}, " +
            "errorOnly=${coverage.bindingCases.errorOnly}, " +
            "simple=${coverage.bindingCases.simple}, null=${coverage.bindingCases.nulls}, " +
            "error=${coverage.bindingCases.errors}, list=${coverage.bindingCases.lists}}, " +
            "bindingOccurrences={simple=${coverage.bindingOutcomes.simple}, " +
            "null=${coverage.bindingOutcomes.nulls}, error=${coverage.bindingOutcomes.errors}, " +
            "list=${coverage.bindingOutcomes.lists}}, " +
            "resolverApplicationCases={any=${coverage.applicationCases.any}, " +
            "dependency=${coverage.applicationCases.dependencies}, " +
            "inputSensitive=${coverage.applicationCases.inputSensitive}, " +
            "argumentSensitive=${coverage.applicationCases.argumentSensitive}, " +
            "inputAndArgumentSensitive=${coverage.applicationCases.inputAndArgumentSensitive}}, " +
            "resolverApplications={total=${coverage.applications.total}, " +
            "dependency=${coverage.applications.dependencies}, " +
            "constant=${coverage.applications.constant}, " +
            "inputOnly=${coverage.applications.inputOnly}, " +
            "argumentOnly=${coverage.applications.argumentOnly}, " +
            "inputAndArgument=${coverage.applications.inputAndArgument}, " +
            "inputSensitive=${coverage.applications.inputSensitive}, " +
            "argumentSensitive=${coverage.applications.argumentSensitive}}"

    private fun assertCoverageThresholds(
        requestedCases: Int,
        coverage: StressCoverage,
        report: String,
    ) {
        val requirements =
            listOf(
                CoverageRequirement(
                    "cases with a non-error binding",
                    coverage.bindingCases.successful,
                    requestedCases / 10,
                ),
                CoverageRequirement(
                    "cases with a simple binding",
                    coverage.bindingCases.simple,
                    requestedCases / 50,
                ),
                CoverageRequirement(
                    "cases with a null binding",
                    coverage.bindingCases.nulls,
                    requestedCases / 1_000,
                ),
                CoverageRequirement(
                    "cases with an error binding",
                    coverage.bindingCases.errors,
                    requestedCases / 2_000,
                ),
                CoverageRequirement(
                    "cases with a list binding",
                    coverage.bindingCases.lists,
                    requestedCases / 400,
                ),
                CoverageRequirement(
                    "cases with an activated dependency-bearing resolver",
                    coverage.applicationCases.dependencies,
                    requestedCases / 20,
                ),
                CoverageRequirement(
                    "activated dependency-bearing resolver applications",
                    coverage.applications.dependencies,
                    requestedCases / 10,
                ),
                CoverageRequirement(
                    "cases with an input-sensitive resolver application",
                    coverage.applicationCases.inputSensitive,
                    requestedCases / 100,
                ),
                CoverageRequirement(
                    "cases with an argument-sensitive resolver application",
                    coverage.applicationCases.argumentSensitive,
                    requestedCases / 100,
                ),
                CoverageRequirement(
                    "cases with a jointly input-and-argument-sensitive resolver application",
                    coverage.applicationCases.inputAndArgumentSensitive,
                    requestedCases / 1_000,
                ),
            )
        val unmet = requirements.filterNot(CoverageRequirement::met)
        assertTrue(
            unmet.isEmpty(),
            buildString {
                appendLine("Resolver04 stress coverage thresholds were not met:")
                unmet.forEach { requirement ->
                    appendLine(
                        "  ${requirement.label}: " +
                            "${requirement.actual} < ${requirement.minimum}",
                    )
                }
                append(report)
            },
        )
    }

    context(world: Assumptions)
    private fun EngineResult.Object.missingFragmentPaths(
        selections: SelectionForest,
        path: String = type.typeName,
    ): List<String> {
        val missing = mutableListOf<String>()
        selections.forEach { selection ->
            if (type !in selection.possibleTypes) return@forEach
            val key = selection.concreteObjectKey(type)
            val fieldPath = "$path.${key.field.fieldName}(${key.arguments})"
            if (key !in keys) {
                missing += fieldPath
            } else {
                missing +=
                    fetch(key).value.missingFragmentPaths(
                        selections = selection.subselections,
                        path = fieldPath,
                    )
            }
        }
        return missing
    }

    context(world: Assumptions)
    private fun EngineResult?.missingFragmentPaths(
        selections: SelectionForest,
        path: String,
    ): List<String> =
        when (this) {
            null,
            Value.Error,
            is Value.Simple,
            -> emptyList()
            is EngineResult.Object -> missingFragmentPaths(selections, path)
            is EngineResult.List ->
                flatMapIndexed { index, cell ->
                    cell.value.missingFragmentPaths(selections, "$path[$index]")
                }
        }

    private companion object {
        const val MINIMUM_STRESS_CASES = 10_000
        const val STRESS_CASES_ENV = "RESOLVER04_STRESS_CASES"
        const val STRESS_CASES_PROPERTY = "resolver04.stress.cases"
        const val STRESS_SEED_ENV = "RESOLVER04_STRESS_SEED"
        const val STRESS_SEED_PROPERTY = "resolver04.stress.seed"
    }
}

private data class BindingOutcomes(
    val simple: Int = 0,
    val nulls: Int = 0,
    val errors: Int = 0,
    val lists: Int = 0,
) {
    val total: Int
        get() = simple + nulls + errors + lists

    val successful: Int
        get() = simple + nulls + lists

    operator fun plus(other: BindingOutcomes): BindingOutcomes =
        BindingOutcomes(
            simple = simple + other.simple,
            nulls = nulls + other.nulls,
            errors = errors + other.errors,
            lists = lists + other.lists,
        )
}

private data class GeneratedResolution(
    val result: EngineResult.Object,
    val applications: ResolverApplicationMetrics,
)

private data class StressCoverage(
    val resolvedCases: Int = 0,
    val verifiedCases: Int = 0,
    val bindingCases: BindingCases = BindingCases(),
    val bindingOutcomes: BindingOutcomes = BindingOutcomes(),
    val applicationCases: ResolverApplicationCases = ResolverApplicationCases(),
    val applications: ResolverApplicationMetrics = ResolverApplicationMetrics(),
) {
    fun recordResolved(
        outcomes: BindingOutcomes,
        applicationMetrics: ResolverApplicationMetrics,
    ): StressCoverage =
        copy(
            resolvedCases = resolvedCases + 1,
            bindingCases = bindingCases.record(outcomes),
            bindingOutcomes = bindingOutcomes + outcomes,
            applicationCases = applicationCases.record(applicationMetrics),
            applications = applications + applicationMetrics,
        )

    fun recordVerified(): StressCoverage = copy(verifiedCases = verifiedCases + 1)
}

private data class BindingCases(
    val any: Int = 0,
    val successful: Int = 0,
    val errorOnly: Int = 0,
    val simple: Int = 0,
    val nulls: Int = 0,
    val errors: Int = 0,
    val lists: Int = 0,
) {
    fun record(outcomes: BindingOutcomes): BindingCases =
        copy(
            any = any + outcomes.total.present(),
            successful = successful + outcomes.successful.present(),
            errorOnly =
                errorOnly +
                    (outcomes.errors > 0 && outcomes.successful == 0).present(),
            simple = simple + outcomes.simple.present(),
            nulls = nulls + outcomes.nulls.present(),
            errors = errors + outcomes.errors.present(),
            lists = lists + outcomes.lists.present(),
        )
}

private data class ResolverApplicationCases(
    val any: Int = 0,
    val dependencies: Int = 0,
    val inputSensitive: Int = 0,
    val argumentSensitive: Int = 0,
    val inputAndArgumentSensitive: Int = 0,
) {
    fun record(applications: ResolverApplicationMetrics): ResolverApplicationCases =
        copy(
            any = any + applications.total.present(),
            dependencies = dependencies + applications.dependencies.present(),
            inputSensitive = inputSensitive + applications.inputSensitive.present(),
            argumentSensitive = argumentSensitive + applications.argumentSensitive.present(),
            inputAndArgumentSensitive =
                inputAndArgumentSensitive + applications.inputAndArgument.present(),
        )
}

private data class ResolverApplicationMetrics(
    val total: Int = 0,
    val dependencies: Int = 0,
    val constant: Int = 0,
    val inputOnly: Int = 0,
    val argumentOnly: Int = 0,
    val inputAndArgument: Int = 0,
) {
    val inputSensitive: Int
        get() = inputOnly + inputAndArgument

    val argumentSensitive: Int
        get() = argumentOnly + inputAndArgument

    operator fun plus(other: ResolverApplicationMetrics): ResolverApplicationMetrics =
        ResolverApplicationMetrics(
            total = total + other.total,
            dependencies = dependencies + other.dependencies,
            constant = constant + other.constant,
            inputOnly = inputOnly + other.inputOnly,
            argumentOnly = argumentOnly + other.argumentOnly,
            inputAndArgument = inputAndArgument + other.inputAndArgument,
        )
}

private data class CoverageRequirement(
    val label: String,
    val actual: Int,
    val minimum: Int,
) {
    fun met(): Boolean = actual >= minimum
}

private fun ResolutionWitness.applicationMetrics(
    testCase: ResolverTestCase,
): ResolverApplicationMetrics =
    applications.fold(ResolverApplicationMetrics()) { metrics, application ->
        val sourceField = application.key.field
        val dependency =
            testCase.registry.objectFragmentSources.getValue(sourceField).isNotEmpty()
        val program = testCase.registry.resolverProgram(sourceField)
        metrics +
            ResolverApplicationMetrics(
                total = 1,
                dependencies = dependency.present(),
                constant = (program == ResolverProgramKind.CONSTANT).present(),
                inputOnly = (program == ResolverProgramKind.INPUT_SENSITIVE).present(),
                argumentOnly = (program == ResolverProgramKind.ARGUMENT_SENSITIVE).present(),
                inputAndArgument =
                    (program == ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE).present(),
            )
    }

private fun Boolean.present(): Int = if (this) 1 else 0

private fun Int.present(): Int = (this > 0).present()
