# Arbitrary Generators

The arbitrary project generates valid GraphQL schemas, resolver registries, and Query selections for property testing qplan resolver algorithms. It is pre-reasoning infrastructure: generated recipes may use ordinary implementation state, but every emitted world crosses the same canonical schema, registry, lowering, and validation boundaries used by static fixtures.

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
    // Run the resolver and judge the completed result.
}
```

`checkResolverTestCases` uses `S` as its outer Kotest iteration count. Each schema sample contains `R` independently generated registries and `Q` independently generated queries, and the runner evaluates their Cartesian product while reusing one canonical world per registry.

## Generated Worlds

`Arb.schema(config)` generates supported GraphQL SDL. `schema.registry(config)` chooses field-resolver coordinates and raw node resolvers, derives output paths and fixed object fragments, and produces deterministic resolver programs. `schema.query(config)` generates valid Query selections against the schema.

Resolver programs may be constant, input-sensitive, argument-sensitive, or sensitive to both. Structured outputs derive bounded occurrence-distinct values from canonical input and argument fingerprints, never from application order or mutable randomness.

Generated node implementations are fixture inputs. Composition retains the generated GraphQL-Java schema for source validation and derives a separate canonical lowered `ViaductSchema` in which `foo: W<T>` is replaced by `foo_V_A_node: W<T_V_A_Bridge>`. Generated resolvers return source-shaped node references; fixture composition lowers them and supplies argumentless `T_V_A_Bridge.node` loaders. Generated non-`Node` abstract types remain disjoint from node-resolved objects.

Resolver dependencies and variable provider/use branches are generated in one acyclic rank order and then validated by canonical registry assembly. Provider paths are inserted into the defining resolver's fixed object fragment before compilation.

Resolver02/03, Resolver07/08, and Resolver22/23 generated profiles exercise `FromArgument`, including paths through nullable input objects. Resolver26 profiles additionally execute `FromObjectField`, including mixed-variable, nested-provider, and late-demand interactions described in [`../semantics/testing-contracts.md`](../semantics/testing-contracts.md). A separate query-fragment profile generates Query-rooted resolver inputs and is enabled only for resolver versions that implement them.

Queries and registries are independently generated from one schema. Query sources are bounded below GraphQL Java's parser limit, and oversized candidates are discarded before becoming test cases.

## Feature Controls

Configuration controls argument count and shape, resolver object and query fragments, variables by source, interfaces, unions, lists, node lowering, selection depth, resolver density, and other size or weighting decisions. Argument-bearing fields may have multiple independently generated arguments. `ResolverVariableSingletonCoercionEnabled` lets list-target variables admit scalar and shallower-list providers through GraphQL singleton coercion, including nested list layers; it defaults off so resolver profiles opt in only after their implementation supports that grounding behavior. Resolver26's generated and stress profiles enable it. Object- and Query-fragment shapes are generated independently through the same root-type-parameterized selection primitive, then variable assignment considers both fragments together so one binding can be consumed by multiple selections in either or both fragments. `FromObjectField` provider paths are generated only in the object fragment, while `FromQueryField` provider paths are generated only in the Query fragment; the fragment consuming either variable does not change its source. `ResolverFromObjectFieldVariablesEnabled` and `ResolverFromQueryFieldVariablesEnabled` admit the respective sources, while both from-field sources share provider-path length, use-depth, passive-use, owner-use, provider-argument, and per-source owner-count controls. Both variable sources can generate literal/symbolic convergence. `ResolverQueryFragmentsEnabled` admits ordinary Query-rooted resolver inputs, while `ResolverQueryFragmentWeight` independently bounds their density; enabling `FromQueryField` may add its required provider selection to an otherwise empty Query fragment. `ParentFieldsEnabled` adds the fixed great-grandparent spine used as a stable activation witness. `RandomParentFieldsEnabled` supplements that spine with one to three independently shaped parent chains of depth one through four, randomly choosing singular, list, and nested-list producers, nullable positions, concrete or union-valued parent targets, scalar siblings, and ordinary generated resolver fragments that may select those parent fields. Each random-parent object has at least one scalar field with two compatible argument positions so multiple variable sources can coexist in one generated resolver input. A sampled subset of those scalar resolvers receives a bounded top-level `parent { __typename }` requirement; when another random fragment selects that resolver beneath its own parent selection, the result is a diagonal parent dependency without replacing either resolver's otherwise random fragment or recursively forcing a large dependency subtree. Internal parent-target unions are not exposed through unrelated ordinary Query fields, so every parent-bearing occurrence has its validated producer ancestry. `SometimesPassiveFieldWeight` optionally lets generated resolver outputs supply argumentless fields that also have standard registered resolvers. It defaults to `0.0` and consumes no additional randomness at that value. The ordinary `sometimes-passive` profile, serialized broad-campaign profiles, and Resolver26 deep stress override it and require both generation and runtime activation evidence. Feature generation does not imply runtime activation; profiles that claim an interaction must record or require evidence that the relevant source-owned occurrence executed without its standard resolver application.

## Generator Configuration Data

`GeneratorConfigData` is a versioned data-class representation of a fully resolved `Config`, built only from primitive maps and range data. It records every supported key, including defaults, so a later default change cannot reinterpret existing data. Conversion back to `Config` rejects unsupported versions, missing or unknown keys, keys in the wrong type group, and values rejected by their `ConfigKey`.

`arbitrary` does not serialize this data or load resources. The property-test launcher layer owns JSON, resource indexes, and round files.

Generated witnesses identify applications by canonical post-lowering field, exact arguments, materialized-input fingerprint, and, where required, result occurrence. Focused selective-demand profiles may capture supplied-demand detail; ordinary stress profiles avoid unnecessary witness cost.

## Failure Replay

Every semantic failure reports the profile, seed, one-based `S:R:Q` coordinate, schema, registry, and query. Replay the exact coordinate through `:semantics:resolverPropertyReplay` before changing generator or resolver code. [`../semantics/testing-contracts.md`](../semantics/testing-contracts.md) defines the stable profile IDs and replay interface.

Classify failures as resolver, generator, oracle, campaign, or resource-envelope behavior before making changes. A generated world that contains a feature but never activates it is a coverage defect, not evidence about that feature.

## Validation

Run generator tests with:

```shell
./gradlew :arbitrary:test
```

Resolver properties live in `semantics` and are included in `./gradlew check`. Deep stress and broad campaigns are opt-in and require explicit seeds.
