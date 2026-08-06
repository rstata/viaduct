# Arbitrary Generator Guidance

## Purpose

This project is pre-reasoning property-test infrastructure. It generates valid external GraphQL schemas, executor registries, and Query fragments for testing semantic resolver functions; it does not add semantic carrier types or judgments.

## Construction Boundaries

Generated schema, query, and resolver-fragment text is external input and is validated through the existing GraphQL Java test fixtures. A registry recipe stores schema names, resolver coordinates, required object fragments, inferred output selection paths, and value plans until `ArbitraryRegistry.world` supplies one canonical decoded `Schema`.

Schema and registry assembly may use ordinary generator state and Kotlin collections. Every semantic `Value`, `Value.Key`, `Fragment`, and `Selection` is constructed through its precise model factory against the canonical schema of the generated world.

Source field-resolver sites are chosen before output paths and value plans are derived. Every source field resolver denotes a deterministic, selection-independent function: equal materialized object-fragment values and arguments yield equal complete outputs. Scalar and structured outputs may vary with those inputs. Each generated concrete domain object has a reserved passive `hash: Hash!` field; its bounded recursive value is derived from canonical input and argument fingerprints plus one fixed value-plan salt, so separate plans and list positions can have different structures without consulting application order, OER position, supplied demand, or mutable randomness. The potential output paths recorded by a registry are therefore a bounded envelope rather than a promise that every application supplies every path.

The support type is `type Hash { nested: Hash, hash: Int! }`. A terminal generated Hash object omits nullable `nested`. Ordinary generated selections add only the always-present `hash { hash }` branch, and resolver-fragment and variable-provider generation exclude the synthetic field. This keeps independently generated queries valid while complete non-selective outputs retain occurrence-distinct passive structure. The support type and field are excluded from ordinary schema-feature accounting and resolver-site selection.

When node resolvers are enabled, every generated `Node` implementation receives a raw deterministic node resolver whose value substitutes the input ID and derives its Hash seed from that ID; fixture composition then lowers those raw functions and node-valued source fields into canonical field resolvers and synthetic singular `$id` or list-shaped `$ids` bridge fields.

Generated non-`Node` interfaces and unions contain only non-`Node` objects. This keeps their possible-type sets disjoint from fixture-lowered node outputs because the current lowering rejects abstract outputs that mix node-resolved and inline objects.

Generated object fragments are acyclic by construction and are still checked by the ordinary registry assembly invariant. Resolver dependencies target only lower-ranked coordinates, so arbitrary properties do not exercise worlds conservatively rejected by the coordinate-level cycle check, including syntactic cycles whose exact active occurrences would be acyclic because one edge has error-valued arguments. Passive structural branches precede registered branches, registered branches retain their resolver-coordinate rank, and a generated variable provider branch must have a strictly lower rank than its use branch; ordinary and variable branch edges therefore share one acyclic order. Every generated variable provider path is inserted into its defining resolver's object fragment before the variable use is emitted. Queries and registries are generated independently from their common schema.

Generated query sources and their permutation-equivalent forms are character-bounded below GraphQL Java's parser token limit. An oversized candidate is discarded inside query generation and does not become a resolver test case.

## Validation

Run `./gradlew :arbitrary:test` for generator validity and configuration tests. The resolver property tests in `semantics` parse and flatten each generated query through the test fixture, apply the semantic resolver constructor, and require the result to satisfy `correctResolution`. Resolver04 enables generated field-relative variables and provider paths; resolver01-03 leave that generator feature disabled. The generated Hash depth diversity exercises position-distinct complete list outputs in Resolver01 and Resolver02; `correctResolution` checks each OER occurrence as a positional subset of that complete output without combining demand across list positions. Resolver03's selective generated property also compares its projected OER values against the corresponding complete-output positions.

Generated resolution witnesses count deterministic application identities by canonical post-lowering field, exact arguments, and materialized-input fingerprint. Thus a node-valued source field resolver and its generated node loader remain distinct applications at `foo$id`/`foo$ids` and `foo`. Focused Resolver03 witness profiles opt into compact supplied-demand digests; ordinary properties and stress runs leave demand capture disabled. Coverage specifications that are intentionally outside the current generator model remain present with `@Disabled("not currently worth the effort")` rather than being deleted or left red.
