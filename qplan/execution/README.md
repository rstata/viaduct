# Qplan Execution

The execution module is qplan's GraphQL-Java execution harness. It converts a validated query into qplan selections, resolves those selections with Resolver26, and gives the resulting `ObjectEngineResult` tree back to GraphQL Java for ordinary response completion.  This module's main integration surface is a feature-test adapter that runs real Engine API mock executors against qplan.

## Architecture

Bootstrap has the following shape:

```text
EngineTestModule
  -> source GraphQLSchema rendered as SDL
  -> TestWorld.fromSDL
       -> source GraphQL-Java schema plus canonical lowered ViaductSchema
       -> executor-backed field and node resolver definitions
       -> shared node and typename lowering
       -> ResolverRegistry
       -> Assumptions
  -> ExecutionTestFixture
       -> QPlanWiringFactory
       -> QPlanExecutionStrategy
```

Per-request execution has the following shape:

```text
GraphQL Java parsing, validation, and input coercion
  -> QPlanExecutionStrategy
  -> operation decoding into SelectionForest
  -> Resolver26.resolve under Assumptions
  -> ObjectEngineResult tree
  -> QPlanWiringFactory data fetchers and type resolvers
  -> GraphQL Java output completion
  -> ExecutionResult
```

`QPlanExecutionStrategy` establishes the request's `Assumptions` context and invokes Resolver26 once for the complete query demand. `QPlanWiringFactory` does not resolve tenant fields; it only projects already-resolved OER cells into the values GraphQL Java expects while preserving GraphQL null, list, abstract-type, and error completion.  (You can think of `Assumptions` as basically the top-level request execution context, containing for example the schema under which an operation is being executed.)

## Executor-Backed Feature Tests

`EngineTestModule.runQPlanFeatureTest` is defined in `src/testFixtures/kotlin/execution/testing/EngineTestModuleQPlanFeatureTest.kt`. It consumes the pre-dispatcher field and node executor maps exposed by `EngineTestModule`.

The adapter translates field executors into qplan `FieldResolverDefinition` values. It maps source field coordinates through `SourceSchemaAdapter`, decodes object required selections into the canonical schema, passes resolved arguments and synchronous object data through a one-element `FieldResolverExecutor.Selector`, and normalizes source-shaped executor outputs before they enter qplan.

In keeping with the architecture of qplan, the adapter translates node executors into field resolvers on fields that return Node types.  This is a process called "lowering:" the schema used for field resolution is slightly modified ("lowered") to conveniently support node-resolvers-as-field resolvers, and similarly node-resolver executors are modified to be put into the resolver registry as field resolvers.  The adapter also supplies local equivalents of built-in `Query.node` and `Query.nodes` when the module does not provide those executors.

## Current Support

The feature-test adapter currently supports:

- Unbatched, non-selective field and node resolvers.
- Field arguments, including values supplied by GraphQL operation variables.
- Object required selections without required-selection variables, including aliases, arguments, transitive requirements, repeated argumented fields, shared requirements, and multiple requirements.
- Synchronous scalar, enum, list, object, and `NodeReference` outputs.
- Node-valued fields and built-in `Query.node` and `Query.nodes`.
- `__typename` through canonical qplan lowering and GraphQL-Java completion.

The adapter rejects or does not yet model:

- Variables in object required selections, including from-argument and from-object-field providers.  (These are supported by the resolver but haven't yet been adapted to the engine.)
- Batched or selective field and node resolvers.
- Query required selections.
- Checker and type-checker executors.
- Mutations, subscriptions, and custom scalars, which remain outside the current qplan scope.

The test-only adapter uses `runBlocking` to cross the suspend executor SPI. That is acceptable for this synchronous feature-test surface and is not a proposed production scheduling design.

## Testing

Tests under `src/test/kotlin/execution` exercise the GraphQL boundary, resolver semantics, completion, and the executor adapter. `EngineTestModuleQPlanFeatureTest` covers adapter-specific behavior and rejection boundaries.

Runtime feature tests ported into this module retain the originating filename. Immediately after the package declaration, each ported file records its source path from the repository root:

```kotlin
package execution

// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/execution/RequiredSelectionsTest.kt
```

The current ports are `EngineFeatureTestExample.kt` and `RequiredSelectionsTest.kt`, totaling ten runtime tests. Run the adapter and ported tests with:

```shell
./gradlew :execution:test \
  --tests execution.EngineTestModuleQPlanFeatureTest \
  --tests execution.EngineFeatureTestExample \
  --tests execution.RequiredSelectionsTest
```

Run the complete execution suite with `./gradlew :execution:test`, and run every qplan validation gate with `./gradlew check`.

## Next Steps

The next executor feature should be declarative variables in object required selections. Qplan already models `fromArgument` and `fromObjectField` declarations, so the adapter should translate supported Engine API `VariablesResolver` instances into the `variableProviders` input of `TestWorld.fromSDL` instead of invoking arbitrary variable-resolver callbacks during execution.

A conservative first slice should support concrete Engine API `FromArgument` values whose path names one top-level field argument, plus concrete `FromFieldVariablesResolver` values whose object path contains no list traversal and terminates at a scalar or enum. The adapter can map these to `schema.fromArgument(...)` and `schema.fromObjectField(...)` declarations associated with the exact variable occurrences in each decoded object fragment.

That first slice should explicitly reject nested input-object argument paths, from-Query paths, custom or mock `VariablesResolver` implementations, list-valued object paths, and recursively variable-dependent provider selection sets. Each restriction should have an adapter test before more runtime feature tests are ported.

After variables, useful incremental steps are richer executor error preservation, asynchronous EOD support, requested-selection plumbing for selective executors, and a deliberate batching design. Dispatcher and data-loader integration should remain a separate decision because Resolver26 already owns dependency scheduling and should not accidentally inherit a second scheduler.
