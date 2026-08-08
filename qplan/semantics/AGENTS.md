# Semantics Domain Guidance

## Purpose

This project defines transformations, predicates, and other reasoning over the carrier algebra in `model`. Follow the repository-wide mathematical interpretation in [`../AGENTS.md`](../AGENTS.md).

Semantic code may construct model values but must not redefine or defensively re-check model invariants. It may express relations between a carrier value and a judgment input, such as requiring an OER and ground selection forest to share the Query root.

The principal judgment is:

```kotlin
context(world: Assumptions)
fun EngineResult.Object.correctResolution(selections: GroundSelectionForest): Boolean
```

This predicate characterizes whether an OER is a correct field-resolution result for a
Query-rooted ground selection forest under one reasoning world.

## Dependencies

Main code depends only on `model`. Schema parsing, dependency injection, registry assembly, and other pre-reasoning composition belong in test fixtures or application composition code.

Semantic code may receive open selection forests but must cross `mergeToGround(type)` before OER lookup, materialization, dependency ordering, or resolver application. `merge(type)` only filters and specializes occurrences; it preserves open arguments and multiplicity. `mergeToGround(type)` instantiates bindings, applies concrete defaults, throws if any expression remains open, and coalesces ordinary-equal `GroundKey` values.

Resolver01-03 stamp field-relative templates at exact OER paths, bind `fromArgument` variables from each resolver occurrence's ground arguments, and ground demand only after those bindings are available. Canonical registry construction validates argument-variable ownership and every object-field provider path; runtime `fromObjectField` binding remains deferred. Operation-variable substitution remains pre-reasoning composition and is distinct from field-relative execution-variable metadata.

Resolver01-03 share one depth-first constructor in `Resolve.kt`: local demand closure, sibling dependency ordering, resolver-input materialization, one-cell construction, passive output traversal, and recursive continuation are identical. Each public resolver entry point supplies a `SelectionCompleter` context that defines its output-boundary policy. Resolver01 preserves incoming selections and applies complete outputs; Resolver02 applies complete outputs and uses `successorBoundaryDemand()` only to expose nested behavioral continuation paths; Resolver03 uses full `successorDemand()` and selective projection so passive successor prerequisites are retained.

Raw node resolvers, node references, typed-ID encoding, and `$id`/`$ids` schema augmentation are fixture-composition concerns; generated node loaders enter semantic reasoning as ordinary field resolvers with explicit bridge fragments.
