# Resolver10 FromObjectPath Handoff

## Purpose

Add runtime support for `VariableDefinition.FromObjectField`, called **FromObjectPath** here, on top of the completed Resolver09 slot-readiness architecture.

Resolver10 keeps Resolver09's central rule: a created `SlotResolver` has no dependencies and runs immediately. Variables add work to persistent `SlotOrchestrator` scans before an exact slot may be created.

Read `readiness-handoff.md` first. This document assumes Resolver09 is implemented, tested, and in place.

## Assumed Resolver09 Contract

Resolver10 may rely on:

- one exact `List<PathComponent>` coordinate per field-resolver instance, ending in its `Value.GroundKey`;
- a global `Map<List<PathComponent>, Unit>` containing completed resolver coordinates;
- persistent `SlotOrchestrator` tasks only for OER occurrences with one or more applicable top-level active selections;
- `SlotOrchestrator.launchResolvers(): Boolean`;
- naive repeated readiness scans;
- greedy execution of every created `SlotResolver`;
- slot completion only after its cell, passive result tree, and fringe orchestrators are published;
- exact paths containing every list index;
- write-once OER cells and request-local variable bindings;
- selective `successorDemand()` and one application per exact slot; and
- explicit illegal-state diagnostics for quiescent unfinished work and missing producers.

Do not replace these with cell subscriptions, depth priority, or a dependency-indexing framework while adding variables.

## Required Semantics

For one exact defining resolver occurrence:

1. Its identity is its exact OER-tree `List<PathComponent>` coordinate.
2. Every variable template is stamped with `containingObjectPath + groundKey`.
3. `FromArgument` variables bind when that exact occurrence expands.
4. Each FromObjectPath definition registers one occurrence-owned pending provider read with the same stamp.
5. The provider path must become ready before its variable binds.
6. A variable binds once to null, `Value.Error`, a scalar, an enum, or a terminal scalar list.
7. A selection containing unbound variables remains symbolic and creates no exact slot.
8. Binding substitution happens before exact-key grouping.
9. Symbolic selections that converge on one ground key contribute to one slot and one application.
10. Distinct OER occurrences and list positions retain distinct stamps and slot coordinates.

Provider reads distinguish:

```text
not ready
ready with null
ready with Value.Error
ready with a non-null ground input
```

An intermediate null binds null, and an intermediate error binds `Value.Error`. Provider paths cannot cross a list or terminate at an object. Their terminal value may be a scalar, enum, scalar list, or nested scalar list.

## What Variables Change

Resolver09 seals local demand when an orchestrator is registered. Resolver10 cannot.

A FromObjectPath value may be needed to ground a selected resolver key. Resolving its provider can therefore reveal:

- a new exact resolver occurrence;
- that occurrence's stamped direct object fragment;
- more provider definitions;
- more executable demand; and
- late equality with an exact key already known.

Resolver10 orchestrators own **open local demand** for OER occurrences with one or more applicable top-level active selections and repeatedly make progress through three distinct transitions. An active selection counts even while its arguments remain open because its registered field coordinate is already known.

1. **Expand:** a newly ground exact resolver occurrence contributes its fixed input requirements and variable definitions.
2. **Bind:** a ready provider path writes one stamped variable.
3. **Launch:** an exact slot whose dependencies and complete output demand are ready becomes a dependency-free `SlotResolver`.

Expansion is not slot creation. An exact occurrence may expand long before its input is materializable.

## Runtime Carriers

The exact types may follow local conventions:

```kotlin
data class ResolverOccurrence(
    val coordinate: List<PathComponent>,
)

data class PendingSelection(
    val containingObjectPath: List<PathComponent>,
    val selection: Selection,
    val provenance: DemandProvenance,
)

data class PendingObjectPathBinding(
    val owner: ResolverOccurrence,
    val variable: Value.Variable.Stamped,
    val path: List<Value.Key>,
)
```

An orchestrator additionally retains:

