# Qplan Handoff

## Precedence

Follow the current explicit prompt first, then this handoff. This document records qplan's current state and scope boundaries; it does not assign an immediate objective. Do not turn qplan work into `execution2` design or implementation unless the prompt explicitly asks for that work.

## Current State

The response-key materialization checkpoint and all carrier phases are complete. Qplan uses its own validating `EngineObjectData.Sync` implementation throughout resolver and semantics APIs; the former `Value.Object` carrier is gone. The former `Value` and `OpenValue` containers are also gone: argument tuples and expressions now use the `Arguments` model described below.

The `__typename` lowering checkpoint is complete. The unchanged source GraphQL schema retains GraphQL's system `__typename`, while the lowered qplan schema contains the synthetic interface `V_A_AllSourceObjects { V_A_typename: String! }`, an owner-specific ordinary `V_A_typename` field on every lowered source object and source interface, no typename field on unions or synthetic node bridges, and every lowered source object in `V_A_AllSourceObjects.possibleTypes`. All lowered definitions use the reserved `V_A` namespace. External operation translation recursively erases `__typename` selections but preserves selected composite parents with empty lowered subselections; GraphQL Java's system data fetcher completes client typename fields from the concrete `ObjectEngineResult.type`. Internal resolver object fragments lower object- and interface-scoped typename to the owner field, union-scoped typename to `V_A_AllSourceObjects.V_A_typename`, and Node-valued paths through the existing bridge payload. Each concrete typename field has an ordinary generated constant resolver with no arguments, object-fragment demand, variables, or dependency edges. Resolver invocation no longer synthesizes typename recursively, the root EOD is empty, resolver outputs retain no discriminator, and correct-resolution reasoning relies on ordinary resolver conformance rather than a distinguished typename predicate.

EOD selections contain `EngineOutputData?`. Passive source and resolver-produced objects use canonical argumentless field names. Resolver inputs materialized from object fragments use GraphQL response keys, including aliases. Those strings never identify OER cells.

`MaterializeSelection` represents one alias-preserving source field occurrence, and `MaterializeSelectionForest.collect(concreteType)` filters applicability before grouping solely by response key. Co-applicable members must have one syntactically compatible concrete field and open argument tuple before binding; their nested source occurrences are concatenated for collection at the concrete child OER. `ObjectMaterializeSelection` represents the resulting group. Mutually exclusive alternatives remain separate source occurrences until concrete filtering. `constructionSelections()` recursively erases only response keys and preserves every ordinary construction occurrence.

Variable-free aliases share ordinary construction keys and do not require selection stamps. Open response groups acquire group-specific occurrence identity during Resolver26 registry instantiation. Resolver object fragments remain resolver-local fixed input selections and never acquire client or closed demand.

Materialization reproduces construction's exact grounded OER key directly. Resolver26 grounds with resolver-owned bindings and then localizes through the concrete child/list path. Resolver25 and the shared resolvers use a response-preserving view paired with their historical `stampVars(path)` construction view. No occurrence-to-ground-key index exists or is required.

## Longer-Term Context

The longer-term target is a `viaduct.engine.runtime.execution2` query executor based on Resolver26. That target is limited to queries and `EngineObjectData.Sync`. It excludes mutations, subscriptions, custom scalars, query fragments and `fromQueryField` variables, EOD aliases, and asynchronous EOD variants.

Those exclusions guide compatibility choices in qplan. They do not make production routing, engine lifecycle, mutation ordering, response serialization, or other `execution2` concerns part of qplan's current state.

## Carrier Boundary

The qplan `model` project depends on `viaduct.engine.api` and now contains a qplan-owned `EngineObjectData.Sync` implementation. Qplan still represents resolved objects as typed `ObjectEngineResult` values keyed by `ObjectEngineResult.GroundKey`; their `EngineResultCell`s carry independent write-once value and access-result promises and use reference identity for result occurrences.

