# Mutable OER Handoff

## Purpose

This document records the incremental plan for making `EngineResult.Object` monotonically mutable. It supersedes the parts of [Future Demand-Availability Worklist Executor](./execution-handoff.md) that require `EngineResult.Object` itself to remain immutable and require a separate `PartialObject` solely to obtain write-once cells. It does not otherwise replace that document's demand-collection, readiness, one-shot application, or schedule-independence requirements.

The immediate motivation is to make an OER usable as the stable result object populated by a future reactive executor. Each exact field cell begins absent, may be written once, and can never be replaced. A parent cell may contain a child OER before that child's cells are available, allowing descendants to complete without immutable ancestor reconstruction.

## Current State

`EngineResult.Object` is currently an immutable structural value constructed from a complete cell map. Resolver01-03 instead construct private mutable `PartialOER` trees, write each partial cell once, and freeze the completed tree into immutable `EngineResult.Object` values.

`EngineResult.List` no longer implements Kotlin `List`. It exposes only `size`, `indices`, indexed `get`, `map`, `all`, and `forEachIndexed`, which keeps future representation changes local. LER mutation is not part of the initial OER work.

`OnceStore<K, V>` is the shared internal write-once primitive. It uses `ConcurrentHashMap.putIfAbsent` as the atomic write guard, stores non-null values directly, and stores the private `NULL_PROXY` sentinel for null values. An absent read throws, and every write after the winner throws. `Assumptions` variable bindings use this store, including bindings to GraphQL null.

## Target OER Contract

An OER has one fixed concrete object type and a monotonically growing set of exact `Value.GroundKey` cells. Absence means unset. `fetch(key)` continues to throw `MissingFieldException` when the key is unset, including while a mutable OER is incomplete.

Writing a cell is atomic and write-once. Before publication, the write validates the same carrier invariants as the current factory: the key belongs to the OER type, an argument-error key receives the canonical error value and check, and the cell value conforms to the field type expression. Two writers racing for one key must produce exactly one successful write; every loser must throw instead of silently retaining or replacing either value.

The first implementation should expose an opt-in API shaped approximately as follows:

```kotlin
fun EngineResult.Object.Companion.of(
    type: Schema.ObjectType,
    cells: Map<Value.GroundKey, EngineResult.Cell>,
    mutable: Boolean = false,
): EngineResult.Object

fun EngineResult.Object.isSet(key: Value.GroundKey): Boolean

fun EngineResult.Object.write(
    key: Value.GroundKey,
    cell: EngineResult.Cell,
)
```

Existing factory calls remain source-compatible and produce immutable OERs because `mutable` defaults to false. A mutable OER will normally start with an empty cell map, although allowing validated initial cells makes incremental migration easier. `write` on an immutable OER throws even when the key is absent.

This is per-cell immutability, not an initial global sealing protocol. After a key is written its value never changes, while other keys may still be added. The first phase should not add `freeze`, `seal`, blocking reads, promises, callbacks, or suspension.

## Storage And Observation

The private OER implementation should store cells in `OnceStore<Value.GroundKey, EngineResult.Cell>`. `OnceStore` will need construction from initial entries and an immutable snapshot operation in addition to its current `isSet`, `read`, and `write` operations. Mutability policy belongs to the OER wrapper: its private `mutable` mode gates calls to the store, while `OnceStore` remains the reusable write-once mechanism.

The existing public `cells: Map<Value.GroundKey, Cell>` property and derived `keys` property should return immutable snapshots rather than exposing the backing `ConcurrentHashMap`. `fetch` and `isSet` should use direct store operations. Since the store never removes entries, an `isSet` followed by `read` remains valid, but callers should prefer one domain-specific operation when possible.

Snapshots taken during concurrent writes may describe an intermediate monotonic state. Correctness predicates such as `correctResolution`, structural union, witness traversal, and complete materialization should continue to be applied only to quiescent OERs in their documented domains. Scheduler readiness should use exact `isSet` or `fetch` operations rather than infer atomic completion from a multi-cell snapshot.

## Structural Equality