- all open demand contributions;
- exact resolver occurrences already expanded;
- exact keys already assigned to slots;
- pending provider bindings owned by occurrences on that OER;
- exact-key demand accumulated so far; and
- projection-envelope/sealing state.

Every collection grows or moves members monotonically. Do not remove a demand contribution except by recording its terminal classification, overwrite a binding, reopen a created slot, or add demand to a sealed slot.

Keep provenance for query demand and every defining resolver occurrence. Late-demand and illegal-state diagnostics must identify who contributed each selection.

## Model Helpers

Add one model-owned helper that stamps provider definitions exactly as `stampedObjectFragment` stamps selection arguments:

```kotlin
data class StampedObjectPathDefinition(
    val variable: Value.Variable.Stamped,
    val path: List<Value.Key>,
)

fun FieldResolver.stampedObjectPathDefinitions(
    sitePath: List<PathComponent>,
): List<StampedObjectPathDefinition>
```

Add focused variable inspection for:

- `OpenArguments`;
- one selection key;
- a recursive selection subtree; and
- a selection forest.

Readiness predicates should ask which stamped variables are unbound. Do not call `instantiateBindings()` and catch its exception as normal control flow.

Test that one exact occurrence uses the same stamp in its direct fragment, provider paths, pending selections, and `Assumptions` binding store.

## Symbolic And Exact Slot Identity

An exact resolver coordinate remains a `List<PathComponent>` ending in a `Value.GroundKey`.

Do not put an open `Value.ObjectKey` on the readiness board and do not invent a fake exact coordinate for it. Before grounding, identity belongs to a `PendingSelection` plus its containing OER and provenance.

When every stamped variable in a pending top-level key is bound:

1. Instantiate its arguments.
2. Form the exact `Value.GroundKey`.
3. Merge its subselections with every contribution that grounds to that key.
4. Associate the result with the one exact OER-tree coordinate.
5. Expand that exact resolver occurrence once if it is registered and error-free.

This separate pending domain is the symbolic coordinate extension that Resolver09 intentionally did not need.

## Resolver Occurrence Expansion

When a newly ground exact registered occurrence expands:

1. Record its exact coordinate as expanded.
2. Bind its `FromArgument` variables from its exact key.
3. Add `resolver.stampedObjectFragment(sitePath)` to the orchestrator's open demand.
4. Add `resolver.stampedObjectPathDefinitions(sitePath)` to pending provider work.
5. Re-specialize newly added selection contributions against the concrete containing type.

Do not materialize input, apply the resolver, or create its `SlotResolver` yet.

Expand each exact occurrence once even when several symbolic selections converge on it. Repeated discovery merges demand but must not bind its definitions or add its fixed fragment again.

Nested resolver templates receive their own stamps only when their own exact occurrences expand. Do not stamp a transitive fragment with an ancestor's occurrence path or reconstruct aliases later.

## Provider Readiness

Provider binding is another naive orchestrator scan.

Walk each pending provider path relative to its defining occurrence's containing OER:

1. Specialize the next key to the current OER's concrete type.
2. If its key arguments contain unbound stamped variables, report not ready.
3. Ground the key.
4. If it is active, derive its exact OER-tree coordinate.
5. If that coordinate is absent from the readiness board, report not ready.
6. If it is present, require its exact cell and continue through its value.
7. If it is passive, require its value to have been published by the nearest completed authoring slot.
8. At an intermediate null, return ready with null.
9. At an intermediate error, return ready with `Value.Error`.
10. At the terminal, convert the scalar, enum, or terminal list to `Value.Input?`.

Provider paths cannot cross lists, so the reader never chooses among list elements. Terminal list conversion remains recursive and positional.

On readiness, call `world.bind` and mark that pending binding complete. An already-bound variable is an error, not an idempotent retry.

If a passive provider cell is absent after all possible authoring slots are complete, report missing producer rather than waiting forever.

## Orchestrator Scan

Resolver10 extends Resolver09's `launchResolvers(): Boolean`. Before checking exact slot readiness, one call may:

