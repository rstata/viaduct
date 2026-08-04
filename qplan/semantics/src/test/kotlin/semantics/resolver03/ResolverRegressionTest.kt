package semantics.resolver03

import model.Fragment
import model.Selection
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.selectionForestOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import semantics.resolver01.resolve as resolveWithResolver01
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reduced one-off regressions that do not yet form a larger Resolver03 semantic theme.
 *
 * Promote related cases to a dedicated themed suite once a common contract emerges.
 */
class ResolverRegressionTest {
    @Test
    fun `error-valued resolver argument does not import its transitive demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA,
                fieldResolvers = { schema ->
                    val parsedDependency =
                        schema
                            .fragmentFrom(
                                "fragment ignored on Query { dependency(arg: 1) }",
                            ).subselections
                            .single()
                    val errorDependency =
                        Selection.of(
                            key =
                                Value.Key.of(
                                    parsedDependency.key.field,
                                    mapOf("arg" to Value.Error),
                                ),
                            nominalType = parsedDependency.nominalType,
                            possibleTypes = parsedDependency.possibleTypes,
                            subselections = parsedDependency.subselections,
                        )
                    mapOf(
                        schema.field("Query", "container") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Container")
                            },
                        schema.field("Query", "dependency") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { container { helper } }",
                                ),
                            ) { _, _ ->
                                error("An error-bearing resolver must not be applied")
                            },
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                Fragment.of(
                                    schema.query,
                                    selectionForestOf(errorDependency),
                                ),
                            ) { _, _ ->
                                Value.Int.of(1)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { result }")

        val expected =
            context(world) {
                world.objectOf("Query").resolveWithResolver01(fragment.subselections)
            }
        assertTrue(context(world) { expected.correctResolution(fragment) })

        val actual =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertEquals(expected, actual)
        assertTrue(context(world) { actual.correctResolution(fragment) })
    }

    private companion object {
        val SCHEMA =
            """
            type Container {
              helper: Int!
            }

            type Query {
              container: Container!
              dependency(arg: Int!): Int!
              result: Int!
            }
            """.trimIndent()
    }
}
