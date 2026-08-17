# Qplan Handoff

## Precedence

Follow the current explicit prompt first, then this handoff. The immediate work is in qplan. Do not turn a qplan refactor into `execution2` design or implementation unless the prompt explicitly asks for that work.

## Immediate Objective

Refactor every maintained qplan resolver to use Viaduct engine API carriers where they express the same semantic facts as qplan's current types. `EngineObjectData.Sync` is the first important boundary because the intended integration domain uses already-resolved synchronous partial object data and must distinguish an absent selection from a present null value.

The migration applies to Resolver01-03, Resolver06-08, Resolver21-23, Resolver25, and Resolver26. Earlier versions remain the semantic and execution-structure comparison grid; they must not be left on a separate qplan-only carrier model while only Resolver26 moves forward.

Resolver26 is the primary algorithm and eventual implementation blueprint. The purpose of the qplan refactor is to reduce the distance between a formally reasoned model and a future Viaduct implementation, not to maintain two independently shaped designs.

## Longer-Term Context

The longer-term target is a `viaduct.engine.runtime.execution2` query executor based on Resolver26. That target is limited to queries and `EngineObjectData.Sync`. It excludes mutations, subscriptions, custom scalars, query fragments and `fromQueryField` variables, EOD aliases, and asynchronous EOD variants.

Those exclusions guide compatibility choices during qplan alignment. They do not make production routing, engine lifecycle, mutation ordering, response serialization, or other `execution2` concerns part of the current task.

## Current Carrier Boundary

The qplan `model` project already depends on `viaduct.engine.api`, but qplan source does not yet use `EngineObjectData`. Qplan currently represents resolved objects as typed `EngineResult.Object` values keyed by `Value.GroundKey`; their cells carry independent write-once value and `accessAccepted` promises and use reference identity for result occurrences.

`EngineObjectData.Sync` is name-keyed, untyped at the value boundary, and partial. `get` distinguishes an unset selection by throwing, `getOrNull` tolerates it, and `isPresent` distinguishes absent from present-null without reading the value.

The refactor must decide which qplan responsibilities move directly to engine API carriers and which remain model structure around them. Preserve exact-key validation, occurrence identity, selection-occurrence identity, and the difference between result values and access decisions even when the underlying object storage changes.

Response aliases are outside the intended integration scope. Qplan may therefore use canonical field names at the EOD boundary; it does not need to model alias-keyed input EODs for this work.

## Resolver State

[`resolver-versions.md`](./resolver-versions.md) defines the maintained portfolio. Resolver03 is the compact semantic reference, Resolver08 makes scheduling explicit, and Resolver23 is the structured-coroutine baseline. Comparing Resolver26 with those versions is the preferred way to separate essential semantics from incidental machinery.

Resolver25's current implementation is the source of truth. It uses one orchestrator per OER occurrence, conservative field-level potential demand, independently grounded actual-demand activations, per-ground-key merging, key-local launch sealing, output availability, and fringe-installation latches. Its previous `StrictPreparationPlan` and per-field `sealedDemand` architecture is retired; documentation must not preserve that static preparation graph as intended behavior.

Resolver26 synchronously closes one OER's symbolic demand, assigns opaque occurrence identity to variable-bearing resolver-fragment selections, prepares bindings, materializes passive values, grounds and reserves every active key, launches field-resolution tasks under one request scope, and freezes the OER key set. It has no re-orchestration loop or late-demand registry.

Runtime `FromObjectField` execution is present in Resolver25 and Resolver26. Documentation or tests that describe it as metadata-only are stale.

## Migration Sequence

1. Define the exact correspondence between qplan result carriers and `EngineObjectData.Sync`, including absent, present-null, error, nested object, list, and access-decision behavior.
2. Introduce the shared model boundary first, with focused carrier tests that make the correspondence executable.
3. Migrate the recursive Resolver01-03 progression and its contracts.
4. Carry the same boundary through Resolver06-08 and Resolver21-23 without introducing resolver-specific adapter models.
5. Migrate Resolver25 and Resolver26 while preserving their distinct identity and demand policies.
6. Update arbitrary generation, witnesses, correctness judgments, and examples to the resulting shared vocabulary.
7. Keep the complete ordinary test matrix green at each shared step; use Resolver03/08/23 comparisons to localize semantic regressions before diagnosing advanced resolver behavior.

## Backlogged TLA+ Refinement

TLA+ refinement work is explicitly backlogged until the EOD carrier refactor stabilizes. Preserve the existing proof baseline and its stated boundary, but do not make structural extraction, Kotlin refinement, or new variable-aware proofs part of the active EOD migration. [`tla/refinement-backlog.md`](./tla/refinement-backlog.md) is the restart point for that later work.

## Cleanup TODOs

- [ ] Replace the public `StampedObjectPathDefinition` and `SelectionStampedVariableDefinition` data classes with public abstractions backed by private implementations and controlled factories. Define their equality contracts explicitly and update model, resolver, fixture, and oracle call sites without changing provider or occurrence semantics.

## Open Design Questions

- Does `EngineObjectData.Sync` become the object result carrier itself, or does qplan retain a typed occurrence wrapper around it?
- Where should schema conformance and exact `GroundKey` validation live once field storage is name-keyed?
- How should cell occurrence identity and write-once promise ownership be represented without relying on the current `EngineResult.Object` implementation?
- Should `accessAccepted` remain a separate qplan cell fact, or map to an existing engine API concept outside EOD?
- Which conversions belong to the model artifact and which are test-fixture or future integration adapters?
- How should the TLA+ extraction boundary name the aligned carriers without claiming a refinement that has not been proved?

## Validation

Run `./gradlew check` from `qplan` for the ordinary model, arbitrary, semantics, and documentation gates. Use [`maintainer-guide.md`](./maintainer-guide.md) for replay and investigation, [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) for the capability matrix, and Resolver26's local testing guide for stress and concurrency work.
