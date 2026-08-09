# Resolver10 FromObjectPath Handoff

## Status And Purpose

Resolver09 is implemented at commit `e31ceb9` and remains the readiness-based implementation of Resolver03/08's feature set. Resolver10 adds runtime support for `VariableDefinition.FromObjectField`, called **FromObjectPath** here, without changing Resolver09.

Read `handoff.md`, `readiness-handoff.md`, and `semantics/src/main/kotlin/semantics/resolver09/Reactor.kt` first. Treat the implemented Resolver09 code as the scheduling baseline and this document as the design boundary for the new variable feature.

Efficiency is not a goal. Prefer explicit state, repeated scans, and independently checkable predicates over callbacks, subscriptions, or an incremental dependency graph.

## Implemented Resolver09 Baseline

Resolver10 should copy and adapt Resolver09's private reactor into `semantics.resolver10`; do not retrofit Resolver09 or force both versions through a shared scheduler abstraction.

The reusable runtime shape is:

- every unresolved exact local slot is represented immediately by one stable `SlotResolver`;
- exact field-resolver-instance identity is a root-relative `List<PathComponent>` ending in its `Value.GroundKey`;
- `slotResolversByCoordinate: MutableMap<List<PathComponent>, SlotResolver>` is the global registry;
- each slot owns `isFinished`, initially false;
- each `SlotOrchestrator` owns `MutableMap<SlotResolver, Set<SlotResolver>>` for its unfinished local candidates;
- `launchResolvers()` refreshes exact dependencies, queues every ready candidate, removes queued candidates, and returns whether the orchestrator is finished;
- queued `SlotResolver` tasks have no dependencies and run greedily before the next orchestrator scan;
- a slot publishes its cell and active fringe orchestrators before setting `isFinished`;
- quiescent unfinished work is an illegal resolver state caused by a violated invariant or implementation bug, not a deadlock; and
- `resolver09.Reactor` remains private while `ReactorInstrumentation`, `resolveKey`, exact coordinate rendering, and exact `resolverDependencies` are shared where their preconditions still hold.

There is no `Map<List<PathComponent>, Unit>` completion board. Readiness is tested through stable slot objects in `slotResolversByCoordinate` and their `isFinished` state.

Persist a `SlotOrchestrator` only for a published OER occurrence whose applicable demand contains at least one active field with a resolver, including an active field whose key arguments are still open. Passive-only OERs receive no orchestrator.

## Resolver10 Semantic Contract

For one exact defining resolver occurrence:

1. Its identity and variable stamp are its exact resolver coordinate, `containingObjectPath + groundKey`.
2. `FromArgument` definitions bind from that exact key when the occurrence expands.
3. `FromObjectPath` definitions read relative to that occurrence's containing OER and use the same stamp.
4. A binding is written exactly once through `Assumptions.bind`.
5. A provider may produce null, `Value.Error`, a scalar, an enum, or a recursively shaped terminal scalar list.
6. Provider paths cannot cross lists or terminate at objects.
7. A selection containing an unbound stamped variable remains symbolic and creates no exact coordinate.
8. Binding substitution and exact-key regrouping happen before resolver application.
9. Symbolic selections that converge on one ground key contribute to one exact slot and one resolver application.
10. Recursive occurrences and distinct list positions retain distinct coordinates and stamps.

Provider reading must distinguish `not ready`, `ready with null`, `ready with Value.Error`, and `ready with a non-null ground input`. Null or error at an intermediate path component terminates the read and binds that value.

## Why Resolver09 Cannot Be Reused Unchanged

Resolver09 calls `closeResolverDemand` once, grounds every local key, constructs all exact slots, and treats the resulting local demand as sealed. FromObjectPath variables invalidate each of those assumptions.

Binding a provider can reveal a newly exact resolver occurrence. Expanding that occurrence can add its stamped fixed object fragment, register more providers, add demand to an already known exact key, or cause two formerly distinct symbolic selections to converge.

Resolver10 therefore needs two domains:

- **symbolic pending demand**, where selections may contain unbound stamped variables and have no exact slot identity; and
- **exact slot state**, where the field, arguments, coordinate, and stable `SlotResolver` object are known but the slot may still be accumulating demand before it is sealed and launched.

