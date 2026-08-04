# Arbitrary Generator Guidance

## Purpose

This project is pre-reasoning property-test infrastructure. It generates valid external GraphQL schemas, executor registries, and Query fragments for testing semantic resolver functions; it does not add semantic carrier types or judgments.

## Construction Boundaries

Generated schema, query, and resolver-fragment text is external input and is validated through the existing GraphQL Java test fixtures. A registry recipe stores schema names, resolver coordinates, required object fragments, inferred output selection paths, and value plans until `ArbitraryRegistry.world` supplies one canonical decoded `Schema`.

Schema and registry assembly may use ordinary generator state and Kotlin collections. Every semantic `Value`, `Value.Key`, `Fragment`, and `Selection` is constructed through its precise model factory against the canonical schema of the generated world.

Source field-resolver sites are chosen before output selection sets and values are derived. Each source field resolver closes over one constant selection-independent value that inhabits its inferred output selection set. When node resolvers are enabled, every generated `Node` implementation receives a raw node resolver whose value substitutes the input ID; fixture composition then lowers those raw functions and node-valued source fields into canonical field resolvers and synthetic `$id` bridge fields.

Generated non-`Node` interfaces and unions contain only non-`Node` objects. This keeps their possible-type sets disjoint from fixture-lowered node outputs because the current lowering rejects abstract outputs that mix node-resolved and inline objects.

Generated object fragments are acyclic by construction and are still checked by the ordinary registry assembly invariant. Resolver dependencies target only lower-ranked coordinates, so arbitrary properties do not exercise worlds conservatively rejected by the coordinate-level cycle check, including syntactic cycles whose exact active occurrences would be acyclic because one edge has error-valued arguments. Every generated variable provider path is inserted into its defining resolver's object fragment before the variable use is emitted. Queries and registries are generated independently from their common schema.

## Validation

Run `./gradlew :arbitrary:test` for generator validity and configuration tests. The resolver01, resolver02, resolver03, and resolver04 property tests in `semantics` are the green end-to-end judgments: they parse and flatten each generated query through the test fixture, apply the semantic resolver constructor, and require the result to satisfy `correctResolution`. Resolver04 enables generated field-relative variables and provider paths; resolver01-03 leave that generator feature disabled.
