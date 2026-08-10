# Resolver09 Slot-Readiness Handoff

## Purpose

Implement Resolver09 with the full feature coverage and selective output policy of Resolver03 and Resolver08, but replace depth-first task ordering with explicit readiness among field-resolver instances.

Resolver08 and `DepthFirstReactor` remain unchanged as the depth-first queue implementation and comparison oracle. Resolver10 is reserved for FromObjectPath variables.

Resolver09 supports empty and nonempty resolver object fragments, `FromArgument` variables, selective `successorDemand()`, abstract types, lists, recursive values, ordinary field resolvers, and fixture-lowered node loaders. Runtime `VariableDefinition.FromObjectField` is out of scope.

Efficiency is not a goal. Prefer repeated walks, ordinary mutable collections, and naive predicates when they make the model easier to understand.

Resolver09 owns its scheduler in `semantics.resolver09.Reactor`; the scheduler is not a shared readiness-reactor abstraction. It shares only `semantics.ReactorInstrumentation` with the depth-first reactors. The instrumentation records lifecycle events and checks common successful-completion invariants, while Resolver09 retains its queues, slot registry, dependency sets, and illegal-state diagnostics.

## Central Design

A demanded unresolved slot is represented at runtime by one `SlotResolver`. For a field-resolver instance, its coordinate is its exact OER-tree path:

```kotlin
List<PathComponent>
```

The path ends in the `Value.GroundKey` of the field resolved by that instance. Every preceding component identifies the containing OER through exact ground keys and list indices.

A `SlotResolver` trivially knows its own coordinate:

```kotlin
val coordinate = containingObjectPath + key
```

Every demanded unresolved slot is represented by one stable `SlotResolver`. All local slot
resolvers are created when their OER orchestrator launches and registered by exact coordinate:

```kotlin
val slotResolversByCoordinate =
    mutableMapOf<List<PathComponent>, SlotResolver>()
```

Each `SlotResolver` owns `isFinished`, initially false. It becomes true only after the slot has
published its cell, passive result tree, and active fringe. Dependency coordinates from the shared
walk are translated back to these stable objects.

Exact dependency derivation is shared in `semantics.resolverDependencies`, while
`SlotOrchestrator` owns the mutable scan and `SlotResolver` owns slot behavior:

- A persistent orchestrator owns one exact OER occurrence and
  `MutableMap<SlotResolver, Set<SlotResolver>>` for its unfinished local slots.
- Before each scan it refreshes those sets against the current OER tree.
- It launches every candidate whose dependency objects are finished.
- An argument-error or `__typename` slot is immediately ready; a missing passive slot is not executable.
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

`User.greeting` launches only after the shared walk says it requires the first two coordinates and
both registered slot objects are finished.

## Keep Incoming Demand Unchanged

Do not modify the existing transitive object-fragment walk used by `closeResolverDemand`.

That code computes incoming demand and is intentionally simple and shared by every resolver version. Resolver09 should continue to:

1. Call `type.closeResolverDemand(path, selections)` once when an OER occurrence is registered.
2. Let it bind `FromArgument` variables, stamp object fragments, instantiate bindings, and merge exact keys.
3. Treat the returned `ObjectSelectionForest` as sealed local demand.
4. Exclude keys already present in the target OER.

The shared `resolverDependencies` operation walks each candidate's own object fragment through the current result tree. Its only result is a map from local keys to the exact field-resolver coordinates currently needed for readiness.

Keep Resolver08's selective policy unchanged:

```kotlin
SelectionCompletion(
    selections = selections.successorDemand(),
)
```

Resolver09 requires `world.selectiveResolvers == true`; shared resolver invocation and passive
output traversal read that world flag directly.

Resolver09 changes scheduling, not demand discovery or projection.

## Shared Resolver Dependencies

`semantics.resolverDependencies` starts from each local resolver's stamped object fragment and walks its selected requirements through the current OER tree. It returns:

```kotlin
Map<Value.GroundKey, Set<List<PathComponent>>>
```

The walk should:

