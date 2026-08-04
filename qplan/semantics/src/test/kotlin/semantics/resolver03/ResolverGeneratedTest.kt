package semantics.resolver03

import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ImplementationArgumentDefaultWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Generated-world correctness and permutation parity for Resolver03.
 *
 * Keep ordinary arbitrary-world acceptance here; trace oracles belong in the witness suite.
 */
class ResolverGeneratedTest {
    @Test
    fun `arbitrary valid worlds resolve correctly`(): Unit =
        runBlocking {
            var activatedImplementationDefaultCases = 0
            val counts =
                TestCaseCount(
                    schemas = 20,
                    registriesPerSchema = 3,
                    queriesPerSchema = 5,
                )
            val config =
                Config.default +
                    (ImplementationArgumentDefaultWeight to 1.0) +
                    (ResolverFragmentsEnabled to true)

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
            }
            assertTrue(
                activatedImplementationDefaultCases > 0,
                "Resolver03 property activated no abstract implementation defaults",
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
        return context(world) {
            check(result.correctResolution(fragment))
            result
        }
    }

}
