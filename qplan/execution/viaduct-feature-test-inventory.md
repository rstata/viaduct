# Viaduct Feature-Test Inventory

This inventory covers files under `core/engine/runtime/src/test/kotlin` that invoke `runFeatureTest` as of 2026-08-20. Counts are source-level `@Test`, `@ParameterizedTest`, and `@TestFactory` annotations; nested parameterized cases may execute more than once. The inventory is a portability map, not a claim that every source test should eventually run through qplan.

## Best Next Candidates

1. `FromFieldVariablesFeatureTest.kt`: port `single-field-multiple-variable -- multiple required selections with variables`, `same variable name used in operation variable and annotation variable`, and `same variable name used in multiple selection sets`. These stay within argument-free object provider paths.
2. `NamespaceTypeTest.kt`: investigate its two constant namespace-object cases. They use synchronous unbatched fields, but the adapter may need to synthesize namespace root values.

## Observed Port Boundaries

- **Execution:** Six copied `NodeResolverTest.kt` cases are disabled without fixture or assertion changes. Their shared production schema declares both `Query.baz` and `Query.bazList`, while each module registers only the root field under test; qplan fixture composition currently rejects the other lowered Query producer as missing before Resolver26 runs. A temporary diagnostic registration also exposed that production supplies a node reference's `id` independently of the node executor output, while qplan's node lowering currently requires the executor result to repeat it. These are incomplete adapter or fixture-composition behaviors, not observed Resolver26 failures.
- **Execution:** `NodeResolverTest.kt`'s disabled `node reference nested inside resolver response` also preserves an inline `Baz` object from a Node-valued field while a `Baz` node resolver exists. Qplan's integration lowering currently treats every Node-valued producer output as a node reference, so the adapter cannot preserve the inline object as distinct from a `NodeReference`.
- **Execution:** `RequiredSelectionsTest.kt`'s disabled `required selections use deep aliases` preserves production's raw map object output; qplan's executor adapter currently requires an `EngineObjectData.Sync` for that composite value. Resolver26 is not reached with a valid adapted value.
- **Execution, then semantics:** `RequiredSelectionsTest.kt`'s disabled `resolve fields multiple mergeable requirements` preserves its named RSS fragment and two-invocation assertion. Its immediate failure is an incomplete execution adapter: RSS conversion omits the `ParsedSelections.fragmentMap`, so Resolver26 is not reached. Temporarily inlining that fragment produced the expected response but Resolver26 invoked `bar` once rather than production's twice; that downstream invocation-parity question belongs to semantics.
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
| `NodeResolverTest.kt` | 13 | 6 copied and disabled unchanged for execution-adapter investigation; Resolver26 is not reached. Inline Node values, query RSSes, data-loader caching, selectivity, and parallel scheduling remain outside the current adapter. |
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
| `execution/RequiredSelectionsTest.kt` | 60 | 12 copied; 10 pass and 2 remain disabled unchanged. Both immediate failures are in execution adaptation; the merge case also exposes a downstream Resolver26 invocation-parity question. Selective, query RSS, checker, parent, directive, and mutation cases remain blocked. |
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
