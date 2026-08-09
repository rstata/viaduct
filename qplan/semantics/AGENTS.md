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

Resolver01-03 share the recursive monotonic constructor in `Resolve.kt`: local demand closure, dependency order, materialization, write-once publication, passive traversal, and recursive continuation. Resolver06-08 reuse those slot operations through the single-threaded `DepthFirstReactor`, whose stable depth-kind-sequence priority queue reproduces depth-first continuation without recursive scheduling. `ResolveValue.kt` allocates child OERs, retains exact active targets, and populates them deepest first without replacing parent cells.

Resolver01 and its queue-backed counterpart Resolver06 support empty user object fragments plus generated bridge loaders. Resolver02 and its queue-backed counterpart Resolver07 support nonempty fragments and `FromArgument`; Resolver03 and its queue-backed counterpart Resolver08 support that domain with selective projection, while runtime `FromObjectField` is deferred. Resolver01/02/06/07 use complete resolver outputs, while Resolver03/08 use full `successorDemand()` and selective projection.

Raw node inputs and bridge schema augmentation are fixture concerns. Semantic logic sees `foo$bridge` producers and `T$Bridge.$node { $id }` loaders as ordinary field resolvers.

## Tests

Read [`testing-contracts.md`](testing-contracts.md) before changing or interpreting resolver tests. Start generated-failure investigation with its coordinate replay workflow rather than rerunning a whole class when exact `S:R:Q` coordinates are available.
