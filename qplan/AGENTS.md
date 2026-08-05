# Query Planning Model Guidance

## Purpose

This repository uses compiling Kotlin as a precise mathematical modeling language for reasoning about Viaduct field resolution and resolver demand, in the specification role that TLA+ could otherwise play. Read [Query Plan Research](./evergreen.md) for the durable problem statement, established findings, correctness constraints, and open questions.

Keep the Kotlin model suitable as a blueprint for a possible future translation into TLA+ in which its stated theorems could become machine-checked proof obligations. Make mathematical domains, functions, relations, invariants, assumptions, and theorem boundaries explicit, but do not imply that a TLA+ translation or mechanically checked proof already exists.

## This Is Math, Not Programming

Semantic Kotlin declarations denote mathematical sets, values, functions, and relations. `resolver.tenantResolve(input, arguments, selections)` denotes application of a stipulated resolver function followed by projection to selections; it is not an event and implies no execution, invocation, effects, timing, ordering, allocation, caching, or scheduling. Use “function application,” “yields,” and “is related to” when discussing semantics.

Function-valued model properties denote deterministic mathematical maps on their documented domains. Determinism means that equal inputs yield equal outputs; it does not define Kotlin equality for the function values themselves.

A semantic operation may be partial. When such an operation throws, its input is outside its mathematical domain and the exception is not a modeled output. Modeled values such as `Value.Error` remain ordinary members of a codomain and are distinct from thrown exceptions.

Maps, sets, lists, and occurrence families denote finite mathematical collections. Do not infer mutation, implementation type, iteration order, allocation, or complexity from their Kotlin representation. Positional order is semantic only where a declaration explicitly says so.

Kotlin identity, equality, inheritance, generic variance, and exhaustiveness are representation tools; they acquire mathematical meaning only through the model's documented contracts. In particular, do not infer GraphQL subtyping, coercion, interface implementation, or semantic equality merely from Kotlin types or host-language behavior.

Each reasoning exercise fixes one `Assumptions` and one canonical `Schema`. These are mathematical globals rather than JVM globals: semantic functions receive them explicitly and never combine definitions or values from different reasoning worlds.

Schema decoding, GraphQL parsing, registry assembly, dependency injection, and implementations of externally supplied interfaces prepare a world before reasoning begins. This pre-reasoning infrastructure may use implementation techniques that semantic model logic deliberately excludes.

External composition may accept ordinary GraphQL text, source field resolvers, and raw node lookup functions, but the canonical reasoning world exposes only field resolver coordinates. Test-fixture composition lowers node-valued source fields to synthetic `$id` bridge fields and generated field resolvers before semantic logic receives the schema and registry.

Trust carrier invariants established by model factories or stipulated external inputs. Downstream reasoning should not defensively re-check those invariants; retain checks only when they establish a construction boundary, validate raw external input, or express a precondition not already guaranteed by the carrier.

Compilation and tests provide finite evidence that the model is internally consistent and behaves as illustrated. They do not prove mathematical assumptions, semantic claims, or completeness.

## Shared Implementation Discipline

Type aliases introduce no new semantics. For example, a resolver-function alias only abbreviates a Kotlin function type. Put invariants on the properties, parameters, results, or declarations that use an alias rather than expecting the alias to carry them.

Semantic values are assumed immutable as they pass through the model. Do not add defensive collection snapshots solely to guard against mutation.

Semantic logic uses immutable collection types and purely functional transformations such as `map`, `filter`, `fold`, and `+`. Do not use mutable state, mutable collection types, local mutable builders, or mutation hidden inside builder APIs in reasoning code. Pre-reasoning parsing, schema decoding, registry assembly, and composition infrastructure are outside this restriction.

Every context function in semantic logic uses `context(world: Assumptions)`, even when it currently needs only one part of the world such as `world.schema`. Do not introduce narrower `Schema` contexts or make `Assumptions` a subtype of `Schema`.

Context parameters compose implicitly but are not implicit receivers. Access members as `world.schema` and `world.executorRegistry`. Prefer explicit `world` qualification when only a few members are used; when a body benefits from a receiver, use `world.run { ... }`, declare the return type explicitly, and do not use `world.apply { ... }` to produce a modeled result.

See [Context Parameters and the `Assumptions` World](./context-params.md) for rationale, examples, and testing guidance.

## Projects

The [`arbitrary` project](./arbitrary/AGENTS.md) is pre-reasoning property-test infrastructure. It generates valid external schemas, resolver registries, and queries, then materializes model values through the canonical world factories.

The [`model` project](./model/AGENTS.md) defines the carrier algebra and its invariants over GraphQL schemas, selections, values, resolver inputs, and field-resolution results.

The [`semantics` project](./semantics/AGENTS.md) defines transformations, predicates, and other reasoning over that algebra.

Consult each project's local guidance before changing it.

## Claims And Arguments

Record important propositions in [`claims.md`](./claims.md). Give each claim a stable, unique, kebab-case label and a one-sentence statement in this form:

```markdown
**[claim-label]** One-sentence statement of the claim.
```

An optional paragraph of two to five sentences may follow when the claim needs clarification, but keep supporting reasoning out of the registry.

When a claim has a supporting proof, derivation, or body of evidence, put it in `arguments/<claim-label>.md`. State the argument's scope and assumptions there, distinguish finite test evidence from proof, and identify any observations or cases the argument intentionally excludes. An argument file is optional: absence means that support has not been recorded, not that it should be inferred.

Update a claim and its argument together whenever code or later reasoning strengthens, weakens, or invalidates either one.

## Documentation

In Markdown files, keep each prose paragraph and each individual list item on one physical line.

Document carrier invariants on the factory functions that establish them, not on implementation constructors. Document externally stipulated world invariants at the closest applicable type or property. Use a KDoc heading `### Invariant: kebab-case-label`; labels are globally unique across invariants and claims, and `checkDocumentationLabels` enforces the shared namespace.

State related cross-property constraints conjunctively in one invariant block. Do not restate invariants already established by referenced types, and keep preconditions, operation semantics, and derived claims outside invariant blocks.

Keep package-wide assumptions and scope boundaries in `AGENTS.md` files. Keep KDoc focused on invariants and semantics specific to the declaration it documents.

## Validation

Run `./gradlew check` from this directory for complete repository validation.
