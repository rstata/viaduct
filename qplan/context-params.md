# Context Parameters and the `Assumptions` World

This project uses Kotlin context parameters to make the one `Assumptions` value for a reasoning world available to functions interpreted under that world.

The project-wide form is:

```kotlin
context(world: Assumptions)
fun ...
```

Use `world` consistently. Although the parameter name is local to each function, it also appears throughout our claims and arguments, so one conventional name makes the model predictable for humans and coding agents.

## Why We Use Context Parameters

Correctness propositions often have the mathematical shape:

```text
world |- predicate
```

For example, the following is mathematical pseudocode rather than a declaration of existing Kotlin functions:

```text
world |- forall(
    obj: Schema.ObjectValue,
    behavioralField: Schema.OutputField,
    selections: SelectionForest,
) {
    snippable(obj, behavioralField, selections) implies
        behavioralField.snip(obj, selections).type == obj.type
}
```

Here `snippable` abbreviates the documented preconditions of `snip`. A context parameter reflects the `world |-` premise directly while allowing operations such as `snip` to use the schema and other fixed assumptions.

## Context Parameters Compose

A function that already has an `Assumptions` context may directly call another function requiring the same context:

```kotlin
context(world: Assumptions)
fun Schema.ObjectValue.copyInWorld(): Schema.ObjectValue =
    world.schema.objectValue(type, outputObjectFields)

context(world: Assumptions)
fun Schema.ObjectValue.copyTwiceInWorld(): Schema.ObjectValue =
    copyInWorld().copyInWorld()
```

The compiler satisfies both calls to `copyInWorld` from the context already available to `copyTwiceInWorld`; no nested context block is needed.

## Accessing the World

A context parameter is not an implicit receiver. Access members of `Assumptions` through the parameter:

```kotlin
context(world: Assumptions)
fun Schema.ObjectValue.copyInWorld(): Schema.ObjectValue =
    world.schema.objectValue(type, outputObjectFields)
```

The current world surface includes `world.schema`, `world.variableValues`, `world.executorRegistry`, `world.behavioral(...)`, and `world.selectionsFrom(...)`. Value factories such as `objectValue` belong to `Schema`, so call them through `world.schema`; they are not members of `Assumptions`.

Prefer explicit qualification when a function uses only a few world members. This is the clearest style for operations such as `Schema.ObjectValue.snip`.

## Receiver-Style Bodies

When an assumption-heavy body reads more clearly with `world` as an implicit receiver, use `run`:

```kotlin
context(world: Assumptions)
fun Schema.ObjectValue.copyInWorld(): Schema.ObjectValue = world.run {
    val copiedFields = outputObjectFields.toMap()
    schema.objectValue(type, copiedFields)
}
```

Inside the `run` body, `schema` resolves against the `Assumptions` receiver while `type` and `outputObjectFields` remain available from the `Schema.ObjectValue` extension receiver. Functions requiring the same `Assumptions` context also remain callable.

Use `run`, not `apply`, when the function returns a modeled result. `run` returns the lambda result; `apply` returns its receiver, which would be `world`.

Always declare the return type of a receiver-style expression body. The explicit type makes the operation's signature clear and lets the compiler catch an accidental switch to a scope function with different return semantics.

## Call Boundaries

Context parameters are not global variables. Establish an `Assumptions` value at a call boundary:

```kotlin
val result =
    context(world) {
        behavioralField.snip(objectValue, selectionForestOf())
    }
```

Inside another function with `context(world: Assumptions)`, call context-dependent functions directly without another `context` block.

## Testing Context Usage

[ContextParametersTest.kt](./model/src/test/kotlin/model/ContextParametersTest.kt) uses real `Assumptions`, `Schema`, and `Schema.ObjectValue` values to exercise context establishment, implicit composition, receiver-style bodies, and `snip`.

Compilation is the primary evidence for receiver and context resolution. Runtime assertions additionally verify that receiver-style functions return the intended model value rather than the `Assumptions` receiver.
