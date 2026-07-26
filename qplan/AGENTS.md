# Query Planning Model Guidance

In Markdown files, keep each prose paragraph and each individual list item on a single physical line.

## Purpose

This repository uses compiling Kotlin as a precise modeling language for reasoning about Viaduct query execution and resolver demand. Read [Query Plan Research](./evergreen.md) for the durable problem statement, established findings, correctness constraints, and open questions that motivate the code.

## Projects

The [`model` project](./model/AGENTS.md) defines the carrier algebra and its invariants. The [`semantics` project](./semantics/AGENTS.md) defines transformations, predicates, and other reasoning over that algebra; consult each project's local guidance before changing it.

## Shared Modeling Discipline

The Kotlin code must compile, but it is not intended to become running production code. Read declarations as mathematical sets, values, functions, and relations unless their documentation gives them operational semantics.

`Assumptions` contains the schema, variable bindings, and executors stipulated for one reasoning world. Each reasoning exercise has exactly one `Assumptions` and one `Schema`; dependency-injection composition scopes their public bindings as singletons. These are mathematical globals rather than JVM-global values: code receives them explicitly, constructs every `Schema.Value` through that schema's factories, and does not combine values or definitions from different reasoning exercises.

Treat one dependency injector as one reasoning world. Qualify raw world inputs with `@SchemaSDL`, `@VariableValues`, `@NodeResolvers`, or `@FieldResolvers`; construct variable values and resolver functions from that injector's one `GJSchema`; and scope the public `GJSchema`, `ExecutorRegistry`, and `Assumptions` bindings as singletons. DI-framework modules belong in test or application composition code, never a main source set in this repository.

Tests that need a reasoning world must construct it through `model.testing.TestWorld`; do not call `GJSchema.fromSDL`, `Assumptions.of`, or `ExecutorRegistry.of` directly from an ordinary test source set.

Every element of a reasoning world's schema has exactly one canonical definition object. Schema definition classes retain the default reference-based `Any.equals` and `Any.hashCode`, so `a == b` exactly when `a` and `b` represent the same schema element. Compare schema definitions with `==`, `!=`, and ordinary collection operations such as `contains`; do not use identity-specific operators, hashes, or collection scans.

Compilation and tests provide finite evidence that the model is internally consistent and behaves as illustrated. They do not prove its mathematical assumptions or semantic claims.

## Claims And Arguments

Record important propositions in [`claims.md`](./claims.md). Give each claim a stable, unique, kebab-case label and a one-sentence statement in this form:

```markdown
**[claim-label]** One-sentence statement of the claim.
```

An optional paragraph of two to five sentences may follow when the claim needs clarification, but keep supporting reasoning out of the registry.

When a claim has a supporting proof, derivation, or body of evidence, put it in `arguments/<claim-label>.md`. State the argument's scope and assumptions there, distinguish finite test evidence from proof, and identify any observations or cases the argument intentionally excludes. An argument file is optional: the absence of one means the claim is currently being assumed or recorded without support, not that support should be inferred.

Update a claim and its argument together whenever code or later reasoning strengthens, weakens, or invalidates either one.

## Context Parameters for World Assumptions

Use a Kotlin context parameter named `world` for functions interpreted under one reasoning world's `Assumptions`:

```kotlin
context(world: Assumptions)
fun ...
```

Context parameters compose implicitly: a function with an `Assumptions` context may directly call another function requiring the same context. They are not implicit receivers, so access a world member as `world.schema`, `world.variableValues`, `world.executorRegistry`, or `world.selectionsFrom(...)`.

Prefer explicit `world` qualification when only a few members are used. For a body that benefits from making `world` a receiver, use `world.run { ... }` and declare the function's return type explicitly. Do not use `world.apply { ... }` to produce a modeled result: `apply` returns `world`, not the lambda's result.

See [Context Parameters and the `Assumptions` World](./context-params.md) for rationale, examples, and testing guidance.
