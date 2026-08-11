# Resolver25 Design

## Status

Resolver25 is an experimental one-shot resolver built from the Resolver23 coroutine structure. This document describes the design as it exists now and records the reasoning that led to it. The orchestration protocol is expected to last longer than the current path-variable implementation. The current path-variable restrictions are experimental scaffolding, not proposed final semantics. In particular, support only for direct scalar sibling providers is too restrictive to establish a general solution.

## Goal

For each resolver-bearing object-result instance and exact `GroundKey`, aggregate all in-scope demand before launching its resolver, then launch that resolver exactly once. The resolver must receive the complete selective demand that belongs to its producer. Later readers, cache hits, or demand unions must not make an under-supplied resolver invocation appear correct after the fact. Execution state is monotonic: promises, bindings, and latches move from absent or incomplete to one terminal value and are not replaced.

## Historical Shape

Resolver25 has the same two fundamental kinds of work as the earliest resolver constructors:

1. **Orchestrate an OER node.** Discover and coordinate the active fields needed on one concrete `EngineResult.Object`.
2. **Resolve an OER key.** Materialize one resolver instance's inputs, launch its resolver, construct its output, and publish the exact value.

This division is not new, and this document does not repeat all of its inherited mechanics. Humans and agents should read Resolver03 for the compact recursive selective constructor, Resolver08 for the same shape expressed as explicit depth-first orchestrator and resolver tasks, and Resolver23 for the structured-coroutine form on which Resolver25 is based. Resolver03 names the operations `orchestrateKeys` and `resolveKey`. Resolver08 turns those operations into `SlotOrchestrator` and `SlotResolver` work items. Resolver23 replaces explicit queue scheduling with deferred value promises and structured suspension. Resolver25 retains that lineage while adding an explicit preparation protocol before resolver launch.

## Terms

An **object-result instance** is one runtime `EngineResult.Object` at one result-tree location. A **resolver template** is the static schema and registry definition. A **resolver instance** is the dynamic resolver for one exact field key on one object-result instance. An **arg-variable** is bound from the exact arguments of its defining resolver instance. A **path-variable** is bound by reading a value from an OER path. A **latch** is a deferred value used to announce that a coordination phase has completed. A value-bearing deferred can be both a result and the latch announcing that the result is ready. **Prepare** means establish the complete exact demand and all resolver-instance state needed before launch. **Launch** means install value promises and start the resolver coroutines.

## Runtime Ownership

The public `resolve` entry creates the mutable root OER and one structured coroutine scope.

`ResolverRuntime` belongs to that scope and creates at most one `ObjectResultOrchestrator` for each target OER identity.

The identity set rejects late attempts to create a second orchestrator for an already orchestrated object-result instance. Every orchestrator and resolver coroutine is a child of the root structured scope. The public call therefore does not return an OER containing abandoned background jobs.

`coroutineScope` waits for all descendant work to complete, and a child failure cancels the request scope.

No job intentionally escapes the request.

## Object-Result Orchestration

An `ObjectResultOrchestrator` owns coordination for one source object and its corresponding target OER. It creates one `FieldState` for every canonical field of the source object's concrete type. The field is the planning granularity because demand for multiple exact keys of that field can depend on the same statically known contributors. For every field, orchestration starts one preparation coroutine and one launch coroutine. A third coroutine waits until every field has installed all of its demanded value promises and then completes `orchestrationReady`. The coroutines express dependencies by awaiting latches and promises rather than by polling a readiness worklist.

## Coordination Stages

Resolver25 distinguishes four facts that earlier implementations could leave implicit.

### Preparation

`prepareResolverInstances` waits for every field whose preparation can contribute demand to the current field.

It also waits until any currently modeled incoming path-variable provider promises can be looked up. It snapshots accumulated selections, fetches variable bindings, produces exact `GroundKey` values, and groups selections that ground to the same key. Grouping happens after substitution because symbolic keys that look different can become one exact producer. The merged map is immutable for the remainder of orchestration. Preparation then calls `prepareResolverInstance` once for every exact key. Preparing an active resolver instance binds arg-variables, contributes its stamped object-fragment demand, declares path-variable bindings, and launches the current path-variable fetchers. Preparing does not launch the field resolver itself.

### The `sealedDemand` Latch

Each `FieldState` owns:

```kotlin
CompletableDeferred<Map<Value.GroundKey, ObjectSelection>>
```

This value is named `sealedDemand`. It is both the outcome of preparation and the latch for preparation as a whole. Completion means that demand for the field is sealed, equal exact keys have been merged, and every resulting resolver instance has otherwise been prepared. Consumers await the map directly; there is no separate preparation-complete latch. Calling `FieldState.add` after this latch completes is an error. The value-bearing latch makes the phase boundary explicit without duplicating state.

### Launch

`launchResolverInstances` awaits `sealedDemand`.

It finds exact demanded keys that are not already present in the target OER. It eagerly creates every missing value promise and registers its writer before launching any resolver coroutine for the field. It then completes `promisesInstalled`. Before launching, it waits until promises for the resolver's object-fragment input fields are installed and can therefore be read safely. Finally, it launches one `resolveKey` coroutine for every unresolved exact key. The launch phase does not require input values to be complete before the coroutine exists; materialization awaits the exact promises it reads.

### Promise Installation

`promisesInstalled: CompletableDeferred<Unit>` means that every exact promise selected by the field's sealed demand exists in the target OER.

It does not mean that those promises have values. This distinction prevents lookup races while preserving concurrency between producers.

### The `orchestrationReady` Latch

Each `ObjectResultOrchestrator` owns one `orchestrationReady: CompletableDeferred<Unit>`. It completes after every `FieldState.promisesInstalled` latch on that OER instance has completed. `ResolverRuntime.createOrchestrator` returns this latch to the resolver that created the OER instance.

When `resolveKey` creates descendant OER instances, it collects their `orchestrationReady` latches and awaits them all before completing the parent key's value promise. A reader therefore cannot observe a published child OER before all of that child's demanded exact promises can be looked up. This preserves Resolver23's install-before-parent-publication discipline.

`orchestrationReady` does not mean that any descendant resolver has completed. It marks only the point at which the descendant OER instance is structurally ready to publish; exact value promises remain the resolver-instance completion latches.

### Promise Completion

The exact value promise stored at `target[key]` is the completion latch for that resolver instance. There is deliberately no additional resolver-complete latch. Provider reads and ordinary materialization await the exact value promises they need. The promise is value-bearing, so completion and the produced result cannot diverge. An OER-wide or field-wide completion latch would be coarser and duplicative.

## Resolving One Key

`resolveKey` handles one exact `GroundKey`.

Argument errors and `__typename` complete directly. An ordinary active key materializes the resolver template's object fragment from the target OER. Materialization reads exact promises and records the resolver instance as the reader for cycle diagnostics. The resolver receives its ground arguments and selective successor demand. Its returned `Value.Output` is converted into `EngineResult` structure. Any descendant OERs requiring active resolution receive their own orchestrators. The parent value is published only after those descendant orchestrators report that their promises are installed. The descendant values need not be complete at that moment; their exact promises remain the completion mechanism. Resolver25 treats a passive key reaching `resolveKey` as an invalid state.

## Static Preparation Plan

`StrictPreparationPlan` currently builds a plan for one concrete object type.

It gives every field two steps: `PREPARE(field)` and `LAUNCH(field)`. Every field has an edge from its own preparation to its own launch. Object-fragment demand adds preparation edges from a consuming resolver field to fields that can receive its contributed demand. Object-fragment reads add launch edges ensuring required field promises are installed before the consumer launches. The current path-variable subset adds an edge from provider launch to preparation of a field whose key consumes the variable. The combined graph is checked for cycles before orchestration. The runtime projects that graph into `demandContributors`, `incomingPathVarProviders`, and `resolverInputFields`. This phase graph is useful evidence that preparation dependencies and value-reading dependencies are not the same relation. Its current type-local construction is not yet a general operation-and-registry ordering analysis.

## Diagnostic Instrumentation

`RuntimeSupport` is carried as `diagnosticInstrumentation`.

Resolver25 calls `successorDemand()` directly rather than hiding ordinary demand semantics behind the instrumentation abstraction. The instrumentation registers one writer coordinate for every exact value promise. Materialization and provider reads register exact reader coordinates. Those coordinates allow runtime cycle diagnostics to explain a resolver-read cycle in terms of concrete object-result instances and keys. This runtime checking complements static rejection; it does not prove that arbitrary coroutine deadlock is impossible.

## What Resolver25 Currently Demonstrates

