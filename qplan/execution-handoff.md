# Future Demand-Availability Worklist Executor

## Purpose

This document describes a possible future executor that preserves Resolver03's current demand-collection work and incorporates lessons from the historical Resolver04 experiment while replacing recursive depth-first construction with a worklist of resolution obligations over a write-once OER store.

The proposal responds to one specific discovery. Variables can transport a value from a provider path already contained in the defining resolver's fixed-shape object fragment to resolver arguments elsewhere in that fragment. A subtree may therefore contain structurally known demand whose exact `Value.ObjectKey` cannot be formed until work in another subtree finishes. Resolver04 attempted to handle this by retaining immutable prefixes, preserving raw output provenance, and widening already-resolved cells; focused counterexamples showed that recovery after an under-projected application was insufficient. A worklist executor would model pending work directly and must seal complete producer demand before dispatch.

## Demand Collection Is Not Being Reopened

A worklist executor should not rediscover demand incrementally as execution proceeds. Resolver03 establishes the current variable-free demand-collection shape, while the Resolver04 retrospective supplies requirements for any future variable-aware extension:

- Resolver fragments contribute transitive demand before a selective producer is applied.
- Demand is rooted at the exact OER occurrence path and guarded by possible concrete types.
- Multiple contributors targeting one producer occurrence are combined.
- Symbolic variable arguments may remain at behavioral boundaries where projection stops before materializing the exact key.
- Possible symbolic and concrete occurrences that may later converge on one exact key must contribute complete producer demand before application; Resolver04 exposed this obligation but did not solve it generally.

Resolution obligations would still be discovered through a structural traversal related to Resolver03's demand collection. What changes is the mechanism for enforcing field-execution order: readiness follows demand availability rather than the current recursive depth-first order, and resolvers whose arguments depend on variables join that same readiness relation even when their order is not determined by tree depth.

## Resolution Obligations

The initial sketch is:

```kotlin
data class ResolutionObligation(
    val containingObject: ObjectId,
    val key: Value.ObjectKey,
    val demandedOutput: SelectionForest,
)
```

The identity must include the concrete `Value.ObjectKey`, not only `Schema.ObjectField`, because unequal fully coerced argument tuples are distinct OER cells. `ObjectId` identifies one concrete OER occurrence; equal object values, node IDs, or resolver coordinates at different occurrences remain separate one-shot identities.

An obligation is ready when:

1. Its exact key contains no unresolved variable.
2. Every field required to materialize its exact object fragment is written in the containing OER.
3. Every variable referenced by that fragment is bound on the containing OER.
4. Its destination cell is still unwritten.

Argument errors follow the current carrier rule: they write the required error cell without applying the resolver.

The worklist need not be a FIFO queue. It is better understood as a finite set or map of obligations plus a choice of any currently ready member. Obligations should be indexed by `(ObjectId, Value.ObjectKey)` so duplicate demand cannot cause duplicate resolver application.

## Symbolic Demand And Variable Binding

A selection containing `$value` cannot become a concrete resolution obligation until `$value` is bound. The scheduler therefore needs a pending representation, separate from OER cells:

```kotlin
data class PendingSelection(
    val containingObject: ObjectId,
    val selection: Selection,
)
```

The exact shape may differ, but the semantic distinction matters. A `Selection` may contain a broad key with variables; a `Value.ObjectKey` stored in an OER may not contain variables.

Variable bindings are also write-once facts on the containing OER. A binding becomes available when its provider path can be read from the current store. Binding the variable instantiates affected pending selections. Each resulting concrete key is then inserted into, or matched with, the obligation map.

Late equality needs special care. A concrete occurrence such as `field(arg: "literal")` and a pending occurrence `field(arg: $value)` may become the same key. Resolver04 exposed this as a demand-coverage problem: the earlier application must have received the output demand of every symbolic occurrence that may converge with it. A worklist executor therefore needs a complete, sound symbolic envelope rather than inheriting Resolver04's matching approximation. It must not execute the currently concrete obligation with only its local subselections and then discover additional output demand when the variable binds.

This is the principal contract between demand collection and obligation scheduling:

> Demand collection accounts conservatively for possible symbolic-key convergence; scheduling later decides whether that envelope produces one concrete obligation or several.

## Applying An Obligation

Applying a ready obligation performs one conceptual transition:

1. Materialize the resolver input from the current containing OER.
2. Apply the resolver once with its complete `demandedOutput`.
3. Write the resulting cell exactly once.
4. For object and list outputs, allocate stable child OER identities top-down.
5. Populate demanded passive fields owned by the producer from its projected output.
6. When demand reaches another behavioral field, create the corresponding concrete or pending resolution obligation on the actual child OER occurrence.
7. Record any variable providers that may have become readable.

Step 6 is what the original sketch described as creating obligations when demand extends beyond the producer's output selection set. It crosses an ownership or behavioral boundary, but it does not extend the producer's already-collected demand.

In the running example, execution becomes:

1. Allocate the `Object1` OER.
2. Create the one `child` obligation with the complete symbolic output envelope.
3. Apply `child` once and write `Object1.child = ObjectRef(object2)`.
4. Create concrete `field2(arg: "literal")` and pending `field2(arg: $value)` work on `object2`.
5. Apply the literal obligation.
6. Apply `common`, then write `$value = "bound"` on `Object1`.
7. Instantiate the pending selection as `field2(arg: "bound")` on `object2`.
8. Apply that obligation and finally apply `variableConsumer`.

No field is rewritten. The `Object2` OER simply acquires two distinct field cells over time.

## A Write-Once OER Store

`EngineResult.Object` should remain the immutable, finite result-tree carrier used by `correctResolution`. A future worklist executor should introduce a separate intermediate execution-state domain with explicit object identity:

```kotlin
data class ExecutionState(
    val objects: Map<ObjectId, PartialObject>,
    val obligations: Map<CellId, ResolutionObligation>,
    val pendingSelections: Set<PendingSelection>,
)

data class PartialObject(
    val type: Schema.ObjectType,
    val cells: Map<Value.ObjectKey, PartialCell>,
    val variableValues: Map<Value.Variable.Stamped, Value.Input?>,
)
```

`PartialObject.variableValues` is proposed intermediate execution state. It is not a field of the final `EngineResult.Object` carrier.

Absence from `cells` means unwritten. Object-valued cells contain `ObjectRef`; list values contain terminal values or references at stable list positions. A transition adds a previously absent cell or variable binding and never changes an existing one.

Although this state models semi-mutable OERs, semantic Kotlin should remain purely functional. Each transition yields a new `ExecutionState` with persistent maps and sets. This follows the repository's mathematical modeling discipline and translates naturally to a TLA+ next-state relation. An eventual implementation may use concurrent mutable maps, promises, or atomics without changing the modeled write-once semantics.

When no work remains, a `freeze` operation recursively replaces object references with immutable `EngineResult.Object` values. The current `correctResolution` judgment can check the variable-free final result properties, but it cannot validate provider bindings; a future variable-aware oracle or separate execution-state invariant is required before a variable-bearing executor can claim correctness. Cyclic object references remain outside the model; the allocated occurrence graph must be a finite tree of object and list positions.

The historical `ResolutionSources` side table should not be copied automatically. If demand collection is complete, all producer-owned passive fields needed later are projected and written when the producer runs. The worklist design should retain an immutable raw resolver source only if a focused counterexample proves it necessary, and any later projection must remain covered by the demand originally supplied to that application.

## State Invariants

The worklist executor should make these properties explicit:

- Every `ObjectId` denotes one concrete OER occurrence with one concrete object type.
- Every OER cell is identified by `(ObjectId, Value.ObjectKey)`.
- A cell changes only from absent to written.
- A variable changes only from unbound to one stored nullable input value.
- Every concrete obligation has one complete demanded-output envelope.
- Every resolver-bearing cell has at most one function application.
- Every pending selection is instantiated only from bindings on its defining containing OER.
- Equal instantiated keys converge on one obligation and one cell.
- Different list positions and recursive object occurrences remain different `ObjectId` values.
- Null and error outputs prevent creation of unreachable descendant obligations.
- An empty worklist with unresolved demanded cells is a deadlock or invalid-state witness, not successful completion.
- Freezing a completed store yields a finite `EngineResult` tree satisfying carrier invariants.

