# Viaduct Feature-Test Inventory

This inventory covers files under `core/engine/runtime/src/test/kotlin` that invoke `runFeatureTest` as of 2026-08-20. Counts are source-level `@Test`, `@ParameterizedTest`, and `@TestFactory` annotations; nested parameterized cases may execute more than once. The inventory is a portability map, not a claim that every source file should be migrated into qplan.

Migration is atomic by source file. Once any file is migrated, every source test must be copied in source order; tests outside qplan's current supported surface remain present and are marked `@Disabled` with a specific reason. The counts below report migration state: a copied count smaller than the source count identifies an unfinished legacy migration, not a supported selective port.

The 2026-08-21 whole-file audit found 93 omitted test declarations across four selective legacy ports and one copied test whose behavior had been rewritten. All four files have since been completed from their production sources, the rewritten test has been restored, and the source and port production-test names and order now agree exactly. Together they contain 124 production tests: 28 currently pass through qplan and 96 remain disabled. Two enabled `ALTERNATIVE` tests separately state qplan-compatible behavior for confirmed incompatibilities.

## Best Next Files

1. Adapt and enable the copied disabled tests one at a time, preserving each production fixture and assertion while classifying its blocker.
2. `NamespaceTypeTest.kt`: investigate migrating the complete two-test file. Its constant namespace-object cases use synchronous unbatched fields, but the adapter may need to synthesize namespace root values.

## Observed Port Boundaries

- **Execution:** `NodeResolverTest.kt` initially exposed two shared fixture-lowering gaps before Resolver26: incomplete feature-test modules omitted unrelated Query resolvers, and raw node lookups were required to repeat the ID already supplied by the producing field's fringe value. `TestWorld` now fills missing Query fields with explicit error producers and makes the fringe ID authoritative when composing node lookup data, matching production's `NodeEngineObjectDataImpl`. Five node tests currently pass through qplan; eight remain disabled.
- **Current policy:** `NodeResolverTest.kt`'s disabled `node reference nested inside resolver response` directly materializes its outer `Baz` object while using a `NodeReference` only for the nested `anotherBaz`. Production supports that distinction, but qplan currently requires every Node value to be resolved by its node resolver, so direct inline Node materialization remains outside the modeled scope. Its passing `ALTERNATIVE` returns an outer node reference and materializes both occurrences through the node resolver.
- **Semantics:** `RequiredSelectionsTest.kt`'s disabled `resolve fields multiple mergeable requirements` preserves its named RSS fragment and production's two-invocation assertion. Qplan deliberately coalesces alias-shaped demand into one resolver application; its passing `ALTERNATIVE` differs only by expecting that one-shot count.
- `NodeResolverTest.kt`'s copied and disabled `node resolver not executed twice for the same query path` uses a query required selection, which the executor adapter deliberately rejects.
- `FromFieldVariablesFeatureTest.kt`'s source-success case `from arg -- path traverses nested input` is restored unchanged and disabled; adapter rejection coverage belongs in a separate qplan-specific test.
- `OperationValidationTest.kt` is not a Resolver26 candidate. Its invalid operations are rejected before `QPlanExecutionStrategy`, while its valid case only executes two independent constant root fields.

## Full Inventory

