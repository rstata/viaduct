package semantics.resolver04

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
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
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

class ResolverStressTest {
    @Test
    fun `deep dependency-heavy arbitrary worlds resolve correctly`(): Unit =
        runBlocking {
            val configuredCases = System.getenv(STRESS_CASES_ENV) ?: return@runBlocking
            val requestedCases =
                requireNotNull(configuredCases.toIntOrNull()) {
                    "$STRESS_CASES_ENV must be an integer"
                }
            val registriesPerSchema = 10
            val queriesPerSchema = 10
            val casesPerSchema = registriesPerSchema * queriesPerSchema
            require(requestedCases > 0 && requestedCases % casesPerSchema == 0) {
                "$STRESS_CASES_ENV must be a positive multiple of $casesPerSchema"
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
            var executedCases = 0
            var activatedVariableCases = 0
            var dependencyFragments = 0

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                executedCases += 1
                assertTrue(testCase.query.selectionDepth >= 4)
                dependencyFragments +=
                    testCase.registry.objectFragmentSources.values.count(String::isNotEmpty)
                val result = generatedResolution(testWorld, testCase)
                if (result.hasVariableValues()) {
                    activatedVariableCases += 1
                }
            }

            assertEquals(requestedCases, executedCases)
            assertTrue(activatedVariableCases >= requestedCases / 10)
            assertTrue(dependencyFragments >= requestedCases * 3)
            println(
                "Resolver04 stress: cases=$executedCases, minimumDepth=4, " +
                    "activatedVariableCases=$activatedVariableCases, " +
                    "dependencyFragments=$dependencyFragments",
            )
        }

    private fun generatedResolution(
        testWorld: TestWorld,
        testCase: ResolverTestCase,
    ): EngineResult.Object {
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom(testCase.query.source)
        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
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
        return result
    }

    private fun EngineResult?.hasVariableValues(): Boolean =
        when (this) {
            is EngineResult.Object ->
                variableValues.isNotEmpty() ||
                    keys.any { key -> fetch(key).value.hasVariableValues() }
            is EngineResult.List -> any { cell -> cell.value.hasVariableValues() }
            null,
            Value.Error,
            is Value.Simple,
            -> false
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
        const val STRESS_CASES_ENV = "RESOLVER04_STRESS_CASES"
    }
}
