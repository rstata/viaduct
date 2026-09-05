# Viaduct Feature-Test Inventory

This inventory records the current synchronization boundary between qplan and the 32 `core/engine/runtime` feature-test files selected for this porting surface. Thirteen qplan port files exist and 19 source files remain whole-file exclusions.

Migration is atomic by source file. A synchronized port is authoritative through its copied source tests and coded `@Disabled` reasons; this document intentionally does not duplicate that per-test status.

Eleven ports currently match the source test names and counts. The synchronization gaps below keep the other two ports from being complete whole-file migrations.

Migrated tests are source-faithful: aside from package/import plumbing, `runFeatureTest` to `runQPlanFeatureTest`, source metadata, and coded `@Disabled` annotations, their fixture code, helpers, behavior, and assertions must remain unchanged. Tests requiring production `KeyTree` or `KeyTreeBuilder` utilities are out of scope and belong in the N/A worklist until that infrastructure is deliberately added.

## Omitted Whole Files

The following source files are intentionally not copied because every test in each file is outside qplan's resolver-correctness boundary. These are file-level exclusions; migrated files remain authoritative through their individual enabled or coded `@Disabled` tests.

| Source file | Tests | Reason |
| --- | ---: | --- |
| `BatchFieldResolverTest.kt` | 5 | Batched field-executor behavior |
| `BatchNodeResolverTest.kt` | 8 | Batched node-executor behavior |
| `CompleteSelectionSetTest.kt` | 8 | Production `completeSelectionSet` API |
| `CycleDetectorFeatureTest.kt` | 1 | Tenant-loading bootstrap cycle detector |
| `ExecutionSelectionSetTest.kt` | 146 | Production `ExecutionSelectionSet` implementation and dispatcher projection |
| `FetchObjectInstrumentationFeatureTest.kt` | 3 | Fetch-object instrumentation ordering |
| `FieldDataLoaderTest.kt` | 1 | Field data-loader scope and batching |
| `FieldExecutionObservabilityFeatureTest.kt` | 6 | Production execution observability instrumentation |
| `FieldResolverExecutionConditionTest.kt` | 2 | Production query-plan execution conditions |
| `NodeDataLoaderTest.kt` | 19 | Node data-loader caching and selection coverage |
| `OperationValidationTest.kt` | 6 | Operation/schema-scope validation before resolution |
| `ParentManagedValueTest.kt` | 5 | Production parent-managed resolution policy; qplan owns descendant output through resolver output selections |
| `ResolveSelectionSetTest.kt` | 5 | Production `resolveSelectionSet` API |
| `ResolverInstrumentationFeatureTest.kt` | 5 | Resolver instrumentation callbacks |
| `ShadowFieldExecutionTest.kt` | 10 | Shadow execution and comparison |
| `StandardResolutionValueTest.kt` | 2 | Production `StandardResolutionValue` wrapper |
| `SubqueryExecutionTest.kt` | 27 | `ctx.query()` and `ctx.mutation()` subquery execution |
| `SubquerySchemaTest.kt` | 3 | Subquery schema selection |
| `ViaductFieldResolutionFatalExceptionTest.kt` | 8 | Production instrumentation failure boundaries |

## Port Synchronization Gaps

| Port | Current source difference |
| --- | --- |
| `RootFieldReferenceResolutionTest.kt` | Missing `caller is derived from resolver object traversal` and `caller survives a chain of resolver RSS dependencies` (21 of 23 source tests copied). |
| `SelectiveFieldResolversExecutionTest.kt` | Missing `selective list item can read its non-selective parent` and `selective resolver materialization rejects DataFetcherResult` (63 of 65 current source tests copied). |

## Observed Port Boundaries

- **Execution:** `TestWorld` fills missing nullable Query fields with null producers and missing non-null Query fields with error producers. Node lowering treats the fringe ID as authoritative when composing raw lookup data, matching production's `NodeEngineObjectDataImpl`; the lookup payload need not repeat it. Seven node tests currently pass through qplan; six remain disabled.
- **Current policy:** `NodeResolverTest.kt`'s disabled `node reference nested inside resolver response` directly materializes its outer `Baz` object while using a `NodeReference` only for the nested `anotherBaz`. Production supports that distinction, but qplan currently requires every Node value to be resolved by its node resolver, so direct inline Node materialization remains outside the modeled scope. Its passing `ALTERNATIVE` returns an outer node reference and materializes both occurrences through the node resolver.
- **Semantics:** `RequiredSelectionsTest.kt`'s disabled `resolve fields multiple mergeable requirements` preserves its named RSS fragment and production's two-invocation assertion. Qplan deliberately coalesces alias-shaped demand into one resolver application; its passing `ALTERNATIVE` differs only by expecting that one-shot count.
- **Selective fields:** The executor adapter now passes converted Resolver26 demand to selective field executors. Seventeen of the 71 formerly `Selective`-only cases in `SelectiveFieldResolversExecutionTest.kt` and three of the seven such cases in `RequiredSelectionsTest.kt` now pass unchanged. The remaining cases have been reclassified by their actual blocker. `SelSem` marks unresolved Resolver26/production differences in selective coverage, argument-shape isolation, concrete applicability, key coalescing, or handling of outputs outside qplan's resolver-perfect relation.
- **Result metadata:** `SelectiveFieldResolversExecutionTest.kt` preserves production's disabled `selective resolver rematerializes DataFetcherResult list items`; its passing `ALTERNATIVE` unwraps the metadata-free list item to the directly conforming EOD value represented by qplan.
- `NodeResolverTest.kt`'s copied and disabled `node resolver not executed twice for the same query path` tests memoization across the primary operation and an independently rooted resolver Query fragment. Qplan will not support memoizing query-fragment OERs across resolver roots, so the test is N/A rather than an intentional behavior alternative.
- `FromFieldVariablesFeatureTest.kt`'s source-success case `from arg -- path traverses nested input` remains unchanged and disabled; adapter rejection coverage belongs in a separate qplan-specific test.
- `OperationValidationTest.kt` is not a Resolver26 candidate. Its invalid operations are rejected before `QPlanExecutionStrategy`, while its valid case only executes two independent constant root fields.