Engine-result equality remains structural. Immutable OERs compare exactly as they do today. Mutable OER implementations should compare their concrete type and cell snapshots rather than the identity of their `OnceStore`.

The hash code of a mutable OER necessarily changes as cells are added, so a mutable OER must not be used as a hash key or set element while writes may continue. This restriction must be documented with the mutable factory contract. OER identity needed by a reactive scheduler remains a separate execution concept and must not be inferred from structural equality.

## Nested Objects And Lists

A written parent cell may directly contain a mutable child `EngineResult.Object`. Later writes to the child become visible through the already-written parent cell without replacing that cell. Cyclic result graphs remain outside the model.

LER mutation is deferred. Resolver outputs already determine list length and positions when a list is constructed, and an immutable LER cell can contain a mutable child OER. This is sufficient to populate object descendants at independent list positions without adding unset list slots. Add mutable LERs only if a concrete execution case later requires list elements themselves to become available separately.

## Bindings

Variable bindings remain in request-local `Assumptions` for this phase. They use `OnceStore<Value.Variable.Stamped, Value.Input?>`, so their atomic write-once behavior matches OER cells without encoding variables as GraphQL field cells.

Do not move bindings onto OERs or introduce an annotation carrier as part of mutable OER implementation. A later reactive executor may revisit occurrence-local ownership when it has explicit OER identities and provider scheduling, but that is separate from changing cell storage.

## Compatibility

All legacy construction paths remain immutable by default, including fixture DSLs and direct `EngineResult.Object.of(type, cells)` calls. Copy-producing operations such as `union` must always return immutable OERs regardless of the modes of their inputs.

No existing resolver should opt into mutable OERs in the carrier commit. `PartialOER` remains the constructor's intermediate representation until the mutable carrier behavior and concurrency tests are independently established.

## Test Requirements

Carrier tests should establish:

- A mutable empty OER reports an unset key and throws on `fetch`.
- A valid first write succeeds and is observable through `fetch`, `cells`, and `keys`.
- A second sequential write to the same key throws and preserves the first value.
- Two concurrent writers for one key produce one success and one exception, with either submitted value permitted to win.
- Concurrent writes to distinct keys are all retained.
- Writes to an immutable OER throw.
- Invalid keys and cells are rejected before publication.
- A parent cell containing a mutable child reflects later child writes without a parent rewrite.
- Cell and key snapshots cannot mutate the OER.
- Structural equality remains correct for completed results.
- `union` and fixture construction continue to produce immutable results.

The existing `OnceStore` tests remain the lower-level evidence for null representation, duplicate-write rejection, same-key races, and preservation of distinct-key races. Full repository validation remains `./gradlew check`.

## Migration Sequence

1. Keep the current `OnceStore` and `Assumptions` binding migration as an independent foundation.
2. Extend `OnceStore` with initial entries and immutable snapshots.
3. Add opt-in mutable mode, `isSet`, and atomic `write` to `EngineResult.Object`, while preserving immutable defaults and structural behavior.
4. Add the focused carrier and concurrency tests above without changing resolver construction.
5. Update `AGENTS.md`, `model/AGENTS.md`, `semantics/AGENTS.md`, `handoff.md`, and `execution-handoff.md` where they currently require immutable OER carriers or freezing.
6. In a later commit, make the shared resolver constructor allocate mutable OERs directly. Parent cells can then contain child OER references, and immutable LERs can contain cells whose values are mutable child OERs.
7. Remove `PartialOER`, `PartialCell`, `PartialValue`, and recursive freezing only after Resolver01-03 and their correctness, witness, generated, and stress tests pass against direct mutable construction.
8. Build the reactive readiness and variable-provider model on top of write-once OER cells as a separate design step; mutable storage alone does not solve symbolic demand closure, late key convergence, obligation claiming, or one-shot dispatch.

## Completion Boundary

The mutable-carrier milestone is complete when the opt-in OER API and concurrency contract are tested, immutable callers remain compatible, and no resolver behavior has changed. Direct resolver adoption is a subsequent milestone. A reactive executor remains later work and must still establish complete producer demand before application, exact occurrence identity, readiness, deadlock detection, and schedule-independent results.
