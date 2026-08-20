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

The adapter translates field executors into qplan `FieldResolverDefinition` values. It maps source field coordinates through `SourceSchemaAdapter`, decodes object required selections into the canonical schema, recovers supported variable declarations from the same executor RSS, passes resolved arguments and synchronous object data through a one-element `FieldResolverExecutor.Selector`, and normalizes source-shaped executor outputs before they enter qplan.

In keeping with the architecture of qplan, the adapter translates node executors into field resolvers on fields that return Node types.  This is a process called "lowering:" the schema used for field resolution is slightly modified ("lowered") to conveniently support node-resolvers-as-field resolvers, and similarly node-resolver executors are modified to be put into the resolver registry as field resolvers.  The adapter also supplies local equivalents of built-in `Query.node` and `Query.nodes` when the module does not provide those executors.

### Required-Selection Variables

`RequiredSelectionSetVariableRecovery` is the boundary that converts supported Engine API RSS variable resolvers back into qplan `VariableDeclaration` values. This intentionally reverse engineers the production compilation path rather than invoking `VariablesResolver.resolve`: [`RequiredSelectionSetSupport`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/bootstrap/executionregistry/RequiredSelectionSetSupport.kt) turns execution-registry declarations into selection-set variables, [`VariablesResolver.Builder.buildOne`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/VariablesResolver.kt) compiles those declarations into resolver recipes, and [`RequiredSelectionSetFactory`](../../core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/bootstrap/RequiredSelectionSetFactory.kt) validates and installs them on each executor [`RequiredSelectionSet`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/RequiredSelectionSet.kt).

`FromArgument(name, path)` recovery accepts exactly one path segment. Recovery recursively unwraps production `Validated` decorators, associates each provider name with the exact variable occurrence decoded from its owning resolver fragment, and emits `schema.fromArgument(ownerField, argumentName)`. This preserves renamed bindings such as `$vary` sourced from argument `y`; it also prevents same-named variables owned by different resolvers from being conflated. Engine runtime validates the richer nested-path form in [`FromArgumentVariablesHaveValidPaths`](../../core/engine/runtime/src/main/kotlin/viaduct/engine/runtime/tenantloading/FromArgumentVariablesHaveValidPaths.kt), but qplan recovery rejects that form until its registry model can represent input-object traversal.

`FromFieldVariablesResolver(name, path, requiredSelectionSet)` recovery treats `path` as an alias-preserving object response-key path. Because the Engine API type does not retain whether it came from `fromObjectField` or `fromQueryField`, recovery proves the object origin by requiring its nested RSS to equal the executor object RSS filtered to that path. It recursively checks nested RSS variable dependencies against the root object RSS, requires repeated provider recipes to agree, and emits `schema.fromObjectField(objectFragment, path)`.

Recovery requires exact agreement between the fragment's variable occurrences and the RSS provider names. It rejects missing, unused, duplicate, or inconsistent providers; nested argument paths; field paths not proven by the root object RSS; and arbitrary provider callbacks. Query RSS variables remain outside this adapter because query required selections themselves are not yet supported.

## Current Support

The feature-test adapter currently supports:

- Unbatched, non-selective field and node resolvers.
- Field arguments, including values supplied by GraphQL operation variables.
- Object required selections, including aliases, arguments, transitive requirements, repeated argumented fields, shared requirements, and multiple requirements.
- Top-level from-argument variables in object required selections, including variable names that differ from their source argument names.
- From-object-field paths through singular objects to scalar, enum, or scalar-list terminals, including aliases, nullable traversal, multiple variables, and non-root resolver owners.
- Synchronous scalar, enum, list, object, and `NodeReference` outputs.
- Node-valued fields and built-in `Query.node` and `Query.nodes`.
- `__typename` through canonical qplan lowering and GraphQL-Java completion.

The adapter rejects or does not yet model:

- Nested input-object paths for from-argument variables.
- From-object-field provider paths containing a field that declares arguments, an explicit Resolver26 restriction even when those arguments are ground.
- From-Query-field and arbitrary callback variable providers.
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

The current ports are `EngineFeatureTestExample.kt`, `RequiredSelectionsTest.kt`, and supported from-argument and from-object-field cases in `FromFieldVariablesFeatureTest.kt`, totaling twenty-one runtime tests. Run the adapter and ported tests with:

```shell
./gradlew :execution:test \
  --tests execution.EngineTestModuleQPlanFeatureTest \
  --tests execution.EngineFeatureTestExample \
  --tests execution.RequiredSelectionsTest \
  --tests execution.FromFieldVariablesFeatureTest \
  --tests execution.testing.RequiredSelectionSetVariableRecoveryTest
```

Run the complete execution suite with `./gradlew :execution:test`, and run every qplan validation gate with `./gradlew check`.

## Next Steps

The next path-provider question is whether to lift Resolver26's argument-free provider-path restriction. Recovery already reconstructs provider dependencies recursively, including a path field whose argument comes from a top-level `FromArgument`, but Resolver26 deliberately rejects every provider path containing a field definition with arguments.

Nested input-object argument paths need a deliberate qplan representation before they can be recovered from multi-segment `FromArgument.path` values. From-Query paths and custom or mock `VariablesResolver` implementations should remain explicit rejection cases until each has both a model and adapter tests.

After variables, useful incremental steps are richer executor error preservation, asynchronous EOD support, requested-selection plumbing for selective executors, and a deliberate batching design. Dispatcher and data-loader integration should remain a separate decision because Resolver26 already owns dependency scheduling and should not accidentally inherit a second scheduler.