Do not represent an open key with a fake coordinate or put it in `slotResolversByCoordinate`.

## Monotonic Runtime State

Each OER-local orchestrator should retain explicit monotonic state:

- open applicable demand contributions and their provenance;
- pending symbolic selections;
- exact keys discovered so far;
- exact resolver occurrences expanded so far;
- pending stamped provider bindings;
- stable exact slot objects registered for discovered exact keys;
- accumulated pre-launch selection demand for each exact slot;
- conservative projection-envelope and sealing state; and
- launched and finished facts.

Collections may grow, or an item may move once to a terminal classification. Never overwrite a binding, expand an occurrence twice, register a second slot at one coordinate, add demand after sealing, launch twice, finish twice, or reopen a sealed slot.

An exact Resolver10 slot object may need more pre-launch state than Resolver09's immutable `selection`:

```kotlin
coordinate
accumulatedSelection
expanded
sealed
launched
isFinished
```

The exact coordinate is immutable. `accumulatedSelection` may widen only before `sealed`; all launch inputs become immutable at sealing. Register the stable placeholder in `slotResolversByCoordinate` as soon as its key becomes exact so other orchestrators can refer to the same object before it is launchable.

The object is a stable readiness candidate, not an executing task. Once queued, its `SlotResolver` task has no dependencies and runs immediately under Resolver09's greedy rule.

## Resolver Occurrence Expansion

When a pending selection becomes exact, merge it by `Value.GroundKey` with every earlier contribution. If the key names a registered resolver and has no argument error, expand that exact occurrence once:

1. Record the exact coordinate as expanded.
2. Bind its `FromArgument` definitions from the exact key.
3. Add `resolver.stampedObjectFragment(coordinate)` to the containing orchestrator's open demand.
4. Register the resolver's stamped FromObjectPath definitions for occurrence-relative reading.
5. Re-specialize new demand against the concrete containing type.
6. Continue the local fixed point because these actions may ground more selections.

Expansion is not launch. It does not materialize input, apply a resolver, publish a cell, or finish a slot.

Repeated discovery of the same exact key merges subselections into the existing placeholder but does not bind definitions or add the fixed fragment again. Nested resolver templates are stamped only when their own exact occurrence expands.

Argument-error and `__typename` keys do not expand a field resolver or register variable definitions. Their exact slots retain Resolver09's engine-owned behavior once their keys exist and their demand is sealed.

## Stamped Provider Definitions

Add a model-owned helper parallel to `FieldResolver.stampedObjectFragment`:

```kotlin
data class StampedObjectPathDefinition(
    val variable: Value.Variable.Stamped,
    val path: List<Value.Key>,
)

fun FieldResolver.stampedObjectPathDefinitions(
    sitePath: List<PathComponent>,
): List<StampedObjectPathDefinition>
```

`sitePath` is the full exact defining resolver coordinate. The helper stamps both the defined variable and every variable template nested in every provider-path key argument with that same path.

Add focused inspection operations that return stamped variables used by `OpenArguments`, one key, one recursive selection subtree, and a selection forest. Readiness code should ask whether those variables are bound; do not use `instantiateBindings()` exceptions as ordinary control flow.

Test stamp coherence directly: the defining variable, its provider path, its stamped object fragment, pending symbolic selections, and `Assumptions` must all use the same occurrence coordinate.

## Provider Readiness

A pending provider read owns its defining occurrence coordinate, containing OER path and target object, stamped variable, and stamped key path.

Read the path from the defining occurrence's containing OER:

1. Specialize the next path key to the current concrete object type.
2. If the key contains an unbound stamped variable, report not ready.
3. Instantiate its arguments and derive the exact coordinate.
4. For a registered resolver field, look up the stable slot in `slotResolversByCoordinate`; absence or `isFinished == false` means not ready.
5. After an active slot is finished, require its cell to be published and continue through its value.
6. For a passive field, require the cell to be present; otherwise report not ready and retain enough information to diagnose a missing producer at quiescence.
7. Return ready null or ready `Value.Error` immediately when either is encountered.
8. At the terminal, convert the scalar, enum, or `EngineResult.List` recursively to `Value.Input?`.
9. Reject an intermediate list, a terminal object, or any value incompatible with the compiled definition.

