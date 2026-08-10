# Resolver21 Coroutine Resolution Handoff

## Objective

Implement Resolver21 as a coroutine-based field resolver with Resolver01's value-resolution feature surface and complete-output policy.

Resolver21 supports empty user-declared resolver object fragments, fixture-generated `T$Bridge.$node { $id }` loaders, ordinary field arguments, concrete-type specialization, lists, recursion, and non-selective complete resolver outputs. It does not support nonempty user-declared object fragments, resolver variables, selective resolver output, or field- and type-check execution.

Implement only Resolver21. Put the reusable procedure in `semantics/src/main/kotlin/semantics/CoroutineResolve.kt` so Resolver22 and Resolver23 can specialize it through `RuntimeSupport.complete(...)` and `Assumptions.selectiveResolvers`.

| Resolver | Value-resolution capability | Output policy |
| --- | --- | --- |
| Resolver21 | Resolver01: empty user object fragments plus generated `$node { $id }` loaders | Complete and non-selective |
| Resolver22 | Resolver02: nonempty object fragments and `FromArgument` variables | Complete and non-selective via `successorBoundaryDemand()` |
| Resolver23 | Resolver03: Resolver22 plus selective output | Selective via `successorDemand()` |

## Read First

- `AGENTS.md`
- `model/AGENTS.md`
- `semantics/AGENTS.md`
- `handoff.md`
- `semantics/testing-contracts.md`
- `model/src/main/kotlin/model/Promise.kt`
- `model/src/main/kotlin/model/EngineResult.kt`
- `semantics/src/main/kotlin/semantics/RuntimeSupport.kt`
- `semantics/src/main/kotlin/semantics/Materialize.kt`
- `semantics/src/main/kotlin/semantics/Resolve.kt`
- `semantics/src/main/kotlin/semantics/ResolveValue.kt`
- `semantics/src/main/kotlin/semantics/ResolverDemand.kt`
- `semantics/src/main/kotlin/semantics/resolver01/Resolver.kt`
- `semantics/src/test/kotlin/semantics/RuntimeSupportTest.kt`
- `semantics/src/test/kotlin/semantics/MaterializeTest.kt`
- `semantics/src/test/kotlin/semantics/resolver01/ResolverContractTest.kt`
- `semantics/src/test/kotlin/semantics/resolver01/ResolverGeneratedTest.kt`

Never edit `notes.md`.

## Current APIs

### Value Promises

An `EngineResult.Object` stores write-once value promises by exact ground key:

```kotlin
fun getValue(field: Value.GroundKey): Promise<EngineResult?>
fun setValue(field: Value.GroundKey, value: EngineResult?)
fun createValuePromise(field: Value.GroundKey): Promise<EngineResult?>
```

`Promise<T>` exposes:

```kotlin
suspend fun await(): T
fun get(): T
fun complete(value: T)
```

`get()` returns immediately or throws `UncompletedPromiseException`. `await()` suspends until completion. `complete()` is non-suspending and rejects repeated completion. OER-created value promises validate their completed values against the field schema.

### Runtime Support

`RuntimeSupport` combines output-boundary completion policy with resolver-read cycle detection:

```kotlin
context(world: Assumptions)
fun complete(selections: SelectionForest): SelectionCompletion

fun registerWriter(
    target: EngineResult.Object,
    key: Value.GroundKey,
    writer: List<PathComponent>,
)

fun cycleCheck(
    reader: List<PathComponent>,
    target: EngineResult.Object,
    key: Value.GroundKey,
)
```

`RuntimeSupport.cycleChecking(complete)` creates one thread-safe writer registry and monotonic read graph for a root resolution. `RuntimeSupport.noCycleChecking()` and the interface defaults make registration and cycle checking no-ops.

For Resolver21, construct support with identity completion:

```kotlin
val runtimeSupport =
    RuntimeSupport.cycleChecking { selections ->
        SelectionCompletion(selections)
    }
```

Register every deferred value slot exactly once with:

```kotlin
runtimeSupport.registerWriter(
    target = target,
    key = key,
    writer = path + key,
)
```

