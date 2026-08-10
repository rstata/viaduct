# Resolver21 Coroutine Resolution Handoff

## Mission

Implement Resolver21 as the first coroutine-based field resolver.

Resolver21 is intentionally only the coroutine counterpart of Resolver01. It supports empty user-declared resolver object fragments, fixture-generated `T$Bridge.$node { $id }` loaders, ordinary field arguments, concrete-type specialization, lists, recursion, and non-selective complete resolver outputs. It does not support nonempty user-declared object fragments, resolver variables, or selective resolver output.

The purpose of Resolver21 is to validate the coroutine execution foundation before adding semantic complexity. Do not implement Resolver22 or Resolver23 in this task.

The intended sequence is:

| Resolver | Capability | Output policy |
| --- | --- | --- |
| Resolver21 | Resolver01 capability: empty user object fragments, plus generated `$node { $id }` loaders; no resolver variables | Complete and non-selective |
| Resolver22 | Resolver02 capability: nonempty object fragments and `FromArgument` variables | Complete and non-selective, using `successorBoundaryDemand()` |
| Resolver23 | Resolver03 capability: Resolver22 plus selective output | Selective, using `successorDemand()` |

The shared implementation should live in `semantics/src/main/kotlin/semantics/CoroutineResolve.kt`. Resolver21, Resolver22, and Resolver23 should eventually differ only in the output-boundary completion policy they supply and the `Assumptions.selectiveResolvers` world mode they require.

## Read First

Read these files before editing:

- `AGENTS.md`
- `model/AGENTS.md`
- `semantics/AGENTS.md`
- `handoff.md`
- `semantics/testing-contracts.md`
- `model/src/main/kotlin/model/Promise.kt`
- `model/src/main/kotlin/model/EngineResult.kt`
- `semantics/src/main/kotlin/semantics/Materialize.kt`
- `semantics/src/main/kotlin/semantics/Resolve.kt`
- `semantics/src/main/kotlin/semantics/ResolveValue.kt`
- `semantics/src/main/kotlin/semantics/ResolverDemand.kt`
- `semantics/src/main/kotlin/semantics/resolver01/Resolver.kt`
- `semantics/src/test/kotlin/semantics/resolver01/ResolverContractTest.kt`
- `semantics/src/test/kotlin/semantics/resolver01/ResolverGeneratedTest.kt`

Never edit `notes.md`.

The Promise/OER migration that introduced the foundation below is part of the committed baseline. Treat these APIs as current behavior rather than proposed work to recreate.

## Current Foundation

`EngineResult.Cell` no longer exists. An `EngineResult.Object` owns independent write-once value promises.

The relevant OER operations are:

```kotlin
fun getValue(field: Value.GroundKey): Promise<EngineResult?>

fun setValue(field: Value.GroundKey, value: EngineResult?)

fun createValuePromise(
    field: Value.GroundKey,
    deferredStamp: Any? = null,
): Promise<EngineResult?>
```

`Promise<T>` has an immediate implementation and a `CompletableDeferred`-backed deferred implementation:

```kotlin
suspend fun await(): T
fun get(): T
fun complete(value: T)
fun getDeferredStamp(): Any?
```

`get()` throws `UncompletedPromiseException` immediately when a deferred promise is incomplete. `await()` suspends. `complete()` is non-suspending and rejects repeated completion. A deferred value promise created through the OER validates its completed value against the field schema before publication.

`getDeferredStamp()` is independent of completion status. Resolver21 must stamp each deferred value promise with the exact root-relative resolver coordinate `path + key`.

`EngineResult.Object.materialize` is already suspending and calls `await()` on each selected value promise. Reading a selected field whose promise was never installed still throws `MissingFieldException` immediately. That distinction is essential: Resolver21 must install every promise that can legitimately be awaited before launching work that can read it.

Resolvers01-10 still call suspending materialization through `runBlocking` only at their synchronous resolver-application boundary. Do not make those resolvers or all shared semantic functions suspending as part of Resolver21.

## Architectural Target

