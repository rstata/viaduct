# Semantics Domain Guidance

## Purpose

This project defines transformations, predicates, and other reasoning over the algebra in the
`model` project. It may construct model values, but it must not redefine the model's invariants.

Follow the repository-wide purpose and modeling discipline in [`../AGENTS.md`](../AGENTS.md).

## Assumption Context

Public semantic operations should be classes that receive one `GlobalAssumptions`. Obtain the
operation's schema and variable bindings through that value. DI constructs objects but does not
define semantic composition across assumption snapshots.

Private pure helpers may be top-level when they require no assumptions. Public operations should
make their modeled world explicit through the injected assumptions.

## Dependencies

Main code depends only on `model` and the standard `jakarta.inject` annotations. Injection frameworks
belong in the test source set, where they assemble concrete assumptions and semantic operations.
