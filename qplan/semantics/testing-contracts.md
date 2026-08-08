# Testing Contracts

## Idea

A testing contract is a reusable set of tests for one capability of a resolver.
The contract owns its fixtures, operations, and assertions, while the concrete
test class supplies only the resolver under test:

```kotlin
interface ResolverContract {
    fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object
}
```

JUnit 5 discovers `@Test` methods inherited from Kotlin interfaces. A concrete
resolver test opts into the contracts that describe its supported feature
scope and implements `resolve`. This keeps a scenario and all of its precise
assertions in one place while running it unchanged against every applicable
resolver.

Contracts are organized by user-visible semantic capability, not by the
resolver that first motivated a regression test. A contract should therefore
answer "which feature is exercised?" rather than "which resolver is being
tested?" Fixture-lowered node loaders are an internal implementation detail and
do not define a fragment feature scope.

Assertions specific to a fixture remain in the shared contract. This includes
exact result shapes, resolver-input shapes, application counts, null and error
positions, concrete defaults, and other regression-sensitive details. Moving a
test into a contract must not weaken those assertions to a common denominator.

## Policy Mixins

Some expected behavior is not a feature supported by a resolver but a policy
chosen by its implementation. These expectations are represented by separate
contract interfaces that a concrete test mixes in alongside its feature
contracts.

The output-policy mixins distinguish resolvers that consume complete resolver
outputs from those that selectively project outputs to demand:

- `CompleteResolverOutputPolicyContract` and
  `SelectiveResolverOutputPolicyContract` check unselected passive fields.
- `CompleteObjectFragmentOutputPolicyContract` and
  `SelectiveObjectFragmentOutputPolicyContract` check recursive passive
  subtrees exposed while satisfying object-fragment demand.

This makes the implementation policy part of the concrete test class's type
instead of passing booleans or expected-output parameters into otherwise
shared tests.

`CorrectResolutionPostTestPolicy` is a lifecycle policy. Calls through
`resolveAndValidate` record each result produced by a contract test. An
inherited `@AfterEach` hook then checks every recorded result with
`correctResolution`. Validation is deliberately deferred until after the test
body so replaying resolver functions cannot contaminate the fixture's
application counters before their explicit assertions run.

A policy mixin should provide an executable guard: if the implementation stops
satisfying the policy, an inherited assertion fails. A manual before/after
audit is useful validation evidence, but it is not itself a policy mixin.

## Current Contracts

The shared static contracts live in
`src/testFixtures/kotlin/semantics/contract`:

- `EmptyObjectFragmentResolverContract` covers user-declared resolvers with
  empty object fragments, including `__typename`, list-position differences,
  interface dispatch, arguments, and concrete implementation defaults.
- `NodeResolverContract` covers source-level node resolution through
  fixture-lowered loaders, including nested passive nodes and
  argument-bearing abstract node lists.
- `ObjectFragmentResolverContract` covers nonempty object fragments without
  variables. Its cases exercise transitive and descendant demand, recursive
  output, defaults, nulls and errors, argument errors, and occurrence-distinct
  list behavior.
- `ObjectFragmentFromArgumentResolverContract` covers nonempty object
  fragments containing variables bound from resolver arguments, including a
  transitive variable chain.
- `ResolverOutputPolicyContract.kt` contains the complete and selective output
  policy mixins.
- `PostTestValidationPolicy.kt` contains the reusable whole-result correctness
  policy.

The concrete classes in `src/test/kotlin/semantics/resolver01`,
`resolver02`, and `resolver03` compose those interfaces as follows:

| Contract or policy | Resolver01 | Resolver02 | Resolver03 |
| --- | --- | --- | --- |
| Empty object fragments | yes | yes | yes |
| Source-level node resolution | yes | yes | yes |
| Nonempty object fragments | no | yes | yes |
| Nonempty fragments with `FromArgument` | no | yes | yes |
| Complete output policies | yes | yes | no |
| Selective output policies | no | no | yes |
| `correctResolution` post-validation | yes | yes | yes |

The ordinary generated properties are currently separate resolver-specific
tests, and Resolver03's opt-in stress suite remains separate by design. Future
generated contracts should use the same feature-scope composition while
preserving profile activation assertions, sample strength, shrinking, replay,
and resolver-specific depth or witness checks.

## Adding Tests

Add a scenario to the narrowest existing feature contract when every resolver
claiming that feature must pass it. Create a new feature contract when the
scenario establishes a distinct capability with a different resolver support
matrix. Create a policy mixin when the expectation describes an explicit
implementation choice that cuts across feature scopes.

Keep resolver-specific depth, witness, mutation, and stress tests separate when
they intentionally make a stronger claim than the shared feature contract.
