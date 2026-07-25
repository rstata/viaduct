# Query Planning Model Guidance

In Markdown files, keep each prose paragraph and each individual list item on a single physical line.

## Purpose

This repository uses compiling Kotlin as a precise modeling language for reasoning about Viaduct query execution and resolver demand. Read [Query Plan Research](./evergreen.md) for the durable problem statement, established findings, correctness constraints, and open questions that motivate the code.

## Projects

The [`model` project](./model/AGENTS.md) defines the carrier algebra and its invariants. The [`semantics` project](./semantics/AGENTS.md) defines transformations, predicates, and other reasoning over that algebra; consult each project's local guidance before changing it.

## Shared Modeling Discipline

The Kotlin code must compile, but it is not intended to become running production code. Read declarations as mathematical sets, values, functions, and relations unless their documentation gives them operational semantics.

`GlobalAssumptions` contains the schema and variable bindings stipulated for one reasoning world. These are mathematical globals, not JVM-global values. Code interpreting model values does so under an explicit assumptions value; do not assume singleton scope, because an operation may derive a new immutable assumption snapshot.

Compilation and tests provide finite evidence that the model is internally consistent and behaves as illustrated. They do not prove its mathematical assumptions or semantic claims.

## Claims And Arguments

Record important propositions in [`claims.md`](./claims.md). Give each claim a stable, unique, kebab-case label and a one-sentence statement in this form:

```markdown
**[claim-label]** One-sentence statement of the claim.
```

An optional paragraph of two to five sentences may follow when the claim needs clarification, but keep supporting reasoning out of the registry.

When a claim has a supporting proof, derivation, or body of evidence, put it in `arguments/<claim-label>.md`. State the argument's scope and assumptions there, distinguish finite test evidence from proof, and identify any observations or cases the argument intentionally excludes. An argument file is optional: the absence of one means the claim is currently being assumed or recorded without support, not that support should be inferred.

Update a claim and its argument together whenever code or later reasoning strengthens, weakens, or invalidates either one.