Resolver25 demonstrates that Resolver23's structured-coroutine shape can be extended with an explicit prepare-before-launch protocol. It demonstrates a value-bearing per-field latch that publishes exact merged demand. It demonstrates eager promise installation without a duplicate resolver-completion mechanism. It demonstrates late equality merging before one launch within its accepted direct-sibling cases. It demonstrates that a combined prepare/launch graph can reject a cycle missed by a graph containing only ordinary resolver reads. It preserves one structured request scope and exact read/write diagnostics.

## What Resolver25 Does Not Yet Demonstrate

Resolver25 does not support general path-variable provider paths. The current implementation accepts only a direct scalar sibling provider with a variable-free key. It rejects nested provider paths, list-valued paths, provider chains, nested variable uses, and several otherwise potentially acyclic shapes. Those restrictions reflect the implementation experiment, not acceptable long-term restrictions. The direct-sibling `readDirectProvider` helper is therefore not the general provider traversal design. The type-local phase graph has not shown that it can order resolver instances across descendant object-result instances or list positions. The implementation has not proved that static operation-and-registry analysis can bound every future demand contributor for the general path-variable domain. It also has not proved schedule independence on arbitrary dispatchers. Passing focused and generated tests is implementation evidence, not a proof of producer-complete one-shot resolution.

## Near-Term Design Direction

Keep the old orchestrate-OER/resolve-key shape. Keep structured concurrency, install-before-publication, exact value promises, and the distinction between prepare and launch. Keep `sealedDemand` as a value-bearing per-field preparation latch unless a more precise granularity is shown necessary. Replace the direct-sibling path-variable model with one that can represent provider paths through descendant object-result instances and terminal scalar-list providers while preserving distinct resolver instances inside list-element instances. Analyze a GraphQL operation together with the executor registry to add ordering edges between the resolver instances that can exist for that operation. Prefer restrictions that ensure this ordering is acyclic and statically bounded. Do not treat a one-component path restriction as the solution. Do not recover correctness by retaining complete output everywhere or by keeping every orchestrator open indefinitely.

## Appendix: Context Dump

### Why One-Shot Is Hard

The one-shot requirement is producer-specific: all in-scope demand for one exact resolver-bearing OER key must be supplied to the invocation that produces it. Final result coverage is weaker because later demand, cache hits, or additional materialization can hide that the producing invocation was incomplete. Waiting for all currently known contributors is also weaker than proving that no future execution can reveal another contributor. Selective resolvers make this distinction observable because a resolver can discard output that was not requested when it launched. Once that happens, widening demand afterward cannot recover the missing output without a second invocation.

### The Resolver09 to Resolver10 Jump

Resolver09 works with exact grounded resolver instances and refreshes readiness as parent execution publishes more OER structure. Its persistent OER-local orchestrators can discover descendant and list-element instances after their containing values exist. Dependencies can be recomputed from grounded materialized object fragments and exact instance coordinates. Resolver10 adds runtime path-variable providers, and that changes the state space rather than merely adding another dependency edge to an already ground graph. A resolver object fragment may contain a symbolic key whose exact `GroundKey` is unavailable until another OER value is produced. Resolver10 consequently needs pending symbolic selections, pending bindings, provider traversal, late grounding, equality convergence, and a rule for when demand is safe to seal. Provider traversal can itself require resolver work and can cross nested objects whose runtime instances do not exist until ancestors publish their shape. The implementation must distinguish "the provider path is statically known" from "the exact provider instance and value are now available." It must also distinguish "this symbolic key became ground" from "all demand that may converge on that ground key has been found." That is the core reason the Resolver09 to Resolver10 progression grows sharply in complexity.

### Path Variables Add Resolver-Instance Dependencies

A path-variable is not merely delayed argument substitution. It adds a value-flow dependency between dynamic resolver instances. In the running `B -> C -> A` shape, `B` produces the value used to ground a key in `C`'s object fragment, and preparing that grounded `C` instance can contribute additional demand to `A`. If the relevant key is `C.f($v)`, `A` needs to wait only when preparing that exact use can add demand to `A`. This observation led away from generic "prerequisite" vocabulary and toward incoming path-variable providers and per-field preparation. The important relation is not just that `C` reads `B`; grounding `C` can change what demand must be sealed for `A`. Thus successor demand for `A` cannot always be computed precisely before the variable use in `C` is grounded.

