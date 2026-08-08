# Query Planning Model Guidance

## Interpretation

Compiling Kotlin is a precise mathematical modeling language for Viaduct field resolution. Declarations denote sets, values, functions, relations, and partial operations; they do not imply JVM execution events, effects, timing, caching, allocation, or complexity. Use mathematical language such as "function application," "yields," and "is related to."

Each reasoning exercise fixes one `Assumptions` and one canonical `Schema`. Schema decoding, GraphQL parsing, registry assembly, dependency injection, and source node lowering are pre-reasoning composition. Trust carrier invariants established by factories and stipulated world inputs.

The repository also contains a scoped machine-checked TLA+ construction calculus. Keep its assumptions and refinement boundary explicit; do not describe it as an unconditional proof of the Kotlin implementation.

## Implementation Discipline

Semantic values are immutable except for two documented monotonic stores: request-local variable bindings and opt-in mutable `EngineResult.Object` cells. Each entry changes only from absent to one validated value. The shared resolver constructor may allocate mutable OERs, publish an exact cell once, and retain mutable child OERs through written parents; do not rewrite cells or introduce unrelated mutable reasoning state.

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

## Validation

Run `./gradlew check` from this directory.
