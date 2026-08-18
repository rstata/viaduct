# Qplan Handoff

## Precedence

Follow the current explicit prompt first, then this handoff. The immediate work is in qplan. Do not turn a qplan refactor into `execution2` design or implementation unless the prompt explicitly asks for that work.

## Immediate Objective

Introduce `MaterializeSelection` and `MaterializeSelectionForest` so resolver object fragments retain GraphQL response keys independently from ordinary construction selections. Settle their source-occurrence versus collected-group representation with executable alias, duplicate-response-key, mutually exclusive type-condition, open-argument, nested-list, and lowered-Node examples before fixing the public factory.

The string-key carrier checkpoint is complete. `Value.ObjectFields` is `Map<String, Value.Output?>`. Passive source and resolver-produced objects use canonical argumentless field names. Materialized argument-bearing fields use the private deterministic `GroundKey.materializedFieldKey()` address, which ignores occurrence stamps while preserving visible field-and-ground-argument equality. Materialization and construction still consume ordinary `SelectionForest`; aliases are not preserved yet.

The next response-key work must retain one explicit lossless construction view of each materialize forest. Resolver object fragments remain resolver-local fixed input selections and never acquire client or closed demand. Synthetic `FromObjectField` provider markers belong only to the construction view.

Do not begin the `EngineObjectData.Sync` migration in the same change. The response-key endpoint should remain on qplan `Value` and typed `ObjectEngineResult` carriers so the later EOD comparison starts from a stable name-keyed value domain.

## Longer-Term Context

The longer-term target is a `viaduct.engine.runtime.execution2` query executor based on Resolver26. That target is limited to queries and `EngineObjectData.Sync`. It excludes mutations, subscriptions, custom scalars, query fragments and `fromQueryField` variables, EOD aliases, and asynchronous EOD variants.

Those exclusions guide compatibility choices during qplan alignment. They do not make production routing, engine lifecycle, mutation ordering, response serialization, or other `execution2` concerns part of the current task.

## Current Carrier Boundary

The qplan `model` project already depends on `viaduct.engine.api`, but qplan source does not yet use `EngineObjectData`. Qplan currently represents resolved objects as typed `ObjectEngineResult` values keyed by `ObjectEngineResult.GroundKey`; their cells carry independent write-once value and `accessAccepted` promises and use reference identity for result occurrences.

The resolver-value and engine-result domains are now distinct at every GraphQL output shape. Resolver inputs and outputs use `Value`, while completed fields use `IntEngineResult`, `FloatEngineResult`, `StringEngineResult`, `BooleanEngineResult`, `IDEngineResult`, `EnumEngineResult`, `ObjectEngineResult`, `ListEngineResult`, or `ErrorEngineResult`. `toEngineResult` and `toValue` mark crossings for simple values. This temporary parallel hierarchy keeps result semantics independent while the resolver-value side moves toward the engine API's future `EngineValueData` boundary.

The complete key hierarchy belongs to `ObjectEngineResult`: `Key`, `VariableKey`, `ObjectKey`, and `GroundKey`. `Value.Object` stores only strings. Its construction-time `FieldValue` entries retain a schema field long enough to validate a value and then forget that metadata. Generic object union and schema conformance therefore reason over already-validated string-keyed content rather than reconstructing OER identity.

At the current checkpoint, resolver-visible materialization uses canonical field names for argumentless fields and deterministic private materialized-field addresses for argument-bearing fields. Equal visible keys with different occurrence stamps union under one string. Passive object construction rejects argument-bearing fields, and `ResolveValue` joins passive demand to source values by canonical field name before writing exact ground keys into the OER.

Schema alignment is deliberately independent of this carrier migration. GraphQL-Java remains the source-facing schema representation, while qplan's lowered `Schema` is used exclusively for field-resolution reasoning. Do not transform the GraphQL-Java schema or make tenant-visible APIs expose bridge coordinates.

