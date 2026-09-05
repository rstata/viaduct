# Resolver26 Design

## Status

Resolver26 is the primary qplan algorithm and eventual implementation blueprint. It is a selective query resolver based on structured concurrency and synchronous symbolic closure.

The exercise assumes every field resolver completes normally, including with respect to `CancellationException`. Recovery after a resolver exception and partial promise claiming is outside the modeled domain.

## Resolver Identity

Every OER is a map whose keys compare by canonical field and argument expression. Ground keys with equal values coalesce. Symbolic keys coalesce when they contain equal variable instances in equal expression positions. A variable-bearing resolver-fragment selection retains that symbolic `ObjectKey` as its permanent cell identity; completing its bindings makes the key contextually grounded but never rekeys the cell.

Registry variables are templates. Instantiating a resolver fragment replaces all uses of each template with one variable instance identified by the concrete resolver path. Equal uses of that instance therefore coalesce within an OER, including duplicate selections and aliases that project the same canonical key. Different variable names, defining fields, or resolver paths remain distinct even when their completed bindings have equal values, so the same grounded field arguments may be invoked more than once.

Keys need no child-occurrence localization. Each object and list element owns a distinct OER, so the same symbolic key can be reused in multiple containing objects without conflating their cells. A nested resolver receives its own concrete path when its fragment is instantiated.

Resolver input materialization filters source occurrences to the concrete object type and collects them by response key. Each response group awaits every argument binding, requires its symbolic construction key to be contextually grounded, and reads that exact OER cell without substituting arguments into its identity. Duplicate occurrences in one response-key group contribute one input entry with their combined subselections. Distinct aliases remain distinct input entries even when they project one shared construction key.

Shared correctness validation has no resolver-family addressing mode. Every stored key it traverses must be contextually grounded. To validate demanded selections and reconstructed resolver input, it prefers the exact symbolic key when that cell exists and otherwise accepts the key's grounded projection, which keeps validation compatible with earlier resolver families without weakening Resolver26's runtime identity.

## Request And Task Ownership

One root `coroutineScope` owns the request. Every orchestration task and field-resolution task is a direct child of that request scope. Successful synchronous return therefore means all request work has reached quiescence.

Task completion is not a cross-task readiness protocol. Cross-task reads use OER value promises, binding promises, or an OER's bindings-declared signal. The dispatcher changes scheduling only; it does not change resolver, variable, path, or task identity.

`Resolver26OperationContext` is the stable reference bundle for this scope. It extends the shared `OperationContext`, retains the request coroutine scope and Resolver26 observer, and exposes three independent mutable protocols as properties: `cycleChecker: CycleCheckState`, `bindingDeclarationsState: BindingDeclarationsState`, and `queryValuesState: QueryValuesState`. The context neither implements those protocols nor owns their mutable storage.

`OEROccurrenceContext` bundles the root OER, exact root-relative structural path, target OER, and optional immediate parent occurrence that remain unchanged while Resolver26 orchestrates or resolves one object occurrence. Primary-operation and Query-fragment roots create self-rooted occurrences without parents. Passive object materialization creates descendant occurrences at the exact field-and-list path where each new target is published. This is deliberately Resolver26-local: it captures a coherent task boundary here without requiring unrelated model or resolver APIs to unpack and transform the bundle.

## Synchronous Demand Closure

`ObjectOrchestrationTask.prepare` synchronously computes the single closed `ObjectSelectionForest` for its concrete OER and establishes its binding-declaration domain.

Closure repeatedly expands each newly seen resolver `ObjectKey` whose field is absent from the source EOD with that resolver's complete object fragment instantiated at the resolver path. As part of the same fixed point, it analyzes parent selections in requested descendants and reachable resolver inputs, transposes their variable-free demand across the matching producer edge, and adds the resulting ancestor demand to the containing OER. A source-present argumentless field remains unexpanded and is materialized from the source even when the registry contains its standard resolver. Expansion does not await argument bindings. It records the resolver template, its fixed input demand, and one definition for each instantiated variable.

Every resolver key in closed demand is represented either by the expansion map or by an argumentless source-provided field. Successor-demand construction still transposes parent selections into selective producer output before returned objects exist, while input-demand closure independently ensures the required ancestor cells have writers. Repeating the one-level transposition through input analysis handles grandparents without recursively traversing parent backedges as structural children or reopening an ancestor task.