Return to ordinary procedure-call structure expressed with coroutine scopes and recursive calls.

Resolver21 must not have:

- a reactor or event loop;
- a ready queue;
- repeated readiness scans;
- `isFinished` state;
- a dependency map used for scheduling;
- explicit topological ordering through `dependencyOrder`;
- polling, yielding loops, or retry loops;
- `GlobalScope`;
- an independent scope or job per OER that escapes the root resolution.

The coroutine tree itself is the execution structure. Each exact field slot is one launched coroutine. A resolver that materializes an incomplete dependency suspends at `Promise.await()`, retaining its traversal continuation. Completing the dependency resumes that continuation.

The public Resolver21 API remains synchronous for parity with the existing resolvers:

```kotlin
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object
```

This public function should use `runBlocking` once around the complete Resolver21 coroutine tree, with a 90-second `withTimeout` immediately inside it. The timeout is a final guard against an undetected hang, not a replacement for resolver-read cycle detection. The shared implementation inside `CoroutineResolve.kt` should be suspending and use `coroutineScope`. Do not add `Dispatchers.Default`; inherited single-threaded `runBlocking` execution is sufficient to prove the continuation model without introducing parallel scheduling.

## Runtime Support And Completion Policy

`RuntimeSupport` already exists in `semantics/src/main/kotlin/semantics/RuntimeSupport.kt` as the completion-policy context service. Extend that service rather than introducing another context abstraction.

`RuntimeSupport` owns:

- its existing `complete(selections)` operation containing the output-boundary completion policy;
- a `cycleCheck` operation that materialization invokes immediately before every promise await;
- the monotonic resolver-read cycle graph used by cycle-checking variants.

The completion result retains the two boundary-dependent facts:

```kotlin
val selections: SelectionForest
val retainCompleteOutput: Boolean
```

The exact private carrier for those two facts may remain a small data class, but it is not a context service. The only context service used by resolution and materialization is `RuntimeSupport`.

Resolver21's `complete` operation supplies identity completion. Resolver22 will later supply `selections.successorBoundaryDemand()`. Resolver23 will later supply `selections.successorDemand()`. Resolver21 and Resolver22 require `world.selectiveResolvers == false`; Resolver23 requires it to be true. The shared procedure uses that world flag for resolver invocation and passive output traversal.

Provide two explicit constructions:

- cycle-checking support for Resolver21, which owns one shared graph and has a functional `cycleCheck`;
- no-cycle-check support for Resolver01-10 and for post-resolution validation, dependency, and witness oracles, whose `cycleCheck` remains a no-op even when a promise has a deferred writer-coordinate stamp.

Do not infer this policy from whether a reader happens to be present. Every actual Resolver21 resolver-input materialization must pass its exact reader coordinate.

The desired Resolver21 wrapper shape is:

```kotlin
val runtimeSupport =
    RuntimeSupport.cycleChecking(
        complete = { selections ->
            // Identity selections, no retained-complete-output override.
        },
    )

return context(runtimeSupport) {
    // New coroutine constructor.
}
```

Resolvers01-10 already use `RuntimeSupport` and obtain completion facts through `runtimeSupport.complete(...)`. Give their existing lambda-constructed support a no-op `cycleCheck`; do not otherwise change their scheduling or output policy.

Keep this API small. Resolver21-23 should not require separate scheduler subclasses or duplicated copies of the coroutine procedure.

## Resolver-Read Cycle Detection

Coroutine dependency cycles otherwise suspend forever. Resolver21 must add monotonic resolver-read cycle detection to `RuntimeSupport`.

This is cycle detection for violated resolver-validation or invariant assumptions. Do not call it general deadlock detection, and do not try to infer JVM thread deadlocks.

The cycle-checking behavior belongs to `RuntimeSupport`:

```kotlin
fun cycleCheck(
    reader: List<PathComponent>,
    promise: Promise<*>,
)
```

The cycle-checking implementation records and checks the edge from `reader` to the promise's writer. No-cycle-check support instead performs no graph update.

