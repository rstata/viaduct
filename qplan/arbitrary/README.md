# Arbitrary

This module generates valid GraphQL schemas, resolver registries, and Query fragments for property-testing the qplanning resolver models. Its design and significant portions of its generator infrastructure are copied or adapted from Viaduct's `oss/core/shared/arbitrary` library at `~/ht/projects/viaduct/oss/core/shared/arbitrary`.

The public generators live in the `semantics.arbitrary` package and are exposed as Kotest property-testing `Arb` values. A generated schema is the common input to independent registry and query generators; `Arb.resolverTestBatch` composes them into one schema, `R` registries, and `Q` queries so a property can evaluate the full `R x Q` product.

## Composition

```kotlin
val counts = TestCaseCount(
    schemas = 20,
    registriesPerSchema = 3,
    queriesPerSchema = 5,
)
val config =
    Config.default +
        (ArgumentsEnabled to true) +
        (ResolverFragmentsEnabled to false)

checkResolverTestCases(counts, config) { testWorld, testCase ->
    // Parse testCase.query, run the resolver, and judge correctResolution.
}
```

`checkResolverTestCases` uses `S` as its outer Kotest iteration count. Each sample contains exactly `R` registries and `Q` queries, and the runner evaluates their Cartesian product while reusing one world per registry. `Arb.resolverTestBatch` and `ResolverTestBatch.cases` remain available when a test needs to control execution directly.

## Generators

- `Arb.schema(config)` generates GraphQL SDL in the model's supported domain.
- `schema.registry(config)` chooses source field-resolver coordinates and raw node resolvers, infers each source resolver's output selection paths, generates one constant value for those paths, and optionally generates an acyclic object fragment whose variable providers strictly precede their use branches; generated providers are declared by alias-preserving fragment source and response-key paths, which `TestWorld` compiles to canonical key paths while lowering the result to a field-only registry.
- `schema.query(config)` generates a valid named Query fragment containing literal arguments only.
- `Arb.resolverTestBatch(counts, config)` composes the three generators without coordinating registry choices with query choices.

The configuration includes independent switches for arguments, query fragments, resolver fragments, resolver variables, interfaces, unions, lists, and raw node resolvers, plus weights and size bounds for generated structures and values. Resolver variables are disabled by default so Resolver01-03 remain in their variable-free domains; Resolver04 enables them and generates globally unique variable names, variable-bearing object-fragment arguments, and type-compatible field-relative provider paths that are explicitly inserted into their defining fragments. `MinimumSelectionDepth` creates a concrete object-field backbone and forces each query to select it, while `MaxSelectionDepth` caps the measured field-path depth reported by `ArbitraryQuery.selectionDepth`. Enabling node resolvers registers every generated `Node` implementation, while generated non-`Node` interfaces and unions exclude `Node` objects so fixture lowering never receives a mixed node-resolved and inline possible-type set. Lowering names singular bridges `foo$id` and list-shaped bridges `foo$ids`.

Kotest reports a replay seed for a failing batch. Every individual semantic failure also includes the exact SDL, resolver coordinates with inferred output paths and object-fragment text, and Query fragment.

Resolver03's gated deep stress property runs with `RESOLVER03_STRESS_CASES=10000 RESOLVER03_STRESS_SEED=<long> ./gradlew :semantics:resolver03Stress`. It generates depth-4-to-6 dependency-heavy variable-free worlds and requires exact equality between witnessed application identities and independently reconstructed resolver-bearing OER identities. An identity includes the canonical post-lowering field, exact arguments, and materialized-input fingerprint, keeping value-distinct list occurrences and distinct `foo$id`/`foo$ids` versus `foo` resolver applications separate. Selective-demand fingerprints are captured only by focused witness profiles and are disabled in stress runs. Resolver04's corresponding property runs with `RESOLVER04_STRESS_CASES=10000 RESOLVER04_STRESS_SEED=<long> ./gradlew :semantics:resolver04Stress`. For either task, the case count must be an integer and at least 10,000, and the seed must be a `Long`; Resolver03 requires a multiple of 10 while Resolver04 requires a multiple of 100. Malformed values fail, while ordinary repository validation excludes both dedicated stress tasks. The Resolver04 property counts a variable case only when the resulting OER contains at least one resolved variable binding.