The pair of the target OER's reference identity and exact `Value.GroundKey` identifies the slot. Registration is independent of promise completion status. A repeated registration throws.

`cycleCheck(reader, target, key)` records the edge from the reader resolver coordinate to the registered writer coordinate. A detected cycle throws `ResolverReadCycleException` with the complete coordinate cycle. A slot without a writer registration contributes no resolver-read edge.

### Materialization

Resolver input materialization has this signature:

```kotlin
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal suspend fun EngineResult.Object.materialize(
    selections: ObjectSelectionForest,
    reader: List<PathComponent>,
): Value.Object
```

Materialization calls `runtimeSupport.cycleCheck(reader, target, key)` immediately before each promise `await()` and preserves the same reader coordinate through recursive object and list traversal. A selected field with no installed promise throws `MissingFieldException` immediately.

Call it with the consuming resolver's exact root-relative coordinate:

```kotlin
val input =
    target.materialize(
        selections = objectFragment,
        reader = coordinate,
    )
```

### Passive Result Construction

`Value.Output?.resolveValue(...)` returns a `ResolvedValue` containing:

- `engineResult`: the passive result value to publish;
- `objectsNeedingResolution`: stable child OER occurrences requiring resolver orchestration.

Use `objectsNeedingResolution` directly. `ResolvedValue.resolveObjects` is for the synchronous depth-first constructor and must not be used by `CoroutineResolve.kt`.

## Execution Architecture

The coroutine tree is the execution structure. Create one coroutine for each deferred value promise installed by orchestration. A resolver awaiting an incomplete input retains its continuation in `Promise.await()` and resumes when the producer completes that promise.

Resolver21 must not use:

- a reactor or event loop;
- a ready queue or repeated readiness scan;
- `isFinished` state;
- dependency maps for scheduling;
- `dependencyOrder` or `resolverDependencies`;
- polling, yielding, or retry loops;
- `GlobalScope`;
- an independent scope, job, or dispatcher that escapes the root execution.

The public API remains synchronous:

```kotlin
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object
```

Require `world.selectiveResolvers == false`.

Create one cycle-checking `RuntimeSupport`, then run the complete coroutine tree under one root timeout:

```kotlin
return runBlocking {
    withTimeout(90_000) {
        context(runtimeSupport) {
            this@resolve.coroutineResolve(selections)
        }
    }
}
```

Do not catch `TimeoutCancellationException`. Do not add child timeouts.

All launched slots must inherit the `runBlocking` context and use `CoroutineStart.DEFAULT`. Do not use `CoroutineStart.UNDISPATCHED`, `Dispatchers.Unconfined`, `Dispatchers.Default`, or another dispatcher.

## Shared Root Procedure

Add this shared entry point in `CoroutineResolve.kt`:

```kotlin
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal suspend fun Value.Object.coroutineResolve(
    selections: SelectionForest,
): EngineResult.Object {
    val result =
        EngineResult.Object.of(
            type = type,
            values = emptyMap(),
            mutable = true,
        )

    coroutineScope {
        orchestrateSlot(
            path = emptyList(),
            source = this@coroutineResolve,
            selections = selections,
            target = result,
        )
    }

    return result
}
```

Helper names may differ, but the implementation must have one root structured scope and return only after every descendant coroutine completes.

## Object Orchestration

Implement promise installation and coroutine launch in one non-suspending `CoroutineScope` extension:

```kotlin
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private fun CoroutineScope.orchestrateSlot(
    path: List<PathComponent>,
    source: Value.Object,
    selections: SelectionForest,
    target: EngineResult.Object,
) {
    require(source.type == target.type)

    val closedDemand = source.type.closeResolverDemand(path, selections)
    val unresolvedKeys = closedDemand.groundKeys() - target.keys

    unresolvedKeys.forEach { key ->
        target.createValuePromise(key)
        runtimeSupport.registerWriter(
            target = target,
            key = key,
            writer = path + key,
        )
    }

    unresolvedKeys.forEach { key ->
        launch(start = CoroutineStart.DEFAULT) {
            resolveSlot(
                path = path,
                source = source,
                selection = closedDemand[key],
                target = target,
                valuePromise = target.getValue(key),
            )
        }
    }
}
```

