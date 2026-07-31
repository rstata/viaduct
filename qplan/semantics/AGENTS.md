# Semantics Domain Guidance

## Purpose

This project defines transformations, predicates, and other reasoning over the carrier algebra in `model`. Follow the repository-wide mathematical interpretation in [`../AGENTS.md`](../AGENTS.md).

Semantic code may construct model values but must not redefine or defensively re-check model invariants. It may express relations between a carrier value and a judgment input, such as requiring an OER and fragment to share the Query root.

The principal judgment is:

```kotlin
context(world: Assumptions)
fun EngineResult.Object.correctResolution(fragment: Fragment): Boolean
```

This predicate characterizes whether an OER is a correct field-resolution result for a fragment under one reasoning world.

## Dependencies

Main code depends only on `model`. Schema parsing, dependency injection, registry assembly, and other pre-reasoning composition belong in test fixtures or application composition code.

Semantic code reasons over the canonical field-only executor registry. Raw node resolvers, node references, typed-ID encoding, and `$id` schema augmentation are fixture-composition concerns; generated node loaders enter semantic reasoning as ordinary field resolvers with explicit bridge fragments.