The graph edge is:

```text
reader resolver coordinate -> promise deferred stamp
```

Every deferred value promise created for a resolver slot must use the exact `List<PathComponent>`
coordinate of that slot as its deferred stamp:

```kotlin
val writer = path + key
val valuePromise =
    Promise.ofDeferred<EngineResult?>(
        deferredStamp = writer,
    )
```

This establishes the conversion from a promise to its writer coordinate:

```text
promise -> promise.getDeferredStamp() as List<PathComponent>
```

Together with `readsByReader: reader path -> Set<Promise<*>>`, that conversion makes the recorded
relation an adjacency list over resolver coordinates:

```text
successors(reader) =
    readsByReader[reader].map { promise -> promise's deferred writer coordinate }
```

Cycle traversal therefore starts at the newly read promise's writer coordinate, looks up every
promise read by that writer, converts those promises to their writer coordinates, and continues
until it either reaches the original reader or exhausts the graph.

If `getDeferredStamp()` returns `null`, the promise is immediate and contributes no resolver edge.
A non-null stamp encountered by cycle-checking must be a `List<PathComponent>` writer coordinate;
fail on any other stamp because it violates Resolver21's promise-creation invariant. Do not inspect
whether a deferred promise is complete; a completed deferred promise retains its writer stamp and
records the same semantic read edge as an incomplete one.

The graph is monotonic. Retain recorded edges for the entire root resolution and never remove them
after an await. One resolver may read several promises, so store a set of promises per reader rather
than one replaceable edge; each promise supplies its adjacent writer coordinate through
`getDeferredStamp()`.

A suitable concurrent representation is:

```kotlin
ConcurrentHashMap<List<PathComponent>, MutableSet<Promise<*>>>()
```

Insert the promise before traversing the graph:

```kotlin
readsByReader.computeIfAbsent(reader) {
    // One resolver coroutine normally owns this reader's set; use a concurrent set defensively.
    ConcurrentHashMap.newKeySet()
}.add(promise)
```

Then search from the promise's deferred writer coordinate for `reader`, traversing the adjacency
list by converting each recorded promise through `getDeferredStamp()`. If `reader` is reachable,
throw an `IllegalStateException` or a focused subtype with the complete cycle in its message.

Insertion occurs before traversal. Because edges are never removed, the insertion that closes a concurrently formed cycle can observe the previously inserted path; no promise-completion handshake is needed. Keep the implementation thread-safe even though Resolver21 initially inherits a single-threaded dispatcher.

Add focused tests for:

- an acyclic chain;
- a direct self-cycle;
- a multi-hop cycle;
- a read of an already completed deferred promise;
- repeated recording of the same edge;
- concurrent insertion of edges that close one cycle;
- no-cycle-check support awaiting a stamped deferred promise without adding an edge.

## Contextual Materialization

Make `RuntimeSupport` a context parameter of materialization:

```kotlin
context(world: Assumptions, runtimeSupport: RuntimeSupport)
suspend fun EngineResult.Object.materialize(
    selections: ObjectSelectionForest,
    reader: List<PathComponent>,
): Value.Object
```

Immediately before each await, materialization uses the contextual support directly:

```kotlin
val promise = getValue(key)
runtimeSupport.cycleCheck(reader, promise)
val value = promise.await()
```

Recursive object and list materialization retain the same `RuntimeSupport` context and pass the same `reader` coordinate throughout the walk. Do not add a callback parameter or another materialization overload solely for cycle checking.

The slot passes its reader coordinate when materializing:

```kotlin
val input =
    target.materialize(
        selections = objectFragment,
        reader = coordinate,
    )
```

Update existing materialization call sites and focused tests to provide `RuntimeSupport`. Post-resolution oracles and existing synchronous resolvers use the explicitly no-op variant.

Do not put cycle-detection state in `Promise`, `EngineResult`, or `Assumptions`. `Promise` supplies
only its writer-coordinate stamp; cycle-enabled `RuntimeSupport` owns the read relation.

