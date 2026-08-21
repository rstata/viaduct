# Viaduct Feature-Test Inventory

This inventory covers files under `core/engine/runtime/src/test/kotlin` that invoke `runFeatureTest` as of 2026-08-20. Counts are source-level `@Test`, `@ParameterizedTest`, and `@TestFactory` annotations; nested parameterized cases may execute more than once. The inventory is a portability map, not a claim that every source file should be migrated into qplan.

Migration is atomic by source file. Once any file is migrated, every source test must be copied in source order; tests outside qplan's current supported surface remain present and are marked `@Disabled` with a specific reason. The counts below report migration state: a copied count smaller than the source count identifies an unfinished legacy migration, not a supported selective port.

The 2026-08-21 whole-file audit found 93 omitted test declarations across four selective legacy ports and one copied test whose behavior had been rewritten. All four files have since been completed from their production sources, the rewritten test has been restored, and the source and port production-test names and order now agree exactly. Together they contain 124 production tests: 28 currently pass through qplan and 96 remain disabled. Two enabled `ALTERNATIVE` tests separately state qplan-compatible behavior for confirmed incompatibilities.

## Observed Port Boundaries

- **Execution:** `NodeResolverTest.kt` initially exposed two shared fixture-lowering gaps before Resolver26: incomplete feature-test modules omitted unrelated Query resolvers, and raw node lookups were required to repeat the ID already supplied by the producing field's fringe value. `TestWorld` now fills missing Query fields with explicit error producers and makes the fringe ID authoritative when composing node lookup data, matching production's `NodeEngineObjectDataImpl`. Five node tests currently pass through qplan; eight remain disabled.
- **Current policy:** `NodeResolverTest.kt`'s disabled `node reference nested inside resolver response` directly materializes its outer `Baz` object while using a `NodeReference` only for the nested `anotherBaz`. Production supports that distinction, but qplan currently requires every Node value to be resolved by its node resolver, so direct inline Node materialization remains outside the modeled scope. Its passing `ALTERNATIVE` returns an outer node reference and materializes both occurrences through the node resolver.
- **Semantics:** `RequiredSelectionsTest.kt`'s disabled `resolve fields multiple mergeable requirements` preserves its named RSS fragment and production's two-invocation assertion. Qplan deliberately coalesces alias-shaped demand into one resolver application; its passing `ALTERNATIVE` differs only by expecting that one-shot count.
- `NodeResolverTest.kt`'s copied and disabled `node resolver not executed twice for the same query path` uses a query required selection, which the executor adapter deliberately rejects.
- `FromFieldVariablesFeatureTest.kt`'s source-success case `from arg -- path traverses nested input` is restored unchanged and disabled; adapter rejection coverage belongs in a separate qplan-specific test.
- `OperationValidationTest.kt` is not a Resolver26 candidate. Its invalid operations are rejected before `QPlanExecutionStrategy`, while its valid case only executes two independent constant root fields.

## Grouped Blocker Counts

Counts overlap because one test may be blocked by more than one requirement.

| Group | Count |
| --- | ---: |
| Query RSS / from-query providers | 27 |
| Selective executors / requested selections | 10 |
| Parent-field semantics | 7 |
| Checkers / access checks | 7 |
| Needs enablement trial | 6 |
| Arbitrary variable-provider callbacks | 5 |
| Likely mechanical adapter enablement | 5 |
| Abstract-type/runtime applicability | 5 |
| Directives | 4 |
| Qplan bugs | 4 |
| Argument-bearing object-provider paths | 3 |
| Mutations | 3 |
| Rich executor error preservation | 2 |
| Nested `FromArgument` paths | 2 |
| Intentional semantic incompatibilities | 2 |
| Private-field schema adaptation | 1 |
| Node fragment/lowering behavior | 1 |

## Not Applicable

### `EngineFeatureTestExample`

| Test | Reason |
| --- | --- |
| [`test invalid object fragment`](./src/test/kotlin/execution/viaductfeaturetests/EngineFeatureTestExample.kt#L222) | Validates bootstrap rejection of an invalid object RSS; no resolver executes. |
| [`test invalid query fragment`](./src/test/kotlin/execution/viaductfeaturetests/EngineFeatureTestExample.kt#L245) | Validates bootstrap rejection of an invalid query RSS; no resolver executes. |

### `FromFieldVariablesFeatureTest`

| Test | Reason |
| --- | --- |
| [`invalid from object field -- selection output type is not compatible with variable input type -- nullability mismatch`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L383) | Tests tenant-loading variable type validation, not resolution. |
| [`invalid from object field -- selection output type is not compatible with variable input type -- type mismatch`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L401) | Tests tenant-loading variable type validation, not resolution. |
| [`invalid from object field -- variable depends on a field in its own subselections`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L503) | Tests static variable-dependency cycle detection. |
| [`invalid from object field -- variable selects a field that uses it`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L524) | Tests static self-cycle detection. |
| [`invalid from object field -- deadlock between 2 variables -- same selection set`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L540) | Tests static variable-cycle validation within one RSS. |
| [`invalid from object field -- deadlock between 2 variables -- diff selection sets`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L564) | Tests static required-selection cycle validation across RSSes. |
| [`invalid from query field -- path refers to missing selection`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L585) | Tests bootstrap validation of a malformed from-query provider path. |
| [`invalid from query field -- path ends on object`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L602) | Tests bootstrap validation that provider paths terminate at compatible values. |
| [`invalid from query field -- variable name overlaps with object field variable`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L779) | Tests bootstrap rejection of duplicate provider registrations. |
| [`invalid from query field -- variable name overlaps with argument variable`](./src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt#L805) | Tests bootstrap rejection of duplicate provider registrations. |

### `RequiredSelectionsTest`

| Test | Reason |
| --- | --- |
| [`queryValueFragment with unclosed brace should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2368) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with invalid field syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2384) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment referencing non-existent field should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2400) | Tests schema validation of a query RSS during bootstrap. |
| [`queryValueFragment with invalid fragment syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2416) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with invalid variable syntax should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2432) | Tests query-RSS parser failure during module construction. |
| [`queryValueFragment with empty selection set should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2448) | Tests query-RSS parser/shape validation during module construction. |
| [`queryValueFragment with wrong type condition should fail at build time`](./src/test/kotlin/execution/viaductfeaturetests/RequiredSelectionsTest.kt#L2463) | Tests schema/type-condition validation during bootstrap. |

### `NodeResolverTest`

| Test | Reason |
| --- | --- |
| [`node field executes in parallel with node resolver`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L192) | Tests production's pending-OER/Dispatcher scheduling policy, not resolver correctness. |
| [`node resolver reads from dataloader cache`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L375) | Tests request-scoped `NodeDataLoader` caching; production already marks it flaky. |
| [`non-selective node resolver reads from dataloader cache for different selection sets`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L409) | Tests production's non-selective data-loader cache-key policy. |
| [`selective node resolver does not read from dataloader cache if selection set does not cover`](./src/test/kotlin/execution/viaductfeaturetests/NodeResolverTest.kt#L447) | Tests selective data-loader cache coverage and cache-key policy, not qplan resolution semantics. |