On readiness, call `Assumptions.bind` once and mark the pending binding complete. An already-bound variable indicates a duplicate transition bug; do not make retries idempotent.

The provider path is structurally contained by the defining resolver's fixed fragment, so its active fields also enter that orchestrator's open demand. Do not invent producer slots from provider definitions separately from demand processing.

## Orchestrator Fixed Point

Resolver10 keeps `SlotOrchestrator.launchResolvers(): Boolean`, with `true` meaning no work remains for that orchestrator.

One call may repeatedly:

1. specialize open contributions to the concrete OER without requiring all arguments to be ground;
2. ground every pending selection whose stamped variables are now bound;
3. regroup newly ground selections by exact key and widen existing pre-launch placeholders;
4. expand newly exact resolver occurrences;
5. bind every provider path currently ready;
6. update conservative projection envelopes and seal exact slots whose demand can no longer grow;
7. refresh exact Resolver09-style dependencies for sealed, fully ground candidates; and
8. queue every sealed candidate whose dependency slot objects are finished.

Repeat the local scan whenever any transition makes progress. A binding, expansion, merge, seal, registration, or launch all count as progress.

One orchestrator's binding may unblock an orchestrator that was scanned earlier in the same global pass. Track a monotonic global progress version or equivalent snapshot, while preserving the Boolean return value solely as “this orchestrator is finished”:

```kotlin
while (true) {
    drainSlotResolverQueue()
    val before = progressVersion

    val iterator = unfinishedOrchestrators.iterator()
    while (iterator.hasNext()) {
        if (iterator.next().launchResolvers()) iterator.remove()
    }

    if (slotResolverQueue.isNotEmpty()) continue
    if (unfinishedOrchestrators.isEmpty()) return validatedResult()
    if (progressVersion != before) continue

    throw IllegalResolverStateException(illegalStateReport())
}
```

No callback subscriptions, waiter index, priority queue, or incremental dependency graph is required.

## Exact Dependency Reuse

Keep `semantics.resolverDependencies` exact. It remains useful after a candidate's key and stamped object fragment are fully ground.

Do not weaken it into an open-demand operation unless implementation evidence makes that unavoidable. Prefer a Resolver10-local gate or overload that waits until the candidate fragment is ground, then calls the existing walk and translates returned coordinates through `slotResolversByCoordinate.getValue`.

After successful resolution, reuse `ResolverDependencyOracle` only after every activated occurrence's bindings are established; its `FieldResolver.objectFragmentAt` currently grounds eagerly.

## Demand Sealing

Readiness of input producers does not prove that an exact slot has received all output demand. Resolver10 must solve producer-demand sealing before retaining Resolver09's selective output policy.

The first required counterexample is:

```text
B provides a variable used in C's key
C's fixed object fragment adds demand to A
A and B are otherwise independent
```

Launching A merely because its current dependencies are ready can omit the demand revealed when B binds and C expands.

The second required counterexample is late equality:

```text
A(arg: "x") { forProvider }
A(arg: $value) { forConsumer }
$value later binds to "x"
```

Both contributions must reach one exact A slot before its single application.

Keep executable demand separate from a conservative **projection envelope**:

- executable demand retains symbolic arguments until they bind and determines exact occurrences;
- the projection envelope may over-approximate producer-owned output needed by every bounded future contributor;
- open behavioral boundaries may remain symbolic in the envelope because projection stops at them;
- passive producer-owned fields must be ground before they are read from an output value; and
- an exact slot seals only when no pending or conservatively bounded contributor can widen the demand supplied to that application.

Over-projection is acceptable for the first correct implementation. Under-projection, post-launch widening, and a second application are not.

Use the finite canonical registry, query demand, fixed resolver fragments, type guards, and branch-order information to compute a conservative bound. Existing branch-order validation is useful evidence but is not itself a sealing proof: ordinary execution order and demand-discovery order are distinct.

If an accepted registry shape cannot produce a finite conservative envelope, reject that shape during registry construction with provenance rather than launch a selectively under-supplied resolver.

## Eager-Grounding Boundaries

Audit these operations together rather than patching around their exceptions:

- `closeResolverDemand`;
- `applicableGroundSelections`;
- `FieldResolver.objectFragmentAt`;
- `SelectionForest.successorDemand` and `successorBoundaryDemand`;
- `Value.Output?.snipToDemand`; and
- `Value.Output?.resolveValue`.