## Object Orchestration

Keep installation and launch together in one ordinary orchestration procedure. There is no need for
a `PreparedSlot` carrier or a separate preparation function.

A suitable shape is:

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
        target.createValuePromise(
            field = key,
            deferredStamp = path + key,
        )
    }

    unresolvedKeys.forEach { key ->
        launch {
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

The two loops express a temporal invariant within one function: install every local destination
promise before launching any local resolver coroutine. They do not justify a separate abstraction.
Keep genuinely complicated work, such as demand closure, behind its existing focused abstraction.

`orchestrateSlot` must not call `dependencyOrder` or `resolverDependencies`. Every installed local
slot may launch immediately.

## Root Procedure

The public Resolver21 wrapper creates one root `RuntimeSupport`, enters `runBlocking`, and places a 90-second timeout around the entire shared coroutine resolution:

```kotlin
return runBlocking {
    withTimeout(90_000) {
        context(runtimeSupport) {
            this@resolve.coroutineResolve(selections)
        }
    }
}
```

Do not catch `TimeoutCancellationException`. Timeout is root-originated cancellation and must cancel the complete structured coroutine tree before escaping to the caller. Do not put separate timeouts around slots, OERs, materialization reads, or child scopes.

The shared suspending procedure should have the following shape:

```kotlin
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal suspend fun Value.Object.coroutineResolve(
    selections: SelectionForest,
): EngineResult.Object {
    val result = EngineResult.Object.of(
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

The exact helper names may differ, but retain one root structured scope, install-before-launch, and ordinary nested procedure calls.

When the root `coroutineScope` returns, every launched descendant coroutine has completed. The returned OER must therefore contain only completed promises, making synchronous `get()`, structural equality, `correctResolution`, and existing test contracts valid.

## Slot Procedure

Each launched resolver slot executes exactly once.

For an argument-error key:

```kotlin
valuePromise.complete(Value.Error)
```

For `__typename`:

```kotlin
valuePromise.complete(Value.String.of(source.type.typeName))
```

For an ordinary key:

1. Call `runtimeSupport.complete(selection.subselections)` to obtain the output-boundary completion.
2. If the field has a registered resolver, stamp its object fragment at `coordinate`, materialize that fragment from the containing target OER with `coordinate` as its reader, and apply the resolver.
3. If the field has no registered resolver, require non-selective operation and read the value from `source.fieldValues`.
4. Convert the returned `Value.Output?` through `resolveValue(path = coordinate, resolverDemand = completion.selections, retainCompleteOutput = completion.retainCompleteOutput)`; that operation uses `world.selectiveResolvers`.
5. Invoke `orchestrateSlot` for every `ObjectResolution` in
   `resolvedValue.objectsNeedingResolution`. Each call synchronously installs that child's local
   promises and launches its resolver coroutines in the current structured scope.
6. Complete the destination value promise with `resolvedValue.engineResult`.
7. Remain in the slot's structured scope until every launched descendant completes.

The resolver invocation branch should preserve the existing `resolveKey` policy:

- `retainCompleteOutput` uses `resolver.completeOutput(...)`;
- `world.selectiveResolvers` uses `resolver(input, arguments, selections)`;
- otherwise use the complete-output `resolver(input, arguments)` overload.

Resolver21 reaches only the final complete-output branch, but the shared implementation must preserve all three branches so Resolver22 and Resolver23 remain policy-only wrappers.

## Publication Ordering

The critical publication invariant is:

> Every active descendant promise reachable through a value is installed before the ancestor value promise is completed with that value.

`resolveValue` constructs passive values and stable child OER identities. Its
`objectsNeedingResolution` list identifies every active OER occurrence in that passive tree. Call
`orchestrateSlot` synchronously for every occurrence, then complete the parent value promise.

Do not wrap child orchestration itself in `launch`. A default `launch` may not execute
`orchestrateSlot` before parent publication, allowing a resumed consumer to descend into the child
and observe an absent field. Calling `orchestrateSlot` directly installs child promises before it
returns; the resolver coroutines that it launches may execute later.

A suitable ordinary-slot outline is:

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

The enclosing `coroutineScope` waits for descendants after publishing the current value. This permits consumers elsewhere in the tree to traverse the published passive shape and suspend on already-installed descendant promises while the producer subtree continues.

Do not use the synchronous `ResolvedValue.resolveObjects` helper in CoroutineResolve; it sorts and executes callbacks for the depth-first constructor. Consume `objectsNeedingResolution` directly.

## Structured Concurrency And Failure

All slot and child-orchestration coroutines must inherit the one root job.

An uncaught non-cancellation failure in any resolver coroutine fails its parent scope, which cancels siblings and propagates to the root `runBlocking`. Do not swallow failures and do not complete the root successfully after one slot fails.

Cancellation is initiated at the root. Ancestor cancellation cancels descendants. Explicitly cancelling one child does not normally cancel its parent or siblings, so Resolver21 must not use individual child cancellation as semantic failure handling.

The public 90-second `withTimeout` is one root-cancellation source. It guarantees that an undetected suspension cycle or other liveness bug eventually terminates the resolution and its tests instead of hanging indefinitely.

Do not throw `CancellationException` for resolver or invariant failures. Throw ordinary exceptions and let structured concurrency fail the root.

Deferred OER promises are not child jobs and are not completed exceptionally on failure. Waiting resolver coroutines are released by cancellation of their own jobs when the root fails. The failed result tree is not returned.

Add one focused test in which a resolver throws and assert that Resolver21 throws rather than hangs or returns a partial OER.

Do not add a test that waits 90 seconds. Verify the timeout structurally in the Resolver21 wrapper; focused cycle tests should fail immediately through `RuntimeSupport.cycleCheck`.

## Resolver21 Package

Add:

```text
semantics/src/main/kotlin/semantics/CoroutineResolve.kt
semantics/src/main/kotlin/semantics/resolver21/Resolver.kt
semantics/src/test/kotlin/semantics/resolver21/ResolverContractTest.kt
semantics/src/test/kotlin/semantics/resolver21/ResolverGeneratedTest.kt
```

Extend:

```text
semantics/src/main/kotlin/semantics/RuntimeSupport.kt
```

Add focused shared tests where appropriate:

```text
semantics/src/test/kotlin/semantics/RuntimeSupportTest.kt
semantics/src/test/kotlin/semantics/CoroutineResolveTest.kt
```

Resolver21's wrapper should be as small as Resolver01's wrapper: require a non-selective world, construct its identity root `RuntimeSupport`, call the shared coroutine resolver under one `runBlocking`, and contain no scheduling logic.

## Resolver21 Contract Tests

Mirror Resolver01's accepted contracts exactly:

```kotlin
class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    CompleteResolverOutputPolicyContract,
    CorrectResolutionPostTestPolicy
```

Mirror Resolver01's generated profiles exactly:

```kotlin
class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract
```

Do not opt Resolver21 into `ObjectFragmentResolverContract`, `ObjectFragmentFromArgumentResolverContract`, selective policy contracts, mutation tests, witness tests, readiness tests, or stress tests yet.

The Node contracts are required even though Resolver21 otherwise admits empty user object fragments. Fixture lowering creates generated `$node` resolvers with the passive object fragment `{ $id }`; this is the narrow dependency-bearing exception already supported by Resolver01.

Add implementation-focused tests for:

- all local promises exist before slot launch;
- nested active OER promises exist before parent value publication;
- a throwing resolver fails the root scope;
- each exact field promise is completed once;
- a completed Resolver21 result supports synchronous `get()` throughout;
- cycle-detection behavior listed above.

Do not add reactor lifecycle or task-ordering contracts. Resolver21 has no semantic launch-order promise.

## Documentation Updates With Resolver21

After the implementation passes, update only the current-state documents that would otherwise become false:

- `semantics/AGENTS.md`: describe Resolver21 as coroutine-driven, install-before-launch, and non-reactive.
- `semantics/testing-contracts.md`: add Resolver21 alongside Resolver01/06 for empty-fragment, node, and complete-output contracts and generated profiles.
- `handoff.md`: record Resolver21's implemented status, structured-concurrency boundary, and remaining Resolver22/23 work.

Do not rewrite historical Resolver01-10 descriptions or claim that Resolver21 proves parallel correctness. A passing single-threaded coroutine implementation establishes continuation-based scheduling behavior, not a theorem about arbitrary dispatchers.

## Validation

Run focused tests first:

```shell
./gradlew :semantics:test \
  --tests 'semantics.RuntimeSupportTest' \
  --tests 'semantics.CoroutineResolveTest' \
  --tests 'semantics.resolver21.ResolverContractTest' \
  --tests 'semantics.resolver21.ResolverGeneratedTest' \
  -PresolverPropertySeed=1
```

Then run:

```shell
./gradlew check -PresolverPropertySeed=1
```

Also run:

```shell
git diff --check
```

Do not run Resolver03/08/09/10 stress tasks unless a failure suggests a shared regression. Resolver21 does not need a stress task in this milestone.

## Resolver21 Acceptance Criteria

Resolver21 is complete when all of the following hold:

- Its public feature and output-policy surface matches Resolver01 exactly.
- Its wrapper contains no scheduling logic.
- `CoroutineResolve.kt` contains the shared procedure intended for Resolver21-23.
- Every local destination value promise is installed before any local slot launches.
- Every active descendant promise is installed before its ancestor value promise publishes the descendant OER.
- Resolver input materialization calls contextual `runtimeSupport.cycleCheck(reader, promise)` before awaiting the promise.
- Deferred completion status does not affect cycle-edge recording.
- Resolver-read edges are retained monotonically and cycles fail explicitly.
- The implementation does not call `dependencyOrder`, `resolverDependencies`, or a reactor.
- There is one root structured scope and no escaping jobs.
- The public `runBlocking` wraps the entire execution in one 90-second root timeout.
- Child failure fails the root and cancels the remaining coroutine tree.
- Successful return implies every demanded promise is completed exactly once.
- Resolver01's deterministic and generated contract suites pass unchanged through Resolver21's adapters.
- The full seeded `check` task passes.

## Explicitly Deferred To Resolver22

Resolver22 will add nonempty user resolver object fragments and `FromArgument` variables by changing only the supplied completion policy to `successorBoundaryDemand()`. Like Resolver21, it requires a non-selective world.

`closeResolverDemand` already performs local demand closure, stamps resolver occurrences, and binds `FromArgument` variables. If Resolver21 requires changes that would prevent Resolver22 from reusing that operation unchanged, stop and reconsider the shared boundary.

Resolver22 is the first stage expected to exercise ordinary deferred-to-deferred resolver reads heavily: a consumer slot may launch before a sibling producer, suspend during materialization, and resume after producer completion. Add targeted continuation-order tests in Resolver22 rather than broadening Resolver21's feature contract.

## Explicitly Deferred To Resolver23

Resolver23 will require a selective world and switch completion to `successorDemand()`. The shared procedure will consequently use selective resolver invocation and build passive outputs selectively through `world.selectiveResolvers`.

No coroutine scheduler change should be necessary for Resolver23. If selective support requires a separate coroutine executor or reactive orchestration state, the Resolver21 shared abstraction is wrong.

Resolver23 should eventually mirror Resolver03's ordinary feature, policy, witness, mutation, list-deepening, and stress coverage as appropriate, but none of that belongs in the Resolver21 implementation.

## Out Of Scope For 21-23

Do not add runtime `FromObjectField` binding, pending symbolic selections, late-key convergence, demand sealing, batching, caching, parallel dispatchers, checker execution, raw-versus-checked reads, mutations, subscriptions, incremental delivery, or lazy executor values.

Resolver10 remains the synchronous worklist model for runtime `FromObjectField`. A later coroutine resolver can adapt those semantics only after Resolver21-23 establish the coroutine foundation.
