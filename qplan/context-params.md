# Context Parameters And `Assumptions`

This project uses Kotlin context parameters to make the one `Assumptions` value for a reasoning world available to operations interpreted in that world:

```kotlin
context(world: Assumptions)
fun ...
```

Use the name `world` consistently. It mirrors the mathematical judgment `world |- predicate` and keeps claims, code, and tests readable.

## Composition

A function with an `Assumptions` context may directly call another function requiring the same context:

```kotlin
context(world: Assumptions)
fun Value.Object.copyInWorld(): Value.Object =
    Value.Object.of(type, fieldValues)

context(world: Assumptions)
fun Value.Object.copyTwiceInWorld(): Value.Object =
    copyInWorld().copyInWorld()
```

The compiler supplies both calls from the existing context. No nested context block is needed.

## Access

A context parameter is not an implicit receiver. Qualify members as `world.schema`, `world.resolverRegistry`, `world.selectiveResolvers`, `world.behavioral(...)`, and `world.selectionsFrom(...)`.

Prefer qualification when a body uses only a few world members.

For an assumption-heavy body that reads better with a receiver, use `run`:

```kotlin
context(world: Assumptions)
fun Value.Object.copyInWorld(): Value.Object = world.run {
    val copiedFields = fieldValues.toMap()
    Value.Object.of(type, copiedFields)
}
```

Use `run`, not `apply`, when returning a modeled result. `run` returns the lambda result; `apply` would return `world`.

Declare the return type of a receiver-style expression body so an accidental scope-function change is caught by the compiler.

## Call Boundaries

Context parameters are not global state. Establish the world at an outer call boundary:

```kotlin
val result =
    context(world) {
        objectValue.snipToDemand(selectionForestOf())
    }
```

Inside another `context(world: Assumptions)` function, call context-dependent operations directly.

## Validation

[ContextParametersTest.kt](model/src/test/kotlin/model/ContextParametersTest.kt) exercises context establishment, composition, receiver-style bodies, and `snipToDemand` with real model values.

Compilation is the primary evidence for context and receiver resolution. Runtime assertions verify that receiver-style functions return the modeled result rather than the `Assumptions` receiver.
