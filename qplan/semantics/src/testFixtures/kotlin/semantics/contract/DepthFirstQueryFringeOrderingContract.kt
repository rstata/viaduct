package semantics.contract

import semantics.shared.ResolverInvocationObservation
import semantics.correctresolution.CorrectnessResolverObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf

/** Query-fragment recursion must isolate the enclosing fringe, which must finish before a consuming sibling. */
interface DepthFirstQueryFringeOrderingContract : ResolverContract {
    @Test
    fun `reference Query fragment finishes before the enclosing passive fringe runs`() {
        val applications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                applications += field.name
            }
        }
        val fixture = TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = """
                type Query {
                  container: Container!
                  target: Int!
                  dependency: Int!
                  after: Int!
                }
                type Container {
                  left: Child!
                  values: [Int!]!
                }
                type Child { value: Int! }
            """.trimIndent(),
            fieldResolvers = { schema ->
                val target = schema.requireObjectField("Query", "target")
                mapOf(
                    schema.requireObjectField("Query", "container") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            schema.objectOf("Container") {
                                "left" setTo objectOf("Child")
                                "values" setTo listOf(RootFieldReferenceData.of(listOf(target), emptyMap()))
                            }
                        },
                    schema.requireObjectField("Query", "after") to fieldResolverOf(
                        schema.fragmentFrom("fragment Q on Query { container { left { value } values } }"),
                    ) { _, _ -> 9 },
                    target to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom("fragment Q on Query { dependency }"),
                    ) { _, query, _ -> query.selectionValues().getValue("dependency") },
                    schema.requireObjectField("Query", "dependency") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    schema.requireObjectField("Child", "value") to
                        fieldResolverOf(schema.emptyFragmentOf("Child")) { _, _ -> 8 },
                )
            },
        )
        resolveAndValidate(fixture.assumptions, "{ container { left { value } values } after }", resolverObserver = invocationObserver)
        assertEquals(listOf("container", "dependency", "target", "value", "after"), applications)
    }
}
