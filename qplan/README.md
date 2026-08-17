# Query Planning Model

`qplan` is a compiling Kotlin model of Viaduct field resolution. It is used to state resolver algorithms precisely, compare execution structures, test their behavior over generated worlds, and support formal arguments about selected parts of query execution.

The immediate integration direction is to align every maintained qplan resolver with Viaduct engine API carriers, especially `EngineObjectData`, so the model and a future engine implementation can remain close. Resolver26 is the primary algorithm and eventual implementation blueprint. The current work remains in qplan; [`handoff.md`](./handoff.md) records its active scope and priorities.

The longer-term `viaduct.engine.runtime.execution2` goal is query execution only. Its stated boundary excludes mutations, subscriptions, custom scalars, query fragments and `fromQueryField` variables, EOD aliases, and asynchronous EOD variants. That future goal supplies context for the qplan alignment work but is not an instruction to design or implement `execution2`.

## Documentation Map

- [`handoff.md`](./handoff.md) records the current objective, migration boundary, open decisions, and next work.
- [`design-principles.md`](./design-principles.md) states durable modeling and resolver-design principles.
- [`research-evidence.md`](./research-evidence.md) preserves findings, correctness obligations, hard cases, acceptance cases, prior art, and source provenance behind those principles.
- [`resolver-versions.md`](./resolver-versions.md) explains why every maintained resolver exists and how earlier versions help simplify or debug Resolver26 work.
- [`context-params.md`](./context-params.md) defines the canonical `Assumptions` context-parameter conventions used by model and semantics APIs.
- [`viaduct-execution.md`](./viaduct-execution.md) describes the idealized source-world execution model that qplan represents.
- [`examples.md`](./examples.md) gives complete GraphQL examples of demand closure and output projection.
- [`from-object-field-census.md`](./from-object-field-census.md) preserves a dated production-shape census used to choose representative provider-path fixtures.
- [`maintainer-guide.md`](./maintainer-guide.md) contains the practical testing, replay, debugging, and investigation workflow.
- [`claims.md`](./claims.md) indexes scoped propositions; `arguments/` contains their supporting reasoning.
- [`tla/README.md`](./tla/README.md) defines the machine-checked TLA+ baseline and its refinement boundary.

## Projects

- [`model`](./model/guidelines.md) defines semantic carriers, construction rules, equality, and factory-established invariants.
- [`semantics`](./semantics/README.md) defines transformations, correctness judgments, resolver implementations, and test contracts.
- [`arbitrary`](./arbitrary/README.md) generates canonical schemas, resolver registries, and operations for property testing.

The nearest `AGENTS.md` is an annotated index to the documents relevant to work in that directory.