Resolver09 can call them only because its local demand is already ground. Resolver10 needs open-aware local closure and projection behavior.

Preserve these distinctions:

- specializing and merging a selection forest does not require ground arguments;
- creating an exact slot does require a ground key;
- materializing passive input requires ground keys;
- projection may carry an open behavioral boundary without creating a runtime occurrence;
- executable occurrence stamping waits for the real exact coordinate; and
- list indices enter paths only from actual runtime values.

Do not let projection-only symbolic boundaries create slots, bind variables, or acquire occurrence stamps.

## Slot Completion And Orchestrator Creation

Resolver10 slot execution should retain Resolver09's publication order:

1. materialize the now-ground input;
2. apply the resolver once with its sealed demand;
3. project and construct the passive result tree;
4. allocate mutable child OERs;
5. publish the exact cell once;
6. register orchestrators for fringe OERs having at least one active demanded field; and
7. set `isFinished` last.

Completion is proof that the cell, passive result shape, and required active fringe orchestrators are visible. It is not merely proof that the resolver function returned.

## Illegal-State Diagnostics

Queue quiescence with unfinished work is an illegal state under the accepted-world invariants. Report:

- unbound variables and their defining occurrence coordinates;
- pending provider paths and the exact active coordinate or passive cell blocking each;
- pending symbolic selections and their unbound variables;
- exact occurrences not yet expanded;
- registered placeholders that are unsealed or unlaunched;
- possible contributors preventing sealing;
- exact predecessor slots and their finished state;
- absent producer slots or passive cells; and
- any variable or resolver dependency cycle visible in the remaining state.

Classify the observed shape, such as variable cycle, dependency cycle, missing producer structure, or incomplete sealing. These are diagnostics for a composition or implementation bug, not normal outcomes and not a multithreaded deadlock.

Queue emptiness alone is never success. Successful completion also requires no unfinished orchestrators, no pending symbolic selections or bindings, every registered slot finished, and every sealed OER demand published.

## Correctness Evidence

`correctResolution` remains an extensional final-tree judgment. It does not prove provider provenance, exact-once binding, demand sealing, or one application per occurrence.

Extend execution observations to retain:

- each exact slot coordinate and its registration, expansion, sealing, launch, and completion counts;
- the sealed demand supplied to each resolver application;
- each binding's defining occurrence, provider path, and value; and
- demand-contribution provenance sufficient to explain late equality and sealing.

The implementation and test oracle must not share provider-reading code.

Add an independent final-result binding oracle. It should locate activated resolver occurrences, reconstruct each FromObjectPath definition from the registry, stamp it at the occurrence path, read the provider path from the completed result, and compare that value with `world.binding(stampedVariable)`. It must cover null, error, list, recursive, and list-position occurrences.

After final binding validation, Resolver10 can reuse Resolver09's independent dependency oracle to compare applied exact dependency coordinates.

## Static Test Contract

Add `ObjectFragmentFromObjectPathResolverContract` beside `ObjectFragmentFromArgumentResolverContract`. Resolver10's contract test implements all Resolver09 contracts plus this new one; do not move FromObjectPath cases into an implementation-specific reactor test.

The static contract should cover:

- a direct sibling scalar or enum provider;
- a nested provider path;
- terminal scalar and nested scalar lists;
- nullable and error intermediates;
- a provider with active resolver prerequisites;
- provider-key arguments depending on another variable;
- uses nested in input objects and lists;
- FromObjectPath and `FromArgument` in one defining fragment;
- repeated defining keys and exact-key convergence;
- recursive defining occurrences and distinct list-position stamps;
- abstract provider paths and fixture-lowered bridge prerequisites;
- argument-error keys after substitution;
- the `B -> C -> A` late-demand case;
- late equality with disjoint subselections; and
- exact-once binding, expansion, slot registration, sealing, and application.

Assert final output, exact bindings, application identities, and supplied selective demand where relevant.

## CI Property Tests

FromObjectPath generation already exists, but current semantic resolver profiles deliberately set `ResolverVariablesEnabled` to false. Resolver10 needs new generated infrastructure because it adds a new feature.