`EngineObjectData.Sync` is name-keyed, untyped at the value boundary, and partial. `get` distinguishes an unset selection by throwing, `getOrNull` tolerates it, and `isPresent` distinguishes absent from present-null without reading the value.

The refactor must decide which qplan responsibilities move directly to engine API carriers and which remain model structure around them. Preserve exact-key validation, occurrence identity, selection-occurrence identity, and the difference between result values and access decisions even when the underlying object storage changes.

Response aliases are materialization facts, not OER identity. The next phase must preserve aliases from resolver object fragments and eventually replace temporary materialized-field addresses with GraphQL response keys. Aliases must never become exact result-path or OER key components.

## Resolver State

[`resolver-versions.md`](./resolver-versions.md) defines the maintained portfolio. Resolver03 is the compact semantic reference, Resolver08 makes scheduling explicit, and Resolver23 is the structured-coroutine baseline. Comparing Resolver26 with those versions is the preferred way to separate essential semantics from incidental machinery.

Resolver25's current implementation is the source of truth. It uses one orchestrator per OER occurrence, conservative field-level potential demand, independently grounded actual-demand activations, per-ground-key merging, key-local launch sealing, output availability, and fringe-installation latches. Its previous `StrictPreparationPlan` and per-field `sealedDemand` architecture is retired; documentation must not preserve that static preparation graph as intended behavior.

Resolver26 synchronously closes one OER's symbolic demand, assigns occurrence identity to variable-bearing resolver-fragment selections, prepares bindings, materializes passive values, grounds and reserves every active key, launches field-resolution tasks under one request scope, and freezes the OER key set. It has no re-orchestration loop or late-demand registry.

Runtime `FromObjectField` execution is present in Resolver25 and Resolver26. Documentation or tests that describe it as metadata-only are stale.

## Migration Sequence

1. Add `MaterializeSelection` and concrete-type response-key collection with focused executable examples.
2. Make `FieldResolver` privately retain the unstamped materialize template and instantiate paired materialization and construction views from one resolver occurrence stamp.
3. Add an awaitable OER-owned `(field, occurrence stamp) -> GroundKey` index, with declaration and one-writer publication.
4. Migrate every maintained resolver to publish that index while preserving its existing scheduling and identity policy.
5. Switch shared and Resolver26 materialization to response-key collection and exact indexed OER lookup.
6. Update resolver observations, witnesses, arbitrary generation, examples, and design documents for aliases and duplicate response-key groups.
7. Run the complete qplan gate before beginning any `EngineObjectData.Sync` carrier work.

## Backlogged TLA+ Refinement

TLA+ refinement work is explicitly backlogged until the EOD carrier refactor stabilizes. Preserve the existing proof baseline and its stated boundary, but do not make structural extraction, Kotlin refinement, or new variable-aware proofs part of the active EOD migration. [`tla/refinement-backlog.md`](./tla/refinement-backlog.md) is the restart point for that later work.

## Cleanup TODOs

- [ ] Replace the public `StampedObjectPathDefinition` and `SelectionStampedVariableDefinition` data classes with public abstractions backed by private implementations and controlled factories. Define their equality contracts explicitly and update model, resolver, fixture, and oracle call sites without changing provider or occurrence semantics.

## Open Design Questions

- Does one `MaterializeSelection` represent a source occurrence or a collected response-key group?
- How does collection represent mutually exclusive type-conditioned alternatives?
- What carrier owns response-group occurrence IDs and source-key compatibility facts?
- Does resolver-fragment instantiation return a paired view or a materialize forest with one construction-view operation?
- What planning operation declares the awaitable occurrence-to-ground-key lookup?
- Do all variable-free response groups remain unstamped while sharing ordinary OER cells?

## Validation

Run `./gradlew check` from `qplan` for the ordinary model, arbitrary, semantics, and documentation gates. Use [`maintainer-guide.md`](./maintainer-guide.md) for replay and investigation, [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) for the capability matrix, and Resolver26's local testing guide for stress and concurrency work.
