package semantics.resolver26

import model.operationSelectionsFrom
import model.testing.TestWorld
import semantics.shared.OperationContext
import semantics.shared.RecordingResolverObserver
import kotlin.test.Test
import kotlin.test.assertContains
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

        val snapshot = parentFocusedCoverageSnapshot(world, coverage)
        assertContains(snapshot.parentDepths, 1)
        assertContains(snapshot.producerShapes, ParentProducerShape.SINGULAR)
        assertContains(snapshot.targetKinds, ParentTargetKind.CONCRETE)
        assertContains(snapshot.resolverPlacements, ParentResolverPlacement.DIRECT)
        assertContains(
            snapshot.mixedVariableSourcePairs,
            setOf(ParentVariableSource.ARGUMENT, ParentVariableSource.OBJECT_FIELD),
        )
        assertContains(
            snapshot.diagonalVariableFragments,
            ParentVariableFragment(
                ParentVariableSource.OBJECT_FIELD,
                ParentResolverInputFragment.OBJECT,
            ),
        )
    }

    @Test
    fun `reports all eight criteria for each parent-focused slice`() {
        val sources = ParentVariableSource.entries.toSet()
        val fragments = ParentResolverInputFragment.entries.toSet()
        val complete =
            ParentFocusedCoverageSnapshot(
                parentDepths = setOf(1, 2, 3),
                producerShapes = ParentProducerShape.entries.toSet(),
                targetKinds = ParentTargetKind.entries.toSet(),
                resolverPlacements = ParentResolverPlacement.entries.toSet(),
                variableSources = sources,
                mixedVariableSourcePairs =
                    setOf(
                        setOf(ParentVariableSource.ARGUMENT, ParentVariableSource.OBJECT_FIELD),
                        setOf(ParentVariableSource.ARGUMENT, ParentVariableSource.QUERY_FIELD),
                        setOf(ParentVariableSource.OBJECT_FIELD, ParentVariableSource.QUERY_FIELD),
                    ),
                inputFragments = fragments,
                argumentSelectionDepths = ParentArgumentSelectionDepth.entries.toSet(),
                diagonalDepths = setOf(1, 2),
                diagonalVariableFragments =
                    sources.flatMapTo(linkedSetOf()) { source ->
                        fragments.map { fragment -> ParentVariableFragment(source, fragment) }
                    },
            )
        val report = ParentFocusedCoverageReport(schemaCount = 40, sliceCount = 4)
        repeat(40) { schemaOffset ->
            repeat(25) { report.recordCase(schemaOffset + 1) }
            report.record(schemaOffset + 1, complete)
        }

        val rendered = report.render()

        (1..4).forEach { run -> assertContains(rendered, "RUN $run/4: HIT") }
        (1..8).forEach { criterion -> assertContains(rendered, "  $criterion.") }
        assertContains(rendered, "FOUR-RUN RESULT: HIT (4/4 runs hit all eight criteria)")
        assertContains(rendered, "COMBINED RESULT: HIT")
    }

    @Test
    fun `coverage misses are reported without throwing`() {
        val report = ParentFocusedCoverageReport(schemaCount = 4, sliceCount = 4)
        repeat(4) { schemaOffset -> report.recordCase(schemaOffset + 1) }

        val rendered = report.render()

        assertContains(rendered, "RUN 1/4: MISS")
        assertContains(rendered, "1. Parent topology: MISS")
        assertContains(rendered, "8. Variable-bearing diagonals: MISS")
        assertContains(rendered, "FOUR-RUN RESULT: MISS (0/4 runs hit all eight criteria)")
        assertContains(rendered, "COMBINED RESULT: MISS")
    }
}
