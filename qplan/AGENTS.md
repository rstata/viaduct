# Query Planning Model Guidance

## Purpose

This repository uses compiling Kotlin as a precise modeling language for reasoning about Viaduct
query execution and resolver demand. Read [Query Plan Research](./evergreen.md) for the durable
problem statement, established findings, correctness constraints, and open questions that motivate
the code.

## Projects

The [`model` project](./model/AGENTS.md) defines the carrier algebra and its invariants. The
[`semantics` project](./semantics/AGENTS.md) defines transformations, predicates, and other
reasoning over that algebra; consult each project's local guidance before changing it.

## Shared Modeling Discipline

The Kotlin code must compile, but it is not intended to become running production code. Read
declarations as mathematical sets, values, functions, and relations unless their documentation
gives them operational semantics.

`GlobalAssumptions` contains the schema and variable bindings stipulated for one reasoning world.
These are mathematical globals, not JVM-global values. Code interpreting model values does so
under an explicit assumptions value; do not assume singleton scope, because an operation may derive
a new immutable assumption snapshot.

Compilation and tests provide finite evidence that the model is internally consistent and behaves
as illustrated. They do not prove its mathematical assumptions or semantic claims.
