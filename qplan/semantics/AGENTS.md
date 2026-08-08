# Semantics Domain Guidance

## Purpose

This project defines transformations, predicates, and other reasoning over the carrier algebra in `model`. Follow the repository-wide mathematical interpretation in [`../AGENTS.md`](../AGENTS.md).

Semantic code may construct model values but must not redefine or defensively re-check model invariants. It may express relations between a carrier value and a judgment input, such as requiring an OER and ground selection forest to share the Query root.

The principal judgment is:

```kotlin
context(world: Assumptions)
fun EngineResult.Object.correctResolution(selections: ObjectSelectionForest): Boolean
```

This predicate characterizes whether an OER is a correct field-resolution result for a
Query-rooted ground selection forest under one reasoning world.

## Dependencies

Main code depends only on `model`. Schema parsing, dependency injection, registry assembly, and other pre-reasoning composition belong in test fixtures or application composition code.

Semantic code may receive open selection forests. `merge(type)` filters applicability, specializes fields and argument defaults to one concrete object type, and coalesces ordinary-equal open `ObjectKey` values into an `ObjectSelectionForest`. `ObjectSelectionForest.instantiateBindings()` later substitutes bindings and coalesces keys that converge after grounding. OER lookup, materialization, dependency ordering, resolver application, and path formation must cross the checked `groundKeys()`, `byGroundKey()`, or `ObjectSelection.groundKey()` boundary.

Resolver01-03 stamp field-relative templates at exact OER paths, bind `fromArgument` variables from each resolver occurrence's ground arguments, and ground demand only after those bindings are available. Canonical registry construction validates argument-variable ownership and every object-field provider path; runtime `fromObjectField` binding remains deferred. Operation-variable substitution remains pre-reasoning composition and is distinct from field-relative execution-variable metadata.

Resolver01-03 share one recursive depth-first constructor in `Resolve.kt`: local demand closure, sibling dependency ordering, resolver-input materialization, write-once cell publication, passive output traversal, and recursive continuation are identical. Each resolver starts with an empty mutable root OER. `ResolveValue.kt` constructs passive trees containing mutable OERs, retains each exact object occurrence requiring behavioral resolution together with its path and collapsed selections, and populates those target OERs deepest first without replacing parent cells or immutable list positions. Each public resolver entry point supplies a `SelectionCompleter` context that defines its output-boundary policy. Resolver01 preserves incoming selections and applies complete outputs; Resolver02 applies complete outputs and uses `successorBoundaryDemand()` only to expose nested behavioral continuation paths; Resolver03 uses full `successorDemand()` and selective projection so passive successor prerequisites are retained.

Raw node resolvers, node references, typed-ID encoding, and bridge-object schema augmentation are fixture-composition concerns; generated `T$Bridge.$node` loaders enter semantic reasoning as ordinary argumentless field resolvers with fixed `{ $id }` fragments.
