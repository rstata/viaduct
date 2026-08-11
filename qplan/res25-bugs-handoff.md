# Resolver25 Bugs Handoff

## Verdict

Resolver25 is not correct over its canonically accepted domain. The audit found four deterministic failure modes and recorded them as five static regression tests. Resolver25 production code was not changed, so these tests are intentionally red.

## Confirmed Failures

1. A nested `FromObjectField` provider deadlocks when an intermediate value is `null` or `Value.Error`. The terminal marker is never reached, its stamped binding never completes, and consumer grounding suspends indefinitely. `AdversarialRegressionTest` covers both cases with a two-second timeout.
2. A `FromObjectField` variable owned by a resolver in a descendant list element reaches demand expansion as an unstamped template. `DescendantVariableOwnerRegressionTest` currently fails with `IllegalStateException` from `OpenValue.kt`.
3. A variable used below a sibling object branch can be expanded before its provider value completes. `NestedVariableUseRegressionTest` currently fails with `UncompletedPromiseException`.
4. Resolver25's two-phase field graph can reject a canonical registry whose branch order is acyclic. `PhasePlanRegressionTest` uses two path-variable owners with canonical order `source -> sink -> middle -> outer`; Resolver25 manufactures a prepare/launch cycle and throws `IllegalArgumentException`.

The audit also observed that a consumer whose path variable grounds to `Value.Error` may cause resolver-input dependencies to execute even though the consumer itself is skipped. This overexecution is acceptable for Resolver25 and is not a correctness bug or regression requirement.

## Property-Test Assessment

`ResolverGeneratedTest` remains tuned away from the confirmed failures: it disables errors, nullable types, multiple path-variable owners, resolver fragments deeper than one, and non-`Query` variable owners. Resolver25's stress class is now separate from the shared deep-stress contract and splits its configured case budget across five directed profiles, so one failure does not hide the others.

The shared arbitrary framework gained default-neutral controls for Query scalar fields, provider-path length, variable-use depth, non-`Query` owners, and owner-to-owner variable use. It also reports nested-use owners, null/error intermediate owners, and owner dependency edges. Only Resolver25 enables the new scenarios; other resolvers retain their existing distributions.

Every successful directed case checks `correctResolution`, resolver witness identity, and `validateObjectPathBindings()`. Seed `250025` reproduces all failures with a 10,000-case total budget: null intermediate at `S=15 R=1 Q=1`, error intermediate at `S=3 R=1 Q=1`, descendant list owner at `S=34 R=2 Q=2`, nested variable use at `S=1 R=1 Q=2`, and the multiple-owner phase cycle at `S=84 R=1 Q=1`.

## Regression Tests

The static regressions are:

- `semantics/src/test/kotlin/semantics/resolver25/AdversarialRegressionTest.kt`
- `semantics/src/test/kotlin/semantics/resolver25/DescendantVariableOwnerRegressionTest.kt`
- `semantics/src/test/kotlin/semantics/resolver25/NestedVariableUseRegressionTest.kt`
- `semantics/src/test/kotlin/semantics/resolver25/PhasePlanRegressionTest.kt`

Run them with:

```bash
./gradlew :semantics:test --tests 'semantics.resolver25.*RegressionTest'
```

All five tests compile and currently fail for their predicted reasons: two timeouts, one unstamped-template exception, one incomplete-promise exception, and one false phase-cycle rejection. `./gradlew :semantics:compileTestKotlin` succeeds.

Run the generated reproductions with:

```bash
./gradlew :semantics:resolver25Stress -Presolver25StressSeed=250025
```

The task is intentionally red until Resolver25 is corrected. `RESOLVER25_STRESS_CASES` is the total budget and must be a multiple of 50; the default 10,000 cases gives each profile 2,000 cases.

## Next Work

Treat the static and generated regressions as the minimum correctness boundary. After fixes, each directed profile must finish with nonzero shape, query-activation, runtime-activation, and completion counts; unrelated resolver execution is intentionally not rejected.
