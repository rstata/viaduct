# Semantics Domain Guidance

## Purpose

This project defines transformations, predicates, and other reasoning over the algebra in the `model` project. It may construct model values, but it must not redefine the model's invariants.

Follow the repository-wide purpose and modeling discipline in [`../AGENTS.md`](../AGENTS.md).

## Assumption Context

Public semantic operations interpreted under one reasoning world are functions with a context parameter named `world`:

```kotlin
context(world: Assumptions)
fun ...
```

Obtain the operation's schema, variable bindings, and executors through that value. Context-dependent helpers compose from the caller's context; private pure helpers may omit it. Do not combine assumptions, schemas, definitions, or values from different reasoning exercises.

Rely on the canonical schema equality defined by the repository and model guidance: compare schema definitions with `==`, `!=`, and ordinary collection operations, never with identity-specific operators or scans.

## Dependencies

Main code depends only on `model`. Dependency-injection frameworks belong in test or application composition code, where they assemble the reasoning world's singleton assumptions.
