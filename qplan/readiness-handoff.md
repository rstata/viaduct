# Resolver09 Slot-Readiness Handoff

## Purpose

Implement Resolver09 with the full feature coverage and selective output policy of Resolver03 and Resolver08, but replace depth-first task ordering with explicit readiness among field-resolver instances.

Resolver08 and `DepthFirstReactor` remain unchanged as the depth-first queue implementation and comparison oracle. Resolver10 is reserved for FromObjectPath variables.

Resolver09 supports empty and nonempty resolver object fragments, `FromArgument` variables, selective `successorDemand()`, abstract types, lists, recursive values, ordinary field resolvers, and fixture-lowered node loaders. Runtime `VariableDefinition.FromObjectField` is out of scope.

Efficiency is not a goal. Prefer repeated walks, ordinary mutable collections, and naive predicates when they make the model easier to understand.

## Central Design

A field-resolver instance is represented at runtime by one `SlotResolver`. Its coordinate is its exact OER-tree path:

```kotlin
List<PathComponent>
```

The path ends in the `Value.GroundKey` of the field resolved by that instance. Every preceding component identifies the containing OER through exact ground keys and list indices.

A `SlotResolver` trivially knows its own coordinate:

```kotlin
val coordinate = containingObjectPath + key
```

One global readiness board records completed field-resolver instances:

```kotlin
val readinessBoard = mutableMapOf<List<PathComponent>, Unit>()
```

The board starts empty. When a `SlotResolver` finishes, it adds `coordinate to Unit`. Presence means that exact resolver instance has finished publishing its cell, passive result tree, and active fringe. Absence means only “not completed”; no created/running distinction is needed.

Readiness belongs to `SlotOrchestrator`:

- A persistent orchestrator owns one exact OER occurrence with at least one active demanded field and its still-unlaunched resolvers.
- It computes the resolver coordinates required by each candidate.
- It launches a candidate only when every required coordinate is on the readiness board.
- A launched `SlotResolver` has no dependencies and can run immediately.

## Why Depth First Currently Works

Consider:

```graphql
type User {
  profile: Profile!       # resolver
  greeting: String!       # resolver requiring { profile { displayName } }
}

type Profile {
  displayName: String!    # resolver
}
```

Writing `User.profile` publishes a mutable `Profile` OER, but `Profile.displayName` may still be absent. Resolver08 works because depth-first priority happens to finish the deeper resolver before returning to `User.greeting`.

The readiness coordinates make the actual order explicit:

```text
[User.profile]
[User.profile, Profile.displayName]
[User.greeting]
```

`User.greeting` launches only after the transitive object-fragment walk says it requires the first two coordinates and both are present on the board.

## Keep Incoming Demand Unchanged

Do not modify the existing transitive object-fragment walk used by `closeResolverDemand`.

That code computes incoming demand and is intentionally simple and shared by every resolver version. Resolver09 should continue to:

1. Call `type.closeResolverDemand(path, selections)` once when an OER occurrence is registered.
2. Let it bind `FromArgument` variables, stamp object fragments, instantiate bindings, and merge exact keys.
3. Treat the returned `ObjectSelectionForest` as sealed local demand.
4. Exclude keys already present in the target OER.

Resolver09 adds a second independent transitive object-fragment walk whose only result is the field-resolver coordinates needed for readiness. Some structural work is duplicated on purpose.

Keep Resolver08's selective policy unchanged:

```kotlin
SelectionCompletion(
    selections = selections.successorDemand(),
    selective = true,
)
```

Resolver09 changes scheduling, not demand discovery or projection.

## The Readiness Walk

For one still-unlaunched exact key, start from that resolver's stamped object fragment and walk its transitive requirements through the current OER tree.

The walk should:

1. Use the candidate coordinate when stamping its resolver-local fragment.
2. Specialize selections against every concrete OER encountered.
3. Instantiate existing `FromArgument` bindings.
4. At each selected registered field, derive `currentObjectPath + groundKey`.
5. Add that exact path to the required-coordinate set.
6. Walk that resolver's object fragment as well, so the result is transitive.
7. If the selected resolver is complete, traverse its published result for selected descendants.
8. Traverse passive object values directly.
9. Traverse lists positionally, appending every `Value.ListIndex`.
10. Stop below null, `Value.Error`, and simple values.

If an active ancestor is not complete, its exact coordinate is already an unsatisfied requirement. The walk need not invent coordinates for descendants whose containing OERs do not exist yet. On a later event-loop pass, the same walk sees the completed ancestor's result and discovers the deeper coordinates.