These are stronger and easier to inspect than reconstructing execution history from a final immutable union.

## Parallel Execution

The worklist model is substantially closer to an implementation that executes resolvers in parallel. At any state, the ready set exposes every obligation whose inputs and bindings are available. Obligations for distinct destination cells can be claimed and applied concurrently.

The implementation correspondence is direct:

- `ObjectId` becomes the identity of one runtime OER occurrence.
- An unwritten cell becomes a promise, future, or atomic write-once slot.
- Dependency readiness becomes completion of the input slots named by the object fragment.
- Variable binding completes another write-once slot and releases pending argument templates.
- Independent sibling cells, separate list items, and unrelated subtrees become parallel work automatically.
- Resolver batching can group ready obligations below the semantic scheduler without changing their per-occurrence identities.

The semantic model need not execute transitions literally in parallel. It can choose one ready obligation per step and prove or test that all fair schedules freeze to the same result and application set. Randomized scheduler permutations are especially valuable here: they can expose hidden dependence on map iteration or a privileged depth-first order before a real concurrent implementation does.

Parallelism also clarifies why write-once cells matter. Concurrent workers may race to discover the same exact obligation, but they must converge before application. Claiming a cell is an implementation concern; semantically, there is still one obligation, one application, and one write.

## Relationship To Current Resolvers

The worklist design should reuse rather than replace:

- Resolver03's guarded predecessor closure and successor-demand lifting.
- The retained registry's variable ownership, provider-path compilation, containment, and branch-stratification invariants.
- The historical requirements for provider evaluation, substitution before exact merge, and conservative convergence accounting, without assuming Resolver04's coverage approximation was correct.
- `materialize` as the definition of a resolver's input.
- `snipToDemand` as selective projection to complete supplied demand.
- `correctResolution` as the variable-free final extensional oracle, supplemented by a future variable-aware execution oracle.

The worklist design would replace:

- Recursive post-order function application.
- Immutable prefix union as an execution mechanism.
- Post-application widening of an already-returned subtree.
- Identity-based recovery of raw sources when the write-once store can carry the necessary state explicitly.

On variable-free worlds, the worklist executor should observationally agree with Resolver03. On variable-bearing worlds, it should replay the historical accepted and rejected examples against a new variable-aware oracle; agreement with Resolver04 is neither available nor a correctness criterion.

## Proposed Work Sequence

1. Define `ObjectId`, object references, partial cells, variable slots, obligations, pending selections, and immutable `ExecutionState` transitions.
2. Implement freezing and test that hand-constructed completed stores produce the expected `EngineResult.Object`.
3. Port the `common` and `child.field2($value)` regression as the first worklist execution trace.
4. Add direct, nested, list, null, error, recursive, abstract-type, and equal-valued convergence traces.
5. Connect the existing demand collector to obligation creation, preserving symbolic envelopes at behavioral boundaries.
6. Compare the worklist executor with Resolver03 on variable-free generated worlds and replay the historical variable fixtures under a new variable-aware correctness oracle.
7. Run randomized ready-obligation schedules and assert identical frozen results and one application per exact resolver-bearing OER cell.
8. Extend the stress corpus before translating the state machine into TLA+ or using it as an implementation blueprint.

## Open Design Questions

- What is the smallest explicit output of demand collection that lets the scheduler distinguish concrete work, symbolic templates, and conservative convergence coverage?
- Should variable binding be a first-class obligation or a deterministic transition performed whenever a provider path becomes readable?
- Does a partial object need to retain the resolver's raw `Value.Object`, or can complete projection populate every passive field immediately?
- How should list allocation and element-level failure be represented without obscuring independent item scheduling?
- Which static acyclicity measure proves that every unfinished valid state has at least one ready obligation?
- What schedule-independence property is strong enough to justify parallel execution while remaining provable over the finite model?

The central design direction is nevertheless clear: collect demand structurally as before, allocate OER occurrences top-down, and schedule exact resolver applications by data availability rather than by recursive return order.
