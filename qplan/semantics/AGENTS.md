# Semantics Domain Guidance

## Scope

This project defines transformations and judgments over the model carriers. Follow [`../AGENTS.md`](../AGENTS.md). Semantic code may construct model values but must not redefine or defensively re-check carrier invariants.

The principal judgment is:

```kotlin
context(world: Assumptions)
fun EngineResult.Object.correctResolution(selections: ObjectSelectionForest): Boolean
```

It judges a completed Query OER extensionally; it does not establish execution order, application count, provider binding, or concurrency.

## Resolution

`merge(type)` specializes open selections to one concrete object type. `instantiateBindings()` then grounds arguments and coalesces convergent keys. OER operations must cross the checked `groundKeys()`, `byGroundKey()`, or `ObjectSelection.groundKey()` boundary.

Resolver01-03 share the recursive monotonic constructor in `Resolve.kt`: local demand closure, dependency order, materialization, write-once publication, passive traversal, and recursive continuation. Resolver06-08 reuse those slot operations through the single-threaded `DepthFirstReactor`, whose stable depth-kind-sequence priority queue reproduces depth-first continuation without recursive scheduling. `ResolveValue.kt` allocates child OERs, retains exact active targets, and populates them deepest first without replacing parent value promises.

Stamped variable bindings are declared as request-local promises. `FromArgument` declarations complete immediately from their exact resolver key before stamped demand is grounded; synchronous semantic operations use `getBinding`, while future coroutine grounding may suspend through `fetchBinding`. Resolver10 uses the same promise lifecycle for its existing `FromObjectField` provider evaluation.

Resolver21-23 use the shared structured-coroutine constructor in `CoroutineResolve.kt`. Object orchestration installs and registers every local value promise before launching inherited-context producers with `CoroutineStart.DEFAULT`; each producer directly orchestrates active child OERs before publishing its containing value, then remains in its structured scope until all descendants complete. Resolver input materialization suspends on promises and retains continuations, so these resolvers have no reactor, ready queue, repeated readiness scan, dependency ordering, polling, or escaping job. The tested execution model is single-threaded and does not establish behavior under arbitrary dispatchers.

Resolver09 reuses the same slot operations through its package-local `Reactor`. It seals demand once per active OER occurrence, creates one stable `SlotResolver` per unresolved exact key, and refreshes the shared `resolverDependencies` map against current monotonic OER state so descendant and list-element coordinates appear after active ancestors complete. Dependency coordinates resolve to those stable objects; readiness is `dependencies.all(SlotResolver::isFinished)`. It must not use `ResolvedValue.resolveObjects` or depth-prioritized dispatch.

Reactors share `ReactorInstrumentation` for lifecycle observation and successful-completion invariants, not queues or scheduling algorithms. Report orchestrator and slot-resolver launch, start, and finish transitions through it. Keep scheduler-specific state such as Resolver09's worklist, slot registry, and quiescence diagnostics in that resolver's package; exact dependency derivation belongs to the shared `ResolverDependencies.kt`.

Do not retain `Assumptions` or `RuntimeSupport` as reactor instance state. Keep them at the resolver entry point and declare context parameters on reactor initialization, execution, and helper functions that use them.

Resolver01, its queue-backed counterpart Resolver06, and coroutine-based Resolver21 support empty user object fragments plus generated bridge loaders. Resolver02, its queue-backed counterpart Resolver07, and coroutine-based Resolver22 support nonempty fragments and `FromArgument` with complete output. Resolver03, its depth-first queue counterpart Resolver08, readiness-driven Resolver09, and coroutine-based Resolver23 support that domain with selective projection, while runtime `FromObjectField` is deferred from these families. Resolver01/02/06/07/21/22 use complete resolver outputs, while Resolver03/08/09/23 use full `successorDemand()` and selective projection.

Resolver01/02/06/07/21/22 require `Assumptions.selectiveResolvers == false`; Resolver03/08/09/10/23 require it to be true. Use that world flag for resolver invocation and passive output traversal rather than carrying another selectivity flag in completion state.

Raw node inputs and bridge schema augmentation are fixture concerns. Semantic logic sees `foo$bridge` producers and `T$Bridge.$node { $id }` loaders as ordinary field resolvers.

## Tests

Read [`testing-contracts.md`](testing-contracts.md) before changing or interpreting resolver tests. Start generated-failure investigation with its coordinate replay workflow rather than rerunning a whole class when exact `S:R:Q` coordinates are available.
