# Resolver26 Design

## Status

Resolver26 is the primary qplan algorithm and eventual implementation blueprint. It is a selective query resolver based on structured concurrency and synchronous symbolic closure.

Every asynchronous Resolver26 producer terminates the promise it claims or declares when its `Job` is cancelled, including when cancellation prevents the coroutine body from entering. Ordinary `Exception`s, including a task-local `CancellationException` thrown while the coroutine remains active, become modeled outcomes owned by the corresponding field task. `Error`s escape Resolver26 without a cleanup guarantee. The complete protocol is recorded under [Failure And Cancellation Protocol](#failure-and-cancellation-protocol).

## Root-field references

All maintained resolvers implement the common reference semantics summarized in
[`semantics/README.md`](../../../../../README.md#shared-semantic-boundaries). This section specifies
Resolver26's additional closure, condition, runtime-binding, task, and observation machinery; it is
not the capability boundary for Resolver01-23.

`ResolverOutputData ::= EngineOutputData | RootFieldReferenceData`. `RootFieldReferenceData` is therefore an opaque symbolic resolver instruction in the resolver codomain, not engine data available as resolver input. Its canonical path starts at Query, crosses only singular argumentless object namespace fields, and ends at a registered object-field resolver whose target returns a singular composite, scalar, or enum; its target arguments are already grounded and variable-free. A composite reference target conforms to its consumer only when the target's possible concrete object types are a subset of the consumer's possible object types. This conservative check occurs before invocation: an abstract target with only partial overlap is rejected even if one runtime result could have conformed. Unlike production's object-only `LazyEngineObjectData` carrier, Resolver26 may publish referenced scalar and enum values because it resolves the instruction before constructing the consumer result. The descriptor itself has no object fragment, Query fragment, or direct `@parent` relationship. Qplan additionally requires every resolver invoked as a reference target to have an empty object fragment and no `FromObjectField` variables. A target may retain Query RSS and `FromQueryField` variables, including namespace-relative input expressed by prefixing the selection with its Query-to-namespace path. This deliberate fragment restriction matches the audited tenant usage and is narrower than production Viaduct.

Ordinary active fields alone participate in `closeInputDemand`'s fixed point. Source presence takes precedence over registry membership: when a source supplies a root-field reference for a field with a registered resolver, that reference owns the occurrence and the field's standard resolver and its input demand are not activated. Once the fixed point stabilizes, closure scans directly demanded source fields for embedded references and creates root-reference occurrence contexts. A reference occupying a list-element position is likewise valid when the containing list field has a registered resolver and is being passively supplied. References nested in lists do not add closure bookkeeping: passive-value recursion reaches each concrete element occurrence and assigns it a stable pending list cell.

A reference occurrence separates stable publication state from replaceable invocation state. `ResolverOccurrenceContext` is the sealed input to `FieldResolverTask`: ordinary resolver, direct root-reference, and conditioned passive-value occurrences expose their consumer selection, publication path, expected type, and construction demand. A conditioned passive-value occurrence lets the same field task activate a reference-bearing list before passive materialization begins; it does not introduce another coroutine task kind. The task separately receives the single publication cell, while its containing OER occurrence supplies the structural parent and publication root. For each reference hop, the field-resolution task creates an invocation context whose occurrence ID uses a fresh empty Query OER plus the reference path, whose object input is an empty EOD of the target field's containing type, and whose Query fragment runs in the usual independent Query-fragment OER. The grounded reference arguments immediately bind any `FromArgument` definitions; `FromProvider` and Query-fragment-sourced bindings retain their ordinary lifecycles. No namespace OER chain, detached orchestration task, object-demand closure, or target publication cell is created. The returned ordinary value is materialized only at the consumer occurrence, so simple values are published directly while object descendants and parent fields use the consumer structure.

Ordinary fields, object-field references, and list-element references use the same `FieldResolverTask`. Their occurrence context supplies the stable publication path, expected type, and construction demand; the task's separate cell and containing OER occurrence supply the value promise, structural parent, and publication root. The task first performs the publication occurrence's inclusion and activation step, branches between ordinary symbolic-argument invocation and an already-grounded initial reference, then uses one tail loop for every direct reference result before publishing normally.

If a referenced resolver directly returns another root-field reference, the shared tail-resolution loop creates another resolver occurrence with a fresh identity root, invokes the next target, and keeps the original publication identity. Every sibling reference and every tail hop has distinct root identity. Qplan intentionally does not share these roots or deduplicate equivalent descriptors; physical caching can be layered above this semantic occurrence model. Symbolic reference recursion has the same tenant-code nontermination status as other infinitely recursive resolver behavior and has no reference-specific cycle detector.

Resolver observations record both the stable publication identity and every independently rooted invocation. Correctness replay re-evaluates the source-owning resolver, consumes the exact canonical reference-hop chain needed by that output, re-evaluates each target with its empty object input, arguments, canonical completed-output demand, and Query witness, and finally checks the ordinary output at the consumer position. It does not trust the observer's recorded supplied demand; exact runtime-demand validation remains a separate application-witness property. Every invocation path must exactly equal the descriptor's grounded path, every invocation root must be fresh across the request, and every observation published beneath the result root must be consumed by one replayed chain. Application accounting uses only the observations justified by this source-ownership replay; a reference identity root contains no executable namespace occurrences or object-fragment dependencies to traverse.

## Resolver Identity

Every OER is a map whose keys compare by canonical field and argument expression. Ground keys with equal values coalesce. Symbolic keys coalesce when they contain equal variable instances in equal expression positions. A variable-bearing resolver-fragment selection retains that symbolic `ObjectKey` as its permanent cell identity; completing its bindings makes the key contextually grounded but never rekeys the cell.

Registry variables are templates. Instantiating a resolver fragment replaces all uses of each template with one variable instance identified by the concrete resolver path. Equal uses of that instance therefore coalesce within an OER, including duplicate selections and aliases that project the same canonical key. Different variable names, defining fields, or resolver paths remain distinct even when their completed bindings have equal values, so the same grounded field arguments may be invoked more than once.

Keys need no child-occurrence localization. Each object and list element owns a distinct OER, so the same symbolic key can be reused in multiple containing objects without conflating their cells. A nested resolver receives its own concrete path when its fragment is instantiated.

Resolver input materialization filters source occurrences to the concrete object type and collects them by response key. Each response group awaits every argument binding, requires its symbolic construction key to be contextually grounded, and reads that exact OER cell without substituting arguments into its identity. Duplicate occurrences in one response-key group contribute one input entry with their combined subselections. Distinct aliases remain distinct input entries even when they project one shared construction key.

Shared correctness validation has no resolver-family addressing mode. Every stored key it traverses must be contextually grounded. To validate demanded selections and reconstructed resolver input, it prefers the exact symbolic key when that cell exists and otherwise accepts the key's grounded projection, which keeps validation compatible with earlier resolver families without weakening Resolver26's runtime identity.

## Request And Task Ownership

One root `coroutineScope` owns the request. Every orchestration task and field-resolution task is a direct child of that request scope. Each asynchronously produced field publication, Query value, and provider binding is also coupled to its producer job's terminal state, so cancellation before coroutine entry cannot strand its promise. Successful synchronous return therefore means all request work has reached quiescence.

Task completion is not a cross-task readiness protocol. Cross-task reads use OER value promises, binding promises, or an OER's bindings-declared signal. The dispatcher changes scheduling only; it does not change resolver, variable, path, or task identity.

### Failure And Cancellation Protocol

Field-resolution failures are result values. An exception attributable to a field-resolution task completes that field's value slot with `ErrorEngineResult`; it does not exceptionally complete the slot and does not fail the request. Synchronous object-fragment input construction throws unexpected exceptions directly to the owning field task's `run` boundary. Query-fragment production runs in a separate coroutine and therefore publishes the model-level structured union `EngineObjectOrErrorData.Success | EngineObjectOrErrorData.Error` through `QueryValuesState`; the owning field task invokes tenant code only after receiving its successful object variant. Provider readers continue to complete their bindings with `VariableBinding.Error`. Genuine request cancellation is not a data variant: it cancels the relevant promise so `await()` throws `CancellationException` according to structured-concurrency rules.

`CancellationException` is not sufficient by itself to identify request cancellation because tenant or framework code may throw one while the current coroutine remains active. Every field-task and asynchronous bridge exception boundary calls `currentCoroutineContext().ensureActive()`. If the coroutine is active, the caught `Exception`—including a task-local `CancellationException`—is an ordinary field failure. If the coroutine has been cancelled, `ensureActive()` throws the coroutine's cancellation and the boundary propagates it according to Kotlin structured-concurrency rules.

The canonical field-task boundary is:

```kotlin
catch (cause: Exception) {
    try {
        currentCoroutineContext().ensureActive()
    } catch (cancellation: CancellationException) {
        throw cancellation
    }

    publishFieldError(cause)
}
```

Promises expose `complete(value)` and `cancel(CancellationException)`, both as atomic Boolean-returning transitions. Cancellation is the only public exceptional terminal transition: it exists so a coroutine suspended in `Promise.await()` resumes by throwing Kotlin's cancellation exception. Job-completion handlers are the sole cancellation-cleanup authority: after a producer job reaches terminal cancellation, its handler cancels any outstanding owned promises, including when cancellation prevented the coroutine body from entering. Ordinary exceptions are converted to modeled outcomes inside the coroutine body before the job completes.

Kotlin cancellation remains a `CancellationException` until the owning `Job` has reached its terminal cancelled state. A Kotlin coroutine must not replace that exception with a non-cancellation wrapper from inside its body because Kotlin would treat the wrapper as task failure. The GraphQL `CompletableFuture` bridge translates only after job completion: it completes the Java future exceptionally with `CoroutineCancellationBridgeException`, whose cause is the original Kotlin cancellation but which is not itself a `CancellationException`. Consequently Kotlin cancellation does not accidentally set Java `CompletableFuture.isCancelled`, while an explicit Java `future.cancel()` remains distinguishable and cancels the bridge task in the opposite direction.

Resolver26 does not catch, convert, record, or use `java.lang.Error` to complete promises. An `Error` escapes the Resolver26 task unchanged for a broader fault-tolerance boundary; Resolver26 makes no cleanup or quiescence guarantee after such a failure.

`Resolver26OperationContext` is the stable reference bundle for this scope. It extends the shared `OperationContext`, retains the request coroutine scope and Resolver26 observer, and exposes three independent mutable protocols as properties: `cycleChecker: CycleCheckState`, `bindingDeclarationsState: BindingDeclarationsState`, and `queryValuesState: QueryValuesState`. The context neither implements those protocols nor owns their mutable storage.

`OEROccurrenceContext` bundles the root OER, exact root-relative structural path, target OER, and optional immediate parent occurrence that remain unchanged while Resolver26 orchestrates or resolves one object occurrence. Primary-operation and Query-fragment roots create self-rooted occurrences without parents. Passive object materialization creates descendant occurrences at the exact field-and-list path where each new target is published. This is deliberately Resolver26-local: it captures a coherent task boundary here without requiring unrelated model or resolver APIs to unpack and transform the bundle.

## Synchronous Demand Closure

`ObjectOrchestrationTask.prepare` synchronously computes the single closed `ObjectSelectionForest` for its concrete OER and establishes its binding-declaration domain.

Closure repeatedly expands each newly seen resolver `ObjectKey` whose field is absent from the source EOD with that resolver's complete object fragment instantiated at the resolver path. As part of the same fixed point, it analyzes parent selections in requested descendants and reachable resolver inputs, transposes their variable-free demand across the matching producer edge, and adds the resulting ancestor demand to the containing OER. A source-present argumentless field remains unexpanded and is materialized from the source even when the registry contains its standard resolver. Expansion does not await argument bindings. It records the resolver template, its fixed input demand, and one definition for each instantiated variable.

Every source occurrence carries a conjunctive inclusion condition. Merging equal keys disjoins those occurrence conditions locally and pushes each occurrence's condition into only its own descendants. Closure therefore remains unconditional and structural: it discovers and installs every possibly needed resolver task before any condition binding must be ready. Provider paths are additionally retained without their own conditions when they produce variables consumed by conditions, preventing a condition from waiting on a provider cell whose activation depends on that same condition.

Every resolver key in closed demand is represented either by the expansion map or by an argumentless source-provided field. Successor-demand construction still transposes parent selections into selective producer output before returned objects exist, while input-demand closure independently ensures the required ancestor cells have writers. Repeating the one-level transposition through input analysis handles grandparents without recursively traversing parent backedges as structural children or reopening an ancestor task.

An open resolver key contributes its object-fragment dependencies before its arguments ground. If those arguments later become an error, those dependencies may have executed speculatively. That imprecision is accepted by the current model.

## Binding Declaration

After closure, the orchestrator declares every open binding before launching local field work.

`FromArgument` definitions owned by an already-ground key read their canonical input paths and complete immediately. A null input-object intermediate produces a null binding. Definitions owned by symbolic keys complete after the owner's arguments resolve, while the owner key itself remains unchanged.

Each `FromObjectField` definition launches a provider reader that follows its compiled path through the defining occurrence's object OER promises. Each `FromQueryField` definition launches an equivalent reader through that occurrence's fresh Query OER. Provider path templates are instantiated for the owning resolver occurrence, and provider arguments may be grounded from literals, defaults, the owner's arguments, or other acyclic from-field bindings of either kind. All binding promises are declared before provider readers and field resolvers launch.

Before reading a provider component inside an OER, its reader awaits that OER's bindings-declared signal and every argument binding needed to make the component key contextually grounded. `ObjectOrchestrationTask.prepare` marks bindings declared immediately after synchronous demand closure declares every binding in the OER's binding domain, before recursively materializing passive children or launching local field work.

`BindingDeclarationsState` owns these per-OER readiness signals. `VariableBindingsState` separately owns actual variable-instance bindings, and `QueryValuesState` owns the declared-then-completed Query input for each resolver occurrence. Declaration is strict, while each atomic completion attempt returns whether it performed the terminal transition; consumers never manufacture undeclared query values or variable bindings.

Nested provider keys resolve their argument values against the owning resolver occurrence and use the original symbolic key for OER lookup. The separately resolved arguments are a readiness and invocation witness; they do not replace the key.

Readers never insert undeclared binding promises.

## Passive Values

Every argumentless field present in a resolver's source EOD is read by canonical field name through resolver26's local `resolvePassiveValues` path, including fields that have standard resolvers in the registry, except that an engine-provided `@parent` entry is ignored in favor of the structural backedge. Source-provided argument-bearing fields are errors. A demanded registry field absent from the source uses its standard resolver; a demanded non-registry field absent from the source remains an error.

The field-resolution task builds the passive structural result tree supplied by the resolver before publishing the containing value. Resolver26 creates one `ObjectOrchestrationTask` with each OER and calls its non-suspending `prepare` function immediately. Prepare closes only construction demand propagated through the containing field; parent-induced construction demand is derived inside that input closure, using source presence to decide which standard resolvers remain actual work, then declares and marks bindings. It also installs each demanded `ParentKey` with the actual immediate ancestor OER. Invocation demand separately validates selective output and guides recursive materialization of every passive returned field before the task's non-suspending `launch` function runs. This parent-first recursion establishes every descendant binding domain before field work starts.

After passive children have launched, the parent launch validates its materialized passive cells. An object with no active expansions freezes synchronously without creating a coroutine. Otherwise, launch schedules the task's suspending `launchBindingsAndResolvers` function to read providers associated with its active expansions, install active fields, and freeze the OER.

## Active Installation And Freeze

The orchestrator reserves each closed active selection's original symbolic cell, claims its value promise, registers its writer, and launches one field-resolution task before freezing the OER. A root-field reference occupying a list element likewise claims that element's pre-reserved promise before its task launches. The task then awaits argument bindings, derives the invocation `Arguments.Ground`, completes delayed `FromArgument` bindings, and evaluates the selection's merged inclusion condition. Negative activation returns without invoking tenant code; positive activation permits the already-claimed promise to be used. Keeping reservation and writer registration synchronous preserves discoverability before freeze while moving readiness work into the conservatively launched task.

Resolver26's `CycleCheckState` is explicit operation state. Installation registers each active cell's exact writer through `operation.cycleChecker`, and provider and resolver-input reads record their dependency through the same property. Other resolvers and correctness materialization may supply a separate state or the NOP implementation; `Resolver26OperationContext` does not masquerade as a cycle checker.

`reserveCell` explicitly creates an unclaimed cell placeholder when needed. `Cell.createValuePromise` claims that placeholder for the writer. Strict claiming makes disagreement between readers and writers observable.

After every local active key has claimed its symbolic cell, the orchestrator calls `freeze`. Freezing seals the OER key set and completes any unclaimed reader placeholder with a strict missing-cell exception. Claimed promises may complete or their cells may be negatively activated after the OER is frozen.

## Field Resolution

The field-resolution task:

1. grounds its arguments and completes any delayed `FromArgument` bindings;
2. awaits and evaluates its inclusion condition, negatively activating the cell and returning when false;
3. materializes the resolver's condition-filtered input demand from exact OER cells;
4. derives invocation successor demand from the key's closed construction demand;
5. awaits the independently orchestrated Query-rooted input;
6. records the occurrence-aware application observation;
7. invokes the selective resolver once;
8. builds the passive result shape while synchronously launching one orchestration lifecycle per OER; and
9. publishes the containing value.

Parent publication does not wait for descendant orchestration to finish. Readers independently derive and reserve the same symbolic child keys; variable-instance equality and strict reservation rules make disagreement fail rather than silently create another identity.

Query fragments reuse the defining resolver occurrence's variable bindings, retain their complete response-preserving symbolic selection tree, and use an ordinary `ObjectOrchestrationTask` rooted at an otherwise independent Query OER. Their orchestration starts alongside object-path provider readers and active field installation so a `FromQueryField` binding can ground the object fragment and a `FromObjectField` binding can ground the Query fragment without imposing an artificial fragment order. Query-provider readers complete their bindings as soon as their exact paths resolve; the owning field resolver separately awaits materialization of the complete Query input. A Query-only `FromArgument` use binds directly from the owning resolver arguments, while a binding used by both fragments is declared and completed only once. Materialization resolves arguments only to establish contextual grounding and invocation values. The OER is retained as a correctness witness under the owning resolver's exact result path.

The owning selection condition guards ordinary Query-fragment construction demand and provider reads, while explicit provider-path demand keeps the values needed to evaluate nested Query-fragment conditions available. Query-fragment materialization removes false source occurrences before response-key collection. Thus a conservatively launched Query task may produce an empty resolver-visible object and physical inactive cells without invoking the suppressed dependency resolvers.

Argument errors complete the value slot with `ErrorEngineResult` without invoking the resolver. Successful values complete the value slot once. Resolver26 does not publish access-result slots: access-check execution and its validation are future work, and the `true` access results written by some earlier resolver experiments are not part of the current resolver contract.

Resolver observations are semantically passive evidence. Resolver26 records Query-fragment results and application facts for validation, but replacing a normally returning, non-mutating observer with a NOP preserves semantic resolution results. Because callbacks are synchronous, an observer that throws or blocks can still change failure or latency and violates the intended instrumentation contract.

## Successor Demand

Successor demand is output projection, not input closure. It retains passive selections and argumentless resolver-bearing selections that the current resolver may supply. Argument-bearing resolver fields remain necessarily active.

Each boundary resolver's fixed object fragment contributes its passive predecessor demand transitively, conservatively including argumentless resolver-bearing fields that may be supplied by an ancestor. The original downstream construction demand continues separately into each returned child OER, where source-sensitive synchronous closure assigns only unresolved work to standard resolvers.

Successor-demand construction also transposes a selected child's `parent { ... }` demand into the containing producer selection. This static lift ensures a selective producer returns the necessary ancestor coordinates before child identity exists. The lifted portion is also included in the returned ancestor's one-shot input closure so absent fields can use their standard resolvers. Variables are prohibited beneath parent selections, so the lifted shape is fixed before occurrence-specific binding. List and nested-list child results are transparent to the ancestry relation: every contained child OER whose parent is demanded points to the same containing parent occurrence.

Sometimes-passive active fields can make transitive ancestor demand speculative. If a grandchild field with `parent.parent` demand is later supplied passively, its standard resolver need not run but the lifted ancestor demand may already have caused extra resolution or materialization. Resolver26 deliberately accepts this marginal over-work rather than making demand closure depend on a later dynamic ownership decision.

## Strictness

Binding declaration, cell reservation and claiming, writer ownership, and OER freezing are strict. Promise, cell-activation, cell-value, binding, and Query-value completion operations atomically report whether each caller performed the terminal transition. Producer call sites explicitly check that result where losing would be a protocol defect; cancellation and terminal-cleanup paths intentionally tolerate losing to another terminal transition. Invalid values, missing ownership, undeclared state, and prohibited writes still throw rather than becoming a `false` completion result.

## Deliberate Scope

Resolver26 models query resolution with canonical field identity and synchronous source values. It supports runtime `FromObjectField` and `FromQueryField` bindings within their stated provider restrictions.

Correctness validation currently requires an error result only to agree with the resolver value's error variant. Exact `EngineErrorData` carrier identity and metadata agreement are deferred: fixture node lowering and other derived resolver boundaries may replace the carrier while preserving the modeled error outcome. A future error-attribution contract must first define which boundaries preserve identity and which construct a derived carrier before `correctResolution` can validate metadata without rejecting supported resolver behavior.

The current integration target excludes mutations, subscriptions, custom scalars, EOD aliases, and asynchronous EOD variants. These exclusions constrain future alignment and do not require resolver26-specific production adapters inside qplan. The separate execution feature-test adapter still rejects production `FromQueryField` recipes; that adapter boundary does not limit Resolver26's semantic capability.
