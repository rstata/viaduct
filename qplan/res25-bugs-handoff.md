# Resolver25 Bugs Handoff

## Verdict

Resolver25 is not yet correct over its canonically accepted domain. The audit found four deterministic failure modes and records them as seven static regression tests. The four premature from-path termination tests, the descendant-owner test, and the nested-use test are now fixed; the phase-plan test remains intentionally red.

## Fixed

A nested `FromObjectField` provider now propagates `null` or `Value.Error` when the provider path terminates at an active or passive intermediate component. Every provider-path component carries the stamped variable marker, and OER-aware demand merging reports completed binding values at either the terminal field or an earlier null/error. All four `AdversarialRegressionTest` cases and the two directed stress profiles pass.

A `FromObjectField` variable owned by a resolver in a descendant list element is now stamped only after the exact descendant occurrence path exists. Resolver25's successor-demand closure defers unstamped template branches while preserving ordinary and stamped-variable closure; `DescendantVariableOwnerRegressionTest` and its directed stress profile pass.

A stamped variable used below a sibling object branch now suspends successor-demand closure until its provider binding completes. The deferred closure coalesces equivalent keys and type guards at each recursive level, preventing an acyclic resolver-demand DAG from expanding into millions of duplicate selection occurrences. `NestedVariableUseRegressionTest` and its 2,000-case directed stress profile pass.

## Remaining Failures

Resolver25's two-phase field graph can reject a canonical registry whose branch order is acyclic. `PhasePlanRegressionTest` uses two path-variable owners with canonical order `source -> sink -> middle -> outer`; Resolver25 manufactures a prepare/launch cycle and throws `IllegalArgumentException`.

The audit also observed that a consumer whose path variable grounds to `Value.Error` may cause resolver-input dependencies to execute even though the consumer itself is skipped. This overexecution is acceptable for Resolver25 and is not a correctness bug or regression requirement.

## Property-Test Assessment

`ResolverGeneratedTest` remains tuned away from the scenarios that exposed these bugs: it disables errors, nullable types, multiple path-variable owners, resolver fragments deeper than one, and non-`Query` variable owners. Resolver25's stress class is now separate from the shared deep-stress contract and splits its configured case budget across five directed profiles, so one failure does not hide the others.

The shared arbitrary framework gained default-neutral controls for Query scalar fields, provider-path length, variable-use depth, non-`Query` owners, and owner-to-owner variable use. It also reports nested-use owners, null/error intermediate owners, and owner dependency edges. Only Resolver25 enables the new scenarios; other resolvers retain their existing distributions.

Every successful directed case checks `correctResolution`, resolver witness identity, and `validateObjectPathBindings()`. Seed `250025` originally reproduced all failures with a 10,000-case total budget: null intermediate at `S=15 R=1 Q=1`, error intermediate at `S=3 R=1 Q=1`, descendant list owner at `S=34 R=2 Q=2`, nested variable use at `S=1 R=1 Q=2`, and the multiple-owner phase cycle at `S=84 R=1 Q=1`. The first three coordinates now pass.

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

All seven tests compile. The four tests in `AdversarialRegressionTest`, `DescendantVariableOwnerRegressionTest`, and `NestedVariableUseRegressionTest` pass; only `PhasePlanRegressionTest` fails with the predicted false phase-cycle rejection.

Run the generated reproductions with:

```bash
./gradlew :semantics:resolver25Stress -Presolver25StressSeed=250025
```

The full task remains intentionally red until Resolver25 is corrected. At seed `250025`, the null-intermediate, error-intermediate, descendant-list-owner, and nested-variable-use profiles pass with the default 10,000-case total budget. `RESOLVER25_STRESS_CASES` is the total budget and must be a multiple of 50; the default gives each profile 2,000 cases.

## Next Work

Fix the false phase-cycle failure next. Treat its static and generated regressions as the minimum correctness boundary; the directed profile must finish with nonzero shape, query-activation, runtime-activation, and completion counts.
