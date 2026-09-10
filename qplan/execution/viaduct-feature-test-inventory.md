# Viaduct Feature-Test Inventory

This inventory records the current synchronization boundary between qplan and the 32 `core/engine/runtime` feature-test files selected for this porting surface. Thirteen qplan port files exist and 19 source files remain whole-file exclusions.

Migration is atomic by source file. A synchronized port is authoritative through its copied source tests and coded `@Disabled` reasons; this document intentionally does not duplicate that per-test status.

Twelve ports currently match the source test names and counts. The synchronization gap below keeps the remaining port from being a complete whole-file migration.

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
| `SelectiveFieldResolversExecutionTest.kt` | Missing `selective list item can read its non-selective parent` and `selective resolver materialization rejects DataFetcherResult` (63 of 65 current source tests copied). |

## Observed Port Boundaries

- **Namespace types:** Both source tests run unchanged through Resolver26, covering simple and nested namespace paths.
- **Root-field references:** All 23 source tests are copied. Reference resolution cases run through Resolver26 except the independently blocked checker/caller-attribution cases, the node-resolver-to-reference inline bridge, and production's support for object RSS on a referenced target. The object-RSS case remains source-faithful and disabled; its enabled `ALTERNATIVE` prefixes the namespace path and reads the same data through Query RSS. Production's equivalent-reference deduplication test likewise remains disabled; its enabled `ALTERNATIVE` expects one fresh target execution for each of the three reference occurrences.
- **Execution:** `TestWorld` fills missing nullable Query fields with null producers and missing non-null Query fields with error producers. Node lowering treats the fringe ID as authoritative when composing raw lookup data, matching production's `NodeEngineObjectDataImpl`; the lookup payload need not repeat it. Seven node tests currently pass through qplan; six remain disabled.
- **Current policy:** `NodeResolverTest.kt`'s disabled `node reference nested inside resolver response` directly materializes its outer `Baz` object while using a `NodeReference` only for the nested `anotherBaz`. Production supports that distinction, but qplan currently requires every Node value to be resolved by its node resolver, so direct inline Node materialization remains outside the modeled scope. Its passing `ALTERNATIVE` returns an outer node reference and materializes both occurrences through the node resolver.
- **Semantics:** `RequiredSelectionsTest.kt`'s disabled `resolve fields multiple mergeable requirements` preserves its named RSS fragment and production's two-invocation assertion. Qplan deliberately coalesces alias-shaped demand into one resolver application; its passing `ALTERNATIVE` differs only by expecting that one-shot count.
- **Variables:** `VariablesResolverTest.kt`'s disabled `variables are coerced` preserves production's independent object- and Query-RSS bindings for the same variable name. Qplan gives each resolver variable name one occurrence-local binding; its passing `ALTERNATIVE` uses distinct `varx` and `vary` names while preserving the coercion behavior under test.
- **Selective fields:** The executor adapter now passes converted Resolver26 demand to selective field executors and honors configured field-selectivity providers. Eighteen of the 71 formerly `Selective`-only cases in `SelectiveFieldResolversExecutionTest.kt` and three of the seven such cases in `RequiredSelectionsTest.kt` run unchanged. All eighteen production tests formerly labeled `SelSem` retain their source form as disabled `ALT` tests and have passing one-shot `ALTERNATIVE` counterparts. The alternatives preserve final GraphQL data and meaningful error paths while replacing repeated materialization, snapshot reconciliation, passive argument-bearing output, runtime-type-dependent demand narrowing, and surplus-output policy with Resolver26's closed-demand semantics. Other disabled cases are classified by their actual non-`SelSem` blocker.
- **Selective nodes:** Selective node executors are lowered to selection-sensitive synthetic payload resolvers and receive Resolver26's closed one-shot demand. Twenty-three production tests in `SelectiveNodeResolversExecutionTest.kt` run unchanged, covering concrete and abstract lookup, lists, nested node-field demand, merged demand, static directive behavior, RSS coverage, recursive demand, embedded lists, and alias recovery. The remaining production cache/refetch assertions describe repeated materialization rather than missing selective-node invocation; checker, batching, instrumentation, subquery, custom-directive, empty-payload, and argument-bearing node-output shapes retain specific exclusions. The inherited arbitrary suites execute a generated production `Viaduct` directly and do not expose an `EngineTestModule` adapter surface, so they are N/A for this harness.
- **Conditional directives:** Seven production-derived skip/include cases run unchanged. Source-document adaptation now preserves the source `__typename` response key after lowering, and static registry dependency and branch-order validation ignore `Never` selections. The remaining production case permits a selective parent to supply an argumentless registered descendant that production instead leaves to its standard resolver; it retains the production form as an `ALT` with a same-response `ALTERNATIVE` whose parent omits that descendant.
- **Synchronized-port selective semantics:** Four production tests in `RequiredSelectionsTest.kt` expect consumer-shaped resolver execution where qplan deliberately provides one-shot producer execution. Three expect production to invoke a selective source separately for client and RSS shapes, while Resolver26 coalesces those demands into one application. The fourth expects the client selection `{a}` to remain the executor-visible shape for a non-selective source, while Resolver26 supplies the coalesced `{a, b}` demand. Each unchanged production test remains disabled with an `ALT` reason immediately followed by a passing `ALTERNATIVE` that preserves the response assertion and verifies the one coalesced application.
- **Parent fields:** Ordinary singular, list, nested, interface, union, aliased, named-fragment, and selective parent-field cases run unchanged. The sole remaining `ParentFld` case uses a resolver variable beneath `@parent`; qplan deliberately rejects variables on every concrete branch reachable beneath a parent selection. Former parent-labelled checker tests are now classified as `AccessChk`, and the instrumentation-only case is N/A.
- **Legacy callback RSS:** `VarCallbk` is no longer an actionable qplan blocker, and callback ownership by itself does not make a production scenario N/A. Eleven production forms now remain disabled as `ALT` tests with passing `ALTERNATIVE` rewrites: declarative `FromObjectField` providers replace data-dependent callback RSSes, supported no-RSS providers replace callbacks whose RSS contributes no value, bounded parent demand is lifted unconditionally, argument-bearing fields are resolved actively rather than supplied passively, and selective node fixtures use one supported non-selective node application. The three genuine N/A cases are semantic boundaries: two require child-produced variables beneath `@parent` to parameterize ancestor work, and one tests pruning an unused provider that qplan instead rejects during registry construction.
- **Result metadata:** `SelectiveFieldResolversExecutionTest.kt` preserves production's disabled `selective resolver rematerializes DataFetcherResult list items`; its passing `ALTERNATIVE` unwraps the metadata-free list item to the directly conforming EOD value represented by qplan.
- `NodeResolverTest.kt`'s copied and disabled `node resolver not executed twice for the same query path` tests memoization across the primary operation and an independently rooted resolver Query fragment. Qplan will not support memoizing query-fragment OERs across resolver roots, so the test is N/A rather than an intentional behavior alternative.
- `FromFieldVariablesFeatureTest.kt`'s source-success case `from arg -- path traverses nested input` remains unchanged and disabled; adapter rejection coverage belongs in a separate qplan-specific test.
- `OperationValidationTest.kt` is not a Resolver26 candidate. Its invalid operations are rejected before `QPlanExecutionStrategy`, while its valid case only executes two independent constant root fields.

