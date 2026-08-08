package semantics.resolver01

import kotlinx.coroutines.runBlocking
import model.fragmentFrom
import model.objectOf
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverGeneratedTest {
    @Test
    fun `arbitrary valid worlds resolve correctly`(): Unit =
        runBlocking {
            val counts =
                TestCaseCount(
                    schemas = 20,
                    registriesPerSchema = 3,
                    queriesPerSchema = 5,
                )
            val config =
                Config.default +
                    (ResolverFragmentsEnabled to false)

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                val world = testWorld.newAssumptions()
                val fragment = world.fragmentFrom(testCase.query.source)
                val result =
                    context(world) {
                        world.objectOf("Query").resolve(fragment.subselections)
                    }
                assertTrue(context(world) { result.correctResolution(fragment) })

                val permutedWorld = testWorld.newAssumptions()
                val permutedFragment =
                    permutedWorld.fragmentFrom(testCase.query.permutationEquivalentSource)
                val permutedResult =
                    context(permutedWorld) {
                        permutedWorld
                            .objectOf("Query")
                            .resolve(permutedFragment.subselections)
                    }
                assertEquals(result, permutedResult)
            }
        }
}
