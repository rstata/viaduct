package semantics.resolver02

import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverGeneratedTest {
    @Test
    fun `arbitrary valid worlds resolve correctly`(): Unit =
        runBlocking {
            var generatedFromArgumentVariables = 0
            val counts =
                TestCaseCount(
                    schemas = 20,
                    registriesPerSchema = 3,
                    queriesPerSchema = 5,
                )
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0)

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                generatedFromArgumentVariables +=
                    testCase.registry.features.fromArgumentVariableCount
                val result = generatedResolution(testWorld, testCase.query.source)
                val permuted =
                    generatedResolution(
                        testWorld,
                        testCase.query.permutationEquivalentSource,
                    )
                assertEquals(result, permuted)
            }
            assertTrue(generatedFromArgumentVariables > 0)
        }

    private fun generatedResolution(
        testWorld: TestWorld,
        querySource: String,
    ): EngineResult.Object {
        val world = testWorld.newAssumptions()
        val fragment = world.fragmentFrom(querySource)
        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        return context(world) {
            check(result.correctResolution(fragment))
            result
        }
    }
}
