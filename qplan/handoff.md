# Qplan Handoff

## Precedence

Follow the current explicit prompt first, then this handoff. The immediate work is in qplan. Do not turn a qplan refactor into `execution2` design or implementation unless the prompt explicitly asks for that work.

## Immediate Objective

The response-key materialization checkpoint is complete, including removal of the retired deterministic materialized-field string address and its compatibility scaffolding. Do not change OER identity or resolver scheduling, and do not begin the `EngineObjectData.Sync` migration unless the current prompt explicitly requests it.

`Value.ObjectFields` is `Map<String, Value.Output?>`. Passive source and resolver-produced objects use canonical argumentless field names. Resolver inputs materialized from object fragments use GraphQL response keys, including aliases. Those strings never identify OER cells.

`MaterializeSelection` represents one alias-preserving source field occurrence, and `MaterializeSelectionForest.collect(concreteType)` filters applicability before grouping solely by response key. Co-applicable members must have one syntactically compatible concrete field and open argument tuple before binding; their nested source occurrences are concatenated for collection at the concrete child OER. `ObjectMaterializeSelection` represents the resulting group. Mutually exclusive alternatives remain separate source occurrences until concrete filtering. `constructionSelections()` recursively erases only response keys and preserves every ordinary construction occurrence.

Variable-free aliases share ordinary construction keys and do not require selection stamps. Open response groups acquire group-specific occurrence identity during Resolver26 registry instantiation. Resolver object fragments remain resolver-local fixed input selections and never acquire client or closed demand.

Materialization reproduces construction's exact grounded OER key directly. Resolver26 grounds with resolver-owned bindings and then localizes through the concrete child/list path. Resolver25 and the shared resolvers use a response-preserving view paired with their historical `stampVars(path)` construction view. No occurrence-to-ground-key index exists or is required.

## Longer-Term Context

The longer-term target is a `viaduct.engine.runtime.execution2` query executor based on Resolver26. That target is limited to queries and `EngineObjectData.Sync`. It excludes mutations, subscriptions, custom scalars, query fragments and `fromQueryField` variables, EOD aliases, and asynchronous EOD variants.

Those exclusions guide compatibility choices during qplan alignment. They do not make production routing, engine lifecycle, mutation ordering, response serialization, or other `execution2` concerns part of the current task.

## Current Carrier Boundary

The qplan `model` project already depends on `viaduct.engine.api`, but qplan source does not yet use `EngineObjectData`. Qplan currently represents resolved objects as typed `ObjectEngineResult` values keyed by `ObjectEngineResult.GroundKey`; their cells carry independent write-once value and `accessAccepted` promises and use reference identity for result occurrences.

The resolver-value and engine-result domains are now distinct at every GraphQL output shape. Resolver inputs and outputs use `Value`, while completed fields use `IntEngineResult`, `FloatEngineResult`, `StringEngineResult`, `BooleanEngineResult`, `IDEngineResult`, `EnumEngineResult`, `ObjectEngineResult`, `ListEngineResult`, or `ErrorEngineResult`. `toEngineResult` and `toValue` mark crossings for simple values. This temporary parallel hierarchy keeps result semantics independent while the resolver-value side moves toward the engine API's future `EngineValueData` boundary.

The complete key hierarchy belongs to `ObjectEngineResult`: `Key`, `VariableKey`, `ObjectKey`, and `GroundKey`. `Value.Object` stores only strings. Its construction-time `FieldValue` entries retain a schema field long enough to validate a value and then forget that metadata. Object equality and schema conformance therefore reason over already-validated string-keyed content rather than reconstructing OER identity.

Resolver-visible materialization collects applicable object-fragment occurrences by response key, reproduces each group's exact grounded and localized OER key, reads that cell, and writes the value under the response key. Distinct aliases may therefore read the same OER cell while remaining distinct resolver-input entries. Passive object construction rejects argument-bearing fields, and `ResolveValue` joins passive demand to source values by canonical field name before writing exact ground keys into the OER.

Schema alignment is deliberately independent of this carrier migration. GraphQL-Java remains the source-facing schema representation, while qplan's lowered `Schema` is used exclusively for field-resolution reasoning. Do not transform the GraphQL-Java schema or make tenant-visible APIs expose bridge coordinates.

`EngineObjectData.Sync` is name-keyed, untyped at the value boundary, and partial. `get` distinguishes an unset selection by throwing, `getOrNull` tolerates it, and `isPresent` distinguishes absent from present-null without reading the value.

The refactor must decide which qplan responsibilities move directly to engine API carriers and which remain model structure around them. Preserve exact-key validation, occurrence identity, selection-occurrence identity, and the difference between result values and access decisions even when the underlying object storage changes.

Response aliases are materialization facts, not OER identity. They never become exact result-path or OER key components.

## Resolver State

[`resolver-versions.md`](./resolver-versions.md) defines the maintained portfolio. Resolver03 is the compact semantic reference, Resolver08 makes scheduling explicit, and Resolver23 is the structured-coroutine baseline. Comparing Resolver26 with those versions is the preferred way to separate essential semantics from incidental machinery.

Resolver25's current implementation is the source of truth. It uses one orchestrator per OER occurrence, conservative field-level potential demand, independently grounded actual-demand activations, per-ground-key merging, key-local launch sealing, output availability, and fringe-installation latches. Its previous `StrictPreparationPlan` and per-field `sealedDemand` architecture is retired; documentation must not preserve that static preparation graph as intended behavior.

Resolver26 synchronously closes one OER's symbolic demand, assigns occurrence identity to variable-bearing resolver-fragment selections, prepares bindings, materializes passive values, grounds and reserves every active key, launches field-resolution tasks under one request scope, and freezes the OER key set. It has no re-orchestration loop or late-demand registry.

Runtime `FromObjectField` execution is present in Resolver25 and Resolver26. Documentation or tests that describe it as metadata-only are stale.

## Migration Sequence

1. Complete: add source-occurrence `MaterializeSelection`, concrete-type response-key collection, and focused executable examples.
2. Complete: make `FieldResolver` retain the unstamped materialize template and instantiate paired materialization and construction views.
3. Complete: have Resolver26 reproduce construction's exact OER keys directly while preserving its ground-then-localize order.
4. Complete: give Resolver25 and the shared resolvers paired response-preserving views derived from their historical `stampVars(path)` construction semantics.
5. Complete: switch every maintained resolver to response-key materialization without an occurrence-key index.
6. Complete: remove retired checkpoint scaffolding and update remaining evidence and durable documentation.
7. Complete: run the complete qplan gate before beginning any `EngineObjectData.Sync` carrier work.

## Backlogged TLA+ Refinement

TLA+ refinement work is explicitly backlogged until the EOD carrier refactor stabilizes. Preserve the existing proof baseline and its stated boundary, but do not make structural extraction, Kotlin refinement, or new variable-aware proofs part of the active EOD migration. [`tla/refinement-backlog.md`](./tla/refinement-backlog.md) is the restart point for that later work.

## Cleanup TODOs

- [ ] Replace the public `StampedObjectPathDefinition` and `SelectionStampedVariableDefinition` data classes with public abstractions backed by private implementations and controlled factories. Define their equality contracts explicitly and update model, resolver, fixture, and oracle call sites without changing provider or occurrence semantics.

## Validation

Run `./gradlew check` from `qplan` for the ordinary model, arbitrary, semantics, and documentation gates. Use [`maintainer-guide.md`](./maintainer-guide.md) for replay and investigation, [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) for the capability matrix, and Resolver26's local testing guide for stress and concurrency work.
