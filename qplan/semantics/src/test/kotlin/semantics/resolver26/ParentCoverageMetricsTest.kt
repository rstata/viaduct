package semantics.resolver26

import model.operationSelectionsFrom
import model.testing.TestWorld
import semantics.shared.OperationContext
import semantics.shared.RecordingResolverObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParentCoverageMetricsTest {
    @Test
    fun `classifies variable sources and diagonal parent demand across a resolver boundary`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      grand: Grand!
                        @resolver(result: {grandValue: 11})
                    }

                    type Grand {
                      grandValue: Int!
                      parentNode: Parent!
                        @resolver(result: {fromSource: 7})
                    }

                    type Parent {
                      parent: Grand @parent
                      fromSource: Int!
                      child: Child!
                        @resolver(result: {})
                      bridge(seed: Int!): Int!
                        @resolver(
                          of: "fromSource consume(seed: ${'$'}seed, source: ${'$'}fromSource) parent { grandValue }"
                          pathVars: [{name: "fromSource", path: ["fromSource"]}]
                          result: "sum(consume, parent.grandValue)"
                        )
                      consume(seed: Int!, source: Int!): Int!
                        @resolver(result: "sum(${'$'}seed, ${'$'}source)")
                    }

                    type Child {
                      parent: Parent @parent
                      result: Int!
                        @resolver(
                          of: "parent { bridge(seed: 2) }"
                          result: "sum(parent.bridge)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val operation =
            OperationContext(
                world = world,
                resolverObserver = RecordingResolverObserver(),
            )
        val coverage = mutableListOf<ParentSelectionSetCoverage>()

        context(operation) {
            resolveObserved(
                world.operationSelectionsFrom(
                    "query { grand { parentNode { child { result } } } }",
                ),
            ) { application ->
                coverage += ParentCoverageAnalyzer(world).analyze(application)
            }
        }

        val childParent =
            coverage.single { parent ->
                parent.field.containingDef.name == "Child" && parent.field.name == "parent"
            }
        val bridge =
            childParent.selectedResolvers.single { selected ->
                selected.field.containingDef.name == "Parent" && selected.field.name == "bridge"
            }
        assertEquals(1, bridge.selectionDepthBelowParent)
        assertEquals(
            setOf(ParentVariableSource.ARGUMENT, ParentVariableSource.OBJECT_FIELD),
            bridge.requiredInputVariableSources,
        )
        assertEquals(
            listOf(
                ParentResolverVariableArgumentCoverage(
                    fragment = ParentResolverInputFragment.OBJECT,
                    selectionDepth = 1,
                    variableSources =
                        setOf(
                            ParentVariableSource.ARGUMENT,
                            ParentVariableSource.OBJECT_FIELD,
                        ),
                ),
            ),
            bridge.variableArgumentSelections,
        )
        assertEquals(1, bridge.diagonalParentDepth)
        assertTrue(coverage.all { parent -> parent.argumentVariables.isEmpty() })
    }
}
