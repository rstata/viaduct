# Testing Contracts

## Purpose

A testing contract is a reusable suite for one resolver capability. The contract owns fixtures, operations, and assertions; a concrete resolver test supplies only the implementation:

```kotlin
interface ResolverContract {
    fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object
}
```

JUnit 5 discovers `@Test` methods inherited from Kotlin interfaces. A concrete test opts into every supported feature contract and policy mixin.

Organize contracts by user-visible semantic capability, not by the resolver that first exposed a bug. Keep exact result shapes, resolver inputs, application counts, defaults, null and error positions, and other regression-sensitive assertions in the shared contract.

## Feature Contracts

Shared contracts live in `src/testFixtures/kotlin/semantics/contract`:

- `EmptyObjectFragmentResolverContract` covers empty object fragments, arguments, `__typename`, list occurrences, interfaces, and concrete implementation defaults.
- `NodeResolverContract` covers source-level node resolution through fixture-lowered bridge producers and `$node` loaders.
- `ObjectFragmentResolverContract` covers nonempty object fragments without variables, including transitive and descendant demand, recursive output, defaults, failures, and occurrence identity.
- `ObjectFragmentFromArgumentResolverContract` covers variables bound from resolver arguments, including a transitive chain.
- `GeneratedResolverContract.kt` applies those scopes to generated correctness and permutation properties and adds a full-feature interaction contract.

Current support is:

| Contract | Resolver01/06/21 | Resolver02/07/22 | Resolver03/08/09/23 |
| --- | --- | --- | --- |
| Empty object fragments | yes | yes | yes |
| Source-level node resolution | yes | yes | yes |
| Nonempty object fragments | no | yes | yes |
| Nonempty fragments with `FromArgument` | no | yes | yes |

Runtime `FromObjectField` binding is not supported by Resolver01-09.

## Policy Mixins

Policies describe implementation choices that cut across feature scopes:

- `CompleteResolverOutputPolicyContract` and `SelectiveResolverOutputPolicyContract` check unselected passive fields.
- `CompleteObjectFragmentOutputPolicyContract` and `SelectiveObjectFragmentOutputPolicyContract` check recursive passive subtrees reached while satisfying object-fragment demand.
- `CorrectResolutionPostTestPolicy` records results produced through `resolveAndValidate` and validates them in `@AfterEach`.

Resolver01/02/06/07/21/22 use complete-output policies; Resolver03/08/09/23 use selective-output policies. Every contract implementation uses post-test `correctResolution` validation.

Deferred validation keeps replayed resolver functions from changing fixture application counters before explicit assertions run. Every policy mixin must contain an executable guard.

Extended trace, mutation, witness, list-deepening, selective-demand, readiness, and stress tests stay separate from ordinary feature acceptance. Mutation, witness, selective-demand, and deep-stress bodies use shared contracts when their assertions are implementation-independent.

## Generated Profiles

| Profile ID | Scope | Resolvers | Normal `S:R:Q` |
| --- | --- | --- | --- |
| `empty-object-fragment` | Empty fragments | Resolver01-03, Resolver06-09, Resolver21-23 | `10:3:5` |
| `node` | Fixture-lowered nodes | Resolver01-03, Resolver06-09, Resolver21-23 | `10:3:5` |
| `object-fragment` | Nonempty fragments | Resolver02-03, Resolver07-09, Resolver22-23 | `10:3:5` |
| `object-fragment-from-argument` | `FromArgument` variables | Resolver02-03, Resolver07-09, Resolver22-23 | `10:3:5` |
| `feature-interaction` | Full ordinary interaction | Resolver02-03, Resolver07-09, Resolver22-23 | `20:3:5` |
| `resolver03-construction-witness` | Construction witness | Resolver03, Resolver09 | `12:2:4` |

Ordinary profiles check whole-result correctness and permutation-equivalent query results. Profile guards distinguish generation from activation; for example, the node profile requires an actual generated `$node` loader application, and the argument-variable profile requires an application of a variable-bearing resolver. The `mixed-variables` profile applies the caller-provided seed as randomized correctness pressure and uses fixed generated seed `1` as its aggregate generation/coactivation corpus, so a valid random batch cannot fail merely because it samples only one variable kind.

Profile IDs are part of the replay interface and must remain stable.

## Replaying Failures

For a failure reporting concrete `S`, `R`, and `Q`, replay that coordinate:

```shell
./gradlew :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=2:2:1
```

Coordinate replay regenerates through schema iteration `S` to preserve the random stream, but executes only the selected case and suppresses whole-profile sample and activation guards.

For an aggregate `S=all R=all Q=all` failure, replay the full profile:

```shell
./gradlew :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=all
```

`Case=all` retains aggregate guards. `-PresolverPropertySize=S:R:Q` may override profile dimensions only with `Case=all`; a small product can legitimately miss the promised feature and fail activation.

Every failure reports its profile, seed, coordinates, schema, registry, and query. `ResolverTestReplayTest` verifies that coordinate replay reproduces the generated inputs and metadata.

For cross-profile debugging, run the concrete class with only the seed:

```shell
./gradlew :semantics:test \
  --tests 'semantics.resolver02.ResolverGeneratedTest' \
  -PresolverPropertySeed=424242
```

Equivalent seed inputs are `RESOLVER_PROPERTY_SEED` and `-Dresolver.property.seed`. Resolver03, Resolver08, Resolver09, Resolver10, and Resolver23 stress use resolver-specific `<resolver>StressSeed` Gradle properties and `<RESOLVER>_STRESS_SEED` environment variables.

## Adding Tests

Add a scenario to the narrowest existing feature contract when every implementation claiming that feature must pass it.

Create a feature contract when the scenario establishes a distinct capability with a different support matrix. Create a policy mixin when it establishes an implementation choice shared across feature scopes.

Keep implementation-specific witness, mutation, depth, and stress tests separate when their assertions intentionally exceed the shared capability.