Add `ObjectFragmentFromObjectPathGeneratedResolverContract` with an isolating path-only profile:

```kotlin
ResolverVariablesEnabled to true
ResolverFromArgumentVariablesEnabled to false
```

Keep `ObjectFragmentFromArgumentGeneratedResolverContract` unchanged and implemented by Resolver10. Add a mixed-variable interaction profile with:

```kotlin
ResolverVariablesEnabled to true
ResolverFromArgumentVariablesEnabled to true
```

Do not enable path variables in existing Resolver01-09 profiles.

`RegistryFeatures` currently has `variableCount` and `fromArgumentVariableCount` but no explicit path-variable count. Add `fromObjectFieldVariableCount`, a set of FromObjectPath owner fields, and an activation helper such as `sourceResolverHasFromObjectFieldVariables`.

The path-only profile must fail if it generates no path definitions or activates no path-variable-bearing resolver occurrence. The mixed profile must prove generation and coactivation of both variable kinds rather than merely enabling both flags.

Record schema/registry/query coordinates, seed, case number, final bindings, applied dependencies, and application witness on failure.

## Stress Test

Resolver09 correctly reused the shared `DeepResolverStressContract`; no new feature infrastructure was needed there. Resolver10 does need new stress infrastructure because path variables have never been exercised by semantic resolver stress.

Do not silently change Resolver03/08/09 stress behavior. Add overridable stress configuration and feature assertions with defaults preserving their current `ResolverVariablesEnabled = false`, or add a Resolver10-specific stress contract.

Resolver10 stress must enable both:

```kotlin
ResolverVariablesEnabled to true
ResolverFromArgumentVariablesEnabled to true
```

Retain deep dependency-heavy fragments, lists, abstract types, nodes, nulls, errors, and replayable seed/case controls. Report and positively assert generated and activated definitions of both kinds, successful provider bindings, recursive and list-position stamps, null/error bindings, node-loader interactions, and late-equality candidates.

Add a dedicated `resolver10Stress` Gradle task and keep stress opt-in. Static and lightweight CI properties remain required; stress volume is additional finite evidence.

## Implementation Sequence

1. Copy Resolver09 into `resolver10` and keep exact-slot scheduling behavior unchanged for the existing feature contracts.
2. Add variable inspection and stamped provider-definition helpers with stamp-coherence tests.
3. Introduce symbolic pending selections, stable exact placeholders, and one-time occurrence expansion.
4. Add naive provider-path readiness and terminal conversion.
5. Add the independent final-result binding oracle and pass focused complete-output provider cases first.
6. Define open-aware projection envelopes and exact slot sealing.
7. Pass the late-demand and late-equality counterexamples with one selective application per exact slot.
8. Split eager-ground output traversal from projection-only open boundaries.
9. Restore Resolver09's selective output policy and dependency-oracle validation.
10. Add the reusable static FromObjectPath contract.
11. Add path-only and mixed-variable CI generated contracts with generation and activation guards.
12. Add dedicated Resolver10 mixed-variable stress infrastructure.

The complete-output phase is scaffolding, not Resolver10 completion.

## Non-Goals

- operation variables;
- `fromQueryField`, `@parent`, or `VariablesProvider`;
- callback subscriptions or efficient dependency indexing;
- concurrent JVM execution;
- bindings stored in OERs;
- mutable variable identity;
- fake coordinates for symbolic selections;
- reopening a sealed or launched slot;
- post-application widening; and
- repeated resolver application.

## Completion Criteria

Resolver10 is complete when every defining occurrence has coherent exact stamps; provider reads correctly bind null, error, scalar, enum, and terminal-list values; symbolic selections create no exact slot before grounding; exact-key convergence happens before sealing and launch; exact slots remain stable and queued tasks remain dependency-free; every selective application receives a sealed conservative demand envelope; no binding, expansion, slot, seal, launch, finish, or cell is written twice; recursive and list occurrences remain distinct; quiescent unfinished states fail with useful illegal-state diagnostics; the independent binding and dependency oracles pass; Resolver10 implements all Resolver09 contracts plus static FromObjectPath coverage; path-only and mixed-variable CI profiles generate and activate their promised features; dedicated stress enables both variable kinds; and `./gradlew check --console=plain` passes before stress is treated as additional evidence.