Resolver outputs use the `Any`-represented checked `EngineOutputData` union, while completed fields use the distinct `Any`-represented checked `EngineResult` union. Object output values and resolver inputs use qplan's `EngineObjectData.Sync` implementation, with validating factories for canonical passive fields and alias-preserving materialized selections. The implementation snapshots its selection map, preserves absent, present-null, and present-error states, and uses production `UnsetFieldException` for strict reads. `EngineInputData` is likewise an `Any`-represented checked semantic union aligned with production Viaduct: GraphQL String, ID, and enum values are all Kotlin strings and schema context disambiguates them. `EngineOutputData` uses the same overloaded strings, ordinary recursive lists, `EngineObjectData.Sync`, and `EngineErrorData`. `EngineResult` instead uses `EngineIDResult` and canonical `Schema.EnumValue` values for ID and enum results. The retired nominal scalar/list hierarchy and qplan-owned `EngineIDData` and `EngineEnumValueData` wrappers no longer exist. Argument-expression failure uses `ArgumentResolutionError` and remains outside `EngineInputData`.

The complete key hierarchy belongs to `ObjectEngineResult`: `Key`, `VariableKey`, `ObjectKey`, and `GroundKey`. EOD stores only string selections. Construction-time `EngineObjectDataEntry` values retain a schema field long enough to validate a value and then forget that metadata. EOD equality and schema conformance therefore reason over already-validated string-keyed content rather than reconstructing OER identity.

Resolver-visible materialization collects applicable object-fragment occurrences by response key, reproduces each group's exact grounded and localized OER key, reads that cell, and writes the value under the response key. Distinct aliases may therefore read the same OER cell while remaining distinct resolver-input entries. Passive object construction rejects argument-bearing fields, and `ResolveValue` joins passive demand to source values by canonical field name before writing exact ground keys into the OER.

GraphQL-Java remains the source-facing schema representation, while qplan's lowered `Schema` is used exclusively for field-resolution reasoning. The retained source `GraphQLSchema` is unchanged and never contains synthetic bridge or typename-lowering definitions. Fixture decoding and lowering share one `GraphQLTypeRelations` instance built from that source schema; qplan does not define a parallel type-relation API. Output fields directly own their `args` collections, and each canonical `Schema.FieldArg` points back to its owning field; qplan has no synthetic field-argument definition object. `Schema.CompositeTypeDef.possibleObjectTypes` remains transitional model data: source composite types are populated from the shared relation helper, while the synthetic `V_A_AllSourceObjects` contains every source-derived object type and excludes node-bridge objects. Each lowered `Schema.Object` separately owns one canonical opaque generated `GraphQLObjectType` integration witness containing its lowered field surface; source objects include `V_A_typename`, while node-bridge objects contain only `id` and `node`. Access that witness only through `gjDef`; do not inspect it for model reasoning or expose synthetic coordinates through tenant-visible APIs.

`EngineObjectData.Sync` is name-keyed, untyped at the value boundary, and partial. `get` distinguishes an unset selection by throwing, `getOrNull` tolerates it, and `isPresent` distinguishes absent from present-null without reading the value. Qplan's implementation validates values against qplan's canonical schema during construction, retains that canonical `Schema.Object` for context-free semantic access, and uses its canonical opaque `GraphQLObjectType` as the production EOD type. Synthetic lowered bridge definitions are generated during schema composition.

Argument tuples use the broad `Arguments` category: `Arguments.Resolved` is the successful ground tuple passed to resolvers, `Arguments.Error` is the collapsed tuple-level failure, `Arguments.Template` is the resolver-registry form, and `Arguments.Variable` identifies template or occurrence-stamped variables. Recursive argument expressions use a model-internal natural union rather than a public wrapper; ordinary input data, lists, and input-object maps remain in their natural Kotlin representations, while `ArgumentResolutionError` remains outside `EngineInputData`. Optional fully coerced schema defaults use `CoercedDefaultValue`.

