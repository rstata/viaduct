# Testing Contracts

## Purpose

A testing contract is a reusable suite for one resolver capability. The contract owns fixtures, operations, and assertions; a concrete resolver test supplies only the implementation:

```kotlin
interface ResolverContract {
    fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult
}
```

JUnit 5 discovers `@Test` methods inherited from Kotlin interfaces. A concrete test opts into every supported feature contract and policy mixin.

Organize contracts by user-visible semantic capability, not by the resolver that first exposed a bug. Keep exact result shapes, resolver inputs, application counts, defaults, null and error positions, and other regression-sensitive assertions in the shared contract.

## Feature Contracts

Shared contracts live in `src/testFixtures/kotlin/semantics/contract`:

- `EmptyObjectFragmentResolverContract` covers empty object fragments, arguments, `__typename`, list occurrences, interfaces, and concrete implementation defaults.
- `NodeResolverContract` covers source-level node resolution through fixture-lowered `foo_V_A_node` producers and `T_V_A_Bridge.node` loaders.
- `ObjectFragmentResolverContract` covers nonempty object fragments without variables, including response aliases on argumentless and argument-bearing fields, argument-distinct aliases, non-overlapping concrete-type alternatives, transitive and descendant demand, recursive output, defaults, failures, and occurrence identity.
- `ObjectFragmentFromArgumentResolverContract` covers variables bound from resolver arguments, including nested input-object paths, null intermediate traversal, and a transitive chain.
- `QueryFragmentResolverContract` covers independently orchestrated Query-rooted resolver inputs, including response aliases, variables bound from resolver arguments, distinct OER identity for each application, separation from the primary result, transitive query-fragment resolution, and occurrence isolation when the same variable-bearing resolver path appears in the request and one or more query fragments.
- `ObjectFragmentFromObjectPathResolverContract` covers variables bound from exact object-fragment provider paths, including nested paths and scalar-list, null, and error values.
- `SometimesPassiveResolverContract` covers argumentless active fields exceptionally supplied by ancestor resolver outputs. `SometimesPassiveObjectFragmentResolverContract` checks both ownership branches when the standard resolver has input demand, `SometimesPassiveObjectPathResolverContract` preserves a provider path below an ancestor-supplied active field and verifies that binding validation ignores the unbound object-path variables of a skipped standard resolver, and `SometimesPassiveSelectiveResolverContract` witnesses Resolver03, Resolver08, Resolver23, Resolver25, and Resolver26's single selective ancestor application and conservative pre-execution demand.
- The advanced demand contracts cover recursive-key isolation, deferred demand through passive objects and node bridges, nested `FromArgument` and `FromObjectField` uses, recursive lists, and acyclic mixed-variable dependency chains.
- `LateObjectPathDemandResolverContract` covers late symbolic demand across already-published active and passive objects.
- `GeneratedResolverContract.kt` applies those scopes to generated correctness and permutation properties, includes a sometimes-passive profile with separate generation and activation evidence, and adds a full-feature interaction contract.
- `ListPassiveDeepeningGeneratedResolverContract` biases toward list-valued passive fields and verifies exact witnessed applications when resolver input demand deepens those lists.

Current support is:

| Contract | Resolver01/06/21 | Resolver02/07/22 | Resolver03/08/23 | Resolver25/26 |
| --- | --- | --- | --- | --- |
| Empty object fragments | yes | yes | yes | yes |
| Source-level node resolution | yes | yes | yes | yes |
| Nonempty object fragments | no | yes | yes | yes |
| Nonempty fragments with `FromArgument` | no | yes | yes | yes |
| Query fragments | no | yes | yes | Resolver26 |
| Nonempty fragments with `FromObjectField` | no | no | no | yes |
| Advanced `FromArgument` demand | no | yes | yes | yes |
| Advanced `FromObjectField` demand | no | no | no | yes |
| Late symbolic object-path demand | no | no | no | yes |
| List-passive deepening generated coverage | no | no | yes | yes |

Runtime `FromObjectField` binding is supported by Resolver25 and Resolver26. Every resolver that claims a base feature contract inherits its advanced deterministic regressions. Resolver25 and Resolver26 additionally implement late symbolic-demand contracts even though their equal-key identity and late-ancestor-demand policies differ.

## Policy Mixins

Policies describe implementation choices that cut across feature scopes:

- `CompleteResolverOutputPolicyContract` and `SelectiveResolverOutputPolicyContract` check unselected passive fields.
- `CompleteObjectFragmentOutputPolicyContract` and `SelectiveObjectFragmentOutputPolicyContract` check recursive passive subtrees reached while satisfying object-fragment demand.
- `VariableSelectionIdentityResolverContract` distinguishes Resolver25's grounded-key storage from Resolver26's symbolic-key storage. Resolver26 coalesces equal symbolic arguments but keeps different variable instances distinct even when their bindings agree.
- `LateObjectPathDemandResolverContract` separately records whether an ancestor resolver retains an open variable boundary or receives only its passive predecessors; this policy is independent of equal-key identity.
- `CorrectResolutionPostTestPolicy` records results produced through `resolveAndValidate` and validates them in `@AfterEach`.

Resolver01/02/06/07/21/22 use complete-output policies; Resolver03/08/23/25/26 use selective-output policies. Every contract implementation uses post-test `correctResolution` validation.

Sometimes-passive contracts are enabled for Resolver01-03, Resolver06-08, Resolver21-23, Resolver25, and Resolver26. Resolver02-03, Resolver07-08, Resolver22-23, Resolver25, and Resolver26 additionally run the nonempty-standard-object-fragment cases, and Resolver03, Resolver08, Resolver23, Resolver25, and Resolver26 run the selective one-shot witness.

Deferred validation keeps replayed resolver functions from changing fixture application counters before explicit assertions run. Every policy mixin must contain an executable guard.

Extended trace, mutation, witness, list-deepening, selective-demand, and stress tests stay separate from ordinary feature acceptance. Mutation, witness, selective-demand, list-deepening, and deep-stress bodies use shared contracts when their assertions are implementation-independent. Resolver25 and Resolver26 both opt into the shared mutation, construction-witness, and selective-demand-witness contracts; Resolver25's nested-variable-use stress profile and Resolver26's deep stress profile additionally require generated and activated sometimes-passive fields. Lifecycle tracing and multithreaded execution remain implementation-specific.

## Generated Profiles

| Profile ID | Scope | Resolvers | Normal `S:R:Q` |
| --- | --- | --- | --- |
| `empty-object-fragment` | Empty fragments | Resolver01-03, Resolver06-08, Resolver21-23, Resolver25-26 | `10:3:5` |
| `node` | Fixture-lowered nodes | Resolver01-03, Resolver06-08, Resolver21-23, Resolver25-26 | `10:3:5` |
| `sometimes-passive` | Source-owned argumentless active fields | Resolver01-03, Resolver06-08, Resolver21-23, Resolver25-26 | `10:3:5` |
| `object-fragment` | Nonempty fragments | Resolver02-03, Resolver07-08, Resolver22-23, Resolver25-26 | `10:3:5` |
| `object-fragment-from-argument` | `FromArgument` variables, including nested and nullable input paths | Resolver02-03, Resolver07-08, Resolver22-23, Resolver25-26 | `10:3:5` |
| `query-fragment` | Independently orchestrated Query-rooted resolver inputs | Resolver02-03, Resolver07-08, Resolver22-23, Resolver26 | `10:3:5` |
| `object-fragment-from-object-field` | `FromObjectField` variables | Resolver25-26 | `10:3:5` |
| `mixed-variables` | Both variable sources | Resolver25-26 | fixed aggregate corpus |
| `feature-interaction` | Full ordinary interaction | Resolver02-03, Resolver07-08, Resolver22-23, Resolver25-26 | `20:3:5` |
| `resolver03-construction-witness` | Construction witness | Resolver03, Resolver25-26 | `12:2:4` |
| `resolver25-broad-*` | Unfiltered balanced, list-descendant, nullable/error, mixed-variable, and multiple-owner pressure | Resolver25 | opt-in |
| `resolver26-broad-*` | Heterogeneous symbolic-resolution profiles | Resolver26 | opt-in profile-specific products |

Ordinary profiles check whole-result correctness and permutation-equivalent query results. Profile guards distinguish generation from activation; for example, the node profile requires an actual generated bridge `node` loader application, the argument-variable profile requires an application of a variable-bearing resolver, and the sometimes-passive profile requires registered result occurrences whose standard resolvers were not applied. The `mixed-variables` and `sometimes-passive` profiles apply the caller-provided seed as randomized correctness pressure and use fixed generated seed `1` as their aggregate activation corpus, so a valid random batch cannot fail merely because it misses the promised interaction.

