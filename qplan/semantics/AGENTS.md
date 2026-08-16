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

Stamped variable bindings are declared as request-local promises. `FromArgument` declarations complete immediately from their exact resolver key before stamped demand is grounded; synchronous semantic operations use `getBinding`, while coroutine grounding may suspend through `fetchBinding`. Resolver25 and Resolver26 use the same promise lifecycle for `FromObjectField` provider evaluation.

Resolver21-23 use the shared structured-coroutine constructor in `CoroutineResolve.kt`. Object orchestration installs and registers every local value promise before launching inherited-context producers with `CoroutineStart.DEFAULT`; each producer directly orchestrates active child OERs before publishing its containing value, then remains in its structured scope until all descendants complete. Resolver input materialization suspends on promises and retains continuations, so these resolvers have no reactor, ready queue, repeated readiness scan, dependency ordering, polling, or escaping job. The tested execution model is single-threaded and does not establish behavior under arbitrary dispatchers.

Resolver25 experiments with a stricter one-shot coroutine construction colocated with its wrapper in `resolver25/Resolver.kt`. It uses one value-bearing preparation latch per `(OER occurrence, canonical ObjectField)` and a static graph whose vertices distinguish preparation from launch. Preparing a resolver instance contributes its fixed object-fragment demand before the target field completes preparation; direct scalar provider promises are installed before variable-keyed sibling fields prepare; object-fragment input promises are installed before their consumer launches. Exact OER value promises are the resolver-instance completion latches. An OER readiness latch separately guarantees that child promises are installed before the containing value publishes. Keep this experiment free of Resolver24's persistent late-demand acceptance, projection envelope, and complete-output retention.

`DepthFirstReactor` reports orchestrator and slot-resolver launch, start, and finish transitions through `ReactorInstrumentation`. Do not retain `Assumptions` or `RuntimeSupport` as reactor instance state. Keep them at the resolver entry point and declare context parameters on reactor initialization, execution, and helper functions that use them.

Resolver01, its queue-backed counterpart Resolver06, and coroutine-based Resolver21 support empty user object fragments plus generated bridge loaders. Resolver02, its queue-backed counterpart Resolver07, and coroutine-based Resolver22 support nonempty fragments and `FromArgument` with complete output. Resolver03, its depth-first queue counterpart Resolver08, and coroutine-based Resolver23 support that domain with selective projection. Resolver25 and Resolver26 additionally support runtime `FromObjectField` and late symbolic demand under their distinct identity and preparation policies. Resolver01/02/06/07/21/22 use complete resolver outputs, while Resolver03/08/23/25/26 use selective projection.

Resolver01/02/06/07/21/22 require `Assumptions.selectiveResolvers == false`; Resolver03/08/23/25/26 require it to be true. Use that world flag for resolver invocation and passive output traversal rather than carrying another selectivity flag in completion state.

Raw node inputs and bridge schema augmentation are fixture concerns. Semantic logic sees `foo$bridge` producers and `T$Bridge.$node { $id }` loaders as ordinary field resolvers.

## Tests

Read [`testing-contracts.md`](testing-contracts.md) before changing or interpreting resolver tests. Start generated-failure investigation with its coordinate replay workflow rather than rerunning a whole class when exact `S:R:Q` coordinates are available.
