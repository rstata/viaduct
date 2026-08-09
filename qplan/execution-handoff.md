# Future Monotonic Worklist Executor

## Purpose

This document sketches the next executor after Resolver03: retain structural demand collection, but schedule resolver applications by data availability over a monotonic, write-once execution state.

Resolver02 and Resolver03 already execute variables defined `FromArgument`. Runtime binding from `FromObjectField` is the important future extension. Such a binding can make an open selection concrete only after another subtree has produced its provider value, so recursive depth-first order is no longer sufficient.

The durable evidence behind this direction, including the Resolver04 and Resolver05 lessons, lives in [evergreen.md](evergreen.md). This handoff records only the active design.

## Semantic State

The executor should distinguish three kinds of state:

```kotlin
data class ExecutionState(
    val objects: Map<ObjectId, EngineResult.Object>,
    val obligations: Map<CellId, ResolutionObligation>,
    val pendingSelections: Set<PendingSelection>,
)

data class ResolutionObligation(
    val containingObject: ObjectId,
    val key: Value.GroundKey,
    val demandedOutput: SelectionForest,
)

data class PendingSelection(
    val containingObject: ObjectId,
    val selection: Selection,
)
```

The exact carriers may change, but their boundaries should not:

- `ObjectId` identifies one concrete OER occurrence, including distinct list positions and recursive occurrences.
- A concrete obligation targets one exact `(ObjectId, Value.GroundKey)` field value.
- A pending selection may contain open arguments and cannot enter an OER until substitution produces a `Value.GroundKey`.
- Request-local variable bindings remain in `Assumptions` unless a later design gives a compelling reason to relocate them.

## Monotonicity

Mutable execution state follows one rule: facts move from unset to set and never change.

- An OER value, field check, or type check changes atomically from absent to one immediate or deferred `Promise`; each deferred promise completes once.
- A variable changes from unbound to one stored nullable input value.
- An obligation changes from pending to claimed or completed without being recreated.
- A published child OER retains stable identity while its own absent promises are installed and completed.

No transition replaces a promise, changes a binding, widens an application record, or rebuilds an ancestor. The quiescent mutable OER tree already has ordinary `EngineResult` shape, so a separate freeze step is not intrinsically required.

## Demand Collection

Demand discovery remains a structural phase related to Resolver03's guarded predecessor closure and successor-demand lifting. Execution must not discover extra producer demand after applying that producer.

The collector must account for:

- transitive object-fragment demand before selective application;
- exact OER occurrence paths and possible concrete-type guards;
- all contributors targeting one producer occurrence;
- open variable-bearing selections at boundaries where exact arguments are not yet available;
- possible convergence between a currently concrete key and a key that becomes equal after substitution.

Late equality is the central unresolved problem. If `field(arg: "literal")` and `field(arg: $value)` can become the same exact field, their complete output demands must be combined before the one application. Scheduling cannot repair an under-projected result afterward.

The contract is:

> Demand collection conservatively seals the complete output envelope for every possible exact producer field; scheduling later determines which envelopes instantiate and become runnable.

## Readiness And Application

A concrete obligation is ready when:

1. its key is ground;
2. every value promise needed to materialize its object fragment is installed;
3. every referenced stamped variable is bound;
4. its destination value promise is absent; and
5. its demanded-output envelope is sealed.

Argument errors set the required error value without applying the resolver.

Applying a ready obligation performs one conceptual transition:

1. Materialize the resolver input from the containing OER.
2. Apply the resolver once with its complete demanded output.
3. Complete the destination value promise once.
4. Allocate stable child OER identities for object and list output.
5. Populate demanded producer-owned passive fields.
6. Create concrete or pending obligations at behavioral boundaries.
7. Bind any provider variables that have become readable.

The worklist is a finite obligation map, not necessarily a FIFO queue. Duplicate discovery for the same exact field must converge before application.

## Completion And Failure

A successful terminal state has no unfinished demanded values, pending selections, or unreadable required bindings. An empty ready set with unfinished work is a dependency-cycle or invalid-state witness.

`correctResolution` remains useful as a final extensional oracle for its current domain, but it does not inspect provider bindings or application history. Runtime `FromObjectField` support therefore needs a variable-aware execution oracle or explicit state invariants in addition to final-tree validation.

Cyclic object references remain outside the model. The allocated object/list occurrence graph is finite even though its OERs may be populated incrementally.

## Required Invariants

- Every `ObjectId` denotes one concrete OER occurrence with one concrete object type.
- Every exact field is identified by `(ObjectId, Value.GroundKey)`.
- Promises and bindings are write-once.
- Every concrete obligation has one sealed demanded-output envelope.
- Every resolver-bearing exact field has at most one mathematical application.
- Pending selections use bindings stamped for their defining containing OER.
- Equal instantiated keys converge on one obligation and value promise.
- Distinct occurrences never coalesce merely because their values or node IDs are equal.
- Null and error outputs create no unreachable descendant obligations.
- Every valid unfinished state has ready work, subject to the intended acyclicity condition.

## Parallel Interpretation

The ready set exposes independent obligations directly. A runtime may claim distinct fields concurrently, complete promises, and batch compatible resolver calls without changing per-occurrence identities.

The semantic model can still take one transition at a time. Randomized fair schedules should reach the same quiescent result and application set; this is the key test for accidental dependence on map iteration or depth-first order.

## Work Sequence

1. Define `ObjectId`, obligation, pending-selection, and monotonic transition carriers around mutable `EngineResult.Object`.
2. Add hand-constructed traces for completion, deadlock, null, error, lists, recursion, and abstract types.
3. Make the historical late-equality case the first negative demand-sealing fixture.
4. Specify the collector output that represents concrete work, symbolic templates, and conservative convergence coverage.
5. Connect Resolver03 demand collection to initial obligation creation.
6. Add a variable-aware oracle for runtime `FromObjectField` traces.
7. Compare variable-free worlds with Resolver03 and run randomized fair schedules.
8. Translate the stabilized transition system into TLA+ as a monotonic-write refinement target.

## Open Questions

- What finite symbolic envelope is precise enough to seal possible late-equality demand without applying producers unnecessarily?
- Is variable binding a first-class obligation or a deterministic transition once its provider path is readable?
- Can complete initial projection eliminate every need to retain raw resolver output?
- What acyclicity measure proves progress for combined promise and binding dependencies?
- What schedule-independence theorem best supports a future parallel implementation?
