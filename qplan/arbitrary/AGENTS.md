# Arbitrary Generator Guidance

## Scope

This project is pre-reasoning property-test infrastructure. It generates external GraphQL schemas, resolver registries, and Query fragments, then constructs semantic values through the canonical world factories.

Schema and registry recipes may use ordinary generator state. Generated resolver programs are deterministic and may be constant, input-sensitive, argument-sensitive, or sensitive to both. Structured outputs derive bounded occurrence-distinct `Hash` values from canonical input and argument fingerprints, never from application order or mutable randomness.

Generated node implementations receive raw deterministic node resolvers; fixture composition lowers node-valued source fields to `foo$bridge` producers and argumentless `T$Bridge.$node` loaders. Generated non-`Node` abstract types remain disjoint from node-resolved objects.

Resolver dependencies and variable provider/use branches are generated in one acyclic rank order and then validated by ordinary registry assembly. Resolver02/03 generated profiles exercise `FromArgument`; variable-enabled fixture tests exercise `FromObjectField` compilation and ordering, but no semantic profile executes `FromObjectField`.

Queries and registries are independently generated from one schema. Query sources are bounded below GraphQL Java's parser limit; oversized candidates are discarded before becoming cases.

## Validation

Run `./gradlew :arbitrary:test` for generator tests. Resolver properties live in `semantics`; use [`../semantics/testing-contracts.md`](../semantics/testing-contracts.md) for profile scope and replay.

Generated witnesses identify applications by canonical post-lowering field, exact arguments, and materialized-input fingerprint. Focused Resolver03 profiles may capture supplied-demand digests; ordinary and stress profiles do not.