An open resolver key contributes its object-fragment dependencies before its arguments ground. If those arguments later become an error, those dependencies may have executed speculatively. That imprecision is accepted by the current model.

## Binding Declaration

After closure, the orchestrator declares every open binding before launching local field work.

`FromArgument` definitions owned by an already-ground key read their canonical input paths and complete immediately. A null input-object intermediate produces a null binding. Definitions owned by symbolic keys complete after the owner's arguments resolve, while the owner key itself remains unchanged.

Each `FromObjectField` definition launches a provider reader that follows its compiled path through the defining occurrence's object OER promises. Each `FromQueryField` definition launches an equivalent reader through that occurrence's fresh Query OER. Provider path templates are instantiated for the owning resolver occurrence, and provider arguments may be grounded from literals, defaults, the owner's arguments, or other acyclic from-field bindings of either kind. All binding promises are declared before provider readers and field resolvers launch.

Before reading a provider component inside an OER, its reader awaits that OER's bindings-declared signal and every argument binding needed to make the component key contextually grounded. `ObjectOrchestrationTask.prepare` marks bindings declared immediately after synchronous demand closure declares every binding in the OER's binding domain, before recursively materializing passive children or launching local field work.

`BindingDeclarationsState` owns these per-OER readiness signals. `VariableBindingsState` separately owns actual variable-instance bindings, and `QueryValuesState` owns the declared-then-completed Query input for each resolver occurrence. Declaration and completion are strict one-shot transitions; consumers never manufacture undeclared query values or variable bindings.

Nested provider keys resolve their argument values against the owning resolver occurrence and use the original symbolic key for OER lookup. The separately resolved arguments are a readiness and invocation witness; they do not replace the key.

Readers never insert undeclared binding promises.

## Passive Values

Every argumentless field present in a resolver's source EOD is read by canonical field name through resolver26's local `resolvePassiveValues` path, including fields that have standard resolvers in the registry. Source-provided argument-bearing fields are errors. A demanded registry field absent from the source uses its standard resolver; a demanded non-registry field absent from the source remains an error.

The field-resolution task builds the passive structural result tree supplied by the resolver before publishing the containing value. Resolver26 creates one `ObjectOrchestrationTask` with each OER and calls its non-suspending `prepare` function immediately. Prepare closes only construction demand propagated through the containing field; parent-induced construction demand is derived inside that input closure, using source presence to decide which standard resolvers remain actual work, then declares and marks bindings. It also installs each demanded `ParentKey` with the actual immediate ancestor OER. Invocation demand separately validates selective output and guides recursive materialization of every passive returned field before the task's non-suspending `launch` function runs. This parent-first recursion establishes every descendant binding domain before field work starts.

After passive children have launched, the parent launch validates its materialized passive cells. The OER freezes after its local active work has been installed; its claimed promises may complete later within the request scope.

## Active Installation And Freeze

Each active selection awaits only its declared argument bindings and derives one `Arguments.Ground` value for invocation. Installation requires the original `ObjectEngineResult.ObjectKey` to be contextually grounded, completes any delayed `FromArgument` bindings used by either resolver fragment from the resolved argument tuple, reserves the target cell under that original key, claims the value promise, registers the writer, and launches one field-resolution task carrying cell identity and invocation arguments separately.

Resolver26's `CycleCheckState` is explicit operation state. Installation registers each active cell's exact writer through `operation.cycleChecker`, and provider and resolver-input reads record their dependency through the same property. Other resolvers and correctness materialization may supply a separate state or the NOP implementation; `Resolver26OperationContext` does not masquerade as a cycle checker.

`reserveCell` explicitly creates an unclaimed cell placeholder when needed. `Cell.createValuePromise` claims that placeholder for the writer. Strict claiming makes disagreement between readers and writers observable.

After every local active key is contextually grounded and has claimed its symbolic cell, the orchestrator calls `freeze`. Freezing seals the OER key set and fails any unclaimed value placeholders. Claimed promises may complete after the OER is frozen.

## Field Resolution

The field-resolution task:

1. derives invocation successor demand from the key's closed construction demand;
2. materializes the resolver's fixed input demand from exact OER cells;
3. awaits the independently orchestrated Query-rooted input;
4. records the occurrence-aware application observation;
5. invokes the selective resolver once;
6. builds the passive result shape while synchronously launching one orchestration lifecycle per OER; and
7. publishes the containing value.

Parent publication does not wait for descendant orchestration to finish. Readers independently derive and reserve the same symbolic child keys; variable-instance equality and strict reservation rules make disagreement fail rather than silently create another identity.

Query fragments reuse the defining resolver occurrence's variable bindings, retain their complete response-preserving symbolic selection tree, and use an ordinary `ObjectOrchestrationTask` rooted at an otherwise independent Query OER. Their orchestration starts alongside object-path provider readers and active field installation so a `FromQueryField` binding can ground the object fragment and a `FromObjectField` binding can ground the Query fragment without imposing an artificial fragment order. Query-provider readers complete their bindings as soon as their exact paths resolve; the owning field resolver separately awaits materialization of the complete Query input. A Query-only `FromArgument` use binds directly from the owning resolver arguments, while a binding used by both fragments is declared and completed only once. Materialization resolves arguments only to establish contextual grounding and invocation values. The OER is retained as a correctness witness under the owning resolver's exact result path.

Argument errors complete the value slot with `ErrorEngineResult` without invoking the resolver. Successful values complete the value slot once. Resolver26 does not publish access-result slots: access-check execution and its validation are future work, and the `true` access results written by some earlier resolver experiments are not part of the current resolver contract.

Resolver observations are semantically passive evidence. Resolver26 records Query-fragment results and application facts for validation, but replacing a normally returning, non-mutating observer with a NOP preserves semantic resolution results. Because callbacks are synchronous, an observer that throws or blocks can still change failure or latency and violates the intended instrumentation contract.

## Successor Demand

Successor demand is output projection, not input closure. It retains passive selections and argumentless resolver-bearing selections that the current resolver may supply. Argument-bearing resolver fields remain necessarily active.

Each boundary resolver's fixed object fragment contributes its passive predecessor demand transitively, conservatively including argumentless resolver-bearing fields that may be supplied by an ancestor. The original downstream construction demand continues separately into each returned child OER, where source-sensitive synchronous closure assigns only unresolved work to standard resolvers.

Successor-demand construction also transposes a selected child's `parent { ... }` demand into the containing producer selection. This static lift ensures a selective producer returns the necessary ancestor coordinates before child identity exists. The lifted portion is also included in the returned ancestor's one-shot input closure so absent fields can use their standard resolvers. Variables are prohibited beneath parent selections, so the lifted shape is fixed before occurrence-specific binding. List and nested-list child results are transparent to the ancestry relation: every contained child OER whose parent is demanded points to the same containing parent occurrence.

Sometimes-passive active fields can make transitive ancestor demand speculative. If a grandchild field with `parent.parent` demand is later supplied passively, its standard resolver need not run but the lifted ancestor demand may already have caused extra resolution or materialization. Resolver26 deliberately accepts this marginal over-work rather than making demand closure depend on a later dynamic ownership decision.

## Strictness

Binding declaration and completion, cell reservation and claiming, writer ownership, and OER freezing are strict. Repeated or contradictory transitions are protocol defects, not harmless idempotence.

## Deliberate Scope

Resolver26 models query resolution with canonical field identity and synchronous source values. It supports runtime `FromObjectField` and `FromQueryField` bindings within their stated provider restrictions.

Correctness validation currently requires an error result only to agree with the resolver value's error variant. Exact `EngineErrorData` carrier identity and metadata agreement are deferred: fixture node lowering and other derived resolver boundaries may replace the carrier while preserving the modeled error outcome. A future error-attribution contract must first define which boundaries preserve identity and which construct a derived carrier before `correctResolution` can validate metadata without rejecting supported resolver behavior.

The current integration target excludes mutations, subscriptions, custom scalars, EOD aliases, and asynchronous EOD variants. These exclusions constrain future alignment and do not require resolver26-specific production adapters inside qplan. The separate execution feature-test adapter still rejects production `FromQueryField` recipes; that adapter boundary does not limit Resolver26's semantic capability.