1. Ground pending selections whose bindings are now available.
2. Merge exact-key contributions.
3. Expand newly exact resolver occurrences.
4. Bind provider paths whose predecessor slots are complete.
5. Update conservative projection envelopes and seals.
6. Create every exact slot whose predecessor slots are complete and whose demand is sealed.

Repeat from the top whenever any scan made progress, even if no slot was created. Binding one variable or expanding one occurrence is progress.

The Boolean retains Resolver09's meaning: `true` means the orchestrator has no remaining work and should be removed; `false` means it is still waiting. Detect global progress by comparing monotonic expansion, binding, sealing, launch, or readiness-board counts across the pass.

The event loop may be:

```kotlin
while (true) {
    drainCreatedSlotResolvers()
    val progressBefore = progressSnapshot()

    val iterator = slotOrchestrators.iterator()
    while (iterator.hasNext()) {
        if (iterator.next().launchResolvers()) {
            iterator.remove()
        }
    }

    if (slotResolverQueue.isNotEmpty()) continue
    if (slotOrchestrators.isEmpty()) return result

    if (progressSnapshot() == progressBefore) {
        failWithIllegalResolverStateReport()
    }
}
```

No callbacks, subscriptions, incremental dependency graph, or efficient worklist is required.

## Slot Creation

An exact Resolver10 `SlotResolver` may be created only when:

- its key is ground;
- its exact occurrence has expanded;
- every stamped variable needed by its object fragment is bound;
- Resolver09's recursive predecessor-slot readiness predicate is ready;
- its destination cell is absent;
- its exact coordinate is neither already launched nor present on the readiness board; and
- its selective output demand is sealed.

Once created, it has no dependencies and runs under Resolver09's greedy rule.

Argument-error and `__typename` slots remain immediately launchable once their exact keys exist. They do not register resolver variables or apply a field resolver.

## Demand Sealing

Readiness does not solve producer completeness.

Consider:

```text
B provides a variable used in C's key
C's fixed object fragment adds demand to A
A and B are otherwise independent
```

If A's slot is created before B binds the variable and C expands, C's demand arrives too late.

Late equality is the second decisive case:

```text
A(arg: "x") { forProvider }
A(arg: $value) { forConsumer }
$value later binds to "x"
```

The one exact A slot must receive both subselections before creation. A second application or post-application widening is invalid.

Resolver10 therefore needs two demand representations:

- **Executable demand** retains open arguments and eventually creates exact slots.
- **Projection envelopes** conservatively describe all producer-owned output a possible exact slot may need before every symbolic key is ground.

Do not overload one `SelectionForest` value with both meanings.

The recommended first envelope is conservative by structural field branch:

1. Bound every possible contributor using the finite canonical registry, fixed object fragments, query demand, and type guards.
2. Preserve passive paths and behavioral boundaries without requiring every boundary argument to be ground.
3. Union the subselection envelopes of symbolic same-field occurrences into every exact key they may later equal.
4. Allow executable keys to instantiate only after bindings arrive.
5. Seal an exact slot only when no bounded contributor can enlarge its supplied projection envelope.

Over-projection is acceptable. Under-projection is not.

The existing branch-order validation does not by itself prove sealing. Ordinary execution may require `A -> C`, while demand discovery requires `C expansion -> A seal`; these are different phases. If sealing needs a validated contributor relation, expose it from the canonical registry rather than reaching into test-fixture internals.

If the accepted registry domain cannot provide a finite conservative envelope for a shape, reject that shape during registry construction with provenance. Do not run a selectively under-supplied slot.

## Output Traversal

Audit these eager grounding boundaries together:

- `SelectionForest.successorDemand`;
- `SelectionForest.successorBoundaryDemand`;
- `Value.Output?.snipToDemand`; and
- `Value.Output?.resolveValue`.

The required split is:

- passive producer-owned fields must be ground before reading them from a value;
- an open behavioral boundary may contribute projection shape before its arguments are ground;
- an open boundary creates pending executable demand, not an exact slot;
- exact downstream stamping waits for the real containing OER and ground key; and
- list positions always enter the exact path.

