# Qplan Handoff

## Precedence

Follow the current explicit prompt first, then this handoff. The immediate work is in qplan. Do not turn a qplan refactor into `execution2` design or implementation unless the prompt explicitly asks for that work.

## Immediate Objective

The response-key materialization checkpoint is complete, including removal of the retired deterministic materialized-field string address and its compatibility scaffolding. The active work is the staged engine-value and engine-result carrier migration described below. Do not change OER identity or resolver scheduling, and do not begin the `EngineObjectData.Sync` migration until the three carrier phases are complete and the current prompt explicitly requests that later work.

`Value.ObjectFields` is `Map<String, Value.Output?>`. Passive source and resolver-produced objects use canonical argumentless field names. Resolver inputs materialized from object fragments use GraphQL response keys, including aliases. Those strings never identify OER cells.

`MaterializeSelection` represents one alias-preserving source field occurrence, and `MaterializeSelectionForest.collect(concreteType)` filters applicability before grouping solely by response key. Co-applicable members must have one syntactically compatible concrete field and open argument tuple before binding; their nested source occurrences are concatenated for collection at the concrete child OER. `ObjectMaterializeSelection` represents the resulting group. Mutually exclusive alternatives remain separate source occurrences until concrete filtering. `constructionSelections()` recursively erases only response keys and preserves every ordinary construction occurrence.

Variable-free aliases share ordinary construction keys and do not require selection stamps. Open response groups acquire group-specific occurrence identity during Resolver26 registry instantiation. Resolver object fragments remain resolver-local fixed input selections and never acquire client or closed demand.

Materialization reproduces construction's exact grounded OER key directly. Resolver26 grounds with resolver-owned bindings and then localizes through the concrete child/list path. Resolver25 and the shared resolvers use a response-preserving view paired with their historical `stampVars(path)` construction view. No occurrence-to-ground-key index exists or is required.

## Longer-Term Context

The longer-term target is a `viaduct.engine.runtime.execution2` query executor based on Resolver26. That target is limited to queries and `EngineObjectData.Sync`. It excludes mutations, subscriptions, custom scalars, query fragments and `fromQueryField` variables, EOD aliases, and asynchronous EOD variants.

Those exclusions guide compatibility choices during qplan alignment. They do not make production routing, engine lifecycle, mutation ordering, response serialization, or other `execution2` concerns part of the current task.

## Current Carrier Boundary

The qplan `model` project already depends on `viaduct.engine.api`, but qplan source does not yet use `EngineObjectData`. Qplan currently represents resolved objects as typed `ObjectEngineResult` values keyed by `ObjectEngineResult.GroundKey`; their `EngineResultCell`s carry independent write-once value and access-result promises and use reference identity for result occurrences.

Resolver inputs and outputs currently use `Value`, while completed fields use the `Any`-represented checked `EngineResult` union. `EngineInputData` is likewise an `Any`-represented checked semantic union aligned with production Viaduct: GraphQL String, ID, and enum values are all Kotlin strings and schema context disambiguates them. `EngineResult` instead uses `Schema.ID` and canonical `Schema.EnumValue` values for ID and enum results. The retired nominal result-scalar hierarchy and qplan-owned `EngineIDData` and `EngineEnumValueData` wrappers no longer exist. The remaining active migration replaces every resolver-output `Value` variant except `Value.Object`.

The complete key hierarchy belongs to `ObjectEngineResult`: `Key`, `VariableKey`, `ObjectKey`, and `GroundKey`. `Value.Object` stores only strings. Its construction-time `FieldValue` entries retain a schema field long enough to validate a value and then forget that metadata. Object equality and schema conformance therefore reason over already-validated string-keyed content rather than reconstructing OER identity.

Resolver-visible materialization collects applicable object-fragment occurrences by response key, reproduces each group's exact grounded and localized OER key, reads that cell, and writes the value under the response key. Distinct aliases may therefore read the same OER cell while remaining distinct resolver-input entries. Passive object construction rejects argument-bearing fields, and `ResolveValue` joins passive demand to source values by canonical field name before writing exact ground keys into the OER.