1. Use the candidate coordinate when stamping its resolver-local fragment.
2. Specialize selections against every concrete OER encountered.
3. Instantiate existing `FromArgument` bindings.
4. At each selected registered field, derive `currentObjectPath + groundKey`.
5. Add that exact path to the required-coordinate set.
6. Do not expand that selected resolver's own object fragment: its finished state already implies that its input dependencies completed.
7. If the selected resolver is complete, traverse its published result for descendants selected by the candidate.
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

The orchestrator refreshes this map before every scan, resolves the coordinates to stable slot
objects, and applies:

```kotlin
dependencies.all(SlotResolver::isFinished)
```

An unset passive or engine-owned dependency is represented by its missing exact coordinate, so it remains unsatisfied and can be classified by quiescence diagnostics. Do not add subscriptions or an incremental dependency graph.

The candidate's own coordinate must not count as one of its satisfied predecessors. A self-coordinate or transitive cycle leaves unfinished work at quiescence, which is an illegal resolver state under the accepted-world invariants.

## SlotOrchestrator

Create one orchestrator for an exact published OER occurrence when its sealed local demand contains unresolved slots. An OER whose applicable top-level demand is already present receives no `SlotOrchestrator`.

```kotlin
class SlotOrchestrator(
    val path: List<PathComponent>,
    val source: Value.Object,
    val target: EngineResult.Object,
    val closedDemand: ObjectSelectionForest,
) {
    private val unfinished:
        MutableMap<SlotResolver, Set<SlotResolver>>

    fun launchResolvers(): Boolean
}
```

`launchResolvers()`:

1. Refreshes dependencies for every unfinished local slot.
2. Examines a snapshot of the map in natural order.
3. Launches every candidate whose dependencies are complete.
4. Removes each launched slot from `unfinished`.
5. Returns `true` when `unfinished` is empty and `false` otherwise.

Launching means adding a dependency-free `SlotResolver` to the resolver queue. The function does not execute the resolver and does not wait for launched tasks to finish.

The unfinished map prevents duplicate launch. The task queue represents launched work, and
`SlotResolver.isFinished` represents completed work.

## SlotResolver Completion

One `SlotResolver` execution:

1. Materializes its already-ready input.
2. Applies the field resolver or computes the engine-owned result.
3. Projects under the sealed `SelectionCompletion`.
4. Constructs the passive result tree.
5. Allocates mutable child OERs.
6. Writes its exact cell once.
7. Registers a `SlotOrchestrator` for every child OER in its fringe whose applicable demand contains at least one active field.
8. Sets its own `isFinished` state after publication.

The state transition is last. A dependent may treat completion as proof that the cell, passive
values, result shape, and fringe orchestrators are visible.

