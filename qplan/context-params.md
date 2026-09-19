# Context Parameters, `Assumptions`, And `SharedOperationContext`

Qplan uses Kotlin context parameters at two distinct interpretation boundaries. Pure model operations use the one immutable `Assumptions` value for a reasoning world, while semantics operations use the `SharedOperationContext` for one resolution or correctness operation.

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

## Semantics Context

Resolver algorithms, result materialization, and correctness judgments that need operation-local state or observations declare:

```kotlin
context(operation: SharedOperationContext<*>)
fun ...
```

Use the name `operation` consistently. Access stable configuration through its owning context: `operation.world.schema`, `operation.world.resolverRegistry`, and `operation.world.selectiveResolvers`. Access mutable protocols through explicit state properties such as `operation.variableBindings`.

`SharedOperationContext<D>` preserves the type of its dispatcher. Helpers that only read shared semantics state use `SharedOperationContext<*>`; they need no type parameter of their own. Resolver26's `OperationContext` extends `SharedOperationContext<CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence>>`, and shared passive resolution accepts `SharedOperationContext<SharedTaskDispatcher<O, *>>` so its factory's orchestration type `O` remains accepted by `operation.dispatcher`. The dispatcher is accessed through the operation rather than passed separately. `SharedPassiveValueResolutionLogic<T, O>` retains both the accepted orchestration-task type and concrete operation type, so specializations use its one `operation` property instead of storing another typed reference.

`SharedOrchestrationTask<O>` supplies its concrete operation through `operation: O`, and `SharedFieldResolverTask<P>` supplies its concrete publication occurrence through `publication: P`. Tasks compose these contexts without subtyping them, keeping algorithmic work distinct from the contexts it uses. The shared coroutine implementation bases remain responsible for task lifecycle and error handling. `SharedFieldPublicationOccurrence<O, D>` continues to extend `SharedOperationContext<D>` by delegation while retaining its concrete `operation: O`.

`SharedFieldResolverTask` intentionally enforces an architectural relationship even though current callers use concrete tasks. Maintaining Resolver01–23 helps preserve Resolver26's decomposition and encapsulation, and this contract keeps the important field-task/publication boundary explicit across families. The [maintained-resolver rationale](./resolver-versions.md#purpose) applies to shared members whose purpose is architectural consistency as well as those used by generic algorithms.

When a semantics function calls a pure model operation, establish the model context explicitly from the operation:

```kotlin
context(operation: SharedOperationContext<*>)
fun ObjectEngineResult.validate(): Boolean =
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

Context parameters are not global state. Establish the appropriate context at an outer call boundary:

```kotlin
val modelValue = context(world) { objectValue.snipToDemand(selections) }
val resolution = context(SharedOperationContext.create(world)) { resolve(selections) }
```

`SharedOperationContext` is an interface. Its static `create(world, ...)` factory returns an anonymous `SharedOperationContext<Nothing>` for semantic operations that do not dispatch; requesting its dispatcher fails explicitly. The overload accepting `dispatcher` preserves its concrete type. `DepthFirstOperationContext` and `CoroutineOperationContext` are concrete classes that explicitly implement typed `SharedOperationContext` through delegation. Resolver26's `OperationContext` remains an interface because `FieldPublicationOccurrence` delegates to it; its `create(...)` factory returns an anonymous implementation. Each specialized context adds its family-specific references. Resolver26's `forChildScope` creates a new dispatcher under the supplied scope and retains the same world, variable bindings, observer, cycle checker, and binding-declaration state.

Inside a function with the same context type, call context-dependent operations directly. Add a nested `context(...)` block only when crossing from `SharedOperationContext` to `operation.world`, supplying a separate state context such as `CycleCheckState`, or otherwise changing the available context values. Resolver01–23 receive their configuration through the operation context; their demand-policy callbacks and dependency ordering use that operation directly. Keep `context(operation.world)` local to APIs that require `Assumptions`, such as modeled field-resolver invocation and pure parent-input-demand analysis, rather than surrounding whole resolver task bodies.

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

[`ContextParametersTest.kt`](./model/src/test/kotlin/model/ContextParametersTest.kt) exercises model-context establishment and composition. Semantics compilation and tests exercise operation-context composition, explicit state contexts, and crossings through `operation.world`.