The `query-fragment` profile jointly enables variable-bearing object fragments and Query-rooted resolver fragments, and requires generation and activation evidence for both Query fragments and `FromArgument` variables. It omits `exactOrdinaryApplicationCounts` when a resolver policy enables it. That oracle reconstructs applications only from the primary result tree, while query-fragment applications intentionally belong to separate Query OER witnesses.

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

Equivalent seed inputs are `RESOLVER_PROPERTY_SEED` and `-Dresolver.property.seed`. Resolver03, Resolver08, Resolver23, Resolver25, and Resolver26 stress use resolver-specific `<resolver>StressSeed` Gradle properties and `<RESOLVER>_STRESS_SEED` environment variables.

Resolver25 also has independent unfiltered broad profiles. Every generated `S x R x Q` case calls Resolver25 and validates lifecycle events, exact application identities, `correctResolution`, and object-path bindings:

```shell
./gradlew :semantics:resolver25BroadStress \
  -Presolver25BroadStressProfile=mixed-variables \
  -Presolver25BroadStressSeed=424242 \
  -Presolver25BroadStressSize=10:20:50
```

The checked-in campaign distributes persisted seeds across schema breadth, registry diversity, query interactions, and large/deep worlds. Use it for reproducible broad evidence rather than treating one large random product as sufficient coverage.

```shell
./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver25-broad-campaign-v1.json

./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver25-broad-campaign-v1.json \
  1 21 46 81
```

The generic script builds the standalone property-test launcher once and then executes each campaign round in a fresh JVM after Gradle exits. Kotlin expands the serialized campaign and generator resources for both the launcher and the corresponding JUnit exact-coordinate replay.

Replay one failed campaign coordinate through its recorded round and profile:

```shell
./gradlew :semantics:resolver25BroadStressCampaign \
  -Presolver25BroadStressCampaignRound=47 \
  -Presolver25BroadStressCampaignProfile=mixed-variables \
  -PresolverPropertyCase=8:3:41
```

## Resolver26 Broad Campaign

Resolver26's broad tests use five directed distributions: balanced worlds, symbolic list descendants, nullable and error providers, equal grounded arguments from distinct symbolic keys, and multiple object-path owners. Every distribution admits query fragments at bounded density and requires both generated and activated query-fragment evidence. Their structural coverage is classified only from completed OER paths and symbolic keys, resolver-application witnesses, and generated registry metadata. Separate request-local binding validation checks every activated object-path variable. No test observes scheduler events, coroutine ordering, demand phases, or Resolver25 lifecycle concepts.

Every generated case checks exact attempted/resolved/completed accounting, exact application identity counts, `correctResolution`, and independently reconstructed object-path bindings. A profile's aggregate run must also observe its required Resolver26 structural signatures.

The checked-in campaign uses fresh JVM rounds and persisted seeds distributed across schema breadth, registry diversity, query interactions, and large/deep worlds. Large/deep worlds bound generated list fanout so the budget explores depth instead of combinatorial list multiplication. Run persisted rounds with:

```shell
./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json \
  21
```

The campaign script builds the standalone property-test launcher once and invokes the serialized campaign directly; it does not run one Gradle or JUnit invocation per round.

Replay one profile or exact coordinate from that round with:

```shell
./gradlew :semantics:resolver26BroadStressCampaign \
  -Presolver26BroadStressCampaignRound=21 \
  -Presolver26BroadStressCampaignProfile=multiple-owners \
  -PresolverPropertyCase=18:4:1
```

Coordinate replay suppresses aggregate structural-coverage requirements while preserving the recorded profile, seed, and generator dimensions. A failing generated case should be reduced to a small deterministic regression test after determining whether the defect belongs to the generator, an independent oracle, or Resolver26.

## Adding Tests

Add a scenario to the narrowest existing feature contract when every implementation claiming that feature must pass it.

Create a feature contract when the scenario establishes a distinct capability with a different support matrix. Create a policy mixin when it establishes an implementation choice shared across feature scopes.

Keep implementation-specific witness, mutation, depth, and stress tests separate when their assertions intentionally exceed the shared capability.