Keep projection-only open boundaries separate from executable selections so they cannot accidentally create or stamp a resolver occurrence.

Resolver10 slot completion retains Resolver09's meaning: the cell and passive result tree are published, and every fringe OER with one or more active demanded fields has an orchestrator, before the slot inserts its coordinate into the readiness board. Passive-only OERs receive no orchestrator.

## Failure And Diagnostics

A quiescent unfinished Resolver10 state is illegal under the accepted-world invariants and indicates a composition or implementation bug. Its failure report should include:

- unbound variables and their defining occurrence coordinates;
- pending provider paths and the slot coordinate currently blocking each;
- pending symbolic selections and their unbound variables;
- exact expanded occurrences not yet launchable;
- predecessor slot requirements;
- unsealed exact slots and their possible demand contributors;
- absent producer slots;
- missing passive values; and
- readiness-board/OER disagreement.

Distinguish the observed shape of the illegal state, including variable cycles, resolver dependency cycles, missing producers, and incomplete sealing. These are bug diagnostics, not normal execution outcomes. Queue emptiness alone is never success.

## Correctness Evidence

`correctResolution` remains an extensional final-tree judgment. It does not prove provider provenance, sealing, or one-shot application.

Retain a separate execution witness recording:

- each exact slot's defining coordinate;
- its expansion, creation, application, and completion counts;
- the demand envelope supplied to its application;
- every binding's defining occurrence, provider path, and value; and
- demand-contribution provenance.

Check that every exact slot applies once after sealing, every binding equals the value read from its occurrence-relative provider path, and every consumed producer-owned value was covered by the producing application.

## Implementation Sequence

1. Add variable-inspection and stamped-provider helpers with stamp-coherence tests.
2. Add pending symbolic selections and exact occurrence expansion without provider binding.
3. Add naive provider-path readiness and terminal conversion.
4. Validate binding behavior first with complete-output resolver applications so projection cannot hide provider bugs.
5. Define projection envelopes and sealing before enabling selective FromObjectPath execution.
6. Pass the late-demand and late-equality counterexamples.
7. Make output traversal tolerate projection-only open boundaries.
8. Enable Resolver10's selective policy.
9. Add the static FromObjectPath resolver contract alongside the FromArgument contract.
10. Enable FromObjectPath generation in the lightweight Resolver10 CI property suite.
11. Add fair-schedule, witness, mutation, and mixed-variable generated coverage.
12. Add the dedicated fixed-seed Resolver10 stress suite with both variable kinds enabled.

The complete-output step is scaffolding, not completion. Resolver10 is complete only with selective one-shot applications.

## Deterministic Test Matrix

Cover:

- direct scalar and enum providers;
- nested provider paths;
- terminal scalar and nested scalar lists;
- nullable and error intermediates;
- provider keys depending on another variable;
- providers with their own resolver prerequisites;
- uses nested in input objects and lists;
- multiple independent and dependent variables;
- repeated defining keys on one object;
- recursive defining occurrences;
- defining occurrences in distinct list positions;
- abstract provider paths;
- fixture-lowered bridge prerequisites;
- argument-error keys after substitution;
- symbolic keys remaining distinct;
- symbolic keys converging with disjoint demand;
- the `B -> C -> A` late-demand shape;
- null/error preventing descendant slot creation;
- strict second-bind, second-expansion, second-slot, and post-seal failures;
- illegal-state diagnostics for a variable cycle; and
- stable, reverse, and seeded-random orchestrator scans producing equal results and application sets.

For repeated and list cases, assert exact stamps as well as final values. For selective cases, assert demand supplied to the producing slot rather than only final OER contents.

Generated profiles must count generated FromObjectPath definitions, activated defining occurrences, successful provider bindings, null/error bindings, list and recursive stamps, and late-equality candidates. A green profile must not silently exercise only `FromArgument`.

