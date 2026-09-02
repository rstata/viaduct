# Qplan Current Handoff

## Working State

Qplan is a compiling Kotlin model of Viaduct query field resolution. Resolver26 is the primary algorithm and eventual implementation blueprint; the maintained earlier resolvers form the semantic and execution comparison grid documented in [`resolver-versions.md`](./resolver-versions.md).

Every reasoning world uses one canonical lowered `viaduct.graphql.schema.ViaductSchema`. Fields, arguments, enum values, object types, type conditions, possible-object-type sets, type expressions, resolver keys, selections, and EOD schema types come from that schema instance. Model, fixtures, arbitrary generation, semantics, and execution use `ViaductSchema` and its flat `TypeExpr` representation directly.

The model distinguishes `EngineInputData`, `EngineOutputData`, and `EngineResult`. OER cells and paths use canonical `ObjectEngineResult.ObjectKey` values, which may retain occurrence-specific variables in their arguments. `GroundKey` remains the refinement for operations that require resolved argument values. A `ResolverOccurrenceId` combines its Query-rooted OER identity with its exact path; the primary result roots primary occurrences and every independently executed query fragment roots its subordinate occurrences in its own fresh Query OER. Variable instances retain their defining `ResolverOccurrenceId`; keys carry no separate occurrence identity.

## Source And Lowered Schema Boundary

Fixture composition retains two distinct schemas:

- The source `GraphQLSchema` owns external GraphQL parsing, validation, source spread rules, source output types, coercion where required, and response completion.
- The canonical lowered `ViaductSchema` owns qplan fields, types, selections, resolver registry entries, conformance checks, values, and subtype reasoning. Source-backed definitions retain exact source GraphQL-Java EOD witnesses; synthetic bridge definitions retain generated internal witnesses.

`GJSchema.fromSDL` parses and validates the source schema and lowers it to the final canonical `ViaductSchema`. `SourceSchemaAdapter` is the explicit source-to-lowered boundary and requires the canonical source/lowered fixture pair. Node-valued source fields, node lookups, and typename are compiled into ordinary synthetic field resolvers before semantic reasoning.

## Current Semantic Capabilities

All maintained resolvers support source-sensitive ownership of argumentless registered fields: an ancestor output may supply such a field passively, while an absent field uses its standard resolver. Fields with arguments are always active. The exact contract and resolver support matrix live in [`semantics/testing-contracts.md`](./semantics/testing-contracts.md).

Resolver02/03, Resolver07/08, Resolver22/23, and Resolver26 support independently resolved Query-rooted resolver fragments. Each application receives a fresh Query OER materialized with response keys preserved, and correctness validation retains that OER as an occurrence-specific witness. The general distinction between variable producers and fragment consumers is defined in [`semantics/README.md`](./semantics/README.md#variable-production-and-consumption): `FromObjectField` and `FromQueryField` choose the provider fragment, while either the object fragment or Query fragment may consume either binding. Resolver26 implements both provider kinds and all three consumption shapes; Resolver25 implements only `FromObjectField` and does not execute Query fragments.

Every maintained resolver supports canonical `FromArgument` paths through nested input objects, with null propagation through nullable intermediate objects and no list traversal. Resolver25 and Resolver26 additionally evaluate compiled `FromObjectField` provider paths at runtime, and Resolver26 evaluates compiled `FromQueryField` provider paths in each occurrence's fresh Query OER. Resolver25 merges demand by grounded key; Resolver26 preserves symbolic OER keys and treats grounding as readiness and invocation data rather than a rekeying operation.

Ordinary generated tests include a replayable `sometimes-passive` profile across the full maintained resolver grid. Resolver25 and Resolver26 broad campaign profiles also enable sometimes-passive generation and require independent evidence that registered result occurrences were supplied by their source owner without invoking the standard field resolver. Resolver26 application accounting reconstructs registered occurrences across the primary result and every request-local Query-fragment OER, qualifies observed applications by Query root and exact path, and counts sometimes-passive occurrences as the difference from that complete rooted ledger. Resolver25 lifecycle observations and Resolver26 application observations both expose exact applied occurrence sets to from-field binding validation, which requires all and only the `FromObjectField` and `FromQueryField` bindings of applied occurrences and traverses every request-local Query root.

## Execution Harness Boundary

The execution module runs validated GraphQL queries through Resolver26 and uses GraphQL Java for response completion. `EngineTestModule.runQPlanFeatureTest` adapts pre-dispatcher Engine API field and node executors into ordinary qplan fixture inputs; it does not construct production dispatchers or data loaders.

The adapter supports unbatched, non-selective field and node executors; field arguments; object required selections; top-level `FromArgument` variables; supported singular `FromObjectField` paths; synchronous scalar, enum, list, object, error, and node-reference outputs; built-in node lookup; and typename completion. Missing nullable Query executors resolve to null, while missing non-null Query executors resolve to an error.

The adapter rejects batching, selectivity, query required selections, multi-segment production `FromArgument` recipes, from-Query-field and arbitrary callback providers, checkers, asynchronous EOD outputs, mutations, subscriptions, and custom scalars. Qplan's semantic model already represents nested input-object argument paths; the remaining one-segment restriction belongs only to production RSS recovery. Direct inline materialization of a Node-valued field remains outside the modeled execution contract.

Production-derived execution tests live under `execution/viaductfeaturetests`. Whole-file, source-faithful preservation is the migration policy; unsupported tests remain disabled with coded reasons rather than being rewritten. [`execution/viaduct-feature-test-inventory.md`](./execution/viaduct-feature-test-inventory.md) records the current whole-file exclusions and the two ports that have drifted from their source files.

## Validation

Run ordinary qplan validation from this directory:

```shell
./gradlew check
```

Run module-specific gates while investigating a narrower boundary:

```shell
./gradlew :model:test
./gradlew :arbitrary:test
./gradlew :semantics:test
./gradlew :execution:test
```

Broad property campaigns, multithreaded stress, benchmarks, profiles, and TLA+ checks are intentionally separate. Their canonical commands and evidence standards live in the corresponding semantics and TLA documentation.

## Scope

The executor feature-test adapter is a pre-dispatcher integration surface, not a production `execution2` implementation. Future work must preserve resolver scheduling, response-key materialization, OER identity, variable occurrence identity, query-value occurrence identity, and the explicit source/lowered schema boundary. Dispatcher and data-loader integration, production `execution2` integration, custom scalars, mutations, and subscriptions remain separate work.