Schema alignment is deliberately independent of this carrier migration. GraphQL-Java remains the source-facing schema representation, while qplan's lowered `Schema` is used exclusively for field-resolution reasoning. Do not transform the GraphQL-Java schema or make tenant-visible APIs expose bridge coordinates.

`EngineObjectData.Sync` is name-keyed, untyped at the value boundary, and partial. `get` distinguishes an unset selection by throwing, `getOrNull` tolerates it, and `isPresent` distinguishes absent from present-null without reading the value.

Preserve exact-key validation, occurrence identity, selection-occurrence identity, and the difference between result values and access decisions throughout the carrier migration. The later `EngineObjectData.Sync` change will separately decide which qplan object responsibilities move to the engine API carrier.

Response aliases are materialization facts, not OER identity. They never become exact result-path or OER key components.

## Target Carrier Boundary

`EngineInputData`, `EngineOutputData`, and `EngineResult` are distinct semantic domains represented by Kotlin `Any` typealiases plus explicit conformance relations. Nullable uses of those aliases add GraphQL null; null is not an error representation. Because the aliases erase to the same Kotlin type, domain-specific operations need distinct names and every unchecked `Any` boundary must be followed by validation or schema-directed conversion.

Pre-domain scalar representations are values whose Kotlin types do not themselves belong exclusively to one semantic domain. `Int`, finite `Double`, `Boolean`, and `String` are shared pre-domain representations. Qplan adds structural `Schema.ID` and canonical `Schema.EnumValue` values, but initially admits them only to `EngineResult`.

For current production compatibility, `EngineInputData` and `EngineOutputData` use Kotlin `String` for GraphQL String, ID, and enum values. `EngineResult` instead uses `String` for GraphQL String, `Schema.ID` for ID, and the canonical `Schema.EnumValue` owned by the expected `Schema.EnumType` for enum values. Publishing output data into a result recursively wraps ID and enum strings using the expected schema type; materializing a result into resolver-visible output recursively unwraps those values to strings. `EngineIDData` and `EngineEnumValueData` do not survive the migration.

`Schema.EnumType` owns a name-keyed map of canonical `Schema.EnumValue` definitions rather than a set of string names. An enum value carries its name and containing enum definition and uses schema-canonical identity. `Schema.ID` wraps one string and uses structural equality; it is a runtime scalar representation nested under `Schema` for shared vocabulary rather than a schema definition.

`EngineResult` semantically contains the pre-domain scalar representations admitted for results, `ObjectEngineResult`, `ListEngineResult`, and the singleton `ErrorEngineResult`. `ErrorEngineResult` conforms at every GraphQL output type but remains a distinct sentinel rather than impersonating every result shape. `EngineOutputData` separately contains production-compatible scalar representations, ordinary recursive output lists, the temporarily retained `Value.Object`, and a distinct singleton `EngineErrorData`. No narrower output-data category includes `EngineErrorData`. `EngineInputData` has no error sentinel; argument evaluation uses a separate argument-resolution error outcome outside the input-data domain.

Each `EngineResultCell` has independent write-once value and access-result slots. The value slot contains `EngineResult?` and is constrained by the field or list-element schema type. The non-null access-result slot contains `EngineResult` and is constrained to either a Boolean result or `ErrorEngineResult`. Direct writes, deferred promise completion, factories, and recursive conformance checks enforce the same slot-specific rules. The API should call the second slot an access result rather than imply that every completion is an acceptance Boolean.

`ListEngineResult` remains a nominal wrapper because it retains an element type witness and result cells. It implements `List<EngineResultCell>` through delegation to a private list, preserving Kotlin collection operations while preventing callers from downcasting the wrapper to a mutable list. A builder may avoid snapshotting only by transferring exclusive ownership of its mutable backing list and retaining no path that can mutate it after publication. List-result equality remains structural over the element type expression and positional cell identities and therefore requires explicit `equals` and `hashCode` behavior beyond interface delegation.

`ObjectEngineResult`, its exact-key hierarchy, and OER occurrence identity remain nominal model structures during these phases. Replacing the internal object-data carrier with `EngineObjectData.Sync` is explicitly later work.