### Why Preparation Is Separate From Launch

Launching a resolver instance can be too early even when all of its currently visible input values are ready. Another resolver instance may still be waiting on a path-variable provider and may later contribute demand to the same producer. Preparation is the phase in which these potential demand contributions must become exact and merge. Launch is permitted only after preparation establishes the immutable demand for the producer. The user steered the design toward a value-bearing latch at object-field granularity because the relevant unit is "all exact demand for this field has been prepared," not "an entire OER has globally finished." The resulting `sealedDemand` value reports both the merged demand and completion of resolver-instance preparation.

### Why Promise Stages Matter

Promise installation and promise completion release different dependencies. A consumer can safely start materialization once the exact promises it may read have been installed, even while their producers are still running. The exact promise then suspends the consumer until the value exists. An earlier design carried a separate field-level resolver-completion latch. The user identified that the exact value promise already serves as the resolver-instance completion latch, and the duplicate mechanism was removed. Likewise, `orchestrationReady` does not mean descendant resolution is complete; it means descendant promises are installed so publishing the parent cannot expose an OER whose active cells cannot yet be found.

### Why "Retain Everything" Is Not The Endpoint

Resolver24 and Resolver24i keep persistent orchestrators capable of accepting late grounded demand. They conservatively retain complete internal output so a path reader can observe providers and late demand can flow into published children. That strategy is useful implementation evidence and can preserve runtime correctness over a broad path-variable domain. It does not establish the desired one-shot selective-demand argument if a resolver can launch before all demand for its producer is known. Complete retention can also mask the exact point at which selective output would have been under-supplied. The user therefore rejected "keep every orchestrator alive and retain everything" as the intended endpoint. The desired design bounds contributors and orders preparation before launch.

### Why The Coroutine Baseline Is Resolver23

The reactor progression explains the problem, but it is not the intended implementation direction. The user explicitly chose Resolver21-23, especially Resolver23, as the baseline for future work. Structured coroutines let value and coordination dependencies appear as suspension on deferred values. They avoid readiness polling, repeated orchestrator scans, and scheduler-specific work queues. The root scope also gives cancellation and quiescence semantics directly: success means all child work completed, and failure cancels siblings. Resolver25 therefore attempts to express the extra path-variable ordering as latches and promise dependencies inside the Resolver23 shape.

### The Strictness We Actually Want

The user is willing to restrict path variables to recover a statically orderable one-shot domain. The intended restrictions are principally those needed to bound dependency discovery and prevent cycles. The promising analysis unit is a GraphQL operation in the context of an executor registry, because the operation bounds which resolver instances and paths can activate while the registry describes resolver fragments and variable providers. That analysis should add ordering edges between resolver instances, including edges introduced by path-variable value flow and demand contribution. Accepted inputs should admit an acyclic one-shot ordering. A restriction to a single direct path component does not express that semantic property and discards many potentially acyclic cases. Resolver25's current direct-sibling validator must therefore be treated as temporary scaffolding.

### Cycles Need One Relation

Ordinary resolver inputs already create producer-before-consumer dependencies. Path-variable providers create provider-completion-before-consumer-preparation dependencies. Preparing the consumer can then contribute demand that must precede another producer's launch. Checking these relations independently can miss a cycle that appears only when they are composed. Resolver25's `PREPARE(field)` and `LAUNCH(field)` graph is an initial attempt to place them in one diagnosable ordering relation. The current graph is type-local and field-granular, so it is not yet the final instance-level cycle analysis. Runtime exact-coordinate cycle checking remains valuable for diagnostics and for detecting violated assumptions. The design question is whether the static operation-and-registry graph can subsume the extra planner-local cycle rules while preserving precise runtime error reports.

### Instance Identity Cannot Be Flattened

Resolver dependencies are relationships between resolver instances, not merely resolver templates. One schema path can produce many runtime instances through recursion, polymorphism, and lists. Equal node IDs or equal arguments do not merge separate result-tree instances. Conversely, separately discovered symbolic selections can converge on the same `GroundKey` within one OER instance after binding. Any static ordering must preserve enough guards and provenance to map possibilities to these runtime identities. This is why a schema-only field graph is likely conservative and why operation context matters.

### Late Equality Is The Decisive Case