## Static Test Contracts

Add a reusable `ObjectFragmentFromObjectPathResolverContract` alongside `ObjectFragmentFromArgumentResolverContract`, and make Resolver10's static contract test implement both. The new contract covers object fragments whose arguments use path-defined variables; the existing contract continues to establish argument-defined behavior.

The static FromObjectPath contract should cover:

- a direct provider and sibling consumer;
- a nested provider path;
- null and error absorption;
- a terminal scalar list;
- a provider with resolver prerequisites;
- a use nested in an input object or list;
- dependent path and argument variables in one fragment;
- repeated defining occurrences;
- distinct list-position stamps;
- exact-key convergence after binding; and
- one application per resulting exact slot.

Keep the contract independent of Resolver10 implementation details so later resolver versions can reuse it. Assert final output, exact bindings, resolver application identities, and supplied selective demand where relevant.

## CI Property Tests

FromObjectPath generation already exists in arbitrary registry construction but is not enabled by semantic resolver tests. Enabling and activating it in the lightweight property tests that run in CI is a required part of Resolver10.

Add an `ObjectFragmentFromObjectPathGeneratedResolverContract`. Its configuration must enable both variable kinds:

```kotlin
ResolverVariablesEnabled to true
ResolverFromArgumentVariablesEnabled to true
```

Do not replace `ObjectFragmentFromArgumentGeneratedResolverContract`. Resolver10 should implement both generated contracts and an interaction profile in which path-defined and argument-defined variables occur in the same generated worlds.

The CI property suite must assert positive counts for:

- generated FromObjectPath definitions;
- activated defining resolver occurrences;
- successful path bindings;
- generated and activated `FromArgument` definitions;
- cases activating both variable kinds together; and
- nested, repeated, recursive, list, null/error, or abstract provider features across the configured batch.

Preserve schema/registry/query coordinates, seed, case number, bindings, readiness board, and application witness on failure. A passing run that generated path variables but activated none is a failed profile.

## Stress Test

Add a dedicated fixed-seed Resolver10 stress test. It must turn on FromObjectPath generation with `ResolverVariablesEnabled`, keep `ResolverFromArgumentVariablesEnabled`, and retain deep dependency-heavy object fragments, lists, abstract types, nodes, nulls, and errors.

The stress test should use an explicit replayable seed and case-count interface and report attempted cases, verified cases, applications, generated and activated definitions of both kinds, successful provider bindings, list and recursive stamps, null/error bindings, node-loader interactions, and late-equality candidates.

Run the static contracts and lightweight CI property suite before the stress test. Stress volume is additional finite evidence, not a substitute for static contracts or the focused late-demand and late-equality cases.

## Non-Goals

- operation variables;
- `fromQueryField`, `@parent`, or `VariablesProvider`;
- callback subscriptions or efficient dependency indexing;
- concurrent JVM execution;
- mutable variable identity;
- bindings stored in OERs;
- reopening a created or completed slot;
- post-application widening;
- repeated resolver application; and
- treating complete outputs as selective correctness evidence.

## Completion Criteria

Resolver10 is complete when:

- every defining occurrence has coherent exact stamps;
- providers bind null, error, scalar, enum, and terminal-list values correctly;
- symbolic selections create no exact slot before grounding;
- exact occurrences expand once and exact-key convergence precedes slot creation;
- every created slot remains dependency-free;
- every selective slot receives a sealed conservative producer envelope;
- no binding, slot, cell, or seal is written twice or reopened;
- recursive and list occurrences remain distinct;
- fair tested schedules produce the same result and application set;
- quiescent unfinished and missing-producer states fail explicitly as illegal resolver states;
- Resolver10 has static contracts for both FromObjectPath and `FromArgument` object fragments;
- lightweight CI property tests enable and activate both variable kinds;
- a dedicated fixed-seed Resolver10 stress test enables FromObjectPath and `FromArgument` generation; and
- `./gradlew check --console=plain` passes before fixed-seed stress is treated as additional evidence.