## Resolver State

[`resolver-versions.md`](./resolver-versions.md) defines the maintained portfolio. Resolver03 is the compact semantic reference, Resolver08 makes scheduling explicit, and Resolver23 is the structured-coroutine baseline. Comparing Resolver26 with those versions is the preferred way to separate essential semantics from incidental machinery.

Resolver25's current implementation is the source of truth. It uses one orchestrator per OER occurrence, conservative field-level potential demand, independently grounded actual-demand activations, per-ground-key merging, key-local launch sealing, output availability, and fringe-installation latches. Its previous `StrictPreparationPlan` and per-field `sealedDemand` architecture is retired; documentation must not preserve that static preparation graph as intended behavior.

Resolver26 synchronously closes one OER's symbolic demand, assigns occurrence identity to variable-bearing resolver-fragment selections, prepares bindings, materializes passive values, grounds and reserves every active key, launches field-resolution tasks under one request scope, and freezes the OER key set. It has no re-orchestration loop or late-demand registry.

Runtime `FromObjectField` execution is present in Resolver25 and Resolver26. Documentation or tests that describe it as metadata-only are stale.

## Migration Sequence

1. Documentation checkpoint: record the target carrier domains, production compatibility boundary, invariants, and phased sequence; review and commit that documentation before implementation.
2. Complete, phase A engine input data: engine input data uses production-compatible strings for GraphQL String, ID, and enum values; `EngineIDData` and `EngineEnumValueData` are deleted; and input coercion, schema decoding, witnesses, and tests use schema context to interpret overloaded strings.
3. Complete, phase B engine results: `EngineResult` is an `Any` typealias admitting pre-domain scalar values, structural `Schema.ID`, canonical `Schema.EnumValue`, `ObjectEngineResult`, delegated `ListEngineResult`, and `ErrorEngineResult`. `EngineResultCell` has result-typed value and access-result slots; object/list factories, direct writes, deferred completions, and recursive conformance enforce the field-type or Boolean-or-error relation appropriate to each slot. Schema-directed conversions bridge production-compatible input strings and stronger result ID/enum representations.
4. Phase C, resolver output data: introduce `EngineOutputData`, recursive output-list data, and the distinct `EngineErrorData` sentinel; replace every resolver-output `Value` variant except `Value.Object`; replace the argument roles of `Value.Error` with a dedicated argument-resolution error outcome outside `EngineInputData`; make retained object fields contain `EngineOutputData?`; add schema-directed conversions between production-compatible output strings and result-domain `Schema.ID` or `Schema.EnumValue`; and update every maintained resolver, registry operation, fixture, generator, witness, and correctness judgment. Run `./gradlew check`.
5. Documentation reconciliation: update implementation-specific vocabulary and resolver-local documents after each phase so they describe the landed APIs rather than the superseded hierarchy. Run the complete documentation and qplan gate.
6. Later checkpoint, not part of these phases: migrate the retained object-output carrier toward `EngineObjectData.Sync`, preserving OER keys, cells, occurrence identity, response-key materialization, and access-result semantics.

## Backlogged TLA+ Refinement

TLA+ refinement work is explicitly backlogged until the EOD carrier refactor stabilizes. Preserve the existing proof baseline and its stated boundary, but do not make structural extraction, Kotlin refinement, or new variable-aware proofs part of the active EOD migration. [`tla/refinement-backlog.md`](./tla/refinement-backlog.md) is the restart point for that later work.

## Cleanup TODOs

- [x] Replace the public `StampedObjectPathDefinition` and
  `SelectionStampedVariableDefinition` data classes with public abstractions backed by private
  implementations and controlled factories. Their equality is structural; provider and occurrence
  semantics are unchanged.

## Validation

Run `./gradlew check` from `qplan` for the ordinary model, arbitrary, semantics, and documentation gates. Use [`maintainer-guide.md`](./maintainer-guide.md) for replay and investigation, [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) for the capability matrix, and Resolver26's local testing guide for stress and concurrency work.