The first loop installs and registers every local destination promise. The second loop launches their producers. Keep both loops in the same orchestration function; do not introduce a preparation carrier or separate launch phase.

Promise creation and writer registration must be adjacent. No coroutine may launch and no containing value may publish between those operations.

## Slot Procedure

Each launched slot completes its destination value promise exactly once.

For a key whose arguments contain an error:

```kotlin
valuePromise.complete(Value.Error)
```

For `__typename`:

```kotlin
valuePromise.complete(Value.String.of(source.type.typeName))
```

For every other key:

1. Let `coordinate = path + key`.
2. Call `runtimeSupport.complete(selection.subselections)`.
3. For a registered resolver, stamp its object fragment at `coordinate`, materialize that fragment from `target` with `reader = coordinate`, and invoke the resolver.
4. For a passive field, require `!world.selectiveResolvers` and read `source.fieldValues.getValue(key)`.
5. Convert the returned `Value.Output?` with `resolveValue(path = coordinate, resolverDemand = completion.selections, retainCompleteOutput = completion.retainCompleteOutput)`.
6. Synchronously orchestrate every entry in `resolvedValue.objectsNeedingResolution`.
7. Complete `valuePromise` with `resolvedValue.engineResult`.
8. Remain in the slot's structured scope until all descendant slots complete.

Use the resolver invocation policy from `Resolve.kt`:

- `completion.retainCompleteOutput` calls `resolver.completeOutput(input, arguments, selections)`;
- otherwise, `world.selectiveResolvers` calls `resolver(input, arguments, selections)`;
- otherwise, call `resolver(input, arguments)`.

Resolver21 uses the final branch. Supporting all three branches in `CoroutineResolve.kt` makes Resolver22 and Resolver23 policy-only wrappers.

Do not call `resolveKey`: it performs synchronous publication and field-check writes. Resolver21 implements value-promise completion only.

## Publication Ordering

Before completing a value promise with a result containing active child OERs, install and register every demanded promise on those children.

Use this order inside the slot's structured scope:

```kotlin
coroutineScope {
    val resolvedValue = produceAndResolveValue()

    resolvedValue.objectsNeedingResolution.forEach { child ->
        orchestrateSlot(
            path = child.path,
            source = child.source,
            selections = child.selections,
            target = child.target,
        )
    }

    valuePromise.complete(resolvedValue.engineResult)
}
```

Call child orchestration directly; do not wrap it in another `launch`. Direct calls synchronously install child promises before the ancestor value becomes reachable. The child producer coroutines may run after publication because they use `CoroutineStart.DEFAULT`.

The enclosing `coroutineScope` permits publication before descendant completion while keeping every descendant in the producer's structured subtree.

## Completion, Failure, And Cancellation

Successful return is quiescent:

- every coroutine launched by Resolver21 has completed;
- every promise present in the returned result tree is complete;
- every deferred value promise installed by Resolver21 completed exactly once;
- no resolver-owned coroutine can mutate the returned result.

The returned OER remains mutable through its model API; quiescence describes the completed resolution, not permanent immutability.

An uncaught ordinary exception in any child fails its parent scope, cancels the remaining coroutine tree, and escapes the root `runBlocking`. Do not swallow failures, convert invariant failures to `CancellationException`, cancel individual children as failure handling, or return a partial result.

Deferred promises are not jobs and need no exceptional completion path. Root cancellation cancels the waiting coroutines.

The 90-second timeout is the final guard for an undetected liveness defect. Tests for known cycles must fail immediately through `RuntimeSupport.cycleCheck`; do not add a test that waits for the root timeout.

## Files To Add

```text
semantics/src/main/kotlin/semantics/CoroutineResolve.kt
semantics/src/main/kotlin/semantics/resolver21/Resolver.kt
semantics/src/test/kotlin/semantics/resolver21/ResolverContractTest.kt
semantics/src/test/kotlin/semantics/resolver21/ResolverGeneratedTest.kt
```

