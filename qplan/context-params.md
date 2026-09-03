# Context Parameters, `Assumptions`, And `OperationContext`

Qplan uses Kotlin context parameters at two distinct interpretation boundaries. Pure model operations use the one immutable `Assumptions` value for a reasoning world, while semantics operations use the `OperationContext` for one resolution or correctness operation.

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
context(operation: OperationContext)
fun ...
```

Use the name `operation` consistently. Access stable configuration through `operation.world` or the convenience properties `operation.schema`, `operation.resolverRegistry`, and `operation.selectiveResolvers`. Access mutable protocols through explicit state properties such as `operation.variableBindingsState`.

When a semantics function calls a pure model operation, establish the model context explicitly from the operation:

```kotlin
context(operation: OperationContext)
fun ObjectEngineResult.validate(): Boolean =
    context(operation.world) {
        rootedAndWellTyped()
    }
```

Resolver-specific operation contexts may add stable request references and explicit state properties. They should remain structurally immutable bundles rather than service locators or owners of mutable storage.

## Call Boundaries

Context parameters are not global state. Establish the appropriate context at an outer call boundary:

```kotlin
val modelValue = context(world) { objectValue.snipToDemand(selections) }
val resolution = context(OperationContext(world)) { resolve(selections) }
```

Inside a function with the same context type, call context-dependent operations directly. Add a nested `context(...)` block only when crossing from `OperationContext` to `operation.world`, supplying a separate state context such as `CycleCheckState`, or otherwise changing the available context values.

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