Response aliases are materialization facts, not OER identity. They never become exact result-path or OER key components.

## Carrier Domain Details

`EngineInputData`, `EngineOutputData`, and `EngineResult` are distinct semantic domains represented by Kotlin `Any` typealiases plus explicit conformance relations. Nullable uses of those aliases add GraphQL null; null is not an error representation. Because the aliases erase to the same Kotlin type, domain-specific operations have distinct names and every unchecked `Any` boundary is followed by validation or schema-directed conversion.

Pre-domain scalar representations are values whose Kotlin types do not themselves belong exclusively to one semantic domain. `Int`, finite `Double`, `Boolean`, and `String` are shared pre-domain representations. Qplan adds structural `EngineIDResult` and canonical `Schema.EnumValue` values, which it admits only to `EngineResult`.

For current production compatibility, `EngineInputData` and `EngineOutputData` use Kotlin `String` for GraphQL String, ID, and enum values. `EngineResult` instead uses `String` for GraphQL String, `EngineIDResult` for ID, and the canonical `Schema.EnumValue` owned by the expected `Schema.Enum` for enum values. Publishing output data into a result recursively wraps ID and enum strings using the expected schema type; materializing a result into resolver-visible output recursively unwraps those values to strings. `EngineIDData` and `EngineEnumValueData` no longer exist.

`Schema.Enum` owns a collection of canonical `Schema.EnumValue` definitions and exposes nullable lookup through `value(name)`. An enum value carries its name and containing enum definition and uses schema-canonical identity. `EngineIDResult` wraps one string and uses structural equality; it is a runtime scalar representation rather than a schema definition.

`EngineResult` semantically contains the pre-domain scalar representations admitted for results, `ObjectEngineResult`, `ListEngineResult`, and the singleton `ErrorEngineResult`. `ErrorEngineResult` conforms at every GraphQL output type but remains a distinct sentinel rather than impersonating every result shape. `EngineOutputData` separately contains production-compatible scalar representations, ordinary recursive output lists, `EngineObjectData.Sync`, and a distinct singleton `EngineErrorData`. No narrower output-data category includes `EngineErrorData`. `EngineInputData` has no error sentinel; argument evaluation uses a separate argument-resolution error outcome outside the input-data domain.

Each `EngineResultCell` has independent write-once value and access-result slots. The value slot contains `EngineResult?` and is constrained by the field or list-element schema type. The non-null access-result slot contains `EngineResult` and is constrained to either a Boolean result or `ErrorEngineResult`. Direct writes, deferred promise completion, factories, and recursive conformance checks enforce the same slot-specific rules. The API calls the second slot an access result rather than implying that every completion is an acceptance Boolean.

`ListEngineResult` remains a nominal wrapper because it retains an element type witness and result cells. It implements `List<EngineResultCell>` through delegation to a private list, preserving Kotlin collection operations while preventing callers from downcasting the wrapper to a mutable list. A builder may avoid snapshotting only by transferring exclusive ownership of its mutable backing list and retaining no path that can mutate it after publication. List-result equality remains structural over the element type expression and positional cell identities and therefore requires explicit `equals` and `hashCode` behavior beyond interface delegation.

`ObjectEngineResult`, its exact-key hierarchy, and OER occurrence identity remain nominal model structures distinct from the name-keyed EOD carrier.

## Resolver State

[`resolver-versions.md`](./resolver-versions.md) defines the maintained portfolio. Resolver03 is the compact semantic reference, Resolver08 makes scheduling explicit, and Resolver23 is the structured-coroutine baseline. Comparing Resolver26 with those versions is the preferred way to separate essential semantics from incidental machinery.

Resolver25's current implementation is the source of truth. It uses one orchestrator per OER occurrence, conservative field-level potential demand, independently grounded actual-demand activations, per-ground-key merging, key-local launch sealing, output availability, and fringe-installation latches. Its previous `StrictPreparationPlan` and per-field `sealedDemand` architecture is retired; documentation must not preserve that static preparation graph as intended behavior.

