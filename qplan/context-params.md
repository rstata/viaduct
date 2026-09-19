# Context Parameters, `Assumptions`, And `SharedOperationContext`

Pure model operations use Kotlin context parameters for the one immutable `Assumptions` value of a reasoning world. Migrated semantics APIs receive their operation or world explicitly through ordinary parameters or a natural extension receiver; their `SharedOperationContext` still describes one resolution or correctness operation. Correctness judgments retain context parameters during the staged migration. Demand closure and sibling dependencies now use focused logic implementations retaining their operation and object occurrence.

## Model Context

Model operations that depend only on the schema and resolver configuration declare:

```kotlin
context(world: Assumptions)
fun ...
```

Use the name `world` consistently. It mirrors the mathematical judgment `world |- predicate` and makes it explicit that model interpretation depends only on configuration, not request-local mutable state.

A function with an `Assumptions` context may directly call another function requiring the same context:

```kotlin
context(world: Assumptions)
fun EngineObjectData.Sync.isQueryRoot(): Boolean =
    schemaType == world.schema.query

context(world: Assumptions)
fun EngineObjectData.Sync.requireQueryRoot(): EngineObjectData.Sync {
    require(isQueryRoot())
    return this
}
```

The compiler supplies both calls from the existing context. A context parameter is not an implicit receiver; qualify members as `world.schema`, `world.resolverRegistry`, and `world.selectiveResolvers`.

## Semantics Dependencies

Use the operation as an extension receiver when the function's principal role is executing that operation. Resolver entry points use `SharedOperationContext<*>.resolve(...)`, `.resolveObserved(...)`, and `.startResolve(...)`; startup within an existing execution scope uses `OperationContext.startResolve(...)` or `CoroutineOperationContext.startResolve(...)`. These extensions live in the corresponding resolver-family packages, and callers import the selected algorithm. They preserve the existing request scopes, dispatcher/state identities, and demand-policy captures.

Preserve an existing data receiver when the function primarily transforms or inspects selections, keys, values, or results, and pass its operation/world dependency as an ordinary parameter:

```kotlin
fun ObjectEngineResult.ObjectKey.groundedArguments(
    operation: SharedOperationContext<*>,
): Arguments.Ground
```