Add `semantics/src/test/kotlin/semantics/CoroutineResolveTest.kt` if shared implementation tests do not fit naturally in the Resolver21 package.

Do not modify Resolver01-10 scheduling or output policy.

## Contract Coverage

Use these Resolver21 contract adapters:

```kotlin
class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    CompleteResolverOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    // resolve(...) adapter
}
```

```kotlin
class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = false

    // resolve(...) adapter
}
```

The Node contracts exercise fixture-generated `$node` resolvers whose object fragment selects passive `$id`.

Do not add `ObjectFragmentResolverContract`, `ObjectFragmentFromArgumentResolverContract`, selective policy contracts, mutation tests, witness tests, readiness tests, or stress tests to Resolver21.

Add focused implementation tests for:

- every local promise being installed before any local producer starts;
- every active child promise being installed before its ancestor value publishes;
- writer registration and materialization cycle checking working through Resolver21;
- a resolver failure escaping the root without hanging or returning a partial result;
- each installed deferred value promise completing exactly once;
- successful results supporting synchronous `get()` throughout;
- successful results being accepted by `sameCompletedResultAs` without an incompletion exception.

Resolver21 does not define reactor lifecycle events or a semantic launch order.

## Documentation

After implementation:

- update `semantics/AGENTS.md` with Resolver21's coroutine, install-before-launch, and non-reactive execution model;
- add Resolver21 to the empty-fragment, node, complete-output, and generated-profile matrices in `semantics/testing-contracts.md`;
- update `handoff.md` with Resolver21's status and the remaining Resolver22/23 work.

Describe the tested single-threaded coroutine behavior without claiming correctness under arbitrary dispatchers.

## Validation

Run focused tests:

```shell
./gradlew :semantics:test \
  --tests 'semantics.RuntimeSupportTest' \
  --tests 'semantics.MaterializeTest' \
  --tests 'semantics.CoroutineResolveTest' \
  --tests 'semantics.resolver21.ResolverContractTest' \
  --tests 'semantics.resolver21.ResolverGeneratedTest' \
  -PresolverPropertySeed=1
```

Omit a focused class if no corresponding file was added.

Run the full seeded suite:

```shell
./gradlew check -PresolverPropertySeed=1
```

Check the diff:

```shell
git diff --check
```

Resolver21 requires no stress task.

## Acceptance Criteria

- Resolver21 has Resolver01's value-resolution feature surface and complete-output policy.
- Resolver21 requires `world.selectiveResolvers == false`.
- `CoroutineResolve.kt` contains the shared Resolver21-23 procedure.
- The wrapper contains only mode validation, root runtime support, `runBlocking`, the root timeout, and the shared procedure call.
- Every local destination promise is installed and registered before any local producer launches.
- Every active descendant promise is installed and registered before its ancestor value publishes the descendant OER.
- Resolver input materialization receives the exact reader coordinate.
- The implementation uses no reactor, work queue, readiness scan, dependency ordering, polling, or escaping job.
- Every slot uses inherited-context `CoroutineStart.DEFAULT`.
- Child failure fails the root and cancels the remaining coroutine tree.
- Successful return is quiescent and contains only completed promises.
- Resolver01's applicable deterministic and generated contracts pass through Resolver21's adapters.
- The full seeded `check` task passes.

## Future Compatibility

Resolver22 supplies `successorBoundaryDemand()` from `RuntimeSupport.complete(...)`, supports nonempty object fragments and `FromArgument`, and requires a non-selective world.

Resolver23 supplies `successorDemand()`, supports selective output, and requires a selective world.

`CoroutineResolve.kt` must support both future policies without a scheduler variant.

## Out Of Scope

- Resolver22 or Resolver23 wrappers and tests;
- runtime `FromObjectField` binding;
- pending symbolic selections, late-key convergence, or demand sealing;
- batching or caching;
- parallel dispatchers;
- checker execution or raw-versus-checked reads;
- mutations, subscriptions, or incremental delivery;
- lazy executor values.
