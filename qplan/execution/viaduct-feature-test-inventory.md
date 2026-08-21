# Viaduct Feature-Test Inventory

This inventory covers files under `core/engine/runtime/src/test/kotlin` that invoke `runFeatureTest` as of 2026-08-20. Counts are source-level `@Test`, `@ParameterizedTest`, and `@TestFactory` annotations; nested parameterized cases may execute more than once. The inventory is a portability map, not a claim that every source test should eventually run through qplan.

## Best Next Candidates

1. `FromFieldVariablesFeatureTest.kt`: port `single-field-multiple-variable -- multiple required selections with variables`, `same variable name used in operation variable and annotation variable`, and `same variable name used in multiple selection sets`. These stay within argument-free object provider paths.
2. `NamespaceTypeTest.kt`: investigate its two constant namespace-object cases. They use synchronous unbatched fields, but the adapter may need to synthesize namespace root values.

## Observed Port Boundaries

- **Execution:** `NodeResolverTest.kt` initially exposed two shared fixture-lowering gaps before Resolver26: incomplete feature-test modules omitted unrelated Query resolvers, and raw node lookups were required to repeat the ID already supplied by the producing field's fringe value. `TestWorld` now fills missing Query fields with explicit error producers and makes the fringe ID authoritative when composing node lookup data, matching production's `NodeEngineObjectDataImpl`. Five copied node tests now pass unchanged; only `node reference nested inside resolver response` remains disabled.
- **Current policy:** `NodeResolverTest.kt`'s disabled `node reference nested inside resolver response` directly materializes its outer `Baz` object while using a `NodeReference` only for the nested `anotherBaz`. Production supports that distinction, but qplan currently requires every Node value to be resolved by its node resolver, so direct inline Node materialization remains outside the modeled scope.
- **Semantics:** `RequiredSelectionsTest.kt`'s disabled `resolve fields multiple mergeable requirements` preserves its named RSS fragment and two-invocation assertion. Object RSS documents now cross intact into a semantics-owned conversion that currently lowers named spreads into the normalized selection carrier, so Resolver26 is reached and produces the expected response. The remaining discrepancy is invocation parity: Resolver26 invokes `bar` once while production invokes it twice.
- `NodeResolverTest.kt`'s `node resolver not executed twice for the same query path` was not copied because it uses a query required selection, which the executor adapter deliberately rejects.
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
| `NodeResolverTest.kt` | 13 | 6 copied; 5 pass unchanged and 1 remains disabled. Inline Node values, query RSSes, data-loader caching, selectivity, and parallel scheduling remain outside the current adapter. |
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
| `execution/FromFieldVariablesFeatureTest.kt` | 43 | 11 ported; more argument-free object-path cases are candidates, while query paths, directives, mutations, cycles, and argument-bearing provider paths are blocked. |
| `execution/ParentFieldRequiredSelectionsExecutionTest.kt` | 13 | Blocked by `@parent`, checker, and parent-traversal runtime semantics. |
| `execution/RequiredSelectionsTest.kt` | 60 | 12 copied; 11 pass and 1 remains disabled unchanged. Its remaining discrepancy is Resolver26 invocation parity: `bar` is invoked once while production invokes it twice. Selective, query RSS, checker, parent, directive, and mutation cases remain blocked. |
| `execution/ResolverInstrumentationFeatureTest.kt` | 5 | Blocked by resolver/checker/fetch instrumentation integration. |
| `execution/ResolveSelectionSetTest.kt` | 5 | Blocked by runtime `resolveSelectionSet`/subquery APIs outside the qplan adapter. |
| `execution/SelectiveFieldResolversExecutionTest.kt` | 64 | Blocked by selective executor plumbing and requested selections. |
| `execution/SelectiveNodeResolversExecutionTest.kt` | 61 | Blocked by selective node executor plumbing and requested selections. |
| `execution/SubqueryExecutionTest.kt` | 27 | Blocked by `ExecutionHandle` subquery integration and mutations. |
| `execution/SubquerySchemaTest.kt` | 3 | Blocked by subquery and scoped/full-schema integration. |
| `execution/Utils.kt` | 0 | Shared test helper, not a suite. |
| `fixtures/EngineFeatureTestExample.kt` | 8 | 2 ported; remaining cases require query RSSes, checkers, arbitrary providers, or runtime bootstrap validation. |
| `tenantloading/CycleDetectorFeatureTest.kt` | 1 | Blocked because qplan feature bootstrap does not run runtime tenant-loading validators. |

## Revisit Triggers

Revisit the blocked groups when qplan gains the corresponding boundary: batching/data loaders, requested-selection plumbing, checker executors, query RSSes and from-Query providers, applied directives, special output-policy wrappers, parent traversal, subquery APIs, or runtime instrumentation. Keep these concerns separate from ordinary resolver-semantic ports so a passing port remains evidence about qplan rather than an accidental reimplementation of Dispatcher behavior.
