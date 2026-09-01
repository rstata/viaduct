# Resolver25 Design

## Status

Resolver25 is an experimental selective resolver that applies each grounded resolver key at most once. It supports runtime `FromObjectField` bindings and deliberately uses grounded-key identity, so separately discovered selections that become the same `ObjectEngineResult.GroundKey` contribute to one key state.

Resolver25 is maintained as an alternate construction and comparison for Resolver26. It is not the primary implementation blueprint.

The current implementation is authoritative. The earlier static `StrictPreparationPlan` and per-field preparation-graph design is retired; descriptions of that graph are stale rather than requirements the implementation is expected to recover.

## Runtime Ownership

One request `coroutineScope` owns all Resolver25 work. `ResolverRuntime` keeps an identity map from each mutable target OER to its sole `ObjectResultOrchestrator`.

Creating an orchestrator for a new target installs its conservative potential demand, submits initial actual demand, and starts completion coordination. Contributing to an existing target submits additional actual demand to the same orchestrator and returns latches for those contributions.

The implementation distinguishes several facts:

- A value promise is installed for one exact key.
- Actual demand for that key has been merged.
- The key's nested fringe is discoverable.
- Resolver output is available for later descendant demand.
- The key's demand has sealed and the key may launch.
- An OER's submitted activation work has installed its contributions.

These facts must not be collapsed into one generic readiness signal.

## Potential And Actual Demand

Potential demand is a conservative field-level envelope. `closeStructuralDemand` expands each activated resolver field's fixed object fragment once, regardless of how many open or ground keys currently select that field.

Actual demand arrives as selection occurrences. Each occurrence becomes activation work, grounds through available variables, and is interned in its field's `FieldState` by `ObjectEngineResult.GroundKey`.

Potential demand bounds the output and descendant work that may be needed. Each key state separately retains construction demand and conservative invocation demand. Construction demand determines which descendant work actually remains after output ownership is known, while invocation demand includes successor input demand that a one-shot selective producer may choose to supply. Keeping these forms separate avoids activating a standard resolver's input demand after an ancestor has supplied that resolver's field.

## Grounded-Key Activation

Immediately groundable activations start `UNDISPATCHED` so their demand can merge before an existing key seals. Activations that require bindings suspend until those bindings complete.

Resolver output eagerly materializes every returned argumentless field, including a field with a standard resolver. The first activation for a ground key therefore reuses a preexisting cell when its source supplies the field; otherwise it classifies the key as argument-error or resolver-backed, reserves the OER cell, claims its value promise, and registers the writer. A source output that contains an argument-bearing field is invalid because fields with arguments are always active. Later activations merge into the same `KeyState`.

If later demand arrives before launch, it contributes to the key's open demand. If it arrives after launch, Resolver25 does not reapply the containing key: passive values traverse their existing result, and resolver-backed values wait for `outputAvailable` before installing additional descendant fringe work.

## Resolver Preparation

Preparing one resolver-backed key:

1. completes `FromArgument` bindings by reading each canonical input path from the exact key;
2. declares its instantiated `FromObjectField` bindings;
3. contributes the resolver's instantiated object fragment as actual demand; and
4. launches provider readers that complete object-field bindings from published OER values.

Resolver inputs must install before the consumer launches. Provider reads use exact OER cells and the shared cycle-checking instrumentation. Provider paths may traverse singular object values; list traversal is outside this reader.

## Launch And Publication

`KeyState.sealDemandForLaunch` takes final construction and invocation snapshots for that key and rejects subsequent attempts to change the producer's launch demand. Resolver invocation materializes the fixed object fragment from the target OER and computes selective successor demand while deferring still-open template boundaries.

Resolver output is converted to passive engine-result shape. Returned fields are selected against invocation demand but unresolved child work is derived from construction demand and source presence. `outputAvailable` publishes the raw output and its result tree so later descendant demand can traverse it. Child orchestrators become ready before the containing value promise is completed, preserving install-before-parent-publication.

Argument errors complete both the value and access-result slots with `ErrorEngineResult`. Successful values complete the exact cell once and set its access result to the Boolean result `true`.

## OER Readiness

An orchestrator counts submitted activations. Its `orchestrationReady` latch completes when all currently submitted activation contributions have installed their key promises and fringe obligations.

Readiness does not mean every descendant value is complete. Exact cell promises remain the value-completion mechanism. This distinction lets a parent publish a stable child OER without hiding active child work from readers.

## Identity And One-Shot Scope

Resolver25's one-shot unit is `(OER occurrence, ObjectEngineResult.GroundKey)`. Literal and variable-bearing selections that ground to the same key merge if they arrive before launch.

The conservative potential-demand envelope and late descendant traversal are part of this experimental guarantee. Resolver25 does not establish a general static plan for every recursive, list, provider-chain, or mixed-variable world, and passing generated tests is finite evidence rather than proof.

## Comparison With Resolver26

Resolver26 avoids Resolver25's persistent OER-local acceptance of actual demand. It closes one final symbolic forest synchronously and gives variable-bearing source selections distinct occurrence identity, even when visible ground arguments agree.

Use Resolver25 to study the consequences of grounded-key convergence and late descendant installation. Do not copy its activation registry, output handoff, or readiness protocol into Resolver26 unless a specific requirement survives comparison with Resolver03, Resolver08, and Resolver23.
