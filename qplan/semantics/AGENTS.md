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

Semantic code reasons over canonical field resolvers and field-relative variable providers. Raw node resolvers, node references, typed-ID encoding, and `$id` schema augmentation are fixture-composition concerns; generated node loaders enter semantic reasoning as ordinary field resolvers with explicit bridge fragments.

Resolver04 additionally interprets field-relative variable providers. Each provider path is already contained by the defining resolver's fixed-shape object-fragment envelope, and canonical registry construction now rejects any world whose unified argument-insensitive branch order has a provider-production/use cycle. A registered variable is resolved from that path in the defining resolver's containing OER, stored in that OER's `variableValues`, and substituted throughout the registry-computed input closure before exact-key grouping, materialization, or resolver comparison. Resolver04's widening machinery remains in the model during the transition to a depth-first variable-aware construction, but former widening-only worlds are rejected before semantic reasoning. Operation-variable substitution remains pre-reasoning composition and is distinct from these execution-variable bindings.
