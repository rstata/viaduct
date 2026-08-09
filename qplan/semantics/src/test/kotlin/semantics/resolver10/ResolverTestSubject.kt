package semantics.resolver10

import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.SelectionForest
import model.Value
import semantics.ReactorEvent
import semantics.ReactorEventObserver
import semantics.contract.expectedResolverDependencies
import semantics.contract.validateObjectPathBindings
import kotlin.test.assertEquals

internal fun resolveWithDependencyValidation(
    world: Assumptions,
    root: Value.Object,
    selections: SelectionForest,
    eventObserver: ReactorEventObserver = {},
): EngineResult.Object {
    val appliedDependencies =
        mutableMapOf<List<PathComponent>, Set<List<PathComponent>>>()
    val result =
        context(world) {
            root.resolve(
                selections = selections,
                eventObserver = { event ->
                    if (event is ReactorEvent.ResolverDependenciesApplied) {
                        assertEquals(
                            null,
                            appliedDependencies.put(
                                event.coordinate,
                                event.dependencyCoordinates,
                            ),
                            "Resolver dependencies were applied more than once",
                        )
                    }
                    eventObserver(event)
                },
            )
        }
    val expectedDependencies =
        context(world) {
            result.validateObjectPathBindings()
            result.expectedResolverDependencies()
        }
    assertEquals(expectedDependencies, appliedDependencies)
    return result
}
