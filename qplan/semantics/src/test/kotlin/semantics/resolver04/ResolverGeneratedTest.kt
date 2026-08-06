package semantics.resolver04

import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ImplementationArgumentDefaultWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.conformsToFragment
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToTypename
import semantics.correctresolution.conformsToVariables
import semantics.correctresolution.correctResolution
import semantics.correctresolution.isClosedUnderResolverDemand
import semantics.correctresolution.rootedAndWellTyped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Generated-world correctness, variable activation, and permutation parity for Resolver04.
 *
 * Keep ordinary arbitrary-world acceptance here; trace and volume checks have separate suites.
 */
class ResolverGeneratedTest {
    @Test
    fun `arbitrary valid worlds resolve correctly`(): Unit =
        runBlocking {
            var activatedVariableCases = 0
            var activatedImplementationDefaultCases = 0
            val counts =
                TestCaseCount(
                    schemas = 20,
                    registriesPerSchema = 3,
                    queriesPerSchema = 5,
                )
            val config =
                Config.default +
                    (SchemaObjectCount to 3..5) +
                    (ObjectFieldCount to 3..5) +
                    (FieldArgumentWeight to 0.7) +
                    (ImplementationArgumentDefaultWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 0.6) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 0.9) +
                    (ResolverVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0)

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                if (testCase.query.features.hasAbstractImplementationDefaultSelection) {
                    activatedImplementationDefaultCases += 1
                }
                val result = generatedResolution(testWorld, testCase.query.source)
                val permuted =
                    generatedResolution(
                        testWorld,
                        testCase.query.permutationEquivalentSource,
                    )
                assertEquals(result, permuted)
                if (result.hasVariableValues()) {
                    activatedVariableCases += 1
                }
            }
            assertTrue(
                activatedVariableCases > 0,
                "Resolver04 property activated no resolver variables",
            )
            assertTrue(
                activatedImplementationDefaultCases > 0,
                "Resolver04 property activated no abstract implementation defaults",
            )
        }

    private fun generatedResolution(
        testWorld: TestWorld,
        querySource: String,
    ): EngineResult.Object {
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom(querySource)
        val selections = fragment.subselections
        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
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
                    checks.filterValues { correct -> !correct }.keys.joinToString()
            }
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

}