Reject a second finish for the same slot object. Exact paths distinguish recursive occurrences and
list positions even when values, node IDs, fields, or arguments are equal.

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

    val iterator = unfinishedOrchestrators.iterator()
    while (iterator.hasNext()) {
        if (iterator.next().launchResolvers()) {
            iterator.remove()
        }
    }

    if (slotResolverQueue.isNotEmpty()) continue
    if (unfinishedOrchestrators.isEmpty()) return result

    failWithIllegalResolverStateReport()
}
```

Because every launch appends a resolver task, an empty queue after the orchestrator pass proves that no candidate launched. `launchResolvers()` retains the requested single Boolean meaning: whether that orchestrator is finished.

No priority queue, callback subscription, waiter index, or efficient invalidation is needed.

## Completion And Failure

Resolution succeeds when:

- the resolver queue is empty;
- every orchestrator returned `true` and was removed;
- every launched slot resolver is finished; and
- every registered object's sealed local demand is present in its OER.

If an orchestrator pass launches nothing while unfinished orchestrators remain, the single-threaded resolver has reached an illegal state due to a violated invariant or implementation bug. Rerun the readiness walk for diagnostics and report:

- each unfinished candidate coordinate;
- every required resolver coordinate;
- which required slot resolvers are unfinished;
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
- duplicate slot completion failing;
- a shallow ready orchestrator running while deeper work is blocked;
- transitive `FromArgument` closure;
- fixture-lowered bridge and node occurrences; and
- explicit illegal-state diagnostics for a cycle or missing producer.

Resolver09 implements the same existing contracts as Resolver03 and Resolver08 except
`DepthFirstTaskOrderingContract`. Cover exact slot identity, completion, progressive discovery, and
the absence of depth-based dispatch in focused Resolver09 tests rather than adding a new shared
contract.

Reuse the existing generated cases and oracles with fresh `Assumptions`, comparing final results, `correctResolution`, application identity counts, and one application per coordinate.

For every successful Resolver09 test resolution, also compare the exact dependency coordinates
committed at resolver launch with an independent post-resolution oracle. The oracle locates
resolver occurrences in the completed result, materializes each occurrence's stamped object
fragment, and walks only that materialized value. At each visited object field, it uses the schema
registry to recognize resolver-owned fields and combines the traversal path, ground key, and list
indices into the expected resolver-instance coordinate. It must not call `resolverDependencies` or
reuse the runtime readiness selections.

## Reused Tests And Shared Stress

Resolver09 adds no new feature domain, so it should reuse Resolver03 and Resolver08's existing static contracts, generated property-test contracts, witness checks, mutation checks, and replay infrastructure. Point those existing tests at the Resolver09 entry point rather than designing a new lightweight property framework or new generated profiles.

The reused ordinary tests should continue to cover:

- exercise nonempty object fragments, transitive resolver dependencies, nodes, lists, abstract types, nulls, errors, and selective projection;
- keep `FromArgument` generation enabled in its existing variable profile; and
- assert final correctness and one application per exact coordinate through the existing oracles.

Use one shared stress contract for Resolver03, Resolver08, and Resolver09, changing only the resolver entry point and task-specific seed interface. Preserve the configuration, replayable seed and case-count interfaces, feature counters, final-correctness checks, and exact application-identity checks.

Resolver09 may add focused unit tests for stable slot identity, the coordinate walk,
`launchResolvers()`, and illegal-state diagnostics, but it should not create new shared test
contracts or generator infrastructure merely because its scheduler differs.

## Implementation Sequence

1. Add exact path coordinates and stable per-occurrence `SlotResolver` objects.
2. Add the second transitive object-fragment walk without changing `closeResolverDemand`.
3. Make `SlotOrchestrator` persistent with `launchResolvers(): Boolean`.
4. Replace the priority queue with the greedy resolver queue plus orchestrator list.
5. Mark slot resolvers finished only after cell and fringe publication.
6. Add quiescent illegal-state diagnostics.
7. Port Resolver08 contracts without the depth-order contract.
8. Reuse Resolver03/08 static, generated, witness, mutation, and replay tests against Resolver09.
9. Point the shared deep stress contract at Resolver09.

## Completion Criteria

Resolver09 is complete when:

- it covers Resolver08's feature domain and selective policy;
- field-resolver coordinates are exact `List<PathComponent>` paths ending in `Value.GroundKey`;
- every unresolved exact slot has one registered `SlotResolver`;
- `SlotResolver.isFinished` changes only after successful publication;
- incoming-demand closure remains unchanged and shared;
- the shared `resolverDependencies` walk computes and refreshes exact readiness coordinates;
- every launched `SlotResolver` is dependency-free;
- `launchResolvers()` launches all ready local slots and reports orchestrator completion;
- the event loop greedily drains resolver tasks and repeatedly scans unfinished orchestrators;
- nested and list-element coordinates appear as producer results become available;
- launch instrumentation records the exact dependency coordinates used to authorize each field resolver;
- successful Resolver09 tests independently reconstruct those dependencies from materialized
  completed results and compare the exact maps;
- quiescent unfinished and missing-producer states fail explicitly as illegal resolver states; and
- Resolver09 reuses Resolver03/08's ordinary and deep-stress test infrastructure; and
- `./gradlew check --console=plain` passes before fixed-seed stress is treated as additional evidence.
