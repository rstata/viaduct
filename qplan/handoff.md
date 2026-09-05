# Qplan Current Handoff

## Working State

Qplan is a compiling Kotlin model of Viaduct query field resolution. Resolver26 is the primary algorithm and eventual implementation blueprint; the maintained earlier resolvers form the semantic and execution comparison grid documented in [`resolver-versions.md`](./resolver-versions.md).

Mutable interpretation state no longer lives in `model.Assumptions`. Shared semantics uses an `OperationContext` containing the immutable `world: Assumptions`, `VariableBindingsState`, and semantically passive resolver observer. Resolver26 extends that boundary with its request scope and explicit `cycleChecker: CycleCheckState`, `bindingDeclarationsState: BindingDeclarationsState`, and `queryValuesState: QueryValuesState` properties; the operation context does not implement those state protocols. `OEROccurrenceContext` carries the stable root, exact path, target, and optional structural parent bundle through Resolver26 object orchestration and field-resolution tasks.

`FieldResolver` now owns a selection-sensitive function relation. `FieldResolver.of` adapts existing non-selective functions by projecting their stable output in selective worlds, while `FieldResolver.ofSelective` passes runtime `SelectionForest` demand directly to a selective function without post-projection. Test-fixture registry assembly supports both forms. The lasting rationale for using `of` in resolver-algorithm tests, reserving `ofSelective` for integration paths, reapplying the resolver relation during correctness validation, and treating supplied demand as a separate witness property is recorded in [`semantics/testing-contracts.md`](./semantics/testing-contracts.md#resolver-fixture-and-oracle-boundary).

Argument-bearing fields remain outside the resolver-output relation, but `FieldResolver.evaluateRelation` no longer recursively revalidates every returned object and list at runtime. Fixture construction remains responsible for producing argumentless passive output fields; the former runtime-rejection test is retained as an ignored specification test. The performance rationale and controlled measurements are recorded in [`semantics/resolver-profiling.md`](./semantics/resolver-profiling.md#2026-09-04-002258-utc).

The execution feature-test adapter now assembles selective field executors with `selectiveFieldResolverOf` and converts their Resolver26 successor demand into Engine API selection sets. The conversion preserves concrete applicability, nested fields, and resolved arguments, and maps lowered `V_A_typename` demand back to `__typename`. Twenty formerly `Selective`-only feature tests now run unchanged (17 selective-field tests and 3 required-selection tests). Remaining cases were relabeled with their actual blocker; `SelSem` denotes unresolved Resolver26/production selective-demand behavior, including key coalescing and production handling of outputs outside qplan's selective-relation contract. Selective node executors remain unsupported.

Every reasoning world uses one canonical lowered `viaduct.graphql.schema.ViaductSchema`. Fields, arguments, enum values, object types, type conditions, possible-object-type sets, type expressions, resolver keys, selections, and EOD schema types come from that schema instance. Model, fixtures, arbitrary generation, semantics, and execution use `ViaductSchema` and its flat `TypeExpr` representation directly.

The model distinguishes `EngineInputData`, `EngineOutputData`, and `EngineResult`. Engine results are finite graphs whose ordinary containment edges are well-founded; `ObjectEngineResult.ParentKey` is the sole distinguished backedge and references the immediate structural ancestor OER. Structural traversals skip parent keys, while finite selection-driven materialization follows them into fresh EOD projections. OER cells and paths otherwise use canonical `ObjectEngineResult.ObjectKey` values, which may retain occurrence-specific variables in their arguments. `GroundKey` remains the refinement for operations that require resolved argument values. A `ResolverOccurrenceId` combines its Query-rooted OER identity with its exact structural path; the primary result roots primary occurrences and every independently executed query fragment roots its subordinate occurrences in its own fresh Query OER. Variable instances retain their defining `ResolverOccurrenceId`; keys carry no separate occurrence identity. Result union is deliberately rejected for parent-bearing graphs until it can copy and retarget backedges correctly.

## Source And Lowered Schema Boundary

Fixture composition retains two distinct schemas:

- The source `GraphQLSchema` owns external GraphQL parsing, validation, source spread rules, source output types, coercion where required, and response completion.
- The canonical lowered `ViaductSchema` owns qplan fields, types, selections, resolver registry entries, conformance checks, values, and subtype reasoning. Source-backed definitions retain exact source GraphQL-Java EOD witnesses; synthetic bridge definitions retain generated internal witnesses.

`GJSchema.fromSDL` parses and validates the source schema and lowers it to the final canonical `ViaductSchema`. `SourceSchemaAdapter` is the explicit source-to-lowered boundary and requires the canonical source/lowered fixture pair. Node-valued source fields, node lookups, and typename are compiled into ordinary synthetic field resolvers before semantic reasoning.

## Current Semantic Capabilities

All maintained resolvers support source-sensitive ownership of argumentless registered fields: an ancestor output may supply such a field passively, while an absent field uses its standard resolver. Fields with arguments are always active. The exact contract and resolver support matrix live in [`semantics/testing-contracts.md`](./semantics/testing-contracts.md).

Resolver02/03, Resolver07/08, Resolver22/23, and Resolver26 support `@parent` fields. Schema preparation validates an argument-free singular parent field and maps it to its unique argument-free child producer; child producers may return singular objects, lists, or nested lists. Resolver input validation rejects variables on every concrete branch reachable beneath a parent selection, including parent fields retargeted from abstract coordinates, while allowing variables guarded by concrete branches disjoint from all parent retargets. It otherwise preserves selective-resolver support. Each child parent cell points to its actual containing ancestor OER, but EOD materialization creates a fresh finite parent projection rather than preserving resolver-visible object identity. One-level demand lifting composes for grandparent selections. Conservative lifting may over-resolve or over-materialize ancestor demand when an active grandchild field is dynamically passive; this is an accepted tradeoff for the uncommon overlap of those features. The focused Resolver26 parent property test classifies exact variable-bearing argument selections reached across resolver boundaries beneath parents by fragment, depth, and binding-source combination, and measures diagonal dependencies in which a resolver selected below one parent independently requires its own parent.

Resolver26 closes each OER once. Successor demand statically transposes descendant parent demand into selective producer output, while input-demand closure independently discovers parent-induced construction demand in requested descendants and reachable resolver inputs and transposes it across matching producer edges. Passive resolution therefore passes construction demand unchanged into each returned OER rather than mixing in successor invocation demand. This makes all ancestor work discoverable before child construction while retaining source-sensitive suppression of standard resolver demand. Every OER freezes after its one local launch cycle.

Resolver02/03, Resolver07/08, Resolver22/23, and Resolver26 support independently resolved Query-rooted resolver fragments. Each application receives a fresh Query OER materialized with response keys preserved, and correctness validation retains that OER as an occurrence-specific witness. The general distinction between variable producers and fragment consumers is defined in [`semantics/README.md`](./semantics/README.md#variable-production-and-consumption): `FromObjectField` and `FromQueryField` choose the provider fragment, while either the object fragment or Query fragment may consume either binding. Resolver26 implements both provider kinds and all three consumption shapes.

Every maintained resolver supports canonical `FromArgument` paths through nested input objects, with null propagation through nullable intermediate objects and no list traversal. Resolver26 additionally evaluates compiled `FromObjectField` provider paths at runtime and evaluates compiled `FromQueryField` provider paths in each occurrence's fresh Query OER. Resolver26 preserves symbolic OER keys and treats grounding as readiness and invocation data rather than a rekeying operation; distinct symbolic keys may therefore invoke the same grounded field arguments more than once.

Ordinary generated tests include a replayable `sometimes-passive` profile across the full maintained resolver grid. Resolver26 stress profiles additionally generate a schema-valid parent spine whose deepest resolver reads through three parent edges to its great-grandparent; query generation activates that path, and variable insertion excludes every argument occurrence beneath a parent selection. The dedicated `resolver26ParentFocused` task runs a `40:5:5` product as four reported 250-case slices. It supplements the fixed witness with randomized parent chains of depth one through four, singular and nested-list producers, nullable forms, union parent targets, scalar siblings, and ordinary generated resolver fragments. Its application observer retains each fragment's materialization selections and reports per-slice `HIT`/`MISS` results for eight runtime coverage criteria spanning topology, resolver placement, variable sources and pairs, input locations, argument depth, diagonals, and source/location combinations on variable-bearing diagonals. Coverage misses are diagnostic rather than test failures. Resolver26 broad campaign profiles also enable sometimes-passive generation and require independent evidence that registered result occurrences were supplied by their source owner without invoking the standard field resolver. Resolver26 application accounting reconstructs registered occurrences across the primary result and every request-local Query-fragment OER, qualifies observed applications by Query root and exact path, and counts sometimes-passive occurrences as the difference from that complete rooted ledger. Resolver26 application observations expose exact applied occurrence sets to from-field binding validation, which requires all and only the `FromObjectField` and `FromQueryField` bindings of applied occurrences and traverses every request-local Query root.

## Execution Harness Boundary

The execution module runs validated GraphQL queries through Resolver26 and uses GraphQL Java for response completion. `EngineTestModule.runQPlanFeatureTest` adapts pre-dispatcher Engine API field and node executors into ordinary qplan fixture inputs; it does not construct production dispatchers or data loaders.

The adapter supports unbatched, non-selective field and node executors; field arguments; object and Query required selections; top-level `FromArgument` variables; supported singular `FromObjectField` and `FromQueryField` paths; synchronous scalar, enum, list, object, error, and node-reference outputs; built-in node lookup; and typename completion. Each resolver occurrence receives the Query value materialized from its Resolver26 Query fragment. Missing nullable Query executors resolve to null, while missing non-null Query executors resolve to an error.

The adapter rejects batching, selectivity, multi-segment production `FromArgument` recipes, arbitrary callback providers, from-field providers whose erased production representation ambiguously matches both resolver fragments, checker and type-checker executors (including their object- and Query-rooted required selections), asynchronous EOD outputs, mutations, subscriptions, and custom scalars. Qplan's semantic model already represents nested input-object argument paths; the remaining one-segment restriction belongs only to production RSS recovery. Direct inline materialization of a Node-valued field remains outside the modeled execution contract. Query fragments retain their independent fresh Query OERs; caching or deduplicating work shared with the primary operation remains a separate execution-layer concern.

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