Suppose one demand already contains `field(arg: "same") { one }` while another contains `field(arg: $v) { two }`. If `$v` later becomes `"same"`, both selections belong to one exact producer and must be merged before launch. Launching the literal key first with only `{ one }` is already incorrect for a selective one-shot resolver. Interning the grounded key later prevents a duplicate writer but does not repair the missing `{ two }` demand. Resolver25's preparation phase handles this case inside its narrow domain by grounding before grouping and launching. The general design must preserve that property across longer provider paths and dynamically created OER instances.

### Static Shape And Runtime Shape

The registry can expose fixed resolver fragments and structural provider paths. The operation can bound selected roots and guarded alternatives. Runtime values still determine exact arguments, concrete types, null branches, list lengths, and the object-result instances that actually exist. One-shot planning therefore needs a static bound on possible contributors without pretending all runtime identities are already known. It may conservatively include guarded alternatives, but it must avoid conflating unrelated instances or recursively expanding an unbounded envelope. The previous Resolver10 work found both sides of this tension: broad conservative projection preserved potential demand but could explode, while narrow eager projection missed provider branches and late contributors.

### Lessons From Resolver10 Debugging

Generated failures showed that skipping all successor closure whenever path variables were reachable omitted ordinary nested behavioral boundaries. Stopping exactly at a path-variable resolver still omitted variable-free provider branches needed to create the provider's nested resolver instances. Conservative activation closure had to begin from actually incoming fields rather than every resolver on an OER type to avoid pulling unrelated recursive branches into execution. Variable-bearing demand could block sealing, but field-wide blocking was too coarse when only one exact argument shape could receive the late contribution. Potential exact-key comparison was needed to release unrelated instances of the same field while preserving late-equality safety. Conservative demand union also needed structural coalescing because duplicate selections compounded through successor closure and caused pathological growth. These are warnings for a static one-shot analysis: conservatism needs instance and key precision, and "include every possibility" is not automatically finite or practical.

### Cleanup As Design Investigation

The cleanup work was intentionally more than hygiene. Standardizing on resolver-instance terminology exposed which relationships were dynamic. Replacing generic prerequisites with incoming path-variable providers exposed the value-flow origin of the ordering. Renaming seal/apply to prepare/launch clarified that preparation includes demand sealing plus resolver-instance setup. Separating `sealedDemand`, `promisesInstalled`, `orchestrationReady`, and exact promise completion exposed four materially different facts. Removing the duplicate resolver-completion latch showed that value promises already carry precise completion. Replacing hidden runtime completion callbacks with direct `successorDemand()` calls separated semantic demand computation from diagnostic instrumentation. Removing optimization-only short circuits made the required phase protocol visible even for empty demand. Questioning the single-component assumption revealed that the current path-variable reader is a dead end if mistaken for the general solution. Future cleanup should continue to be treated as a way to surface hidden assumptions and test whether each abstraction names one real invariant.

### Current Working Hypothesis

The lasting runtime shape is one orchestrator per object-result instance plus one resolver coroutine per exact key. The lasting coordination shape is prepare demand, install promises, launch resolvers, and await exact values. Path variables add preparation-order edges because grounding one resolver instance can reveal demand for another. A viable one-shot design should derive a finite acyclic ordering from the operation and registry, then realize that ordering with coroutine latches and exact promises. Runtime creation of descendant instances must instantiate the relevant statically bounded dependencies without reopening already sealed producers. The current Resolver25 implementation is evidence for the coordination protocol, not yet evidence that this hypothesis works for general path-variable paths.

### Questions To Carry Forward

1. What static node identifies a possible resolver instance before runtime object and list instances exist?
2. Which operation and registry facts bound every demand contributor to an exact producer?
3. How are dependencies instantiated as descendants and list elements are published?
4. At what granularity should preparation latches live when one field has many argument keys with different possible contributors?
5. How does a multi-component provider path request and await intermediate resolver instances without reopening their sealed demand?
6. How are nulls, errors, abstract types, and lists represented in the ordering without manufacturing nonexistent runtime work?
7. Which cycle restrictions are necessary, and which current restrictions are merely implementation shortcuts?
8. Can static cycle detection and runtime exact-coordinate diagnostics share one dependency representation?
9. How is conservative demand bounded so recursive fragments do not produce an impractical envelope?
10. What focused counterexamples distinguish a true one-shot guarantee from final-result correctness or complete-output masking?
