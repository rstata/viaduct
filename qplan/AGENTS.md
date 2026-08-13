# Query Planning Model Guidance

## Interpretation

Compiling Kotlin is a precise mathematical modeling language for Viaduct field resolution. Declarations denote sets, values, functions, relations, and partial operations; they do not imply JVM execution events, effects, timing, caching, allocation, or complexity. Use mathematical language such as "function application," "yields," and "is related to."

Each reasoning exercise fixes one `Assumptions` and one canonical `Schema`. Schema decoding, GraphQL parsing, registry assembly, dependency injection, and source node lowering are pre-reasoning composition. Trust carrier invariants established by factories and stipulated world inputs.

The repository also contains a scoped machine-checked TLA+ construction calculus. Keep its assumptions and refinement boundary explicit; do not describe it as an unconditional proof of the Kotlin implementation.

## Implementation Discipline

Semantic values are immutable except for documented monotonic stores: request-local variable bindings and opt-in mutable `EngineResult.Object` value, field-check, and type-check promises. Each entry changes only from absent to one immediate or deferred promise, and each deferred promise completes once. The shared resolver constructor may allocate mutable OERs, publish an exact value once, and retain mutable child OERs through written parents; do not replace promises or introduce unrelated mutable reasoning state.

Otherwise use immutable collections and functional transformations in semantic code. Pre-reasoning infrastructure may use ordinary implementation techniques.

Every context-dependent semantic function uses `context(world: Assumptions)`. Access members through `world`; use `world.run` only when a receiver-style body is clearer, declare its return type, and do not use `apply` to produce a modeled result. See [`context-params.md`](./context-params.md).

Compilation and tests are finite consistency evidence, not proof of mathematical claims or completeness.

## Projects

[`model`](./model/AGENTS.md) defines carriers and invariants. [`semantics`](./semantics/AGENTS.md) defines transformations and judgments. [`arbitrary`](./arbitrary/AGENTS.md) is pre-reasoning property-test infrastructure. Follow the nearest guidance file.

## Claims

Record important propositions in [`claims.md`](./claims.md) as stable kebab-case labels with one-sentence statements. Put supporting reasoning in `arguments/<claim-label>.md`, state its assumptions and exclusions, and distinguish proof from finite test evidence. Update a claim and argument together.

## Documentation

Keep each prose paragraph and list item on one physical line. Put durable problem evidence and lessons in [`evergreen.md`](./evergreen.md), current volatile state in [`handoff.md`](./handoff.md), and local implementation rules in `AGENTS.md`.

Document factory-established carrier invariants on the factory using `### Invariant: kebab-case-label`; labels share one namespace with claims and are checked by `checkDocumentationLabels`. Keep KDoc local to the declaration and avoid restating type-established invariants.

## Resolver Examples

Present every concrete resolver/control-flow example as a complete GraphQL schema followed by the triggering query. Order schema types top-down: `Query` first, then each type used by `Query`, then the types used by those types. Put a comment beside every field saying whether it is passive or has a resolver; for every resolver field, state its object fragment, variable definitions and their sources, and the value it returns or produces. For passive fields, state which ancestor resolver produces the value. Use concrete domain names rather than abstract placeholders, and explain the execution only after presenting the complete schema and query.

## Validation

Run `./gradlew check` from this directory.

Resolver26 tests, thread-count configuration, CPU-parallelism probes, and large-campaign guidance are documented in [`testing-resolver26.md`](./semantics/src/main/kotlin/semantics/resolver26/testing-resolver26.md).
