# Qplan Documentation

- [`README.md`](./README.md) - Start here for qplan's purpose, integration direction, project layout, and documentation map.
- [`handoff.md`](./handoff.md) - Read before acting for the current objective, explicit scope boundaries, open decisions, and next work.
- [`design-principles.md`](./design-principles.md) - Use for durable modeling rules, semantic boundaries, occurrence identity, one-shot correctness, and Engine API alignment principles.
- [`research-evidence.md`](./research-evidence.md) - Use for the evidence, obligations, hard cases, prior art, acceptance cases, and provenance behind the design principles.
- [`maintainer-guide.md`](./maintainer-guide.md) - Use for validation, replay, failure classification, debugging, documentation conventions, and investigation workflow.
- [`resolver-versions.md`](./resolver-versions.md) - Use to understand the maintained resolver grid and choose a simpler comparison implementation.
- [`context-params.md`](./context-params.md) - Read before changing model or semantics APIs that use the canonical `Assumptions` context parameter.
- [`model/guidelines.md`](./model/guidelines.md) - Read before changing semantic carriers, equality, factories, promises, keys, or result structures.
- [`semantics/README.md`](./semantics/README.md) - Read before changing semantic transformations, resolver implementations, or correctness judgments.
- [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) - Read before changing or interpreting resolver tests and generated profiles.
- [`resolver-test-dsl.md`](./resolver-test-dsl.md) - Read before adding schema-embedded deterministic resolver worlds or counterexamples.
- [`arbitrary/README.md`](./arbitrary/README.md) - Read before changing schema, registry, query, or witness generation.
- [`claims.md`](./claims.md) - Use for the index of stable propositions and links to their scoped arguments.
- [`tla/README.md`](./tla/README.md) - Read before changing or citing the machine-checked TLA+ baseline.
- [`semantics/resolver-benchmarks.md`](./semantics/resolver-benchmarks.md) - Read before running, changing, or reporting resolver benchmarks.

When writing resolver-test DSL schemas in tests, documentation, or counterexamples, present them
top-down: start with `extend type Query`, then define the types reached from its fields, followed by
their dependencies.