This progressive behavior handles runtime list cardinality without wildcard coordinates:

```text
first pass:  require the active ancestor coordinate
ancestor completes and publishes its result
next pass:   enumerate exact child/list coordinates
```

The readiness predicate may simply be:

```kotlin
fun resolverIsReady(key: Value.GroundKey): Boolean =
    requiredResolverCoordinates(key)
        ?.all(readinessBoard::containsKey)
        ?: false
```

The nullable result represents a walk that cannot yet traverse required structure. A richer diagnostic result is fine, but do not add subscriptions or an incremental dependency graph.

The candidate's own coordinate must not count as one of its satisfied predecessors. A self-coordinate or transitive cycle leaves unfinished work at quiescence, which is an illegal resolver state under the accepted-world invariants.

## SlotOrchestrator

Create one orchestrator for an exact published OER occurrence only when its applicable top-level demand at that OER contains one or more active fields, meaning fields backed by registered resolvers. An OER whose applicable top-level demand is entirely passive has no local resolver work and receives no `SlotOrchestrator`.

```kotlin
class SlotOrchestrator(
    val path: List<PathComponent>,
    val source: Value.Object,
    val target: EngineResult.Object,
    val closedDemand: ObjectSelectionForest,
) {
    private val unlaunched: MutableMap<Value.GroundKey, ObjectSelection>

    fun launchResolvers(): Boolean
}
```

`launchResolvers()`:

1. Examines every still-unlaunched exact key.
2. Runs the naive readiness walk for each registered resolver.
3. Launches every candidate whose required coordinates are all on the board.
4. Removes each launched key from `unlaunched`.
5. Also launches immediately runnable argument-error and `__typename` slots.
6. Returns `true` when `unlaunched` is empty and `false` otherwise.

Launching means adding a dependency-free `SlotResolver` to the resolver queue. The function does not execute the resolver and does not wait for launched tasks to finish.

The unlaunched map prevents duplicate launch. The task queue represents launched work. The readiness board represents completed work. No additional lifecycle map is needed.

## SlotResolver Completion

One `SlotResolver` execution:

1. Materializes its already-ready input.
2. Applies the field resolver or computes the engine-owned result.
3. Projects under the sealed `SelectionCompletion`.
4. Constructs the passive result tree.
5. Allocates mutable child OERs.
6. Writes its exact cell once.
7. Registers a `SlotOrchestrator` for every child OER in its fringe whose applicable demand contains at least one active field.
8. Inserts `coordinate to Unit` into the readiness board.

The board insertion is last. A dependent may treat completion as proof that the cell, passive values, result shape, and fringe orchestrators are visible.

Reject a second insertion for the same coordinate. Exact paths distinguish recursive occurrences and list positions even when values, node IDs, fields, or arguments are equal.

Do not call `ResolvedValue.resolveObjects`, whose deepest-first sort is the mechanism being replaced. Visit retained object occurrences without depth ordering and register only those with one or more active demanded fields.

## Event Loop

Keep:

```kotlin
val slotResolverQueue = ArrayDeque<SlotResolver>()
val unfinishedOrchestrators = MutableList<SlotOrchestrator>()
```

Each iteration greedily runs resolver tasks, then walks every unfinished orchestrator:

```kotlin
while (true) {
    while (slotResolverQueue.isNotEmpty()) {
        slotResolverQueue.removeFirst().execute()
    }

    val launchedBefore = totalLaunchedResolvers
    val iterator = unfinishedOrchestrators.iterator()
    while (iterator.hasNext()) {
        if (iterator.next().launchResolvers()) {
            iterator.remove()
        }
    }

    if (slotResolverQueue.isNotEmpty()) continue
    if (unfinishedOrchestrators.isEmpty()) return result

    if (totalLaunchedResolvers == launchedBefore) {
        failWithIllegalResolverStateReport()
    }
}
```

The exact progress counter can instead compare resolver-queue size before and after the orchestrator pass. `launchResolvers()` retains the requested single Boolean meaning: whether that orchestrator is finished.

No priority queue, callback subscription, waiter index, or efficient invalidation is needed.

## Completion And Failure

Resolution succeeds when:

- the resolver queue is empty;
- every orchestrator returned `true` and was removed;
- every launched resolver coordinate is present on the board; and
- every registered object's sealed local demand is present in its OER.

