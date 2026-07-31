# Arbitrary Generator Guidance

## Purpose

This project is pre-reasoning property-test infrastructure. It generates valid external GraphQL schemas, executor registries, and Query fragments for testing semantic resolver functions; it does not add semantic carrier types or judgments.

## Construction Boundaries

Generated schema, query, and resolver-fragment text is external input and is validated through the existing GraphQL Java test fixtures. A registry recipe stores schema names, resolver coordinates, required object fragments, inferred output selection paths, and value plans until `ArbitraryRegistry.world` supplies one canonical decoded `Schema`.

Schema and registry assembly may use ordinary generator state and Kotlin collections. Every semantic `Value`, `Value.Key`, `Fragment`, and `Selection` is constructed through its precise model factory against the canonical schema of the generated world.

Field-resolver sites are chosen before output selection sets and values are derived. Each field resolver closes over one constant selection-independent value that inhabits its inferred output selection set. Node resolver values additionally substitute the input ID to satisfy the model's node-resolver invariant.

Generated object fragments are acyclic by construction and are still checked by the ordinary registry assembly invariant. Queries and registries are generated independently from their common schema.

## Validation

Run `./gradlew :arbitrary:test` for generator validity and configuration tests. The resolver01 and resolver02 property tests in `semantics` are the green end-to-end judgments: they parse and flatten each generated query through the test fixture, apply the semantic resolver constructor, and require the result to satisfy `correctResolution`. Resolver03 copies the Resolver02 property corpus but is intentionally red until selective transitive demand can be aggregated before resolver application.
