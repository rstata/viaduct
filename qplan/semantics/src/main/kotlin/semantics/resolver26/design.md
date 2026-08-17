# Resolver26 Design

## Status

Resolver26 is the primary qplan algorithm and eventual implementation blueprint. It is a selective query resolver based on structured concurrency and synchronous symbolic closure.

The exercise assumes every field resolver completes normally, including with respect to `CancellationException`. Recovery after a resolver exception and partial promise claiming is outside the modeled domain.

## Resolver Identity

Pre-grounded external selections and variable-free resolver-fragment selections use ordinary ground-key identity and coalesce when their keys are equal.

Every variable-bearing selection introduced from a resolver object fragment carries a `SelectionStamp`. The stamp combines the concrete resolver path with an opaque lineage of registry-assigned `SelectionOccurrenceId` values. Selection equality is undefined; the opaque occurrence IDs provide stable identity without treating selection content as provenance.

When demand descends into an object or list occurrence, its top-level stamps localize through that exact result path. Distinct list positions and object occurrences therefore remain distinct. Stamped keys never coalesce with ordinary keys or with another occurrence lineage, even when they eventually have equal visible arguments.

Resolver input materialization removes storage-only occurrence identity only after reading each exact occurrence. Equal visible values can then combine in the materialized GraphQL input.

## Request And Task Ownership

One root `coroutineScope` owns the request. Every orchestration task and field-resolution task is a direct child of that request scope. Successful synchronous return therefore means all request work has reached quiescence.

Task completion is not a cross-task readiness protocol. Cross-task reads use OER value promises or binding promises. The dispatcher changes scheduling only; it does not change resolver, selection, stamp, path, or task identity.

## Synchronous Demand Closure

`orchestrateObject` first localizes incoming stamps to the concrete OER path and synchronously computes one final `ObjectSelectionForest`.

Closure repeatedly expands each newly seen resolver `ObjectKey` with that resolver's complete stamped object fragment. Expansion does not await argument bindings. It records the resolver template, its fixed input demand, and its stamped variable definitions.

The closed forest must contain exactly the resolver keys represented by the expansion map, and every selection stamp must be unique. There is no later demand contribution, re-orchestration loop, pending-demand registry, or outer fixpoint.

An open resolver key contributes its object-fragment dependencies before its arguments ground. If those arguments later become an error, those dependencies may have executed speculatively. That imprecision is accepted by the current model.

## Binding Preparation

After closure, the orchestrator declares every open binding before launching local field work.

`FromArgument` definitions owned by an already-ground key complete immediately. Definitions owned by open keys complete after that key grounds. Localized child stamps use explicit binding aliases whose values are copied from the source occurrence.

Each `FromObjectField` definition launches a provider reader that follows its compiled path through OER promises and completes the declared binding. Resolver26 currently requires argument-free provider path components.

Readers never insert undeclared binding promises.

## Passive Values

Passive ground keys are copied from the source `Value.Object` through the shared `resolveValue` path. Missing passive source keys are errors; open passive keys are outside the algorithm's domain.

When a passive value contains object or list occurrences, the runtime launches orchestration for those occurrences with the corresponding downstream selections. Existing cells are reused rather than replaced.

## Active Installation And Freeze

Each active selection awaits only its declared argument bindings and grounds to one `Value.GroundKey`. Installation then completes any delayed `FromArgument` bindings owned by that newly grounded resolver key, reserves the exact target cell, claims the value promise, registers the writer, and launches one field-resolution task.

`reserveCell` explicitly creates an unclaimed cell placeholder when needed. `Cell.createValuePromise` claims that placeholder for the writer. Strict claiming makes disagreement between readers and writers observable.

After every local active key has grounded and claimed its cell, the orchestrator calls `freeze`. Freezing seals the OER key set and fails any unclaimed value placeholders. Claimed promises may complete after the OER is frozen.

## Field Resolution

The field-resolution task:

1. derives invocation successor demand from the key's closed construction demand;
2. materializes the resolver's fixed input demand from exact OER cells;
3. records the occurrence-aware application observation;
4. invokes the selective resolver once;
5. builds the passive result shape; and
6. launches orchestration for returned root object or list-element occurrences before publishing the containing value.

Parent publication does not wait for descendant orchestration to finish. Readers independently derive and reserve the same localized child keys; strict occurrence stamps, binding aliases, and reservation rules make disagreement fail rather than silently create another identity.

Argument errors complete the cell with `Value.Error` without invoking the resolver. Successful values complete the cell once and set `accessAccepted` to true.

## Successor Demand

Successor demand is output projection, not input closure. It retains passive selections supplied by the current resolver and stops at each resolver-bearing boundary.

The boundary resolver's fixed object fragment may contribute passive predecessor demand, but its arguments are unnecessary for choosing that template. The original downstream construction demand continues into each returned child OER, where synchronous closure assigns work to successor resolvers.

## Strictness

Binding declaration and completion, cell reservation and claiming, stamp uniqueness, writer ownership, and OER freezing are strict. Repeated or contradictory transitions are protocol defects, not harmless idempotence.

## Deliberate Scope

Resolver26 models query resolution with canonical field identity and synchronous source values. It supports runtime `FromObjectField` bindings within its stated provider restriction.

The future integration target excludes mutations, subscriptions, custom scalars, query fragments and `fromQueryField`, EOD aliases, and asynchronous EOD variants. These exclusions constrain future alignment; they do not require resolver26-specific production adapters inside qplan.