Resolver26 synchronously closes one OER's symbolic demand, assigns occurrence identity to variable-bearing resolver-fragment selections, prepares bindings, materializes passive values, grounds and reserves every active key, launches field-resolution tasks under one request scope, and freezes the OER key set. It has no re-orchestration loop or late-demand registry.

Runtime `FromObjectField` execution is present in Resolver25 and Resolver26. Documentation or tests that describe it as metadata-only are stale.

## Completed Carrier History

1. Complete, documentation checkpoint: the carrier domains, production compatibility boundary, invariants, and phased sequence were recorded, reviewed, and committed before implementation.
2. Complete, phase A engine input data: engine input data uses production-compatible strings for GraphQL String, ID, and enum values; `EngineIDData` and `EngineEnumValueData` are deleted; and input coercion, schema decoding, witnesses, and tests use schema context to interpret overloaded strings.
3. Complete, phase B engine results: `EngineResult` is an `Any` typealias admitting pre-domain scalar values, structural `EngineIDResult`, canonical `Schema.EnumValue`, `ObjectEngineResult`, delegated `ListEngineResult`, and `ErrorEngineResult`. `EngineResultCell` has result-typed value and access-result slots; object/list factories, direct writes, deferred completions, and recursive conformance enforce the field-type or Boolean-or-error relation appropriate to each slot. Schema-directed conversions bridge production-compatible input strings and stronger result ID/enum representations.
4. Complete, phase C resolver output data: `EngineOutputData`, recursive output-list data, and the distinct `EngineErrorData` sentinel replace every resolver-output `Value` variant except the then-retained `Value.Object`; `ArgumentResolutionError` supplies the separate argument-error outcome; object fields contain `EngineOutputData?`; and schema-directed conversions bridge overloaded output strings with result-domain `EngineIDResult` and `Schema.EnumValue`.
5. Complete, documentation reconciliation: implementation-specific vocabulary and resolver-local documents describe the landed APIs rather than the superseded hierarchy.
6. Complete, EOD carrier migration: qplan owns a validating `EngineObjectData.Sync` implementation with canonical passive-field and alias-preserving construction paths. Every lowered object type owns a canonical opaque GraphQL-Java witness, including generated witnesses for synthetic bridge types, and all former `Value.Object` call sites use EOD without changing OER keys, cells, occurrence identity, response-key materialization, or access-result semantics.
7. Complete, argument-model rationalization: the `Value` and `OpenValue` containers are deleted; `Arguments.Resolved`, `Arguments.Error`, `Arguments.Template`, and `Arguments.Variable` name the public argument categories; recursive expressions use an internal natural union; and optional fully coerced schema defaults use `CoercedDefaultValue`.

## Backlogged TLA+ Refinement

TLA+ refinement is a separate backlog with no implied ordering against current qplan work. Preserve the existing proof baseline and its stated boundary. [`tla/refinement-backlog.md`](./tla/refinement-backlog.md) records the refinement gaps and restart point.

## Completed Cleanup

The public `StampedObjectPathDefinition` and `SelectionStampedVariableDefinition` data classes were replaced with public abstractions backed by private implementations and controlled factories. Their equality is structural; provider and occurrence semantics are unchanged.

## Validation

The completed carrier and argument changes passed `./gradlew check`. The changed-file FQN audit also passed. The stress suite passed with seed `424242`: Resolver03, Resolver08, Resolver23, Resolver25, and Resolver26 each passed 10,000 deep cases, while Resolver25 and Resolver26 each passed 10,000 broad cases, for 70,000 total cases.

For future maintenance, `./gradlew check` from `qplan` runs the ordinary model, arbitrary, semantics, and documentation gates. Use [`maintainer-guide.md`](./maintainer-guide.md) for replay and investigation, [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) for the capability matrix, and Resolver26's local testing guide for stress and concurrency work.