Shared `materializeResult` takes an explicit `operation` and an optional `cycleChecker` defaulting to no-op. The two family-specific `materializeResolverInput` extensions require both arguments explicitly. `semantics.resolvers.materializeResolverInput` is a thin wrapper for Resolver01–23 runtime inputs; it delegates to shared `materializeResult` and its private `MaterializationLogic`. `semantics.resolver26.materializeResolverInput` retains its distinct private `ResolverInputMaterializationLogic`, which can reserve symbolic cells and value promises. Both logic implementations retain the two stable dependencies. Runtime coroutine input materialization uses its operation's checker, while DFS explicitly supplies a no-op checker. Nested `ctx.query` results and correctness replay across all families call `materializeResult` directly with its default no-op checker; nested resolution still uses the operation's checker for runtime resolver inputs. The [materialization contract](./semantics/README.md#shared-semantic-boundaries) explains installed promises, pending bindings, symbolic/stored-key lookup, and the distinction between nested queries and declared Query fragments.

Pure semantics-owned demand transformations receive an ordinary `world: Assumptions` parameter. Tasks and logic implementations use their existing operation or publication owner when calling these APIs. Do not introduce a forwarding property or another context bundle just to shorten that access.

Use the name `operation` consistently. Access stable configuration through its owning context: `operation.world.schema`, `operation.world.resolverRegistry`, and `operation.world.selectiveResolvers`. Access mutable protocols through explicit state properties such as `operation.variableBindings`.

`SharedOperationContext<D>` preserves the type of its dispatcher. Helpers that only read shared semantics state use `SharedOperationContext<*>`; they need no type parameter of their own. Resolver26's `OperationContext` extends `SharedOperationContext<CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence>>`, and shared passive resolution accepts `SharedOperationContext<SharedTaskDispatcher<O, *>>` so its factory's orchestration type `O` remains accepted by `operation.dispatcher`. The dispatcher is accessed through the operation rather than passed separately. `SharedPassiveValueResolutionLogic<T, O>` retains both the accepted orchestration-task type and concrete operation type, so specializations use its one `operation` property instead of storing another typed reference.

`SharedOrchestrationTask<O>` supplies its concrete operation through `operation: O`, and `SharedFieldResolverTask<P>` supplies its concrete publication occurrence through `publication: P`. Tasks compose these contexts without subtyping them, keeping algorithmic work distinct from the contexts it uses. The shared coroutine implementation bases remain responsible for task lifecycle and error handling. `SharedFieldPublicationOccurrence<O, D>` continues to extend `SharedOperationContext<D>` by delegation while retaining its concrete `operation: O`.

`SharedFieldResolverTask` intentionally enforces an architectural relationship even though current callers use concrete tasks. Maintaining Resolver01–23 helps preserve Resolver26's decomposition and encapsulation, and this contract keeps the important field-task/publication boundary explicit across families. The [maintained-resolver rationale](./resolver-versions.md#purpose) applies to shared members whose purpose is architectural consistency as well as those used by generic algorithms.

When a semantics function calls a model operation that requires `Assumptions`, establish that model context locally. During the migration, also establish a context locally when calling a retained contextual semantics API:

```kotlin
fun ObjectEngineResult.validate(operation: SharedOperationContext<*>): Boolean =
    context(operation.world) {
        rootedAndWellTyped()
    }
```

Resolver-specific operation contexts may add stable request references and explicit state properties. They should remain structurally immutable bundles rather than service locators or owners of mutable storage.

## Context Property Ownership

Name variables, parameters, and properties for their semantic role rather than repeating their type's `Context` or `State` suffix: `operation`, `publication`, `oerOccurrence`, `sourceOccurrence`, `fieldResolverOccurrence`, and `variableBindings`. Distinguish the containing OER occurrence, value-source occurrence, and specific resolver-invocation occurrence; an invocation occurrence is distinct from its registered `resolver`. Resolver26 uses `bindingsState` as the shorthand for per-object binding-declaration readiness. For `VariableProviderReadOccurrence`, use `variableProviderRead` in property names and `providerRead` for locals and parameters, pluralizing for collections. Retain standard coroutine terminology such as `coroutineContext`. Type names follow the [context-role naming convention](./semantics/README.md#vocabulary): `Occurrence` for occurrence contexts, `Task` and `Logic` for algorithmic contexts, and `Context` for other organizing principles documented in KDoc. A `with(publication)` scope can expose one publication's inputs directly without introducing owner aliases.

Keep a value on its owning context when that context is already reachable at every use site. A forwarding property that only shortens a path does not justify another exposed property or constructor argument. Orchestration tasks supply `operation`; field tasks supply `publication` and reach operation state through that occurrence. They do not subtype those contexts. Publication occurrences retain the deliberate operation-context subtype relationship through delegation, which reuses the same state and dispatcher identities.

Retain a projection when an existing consumer uses a common contract and would otherwise need implementation-specific contexts or branching. `SharedOrchestrationTask.closedDemand` lets passive traversal consume Resolver26 closure output or an earlier resolver's stored forest through one handle. `ValueSourceOccurrence` supplies publication metadata across ordinary, reference, and conditioned-passive variants, whose types and demand may differ from their containing selection. `DepthFirstTask.path` presents a scheduling path for either task kind. Those projections belong to the shared contracts used by their consumers.

`SharedFieldPublicationOccurrence` adds the common operation handle, containing OER occurrence, and publication cell to the operation contract. Concrete publication types expose their own metadata; Resolver26's `FieldPublicationOccurrence` is a class implementing `OperationContext by operation`. Resolver01–23 use the concrete `GroundedFieldPublicationOccurrence<O>` class, which delegates the shared operation interface and retains concrete `operation: O`. Its shared dispatcher view uses the common bound; family-specific dispatch remains fully typed through `operation.dispatcher`. Field tasks retain the exact publication supplied at dispatch and access its properties through `publication`. The depth-first task's `path` projection remains part of its scheduling contract.

## Call Boundaries

Model context parameters are not global state. Establish the model context at its call boundary and pass the operation explicitly to migrated semantics APIs:

```kotlin
val modelValue = context(world) { objectValue.snipToDemand(selections) }
val resolution = SharedOperationContext.create(world).resolve(selections)
```

`SharedOperationContext` is an interface. Its static `create(world, ...)` factory returns an anonymous `SharedOperationContext<Nothing>` for semantic operations that do not dispatch; requesting its dispatcher fails explicitly. The overload accepting `dispatcher` preserves its concrete type. `DepthFirstOperationContext` and `CoroutineOperationContext` are concrete classes that explicitly implement typed `SharedOperationContext` through delegation. Resolver26's `OperationContext` remains an interface because `FieldPublicationOccurrence` delegates to it; its `create(...)` factory returns an anonymous implementation. Each specialized context adds its family-specific references. Resolver26's `forChildScope` creates a new dispatcher under the supplied scope and retains the same world, variable bindings, observer, cycle checker, and binding-declaration state.

Call migrated semantics APIs with explicit dependencies, including inside functions that still declare context parameters. Retain `context(...)` blocks only for calls that still require them: model operations and the remaining correctness judgments. Keep `context(operation.world)` local to modeled field-resolver invocation. Pure parent-input-demand analysis now takes `world` explicitly. Resolver01–23 demand-policy callbacks capture the existing operation lexically; their function types do not acquire context parameters.

## Receiver-Style Bodies

For a body that reads many world members more clearly as a receiver, use `run` and declare the return type:

```kotlin
context(world: Assumptions)
fun EngineObjectData.Sync.isQueryRoot(): Boolean = world.run {
    this@isQueryRoot.schemaType == schema.query
}
```

Use `run`, not `apply`, when returning a modeled result. `run` returns the lambda result; `apply` would return `world`.

## Validation

[`ContextParametersTest.kt`](./model/src/test/kotlin/model/ContextParametersTest.kt) exercises model-context establishment and composition. Semantics compilation and tests exercise explicit operation passing, the retained contextual APIs, and model crossings through `operation.world`.
