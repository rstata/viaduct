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
- `GeneratedResolverContract.kt` applies the same four feature scopes to
  ordinary generated-world correctness and permutation properties. Each
  generated profile owns its configuration, sample budget, and executable
  feature-activation guards. It also defines a full-feature interaction-depth
  contract for resolvers supporting nonempty fragments.
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

Each ordinary generated profile runs 150 cases and checks whole-result
correctness plus permutation-equivalent query results. The empty-fragment and
node profiles apply to Resolver01-03; the two nonempty-fragment profiles apply
to Resolver02-03. Their guards distinguish generation from activation: for
example, the node profile requires an actual fixture-lowered loader
application and activated mixed node/non-node topology, while the
`FromArgument` profile requires an application of a variable-bearing resolver.

Resolver02 and Resolver03 additionally run a 300-case full-feature interaction
profile. It preserves broad pressure across nodes, mixed node/non-node schemas,
nonempty fragments, arguments, and `FromArgument` variables while requiring
those features to be coactivated by generated queries.

Resolver03's opt-in stress suite remains separate by design. Its trace oracle,
mutation, list-deepening, selective-demand, and witness tests also remain
separate because they make stronger Resolver03-specific claims than ordinary
feature acceptance.

## Reproducing Generated Failures

**Agents should start with coordinate replay for a per-case failure. Do not
brute-force the full test class when the failure reports concrete `S`, `R`, and
`Q` coordinates.** Use the dedicated Gradle task, the concrete test class named
by JUnit, and the reported profile, seed, and coordinates:

```shell
./gradlew :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=2:2:1
```

This generates through schema iteration `S` to preserve the random stream, but
executes only the selected registry/query case. It suppresses whole-profile
aggregate guards because a single case cannot establish sample-budget or
feature-activation claims.

`ResolverTestReplayTest` guards this contract directly: across 32 seeded,
randomly sized products, it selects a random case from each full run and
requires coordinate replay to reproduce the same schema, registry recipe,
query, feature metadata, and generated resolver plans.

Every generated resolver profile runs with an explicit `Long` seed. A
per-case failure reports:

- The contract profile.
- The seed.
- One-based `S`, `R`, and `Q` coordinates for the schema iteration, registry
  within that schema, and query within that registry.
- The complete generated schema, registry, and query.

Aggregate activation and sample-budget failures report the same profile and
seed with `S=all R=all Q=all`, because the whole generated run is the failing
observation rather than one case.

The profile identifiers are part of the replay UI and must remain stable:

| Profile ID | Scope | Concrete resolvers | Normal `S:R:Q` |
| --- | --- | --- | --- |
| `empty-object-fragment` | Empty object fragments, no variables | Resolver01-03 | `10:3:5` (150) |
| `node` | Source-level node resolution | Resolver01-03 | `10:3:5` (150) |
| `object-fragment` | Nonempty object fragments, no variables | Resolver02-03 | `10:3:5` (150) |
| `object-fragment-from-argument` | Nonempty fragments with `FromArgument` | Resolver02-03 | `10:3:5` (150) |
| `feature-interaction` | Full ordinary feature interaction | Resolver02-03 | `20:3:5` (300) |
| `resolver03-construction-witness` | Resolver03 construction witness | Resolver03 | `12:2:4` (96) |

For an aggregate `S=all R=all Q=all` failure, rerun the entire reported profile
with `Case=all` (the default):

```shell
./gradlew :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=all
```

`Case=all` keeps aggregate sample-budget and feature-activation guards active.
For ad hoc investigation, `resolverPropertySize=S:R:Q` replaces the profile's
normal product dimensions and still runs those guards:

```shell
./gradlew :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=empty-object-fragment \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=all \
  -PresolverPropertySize=2:1:2
```

`resolverPropertySize` is valid only with `resolverPropertyCase=all`. Small
products can legitimately fail an activation guard when the generated sample
does not exercise the profile's promised feature; increase a dimension or
choose another seed rather than disabling the guard.

The dedicated task is the normal replay interface. A seed-only full-class run
remains the broad fallback and authoritative reproduction when debugging
cross-profile behavior:

```shell
./gradlew :semantics:test \
  --tests 'semantics.resolver02.ResolverGeneratedTest' \
  -PresolverPropertySeed=424242
```

The equivalent inputs are `RESOLVER_PROPERTY_SEED=<reported-seed>` and
`-Dresolver.property.seed=<reported-seed>`. Gradle forwards the selected seed
to both the resolver-test harness and Kotest, and a seeded test task always
runs even when its previous outputs are up to date. Running the same concrete
class and seed regenerates the same cases and product coordinates.

Resolver03's opt-in stress task retains its separate
`resolver03StressSeed`/`RESOLVER03_STRESS_SEED` interface.

## Adding Tests

Add a scenario to the narrowest existing feature contract when every resolver
claiming that feature must pass it. Create a new feature contract when the
scenario establishes a distinct capability with a different resolver support
matrix. Create a policy mixin when the expectation describes an explicit
implementation choice that cuts across feature scopes.

Keep resolver-specific depth, witness, mutation, and stress tests separate when
they intentionally make a stronger claim than the shared feature contract.
