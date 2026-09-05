# Query Planning Model

`qplan` is a compiling Kotlin model of Viaduct field resolution. It is used to state resolver algorithms precisely, compare execution structures, test their behavior over generated worlds, and support formal arguments about selected parts of query execution.

Every maintained qplan resolver now uses the aligned engine carrier model, including qplan's validating `EngineObjectData.Sync` implementation. Resolver26 is the primary algorithm and eventual implementation blueprint. [`handoff.md`](./handoff.md) records the current state and scope boundaries.

Qplan models resolver object fragments and independently resolved Query-rooted fragments. It also models source-sensitive ownership for argumentless fields: a field with a standard resolver is dynamically passive when an ancestor resolver output supplies it and otherwise remains active. Support for these capabilities varies by resolver version; [`resolver-versions.md`](./resolver-versions.md) and [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) contain the maintained capability matrix.

The longer-term `viaduct.engine.runtime.execution2` goal is query execution only. Its stated boundary excludes mutations, subscriptions, custom scalars, resolver query fragments and `fromQueryField` variables, EOD aliases, and asynchronous EOD variants. Qplan's current query-fragment model is intentionally broader than that prospective integration boundary. The future goal supplies context for qplan but is not an instruction to design or implement `execution2`.

## Documentation Map

- [`handoff.md`](./handoff.md) records the current implementation state, carrier boundaries, validation evidence, and longer-term context.
- [`design-principles.md`](./design-principles.md) states durable modeling and resolver-design principles.
- [`research-evidence.md`](./research-evidence.md) preserves findings, correctness obligations, hard cases, acceptance cases, prior art, and source provenance behind those principles.
- [`resolver-versions.md`](./resolver-versions.md) explains why every maintained resolver exists and how earlier versions help simplify or debug Resolver26 work.
- [`context-params.md`](./context-params.md) defines the canonical `Assumptions` context-parameter conventions used by model and semantics APIs.
- [`viaduct-execution.md`](./viaduct-execution.md) describes the idealized source-world execution model that qplan represents.
- [`examples.md`](./examples.md) gives complete GraphQL examples of demand closure, output projection, and the cross-occurrence ordering that prevents the depth-first resolvers from supporting `@parent`.
- [`resolver-test-dsl.md`](./resolver-test-dsl.md) defines the schema-embedded deterministic resolver-world DSL.
- [`execution/README.md`](./execution/README.md) describes GraphQL execution, executor-backed feature tests, current limitations, and the next integration slices.
- [`from-object-field-census.md`](./from-object-field-census.md) preserves a dated production-shape census used to choose representative provider-path fixtures.
- [`maintainer-guide.md`](./maintainer-guide.md) contains the practical testing, replay, debugging, and investigation workflow.
- [`claims.md`](./claims.md) indexes scoped propositions; `arguments/` contains their supporting reasoning.
- [`tla/README.md`](./tla/README.md) defines the machine-checked TLA+ baseline and its refinement boundary.

## Projects

- [`model`](./model/guidelines.md) defines semantic carriers, construction rules, equality, and factory-established invariants.
- [`semantics`](./semantics/README.md) defines transformations, correctness judgments, resolver implementations, and test contracts.
- [`arbitrary`](./arbitrary/README.md) generates canonical schemas, resolver registries, and operations for property testing.
- [`execution`](./execution/README.md) executes queries through Resolver26 and provides the Engine API executor feature-test adapter.

The nearest `AGENTS.md` is an annotated index to the documents relevant to work in that directory.
