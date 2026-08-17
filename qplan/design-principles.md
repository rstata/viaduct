# Qplan Design Principles

## Purpose

This document records durable principles for the qplan model and its resolver algorithms. The evidence, known hard cases, and source provenance behind them live in [`research-evidence.md`](./research-evidence.md). Current implementation priorities belong in [`handoff.md`](./handoff.md), practical workflows belong in [`maintainer-guide.md`](./maintainer-guide.md), and completed chronology belongs in Git history.

## A Compiling Mathematical Model

Compiling Kotlin is qplan's primary specification language. Model declarations denote sets, values, functions, relations, and partial operations; they do not imply JVM timing, allocation, caching, or effects unless the model explicitly represents those concepts.

Each reasoning exercise fixes one canonical `Assumptions`, `Schema`, and resolver registry. Schema decoding, GraphQL parsing, registry assembly, node lowering, and provider-path compilation are pre-reasoning composition. Semantic code trusts the carrier invariants established at that boundary.

Compilation, examples, generated tests, stress campaigns, and cross-resolver agreement are finite consistency evidence. They are not mathematical proof. The TLA+ baseline proves only its explicitly stated finite calculus and assumptions.

## Keep Semantic Domains Distinct

Selections may contain open `Value.Key` values. A `Value.ObjectKey` has a concrete object field but may still contain variables. Only a fully instantiated `Value.GroundKey` belongs in exact OER cells, `Value.Object`, result paths, materialization, dependency ordering, and resolver application.

Resolver input demand, client demand, output projection demand, symbolic or potential demand, and supplied demand serve different purposes. Resolver-owned output, internal selection forests, tenant-visible GraphQL fragments, and completed result coverage are likewise related but not interchangeable representations.

Cross each boundary through an explicit checked operation. Do not make exact operations tolerate open values or reuse one demand representation merely because two values happen to coincide in one example.

## Result Occurrence Is Identity

The semantic identity of work is an occurrence in the result tree. Equal node IDs, schema coordinates, arguments, or values do not merge separate object or list occurrences. List indices and concrete containing paths remain part of occurrence identity. Caching, batching, and request deduplication are separate execution layers.

Cells are allocated by their containing OER or LER. Cell reference identity is the cell occurrence identity; a parallel numeric cell identifier would duplicate and risk disagreeing with the carrier.

Resolver26 additionally preserves the identity of variable-bearing source selections through opaque `SelectionOccurrenceId` lineage. That identity is not explanatory provenance and should not be reconstructed from incidental selection content.

## One-Shot Correctness Is Producer-Specific

For every resolver-bearing occurrence in scope, all producer-owned values later consumed from that occurrence must be covered by the demand supplied to its one selective resolver application.

A correct final union is weaker. A late cache hit, widened result, or second materialization can make the completed OER look adequate even when the producing application discarded required output. Waiting for all contributors currently known to exist is also weaker than proving that no future contributor can target the producer.

One-shot designs must therefore bound all contributors before application, conservatively include bounded alternatives, assign late demand a distinct occurrence, or reject the shape. Post-application widening cannot repair an under-supplied selective producer.

## Attribute Demand To Its Owner

Demand must be projected through the producer that owns the requested output. Traversal through passive fields remains within the current producer; traversal stops at resolver-bearing boundaries and attributes successor work to the successor resolver.

Resolver object fragments determine input requirements. Resolver arguments identify an eventual resolver instance but do not choose the resolver template or its fixed object fragment. This distinction lets symbolic closure discover fixed input requirements before variable values are available.

## Progress Is Monotonic And Strict

Mutable semantic state is limited to documented monotonic stores. An OER or LER cell value, a cell's `accessAccepted` result, and a request-local variable binding move from absent to one immediate or deferred promise; a deferred promise completes once.

A parent may publish a stable child OER before the child is complete. Later work fills absent child cells without replacing the parent or rebuilding the subtree.

Duplicate claims, duplicate writers, repeated lifecycle transitions, undeclared binding reads, and unclaimed cells at freeze time should fail visibly. Idempotence must not hide duplicate scheduling or ownership errors.

## Runtime Variables Add Value-Flow Dependencies

`FromArgument` values are available from the defining resolver occurrence. `FromObjectField` values require reading a resolved provider path and can reveal an exact consumer key only later.

Provider paths are compiled and validated before semantic reasoning, but provider evaluation occurs at runtime in Resolver25 and Resolver26. Provider containment and branch ordering are domain restrictions; they are not themselves an execution algorithm.

Substitution precedes exact-key grouping. Resolver25 merges selections that become the same ground key before launch. Resolver26 instead gives variable-bearing source selections distinct occurrence identity even when they ground to equal visible arguments.

## Structured Concurrency Owns Request Lifetime

One request-root scope owns all request coroutines. Successful synchronous return means request quiescence, and failure cancels sibling work through structured ownership.

Cross-task readiness travels through named promises or value-bearing deferreds, not through another task's call stack or `Job` completion. Independent object and list occurrences should not require a global barrier.

## Use Earlier Resolvers To Remove Accidental Complexity

Resolver03 is the compact semantic reference for demand closure, selective projection, exact-key publication, and completed-result correctness.

Resolver08 holds that semantic capability roughly constant while replacing recursive continuation with explicit depth-first work. Comparing Resolver26 with Resolver03 and Resolver08 helps distinguish essential demand semantics from scheduling machinery.

Resolver23 adds structured suspension and promise installation without path-variable identity. It is the clean coroutine comparison.

Resolver10 is useful as a negative lesson rather than a maintained implementation. Readiness rescanning, persistent late-demand acceptance, and complete-output retention added substantial machinery and could mask an incomplete producing application. Do not recreate that architecture to solve a local Resolver26 problem.

Resolver25 remains a useful alternate experiment because it merges late-equal grounded keys. Its current activation protocol is not a general planning solution and should not be imported into Resolver26 by default.

## Validate Independent Properties Independently

`correctResolution` judges a completed Query OER extensionally. It does not establish resolver application count, supplied demand, binding correctness, execution order, lifecycle ownership, or concurrency.

Keep separate evidence for completed-result correctness, exact and occurrence-aware application identities, object-path bindings, lifecycle invariants, mutation tests, structural activation, and scheduling behavior. An expected-application oracle derived from the completed result under test is not fully independent and must be described accordingly.

Generated presence is weaker than runtime activation. Directed profiles should require the target interaction to execute, and broad campaigns should record enough information to replay one exact `S:R:Q` coordinate. Large green campaigns remain finite evidence and do not override a focused counterexample.

## Engine API Alignment

The qplan model currently owns carriers such as `EngineResult`, typed keys, schema validation, and occurrence-aware cells. Viaduct's `EngineObjectData.Sync` is the intended synchronous partial-object boundary for the current alignment work and distinguishes an absent field from a present null value.

The migration should make qplan use Viaduct engine API carriers where they express the same semantic fact while preserving qplan-only structure needed for formal reasoning. Do not erase occurrence identity, ground-key validation, or model invariants merely to reduce source-level differences.

The purpose of alignment is to keep the future implementation distance small. It does not make production runtime concerns part of every qplan function, and it does not expand the immediate task into `execution2` design.