## Grouped Blocker Counts

Counts overlap because one test may be blocked by more than one requirement. They cover the eleven synchronized ports and exclude the two incomplete ports listed above. Labels appear space-separated in actionable `@Disabled("TODO: ...")` reasons; `IntentDiff` identifies the two intentional incompatibilities whose specific prose reasons are retained. `AccessChk` includes checker and type-checker executors together with their object- and Query-rooted required selections; checker Query fragments carry no additional blocker.

| Group | Count | Label |
| --- | ---: | --- |
| |
| Selective resolution semantics | 4 | `SelSem` |
| Parent-field semantics | 7 | `ParentFld` |
| Checkers / access checks | 8 | `AccessChk` |
| Arbitrary variable-provider callbacks | 10 | `VarCallbk` |
| Likely mechanical adapter enablement | 5 | `MechAdapt` |
| Abstract-type/runtime applicability | 5 | `Abstract` |
| Directives | 6 | `Directive` |
| Mutations | 3 | `Mutation` |
| Rich executor error preservation | 1 | `ErrorData` |
| Nested `FromArgument` paths | 2 | `NestedArg` |
| Intentional semantic incompatibilities | 2 | `IntentDiff` |
| Private-field schema adaptation | 1 | `PrivateFld` |
| Node fragment/lowering behavior | 1 | `NodeLower` |

## Not Applicable

### `EngineFeatureTestExample`

| Test | Reason |
| --- | --- |
| [`test invalid object fragment`](./src/test/kotlin/execution/viaductfeaturetests/EngineFeatureTestExample.kt#L221) | Validates bootstrap rejection of an invalid object RSS; no resolver executes. |
| [`test invalid query fragment`](./src/test/kotlin/execution/viaductfeaturetests/EngineFeatureTestExample.kt#L244) | Validates bootstrap rejection of an invalid query RSS; no resolver executes. |

### `FromFieldVariablesFeatureTest`

| Test | Reason |
| --- | --- |
| [`invalid from object field -- selection output type is not compatible with variable input type -- nullability mismatch`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L382) | Tests tenant-loading variable type validation, not resolution. |
| [`invalid from object field -- selection output type is not compatible with variable input type -- type mismatch`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L400) | Tests tenant-loading variable type validation, not resolution. |
| [`invalid from object field -- variable depends on a field in its own subselections`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L501) | Tests static variable-dependency cycle detection. |
| [`invalid from object field -- variable selects a field that uses it`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L522) | Tests static self-cycle detection. |
| [`invalid from object field -- deadlock between 2 variables -- same selection set`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L538) | Tests static variable-cycle validation within one RSS. |
| [`invalid from object field -- deadlock between 2 variables -- diff selection sets`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L562) | Tests static required-selection cycle validation across RSSes. |
| [`invalid from query field -- path refers to missing selection`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L583) | Tests bootstrap validation of a malformed from-query provider path. |
| [`invalid from query field -- path ends on object`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L600) | Tests bootstrap validation that provider paths terminate at compatible values. |
| [`invalid from query field -- variable name overlaps with object field variable`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L777) | Tests bootstrap rejection of duplicate provider registrations. |
| [`invalid from query field -- variable name overlaps with argument variable`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L803) | Tests bootstrap rejection of duplicate provider registrations. |

### `RequiredSelectionsTest`

| Test | Reason |
| --- | --- |
| [`queryValueFragment with unclosed brace should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2366) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with invalid field syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2382) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment referencing non-existent field should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2398) | Tests schema validation of a query RSS during bootstrap. |
| [`queryValueFragment with invalid fragment syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2414) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with invalid variable syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2430) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with empty selection set should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2446) | Tests query-RSS parser/shape validation during module construction. |
| [`queryValueFragment with wrong type condition should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2461) | Tests schema/type-condition validation during bootstrap. |

### `NodeResolverTest`

| Test | Reason |
| --- | --- |
| [`node resolver not executed twice for the same query path`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L512) | Tests memoization of query-fragment OERs across resolvers, which qplan will not support. |
| [`node field executes in parallel with node resolver`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L191) | Tests production's pending-OER/Dispatcher scheduling policy, not resolver correctness. |
| [`node resolver reads from dataloader cache`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L374) | Tests request-scoped `NodeDataLoader` caching; production already marks it flaky. |
| [`non-selective node resolver reads from dataloader cache for different selection sets`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L408) | Tests production's non-selective data-loader cache-key policy. |
| [`selective node resolver does not read from dataloader cache if selection set does not cover`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L446) | Tests selective data-loader cache coverage and cache-key policy, not qplan resolution semantics. |
