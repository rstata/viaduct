# Semantics Domain Guidance

## Purpose

This project defines transformations, predicates, and other reasoning over the carrier algebra in `model`. Follow the repository-wide mathematical interpretation in [`../AGENTS.md`](../AGENTS.md).

Semantic code may construct model values but must not redefine or defensively re-check model invariants. It may express relations between a carrier value and a judgment input, such as requiring an OER and object selection forest to share the Query root.

The principal judgment is:

```kotlin
context(world: Assumptions)
fun EngineResult.Object.correctResolution(selections: ObjectSelectionForest): Boolean
```

This predicate characterizes whether an OER is a correct field-resolution result for a
Query-rooted object selection forest under one reasoning world.

## Dependencies

Main code depends only on `model`. Schema parsing, dependency injection, registry assembly, and other pre-reasoning composition belong in test fixtures or application composition code.

Semantic code reasons over canonical field resolvers in variable-free worlds. Canonical registry entries may retain field-relative variable-provider metadata for pre-reasoning validation, but Resolver01-03 neither interpret those providers nor accept unresolved variables in executable fragments. Raw node resolvers, node references, typed-ID encoding, and `$id`/`$ids` schema augmentation are fixture-composition concerns; generated node loaders enter semantic reasoning as ordinary field resolvers with explicit bridge fragments.

Canonical registry construction validates that each provider path is contained by its defining resolver's fixed-shape object-fragment envelope and rejects worlds whose argument-insensitive branch order has a provider-production/use cycle. This is a forward pre-reasoning invariant, not an implemented variable-aware semantic constructor. Operation-variable substitution remains pre-reasoning composition and is distinct from field-relative execution-variable metadata.