## Grouped Blocker Counts

Counts overlap because one test may be blocked by more than one requirement. They summarize actionable mixed-feature cases in `EngineFeatureTestExample.kt`, `FromFieldVariablesFeatureTest.kt`, `RequiredSelectionsTest.kt`, and `VariablesResolverTest.kt`; they exclude dedicated ports whose primary unsupported surface is already evident from the file, as well as the incomplete selective-field port listed above. Labels appear space-separated in actionable `@Disabled("TODO: ...")` reasons; `IntentDiff` identifies the fourteen intentional incompatibilities whose specific `ALT` prose reasons are retained. `AccessChk` includes checker and type-checker executors together with their object- and Query-rooted required selections; checker Query fragments carry no additional blocker. Resolved categories and N/A behavior are omitted rather than retained as zero-count blocker rows.

| Group | Count | Label |
| --- | ---: | --- |
| |
| Parent-field semantics | 1 | `ParentFld` |
| Checkers / access checks | 8 | `AccessChk` |
| Likely mechanical adapter enablement | 3 | `MechAdapt` |
| Abstract-type/runtime applicability | 2 | `Abstract` |
| Mutations | 3 | `Mutation` |
| Rich executor error preservation | 1 | `ErrorData` |
| Nested `FromArgument` paths | 2 | `NestedArg` |
| Intentional semantic incompatibilities | 14 | `IntentDiff` |
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
| [`queryValueFragment with unclosed brace should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L3047) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with invalid field syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L3062) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment referencing non-existent field should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L3077) | Tests schema validation of a query RSS during bootstrap. |
| [`queryValueFragment with invalid fragment syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L3096) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with invalid variable syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L3111) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with empty selection set should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L3126) | Tests query-RSS parser/shape validation during module construction. |
| [`queryValueFragment with wrong type condition should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L3140) | Tests schema/type-condition validation during bootstrap. |
| [`parent field with child object field variables runs child plan`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L713) | Requires a child-produced `locale` binding beneath `@parent` to parameterize ancestor `name(locale:)` work; qplan deliberately rejects that dependency direction. |
| [`parent field in variable resolver required selection is available to variables resolver`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L834) | Reads a provider through `@parent` and then uses it beneath the same parent selection; qplan deliberately rejects variables beneath parent selections. |

### `ParentFieldRequiredSelectionsExecutionTest`

| Test | Reason |
| --- | --- |
| [`execution runs checker but skips field instrumentation for parent field itself`](./src/test/kotlin/execution/viaductfeaturetests/ParentFieldRequiredSelectionsExecutionTest.kt#L527) | Tests production GraphQL field-instrumentation behavior, outside qplan resolver correctness. |

### `NodeResolverTest`

| Test | Reason |
| --- | --- |
| [`node resolver not executed twice for the same query path`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L512) | Tests memoization of query-fragment OERs across resolvers, which qplan will not support. |
| [`node field executes in parallel with node resolver`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L191) | Tests production's pending-OER/Dispatcher scheduling policy, not resolver correctness. |
| [`node resolver reads from dataloader cache`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L374) | Tests request-scoped `NodeDataLoader` caching; production already marks it flaky. |
| [`non-selective node resolver reads from dataloader cache for different selection sets`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L408) | Tests production's non-selective data-loader cache-key policy. |
| [`selective node resolver does not read from dataloader cache if selection set does not cover`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L446) | Tests selective data-loader cache coverage and cache-key policy, not qplan resolution semantics. |

### `VariablesResolverTest`

| Test | Reason |
| --- | --- |
| [`variables resolver rss without a selection reference is missing from query plan index`](./src/test/kotlin/execution/viaductfeaturetests/VariablesResolverTest.kt#L226) | Tests production pruning a provider after static directives erase every use; qplan rejects the resulting unused declarative provider during registry construction. |