| Source file | Tests | Portability status |
| --- | ---: | --- |
| `AccessCheckExecutionTest.kt` | 31 | Blocked by checker executors and access-check semantics. |
| `BatchFieldResolverTest.kt` | 5 | Blocked by batching and data-loader scheduling. |
| `BatchNodeResolverTest.kt` | 8 | Blocked by batching and data-loader scheduling. |
| `FieldDataLoaderTest.kt` | 1 | Blocked by dispatcher/data-loader integration. |
| `FieldPolicyCheckTest.kt` | 5 | Blocked by checker and policy semantics. |
| `NamespaceTypeTest.kt` | 2 | Investigate; synchronous fields fit, namespace root synthesis may not. |
| `NodeDataLoaderTest.kt` | 15 | Primarily data-loader coverage; not a qplan feature-test target yet. |
| `NodeResolverTest.kt` | 13 | Complete port: 5 production tests pass through qplan and 8 are disabled; one additional `ALTERNATIVE` passes. Inline Node values, query RSSes, data-loader caching, selectivity, and parallel scheduling remain among the blocked cases. |
| `OperationValidationTest.kt` | 3 | Not a Resolver26 target; validation occurs before qplan execution, and the valid case adds only trivial resolver coverage. |
| `ParentManagedValueTest.kt` | 5 | Blocked by `ParentManagedValue` output-policy adaptation. |
| `RootFieldReferenceResolutionTest.kt` | 21 | Blocked by root-field-reference values, namespace factories, checkers, and directives. |
| `ShadowFieldExecutionTest.kt` | 10 | Blocked by shadow execution, instrumentation, mutations, and special output policies. |
| `StandardResolutionValueTest.kt` | 2 | Blocked by `StandardResolutionValue` output-policy adaptation. |
| `ViaductFieldResolutionFatalExceptionTest.kt` | 8 | Blocked by runtime field/fetch instrumentation boundaries. |
| `VariablesResolverTest.kt` | 11 | Blocked by arbitrary callback providers and variable-resolver runtime behavior. |
| `execution/CompleteSelectionSetTest.kt` | 9 | Blocked by runtime `completeSelectionSet`/child-plan APIs outside the qplan adapter. |
| `execution/ConditionalDirectivesExecutionTest.kt` | 1 | Blocked by applied-directive decoding. |
| `execution/ExecutionSelectionSetTest.kt` | 146 | Primarily Dispatcher-backed selection-projection coverage; not a qplan feature-test target yet. |
| `execution/FetchObjectInstrumentationFeatureTest.kt` | 3 | Blocked by runtime instrumentation integration. |
| `execution/FieldExecutionObservabilityFeatureTest.kt` | 6 | Blocked by runtime observability instrumentation and attribution. |
| `execution/FieldResolverExecutionConditionTest.kt` | 2 | Blocked by runtime query-plan execution conditions. |
| `execution/FromFieldVariablesFeatureTest.kt` | 43 | Complete port: 10 pass through qplan and 33 are disabled. Query paths, directives, mutations, cycles, nested argument paths, and argument-bearing provider paths remain among the blocked cases. |
| `execution/ParentFieldRequiredSelectionsExecutionTest.kt` | 13 | Blocked by `@parent`, checker, and parent-traversal runtime semantics. |
| `execution/RequiredSelectionsTest.kt` | 60 | Complete port: 11 production tests pass through qplan and 49 are disabled; one additional `ALTERNATIVE` passes. Selective, query RSS, checker, parent, directive, and mutation cases remain among the blocked cases. |
| `execution/ResolverInstrumentationFeatureTest.kt` | 5 | Blocked by resolver/checker/fetch instrumentation integration. |
| `execution/ResolveSelectionSetTest.kt` | 5 | Blocked by runtime `resolveSelectionSet`/subquery APIs outside the qplan adapter. |
| `execution/SelectiveFieldResolversExecutionTest.kt` | 64 | Blocked by selective executor plumbing and requested selections. |
| `execution/SelectiveNodeResolversExecutionTest.kt` | 61 | Blocked by selective node executor plumbing and requested selections. |
| `execution/SubqueryExecutionTest.kt` | 27 | Blocked by `ExecutionHandle` subquery integration and mutations. |
| `execution/SubquerySchemaTest.kt` | 3 | Blocked by subquery and scoped/full-schema integration. |
| `execution/Utils.kt` | 0 | Shared test helper, not a suite. |
| `fixtures/EngineFeatureTestExample.kt` | 8 | Complete port: 2 pass through qplan and 6 are disabled while query RSSes, checkers, arbitrary providers, or runtime bootstrap validation are unsupported. |
| `tenantloading/CycleDetectorFeatureTest.kt` | 1 | Blocked because qplan feature bootstrap does not run runtime tenant-loading validators. |

## Revisit Triggers

Revisit the blocked groups when qplan gains the corresponding boundary: batching/data loaders, requested-selection plumbing, checker executors, query RSSes and from-Query providers, applied directives, special output-policy wrappers, parent traversal, subquery APIs, or runtime instrumentation. Keep these concerns separate from ordinary resolver-semantic ports so a passing port remains evidence about qplan rather than an accidental reimplementation of Dispatcher behavior.
