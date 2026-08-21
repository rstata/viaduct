# Viaduct Feature-Test Inventory

This inventory covers files under `core/engine/runtime/src/test/kotlin` that invoke `runFeatureTest` as of 2026-08-20. Counts are source-level `@Test`, `@ParameterizedTest`, and `@TestFactory` annotations; nested parameterized cases may execute more than once. The inventory is a portability map, not a claim that every source file should be migrated into qplan.

Migration is atomic by source file. Once any file is migrated, every source test must be copied in source order; tests outside qplan's current supported surface remain present and are marked `@Disabled` with a specific reason. The counts below report migration state: a copied count smaller than the source count identifies an unfinished legacy migration, not a supported selective port.

The 2026-08-21 whole-file audit found 93 omitted test declarations across four selective legacy ports and one copied test whose behavior had been rewritten. All four files have since been completed from their production sources, the rewritten test has been restored, and the source and port production-test names and order now agree exactly. Together they contain 124 production tests: 33 currently pass through qplan and 91 remain disabled. Two enabled `ALTERNATIVE` tests separately state qplan-compatible behavior for confirmed incompatibilities.

## Observed Port Boundaries

- **Execution:** `NodeResolverTest.kt` initially exposed two shared fixture-lowering gaps before Resolver26: incomplete feature-test modules omitted unrelated Query resolvers, and raw node lookups were required to repeat the ID already supplied by the producing field's fringe value. `TestWorld` now fills missing Query fields with explicit error producers and makes the fringe ID authoritative when composing node lookup data, matching production's `NodeEngineObjectDataImpl`. Five node tests currently pass through qplan; eight remain disabled.
- **Current policy:** `NodeResolverTest.kt`'s disabled `node reference nested inside resolver response` directly materializes its outer `Baz` object while using a `NodeReference` only for the nested `anotherBaz`. Production supports that distinction, but qplan currently requires every Node value to be resolved by its node resolver, so direct inline Node materialization remains outside the modeled scope. Its passing `ALTERNATIVE` returns an outer node reference and materializes both occurrences through the node resolver.
- **Semantics:** `RequiredSelectionsTest.kt`'s disabled `resolve fields multiple mergeable requirements` preserves its named RSS fragment and production's two-invocation assertion. Qplan deliberately coalesces alias-shaped demand into one resolver application; its passing `ALTERNATIVE` differs only by expecting that one-shot count.
- `NodeResolverTest.kt`'s copied and disabled `node resolver not executed twice for the same query path` uses a query required selection, which the executor adapter deliberately rejects.
- `FromFieldVariablesFeatureTest.kt`'s source-success case `from arg -- path traverses nested input` is restored unchanged and disabled; adapter rejection coverage belongs in a separate qplan-specific test.
- `OperationValidationTest.kt` is not a Resolver26 candidate. Its invalid operations are rejected before `QPlanExecutionStrategy`, while its valid case only executes two independent constant root fields.

## Grouped Blocker Counts

Counts overlap because one test may be blocked by more than one requirement. Labels appear
space-separated in actionable `@Disabled("TODO: ...")` reasons; `IntentDiff` identifies the two
intentional incompatibilities whose specific prose reasons are retained.

| Group | Count | Label |
| --- | ---: | --- |
| Query RSS / from-query providers | 28 | `QueryRss` |
| Selective executors / requested selections | 13 | `Selective` |
| Parent-field semantics | 7 | `ParentFld` |
| Checkers / access checks | 8 | `AccessChk` |
| Arbitrary variable-provider callbacks | 10 | `VarCallbk` |
| Likely mechanical adapter enablement | 6 | `MechAdapt` |
| Abstract-type/runtime applicability | 6 | `Abstract` |
| Directives | 6 | `Directive` |
| Qplan bugs | 4 | `QplanBug` |
| Argument-bearing object-provider paths | 4 | `PathArgs` |
| Mutations | 3 | `Mutation` |
| Rich executor error preservation | 3 | `ErrorData` |
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
| [`node field executes in parallel with node resolver`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L191) | Tests production's pending-OER/Dispatcher scheduling policy, not resolver correctness. |
| [`node resolver reads from dataloader cache`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L374) | Tests request-scoped `NodeDataLoader` caching; production already marks it flaky. |
| [`non-selective node resolver reads from dataloader cache for different selection sets`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L408) | Tests production's non-selective data-loader cache-key policy. |
| [`selective node resolver does not read from dataloader cache if selection set does not cover`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L446) | Tests selective data-loader cache coverage and cache-key policy, not qplan resolution semantics. |
