# Resolver26 Design

## Status

Resolver26 is the primary qplan algorithm and eventual implementation blueprint. It is a selective query resolver based on structured concurrency and synchronous symbolic closure.

The exercise assumes every field resolver completes normally, including with respect to `CancellationException`. Recovery after a resolver exception and partial promise claiming is outside the modeled domain.

## Resolver Identity

Pre-grounded external selections and variable-free resolver-fragment selections use ordinary ground-key identity and coalesce when their keys are equal.

Every variable-bearing selection introduced from a resolver object fragment carries a `Stamp.Occurrence`. The stamp combines the concrete resolver path with a nonempty lineage of registry-assigned `SelectionOccurrenceId` values. Selection equality is undefined; the occurrence IDs provide stable identity. Registry template keys have a null stamp, and ordinary concrete keys carry `Stamp.VariableFreeOccurrence`.

When demand descends into an object or list occurrence, its top-level stamps localize through that exact result path. Distinct list positions and object occurrences therefore remain distinct. Stamped keys never coalesce with ordinary keys or with another occurrence lineage, even when they eventually have equal visible arguments.

Resolver input materialization filters source occurrences to the concrete object type and collects them by response key. Each response group grounds and localizes its construction key, then reads the exact OER cell. Duplicate occurrences in one response-key group contribute one input entry with their combined subselections. Distinct aliases remain distinct input entries even when they read the same OER cell.

## Request And Task Ownership

One root `coroutineScope` owns the request. Every orchestration task and field-resolution task is a direct child of that request scope. Successful synchronous return therefore means all request work has reached quiescence.

Task completion is not a cross-task readiness protocol. Cross-task reads use OER value promises, binding promises, or an OER's bindings-declared signal. The dispatcher changes scheduling only; it does not change resolver, selection, stamp, path, or task identity.

## Synchronous Demand Closure

`orchestrateObject` first localizes incoming stamps to the concrete OER path and synchronously computes one final `ObjectSelectionForest`.

Closure repeatedly expands each newly seen resolver `ObjectKey` whose field is absent from the source EOD with that resolver's complete stamped object fragment. A source-present argumentless field remains unexpanded and is materialized from the source even when the registry contains its standard resolver. Expansion does not await argument bindings. It records the resolver template, its fixed input demand, and its stamped variable definitions.

Every resolver key in the closed forest is represented either by the expansion map or by an argumentless source-provided field, and every selection stamp must be unique. There is no later demand contribution, re-orchestration loop, pending-demand registry, or outer fixpoint.

An open resolver key contributes its object-fragment dependencies before its arguments ground. If those arguments later become an error, those dependencies may have executed speculatively. That imprecision is accepted by the current model.

## Binding Declaration

After closure, the orchestrator declares every open binding before launching local field work.

`FromArgument` definitions owned by an already-ground key read their canonical input paths and complete immediately. A null input-object intermediate produces a null binding. Definitions owned by open keys complete after that key grounds. Localized child stamps use explicit binding aliases whose values are copied from the source occurrence.

Each `FromObjectField` definition launches a provider reader that follows its occurrence-stamped compiled path through OER promises and completes the declared binding. Provider arguments may be grounded from literals, defaults, the owning resolver's arguments, or other acyclic `FromObjectField` bindings; all binding promises are declared before readers and field resolvers launch.

Before grounding a provider component inside an OER, its reader awaits that OER's bindings-declared signal. `ObjectOrchestrationTask.prepare` marks bindings declared immediately after synchronous demand closure declares every binding in the OER's binding domain, before recursively materializing passive children or launching local field work.

Nested provider keys ground their arguments against the owning resolver occurrence before the resulting ground key is localized to the concrete provider object path. Grounding and localization commute when the localized binding is an alias of the owner binding; grounding first avoids making provider progress depend on a descendant orchestrator declaring that alias.

Readers never insert undeclared binding promises.

## Passive Values

Every argumentless field present in a resolver's source EOD is read by canonical field name through resolver26's local `resolvePassiveValues` path, including fields that have standard resolvers in the registry. Source-provided argument-bearing fields are errors. A demanded registry field absent from the source uses its standard resolver; a demanded non-registry field absent from the source remains an error.

The field-resolution task builds the passive result tree supplied by the resolver before publishing the containing value. Resolver26 creates one `ObjectOrchestrationTask` with each OER and calls its non-suspending `prepare` function immediately. Prepare closes only construction demand propagated through the parent, using source presence to decide which standard resolvers remain actual work, then declares and marks bindings. Invocation demand separately validates selective output and guides recursive materialization of every passive returned field before the task's non-suspending `launch` function runs. This parent-first recursion establishes each binding domain before any descendant can copy an alias from it without retaining a separate object-occurrence collection.

After passive children have launched, the parent launch validates its materialized passive cells. An object with neither active expansions nor binding aliases freezes synchronously without creating a coroutine. Otherwise, launch schedules the task's suspending `run` function to copy aliases, read providers associated with its active expansions, install active fields, and freeze the OER.

## Active Installation And Freeze

Each active selection awaits only its declared argument bindings and grounds to one `ObjectEngineResult.GroundKey`. Installation then completes any delayed `FromArgument` bindings owned by that newly grounded resolver key, reserves the exact target cell, claims the value promise, registers the writer, and launches one field-resolution task.

`reserveCell` explicitly creates an unclaimed cell placeholder when needed. `Cell.createValuePromise` claims that placeholder for the writer. Strict claiming makes disagreement between readers and writers observable.

After every local active key has grounded and claimed its cell, the orchestrator calls `freeze`. Freezing seals the OER key set and fails any unclaimed value placeholders. Claimed promises may complete after the OER is frozen.

## Field Resolution

The field-resolution task:

1. derives invocation successor demand from the key's closed construction demand;
2. materializes the resolver's fixed input demand from exact OER cells;
3. records the occurrence-aware application observation;
4. invokes the selective resolver once;
5. builds the passive result shape while synchronously launching one orchestration lifecycle per OER; and
6. publishes the containing value.

Parent publication does not wait for descendant orchestration to finish. Readers independently derive and reserve the same localized child keys; strict occurrence stamps, binding aliases, and reservation rules make disagreement fail rather than silently create another identity.

Argument errors complete the value slot with `ErrorEngineResult` without invoking the resolver. Successful values complete the value slot once.

## Successor Demand

Successor demand is output projection, not input closure. It retains passive selections and argumentless resolver-bearing selections that the current resolver may supply. Argument-bearing resolver fields remain necessarily active.

Each boundary resolver's fixed object fragment contributes its passive predecessor demand transitively, conservatively including argumentless resolver-bearing fields that may be supplied by an ancestor. The original downstream construction demand continues separately into each returned child OER, where source-sensitive synchronous closure assigns only unresolved work to standard resolvers.

## Strictness

Binding declaration and completion, cell reservation and claiming, stamp uniqueness, writer ownership, and OER freezing are strict. Repeated or contradictory transitions are protocol defects, not harmless idempotence.

## Deliberate Scope

Resolver26 models query resolution with canonical field identity and synchronous source values. It supports runtime `FromObjectField` bindings within its stated provider restriction.

The future integration target excludes mutations, subscriptions, custom scalars, query fragments and `fromQueryField`, EOD aliases, and asynchronous EOD variants. These exclusions constrain future alignment; they do not require resolver26-specific production adapters inside qplan.
