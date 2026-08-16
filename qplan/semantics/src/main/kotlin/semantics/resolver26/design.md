# Resolver26 Design

## Status

Resolver26 is an experimental selective resolver based primarily on Resolver23. It preserves one
selective invocation per resolver instance while defining a distinct instance for every
variable-bearing selection in a resolver object fragment.

The exercise assumes that every field resolver completes normally and throws no exception, including `CancellationException`. Resolver26's behavior after any resolver exception is outside the modeled domain; request-scope cancellation and failure propagation do not establish recovery of partially claimed promises.

## Resolver Identity

Every variable-bearing source selection receives a nonempty `SelectionStamp`:

```kotlin
data class SelectionStamp(
    val resolverPath: List<PathComponent>,
    val provenance: List<Selection>,
)
```

`provenance` records the source-selection chain that caused the key to exist. `resolverPath`
identifies the concrete resolver occurrence independently of scheduling. When stamped demand
descends into a concrete child OER, its top-level stamps are extended through that OER's exact
absolute path. Paths through lists therefore contain every `ListIndex`; the same source selection
instantiated in two list elements has two different resolver identities.

The arguments, every variable they contain, and the eventual `GroundKey.Stamped` carry the same
stamp. `closeInputDemand` explicitly rejects duplicate stamps in its result. Distinct stamped keys
never coalesce, even when their grounded arguments agree.

Pre-grounded selections remain ordinary. This includes external query selections and resolver
object-fragment selections whose arguments contain no variables. Equal ordinary ground keys
coalesce normally. Resolver input materialization projects stamped storage keys to ordinary
GraphQL keys only after recursively materializing each stored instance at its exact path; equal
visible values are then structurally unioned.

## Request, Task, And Coroutine Ownership

A request is one top-level external resolution. Its root `coroutineScope` owns every coroutine
servicing that request and supplies cancellation, failure propagation, and final quiescence.

Resolver26 has two task kinds:

* An orchestration task closes and installs all demand for one OER.
* A field-resolution task grounds and resolves one field instance.

Coroutines within one task may use structured concurrency and may parent to other coroutines in the
same task. A coroutine may never parent to a coroutine in another task. Cross-task readiness is
communicated through explicit promises, never through another task's call stack or `Job`
completion.

The public synchronous entry point uses `runBlocking` on a process-scoped fixed dispatcher selected by `resolver26.thread.count`, which defaults to one. Every request task and coroutine inherits that dispatcher. An internal validation entry point accepts an explicit `CoroutineContext` for dispatcher instrumentation. Dispatcher choice changes scheduling only, not request, task, coroutine, resolver-instance, or stamp identity.

Resolution-time test instrumentation is thread-safe. Post-resolution witness analysis, structural coverage, extensional-result validation, and object-path-binding validation remain serial and begin only after the synchronous request has reached quiescence.

## Readiness And OER Lifecycle

Only two value-bearing readiness mechanisms cross coroutine boundaries:

| Mechanism | Identity | Completion meaning |
| --- | --- | --- |
| OER value `Promise` | OER identity plus exact `GroundKey` | The field value has been published. |
| Binding `Promise` | Stamped variable | The variable's input value has been produced. |

Binding declarations need no separate readiness mechanism. An OER orchestrator closes demand and
declares every binding before it launches any local field-resolution coroutine. Every source
variable in that resolver's stamped object fragment is therefore already declared when its input
materializer starts.

When recursive input materialization descends into a child OER, it first awaits the source
variable's binding and grounds the stamped key. It then localizes that ground key to the concrete
child path. This produces the same storage key as localizing the open key and then grounding it,
without reading the child orchestrator's localized binding alias. Readers never insert binding
promises.

An OER slot follows this lifecycle:

```text
absent -> reader placeholder -> writer claimed -> completed
```

`getValue` may create an unclaimed placeholder on a mutable OER. `createValuePromise` strictly
claims that placeholder or creates a claimed slot. Once orchestration has grounded and claimed
every final field key, `freeze` forbids new slots and fails every unclaimed placeholder. Claimed
promises may complete after freezing.

## Demand Closure

`closeInputDemand` is a synchronous function nested inside the OER orchestrator. It first localizes
incoming top-level stamps to the concrete OER path, then repeatedly merges the accumulated forest
and expands every new resolver `ObjectKey`. A resolver contributes its complete stamped object
fragment regardless of whether its arguments are already ground. This reaches one closed
`ObjectSelectionForest` whose key set is final before orchestration installs any field.

An open stamped key is not merged with an ordinary key or another stamp. Its grounding coroutine
awaits only its declared argument bindings and then claims its exact OER slot. Variables sourced
from a pre-grounded resolver argument bind immediately. Variables inherited by a localized child
stamp use an explicit binding alias. `FromObjectField` provider coroutines read OER promises and
complete their declared bindings.