If an orchestrator pass launches nothing while unfinished orchestrators remain, the single-threaded resolver has reached an illegal state due to a violated invariant or implementation bug. Rerun the readiness walk for diagnostics and report:

- each unlaunched candidate coordinate;
- every required resolver coordinate;
- which required coordinates are absent from the board;
- the nearest OER path that could not yet be traversed;
- missing passive content; and
- any self or transitive cycle.

Distinguish the observed shape of the illegal state, such as a dependency cycle or missing producer structure. These are diagnostics for a bug, not normal execution outcomes. Diagnostic computation may repeat all walks and be arbitrarily expensive.

## Validation

Start with:

- a consumer waiting for an active grandchild beneath a sibling;
- the first readiness pass finding only the incomplete active ancestor;
- a later pass finding the exact grandchild coordinate;
- the same progressive discovery through each list position;
- null and error ancestry terminating deeper requirements;
- passive content requiring no independent readiness entry;
- a direct required coordinate known before its resolver is launched;
- duplicate readiness-board insertion failing;
- opposite orchestrator orders producing equal results;
- a shallow ready orchestrator running while deeper work is blocked;
- transitive `FromArgument` closure;
- fixture-lowered bridge and node occurrences; and
- explicit illegal-state diagnostics for a cycle or missing producer.

Resolver09 implements the same existing contracts as Resolver03 and Resolver08 except `DepthFirstTaskOrderingContract`. Cover exact coordinates, board insertion, progressive discovery, and the absence of depth-based dispatch in focused Resolver09 tests rather than adding a new shared contract.

Reuse the existing generated cases and oracles with fresh `Assumptions`, comparing final results, `correctResolution`, application identity counts, and one application per coordinate.

## Reused Tests And Stress Clones

Resolver09 adds no new feature domain, so it should reuse Resolver03 and Resolver08's existing static contracts, generated property-test contracts, witness checks, mutation checks, and replay infrastructure. Point those existing tests at the Resolver09 entry point rather than designing a new lightweight property framework or new generated profiles.

The reused ordinary tests should continue to cover:

- exercise nonempty object fragments, transitive resolver dependencies, nodes, lists, abstract types, nulls, errors, and selective projection;
- keep `FromArgument` generation enabled in its existing variable profile; and
- assert final correctness and one application per exact coordinate through the existing oracles.

Clone the Resolver03 and Resolver08 stress tests for Resolver09, changing only the resolver entry point and Resolver09-specific task observation needed for the same assertions. Preserve their configurations, replayable seed and case-count interfaces, feature counters, final-correctness checks, and exact application-identity checks.

Resolver09 may add focused unit tests for its readiness board, coordinate walk, `launchResolvers()`, and illegal-state diagnostics, but it should not create new shared test contracts or generator infrastructure merely because its scheduler differs.

## Implementation Sequence

1. Add exact path coordinates and the global readiness board.
2. Add the second transitive object-fragment walk without changing `closeResolverDemand`.
3. Make `SlotOrchestrator` persistent with `launchResolvers(): Boolean`.
4. Replace the priority queue with the greedy resolver queue plus orchestrator list.
5. Insert board entries only after cell and fringe publication.
6. Add quiescent illegal-state diagnostics.
7. Port Resolver08 contracts without the depth-order contract.
8. Reuse Resolver03/08 static, generated, witness, mutation, and replay tests against Resolver09.
9. Clone the Resolver03 and Resolver08 stress tests for Resolver09.

## Completion Criteria

Resolver09 is complete when:

- it covers Resolver08's feature domain and selective policy;
- field-resolver coordinates are exact `List<PathComponent>` paths ending in `Value.GroundKey`;
- the readiness board contains exactly completed resolver-instance coordinates;
- incoming-demand closure remains unchanged and shared;
- a separate transitive walk computes readiness coordinates;
- every launched `SlotResolver` is dependency-free;
- `launchResolvers()` launches all ready local slots and reports orchestrator completion;
- the event loop greedily drains resolver tasks and repeatedly scans unfinished orchestrators;
- nested and list-element coordinates appear as producer results become available;
- all tested orchestrator orders produce the same result and application set;
- quiescent unfinished and missing-producer states fail explicitly as illegal resolver states; and
- Resolver09 reuses Resolver03/08's ordinary test infrastructure and has clones of their stress tests; and
- `./gradlew check --console=plain` passes before fixed-seed stress is treated as additional evidence.