Known ground argument errors skip object-fragment expansion and resolve directly to `Value.Error`. An open key, however, contributes its object-fragment demand before its arguments ground, so dependencies from that fragment may execute even when the key later grounds to `Value.Error`. This speculative execution of doomed dependencies is a deliberate and currently accepted imprecision: no harmful result behavior is known, and it is not considered a Resolver26 correctness defect for this exercise.

Resolver26 currently requires argument-free `FromObjectField` provider paths. Each provider
component is specialized to the concrete OER type before lookup.

## Orchestration

Orchestration closes one OER's demand and declares its bindings. Every demanded passive value
already materialized by `resolveValue` is reused; a missing value, as at the request root, is copied
from the corresponding source `Value.Object` field through the same `resolveValue` path. It
launches orchestration for every passive child OER without waiting for those child tasks.

Within its structured task, orchestration starts binding aliases, provider readers, and one
grounding coroutine per active resolver key. Each grounding coroutine awaits arguments, claims the
corresponding OER promise, and launches a field-resolution task directly under the request scope. A
reader may create an OER value placeholder before its grounding coroutine claims it; strict
claiming preserves the one-writer invariant. Once all local keys have been claimed, orchestration
freezes the OER.

There is no re-orchestration loop, pending-demand collection, activation registry, installed-value
latch, output handoff, or late-demand fixpoint.

## Field Resolution

`resolveField` accepts only registered resolver fields. It handles argument errors directly,
materializes the resolver's closed input, and invokes the selective resolver exactly once.

Resolver26's local successor-demand function never reads or awaits argument bindings. It retains
requested ground resolver boundaries, omits open resolver boundaries, and substitutes every
boundary's fixed passive predecessor demand. The resolver wrapper immediately snips its output to
that ground invocation demand.

The shared `semantics.resolveValue` recursively materializes the passive output allowed by the
invocation demand. Resolver26 does not close demand or generate additional resolver input inside
value construction. It separately passes the original downstream selections to orchestration for
each returned root object or list element.

Field resolution launches orchestration for the returned root objects or list elements and
immediately publishes the result. A later materializer may descend through that passive result tree
without waiting for descendant orchestration because it grounds selections from its resolver's
already-declared source bindings before localizing their storage-key stamps.

This is a sharp sequencing choice: child orchestration is launched before the parent value is published, but parent publication does not wait for child orchestration. Correctness therefore depends on every later reader independently deriving and reserving exactly the localized child key that child orchestration will eventually claim. That agreement is especially difficult for variable-bearing arguments because the reader must derive the same occurrence-specific `SelectionStamp`, use the matching variable-instance bindings to ground the arguments, and localize the resulting key to the same child path.

Resolver behavior recursively adds canonical `__typename` before selective output projection, and
`ResolverRegistry.resolveRootQuery()` supplies it on the initial Query object. Resolver26 otherwise
treats it as an ordinary passive value: `resolveValue` copies it into an OER when demand selects it
and silently ignores it otherwise. `__typename` never participates in field launch.

## Strictness

Operations reject repeated or contradictory lifecycle transitions whenever repetition is not part
of the protocol. Promise claiming, binding declaration, binding completion, and stamp uniqueness
are strict. This keeps scheduling and ownership bugs observable instead of silently treating them
as idempotent.

## Naming

Variables and properties containing exact `GroundKey` values use `groundKey` or `groundKeys` in
their names. The `RuntimeSupport` context argument is named `diagnosticInstrumentation`.

Any variable, property, or function that is, contains, or returns a
`CompletableDeferred<Unit>`/`Deferred<Unit>` has a name ending in `Latch` or `Latches`.

## Appendix: Working Learnings

### Successor Demand Stops At OSS Boundaries

Successor demand is an output projection. It retains passive selections within a resolver's output
selection set and stops at every resolver-bearing field, including every field with arguments. A
requested ground boundary remains in the invocation selection set for compatibility with the
established selective-resolver contract. An open boundary is omitted because it cannot be passed to
the shared ground-only value constructor.

Every boundary identifies another resolver template whose object fragment must be traversed for its
passive predecessor demand. Active selections encountered inside that object fragment are traversal
boundaries rather than output supplied by the preceding resolver.

Resolver arguments are therefore unnecessary when computing successor demand. They identify a
resolver instance and may supply its eventual values, but they do not select its object fragment.
Successor-demand traversal can use an `ObjectKey` to identify the field and registered resolver
template without grounding the key, reading a binding, or awaiting a binding.

Demand used for two different purposes must remain distinct:

* Ground successor demand is passed to a selective resolver and used to construct its passive
  output.
* The original downstream selections are passed to each returned child OER's orchestration, where
  `closeInputDemand` stamps resolver-fragment selections and eventually grounds resolver instances.

The shared historical `successorDemand` retains its existing behavior for compatibility. Resolver26
should use a local OSS-pruned variant that applies these rules.
