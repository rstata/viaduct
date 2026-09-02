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

The adapter translates field executors into qplan `FieldResolverDefinition` values. It maps source field coordinates through `SourceSchemaAdapter`, decodes object and Query required selections into the canonical schema, recovers supported variable declarations across both executor fragments, passes resolved arguments plus synchronous object and occurrence-specific Query data through a one-element `FieldResolverExecutor.Selector`, and normalizes source-shaped executor outputs before they enter qplan.

The mock field-executor surface returns `Any?`, permits a raw map or source-shaped EOD as the source for a concrete GraphQL object field, and relies on GraphQL completion to serialize built-in scalar results. Qplan's `EngineOutputData` contract is stricter: object output must be a conforming `EngineObjectData.Sync`, and scalar output must already inhabit its canonical runtime domain. The adapter therefore uses the declared concrete object type to recursively materialize those object sources and applies the source scalar's GraphQL-Java serialization before values cross into qplan. It does not accept raw maps for interface or union outputs because those values do not provide the concrete runtime type needed for an unambiguous conversion.

In keeping with the architecture of qplan, the adapter translates node executors into field resolvers on fields that return Node types.  This is a process called "lowering:" the schema used for field resolution is slightly modified ("lowered") to conveniently support node-resolvers-as-field resolvers, and similarly node-resolver executors are modified to be put into the resolver registry as field resolvers. A raw node-executor payload may omit the repeated `id`: shared fixture lowering combines its fields with the authoritative ID supplied by the Node-valued fringe before the effective object enters qplan. The adapter also supplies local equivalents of built-in `Query.node` and `Query.nodes` when the module does not provide those executors.

### Required-Selection Variables

`RequiredSelectionSetVariableRecovery` is the boundary that converts supported Engine API RSS variable resolvers back into qplan `VariableDeclaration` values. This intentionally reverse engineers the production compilation path rather than invoking `VariablesResolver.resolve`: [`RequiredSelectionSetSupport`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/bootstrap/executionregistry/RequiredSelectionSetSupport.kt) turns execution-registry declarations into selection-set variables, [`VariablesResolver.Builder.buildOne`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/VariablesResolver.kt) compiles those declarations into resolver recipes, and [`RequiredSelectionSetFactory`](../../core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/bootstrap/RequiredSelectionSetFactory.kt) validates and installs them on each executor [`RequiredSelectionSet`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/RequiredSelectionSet.kt).

`FromArgument(name, path)` recovery accepts exactly one path segment. Recovery recursively unwraps production `Validated` decorators, associates each provider name with the exact variable occurrence decoded across its owning resolver's object and Query fragments, and emits `schema.fromArgument(ownerField, argumentName)`. This preserves renamed bindings such as `$vary` sourced from argument `y`; it also prevents same-named variables owned by different resolvers from being conflated. Qplan's registry model supports canonical paths through nested input objects, and Engine runtime validates that richer form in [`FromArgumentVariablesHaveValidPaths`](../../core/engine/runtime/src/main/kotlin/viaduct/engine/runtime/tenantloading/FromArgumentVariablesHaveValidPaths.kt); only this production-recovery adapter remains restricted because it has not yet decoded multi-segment recipes into that model.

`FromFieldVariablesResolver(name, path, requiredSelectionSet)` recovery treats `path` as an alias-preserving response-key path. Because the Engine API type does not retain whether it came from `fromObjectField` or `fromQueryField`, recovery reconstructs the origin by requiring its nested RSS to equal exactly one of the executor's object or Query RSSes filtered to that path. It recursively checks nested RSS variable dependencies against both root fragments, requires repeated provider recipes to agree, and emits `schema.fromObjectField(objectFragment, path)` or `schema.fromQueryField(queryFragment, path)`. A provider that matches both fragments is rejected as ambiguous rather than guessed.

Recovery requires exact agreement between both fragments' variable occurrences and the RSS provider names. It rejects missing, unused, duplicate, inconsistent, or ambiguous providers; nested argument paths; field paths not proven by either root RSS; and arbitrary provider callbacks.

## Feature Test Guidelines

A failing production feature-test port is evidence of a disagreement, but not by itself evidence of an engine bug. Before changing code, identify the value or behavior under dispute, who produces it, who consumes it, and which boundary owns the governing contract.

Classify the disagreement before choosing a repair:

1. A normative engine contract should be enforced by the model and semantics -- **DO NOT FIX THESE**.
2. A feature-test convenience that falls outside that contract should be normalized by the test adapter before it enters qplan.
3. A documented qplan restriction should remain an unchanged, disabled production test until that restriction is deliberately lifted.
4. An implementation that violates its claimed contract should be fixed at the narrowest owning boundary.

When you find resolver (engine contract) errors, do not fix them.  Instead report them to the User.

Compatibility belongs at ingress. Do not make qplan's engine accept a representation excluded by its contract merely because a production fixture or mock executor can produce it. Conversely, an ingress adapter must not conceal a contract violation produced inside qplan.

Keep production test fixtures, behavior, and assertions intact so failures continue to describe the real disagreement. Generated tests are useful for finding invariant failures and interactions; once understood, add focused deterministic regressions that state the contract directly. Validate semantic changes across nested object and list occurrences rather than only against the first failing example.

## Current Support

The feature-test adapter currently supports:

- Unbatched, non-selective field and node resolvers.
- Field arguments, including values supplied by GraphQL operation variables.
- Object required selections, including aliases, arguments, transitive requirements, repeated argumented fields, shared requirements, and multiple requirements.
- Query required selections, including aliases, arguments, fragments, transitive requirements, nested object access, null values, and combinations with object required selections.
- Top-level from-argument variables in object or Query required selections, including variable names that differ from their source argument names.
- From-object-field and from-Query-field paths through singular objects to scalar, enum, or scalar-list terminals, including aliases, nullable traversal, multiple variables, non-root resolver owners, cross-fragment consumption, and argument-bearing provider keys grounded from literals, defaults, owner arguments, or other acyclic from-field bindings.
- Synchronous scalar, enum, list, object, and `NodeReference` outputs, including raw map sources for concrete object fields.
- Partially populated Query executor maps, with missing nullable fields resolving to null and missing non-null fields resolving to an error.
- Node-valued fields and built-in `Query.node` and `Query.nodes`.
- `__typename` through canonical qplan lowering and GraphQL-Java completion.

The adapter rejects or does not yet model:

- Nested input-object paths for from-argument variables.
- Arbitrary callback variable providers and from-field providers whose erased production representation ambiguously matches both resolver fragments.
- Batched or selective field and node resolvers.
- Inline object values from a Node-valued field; qplan currently requires every Node value to be resolved by its node resolver.
- Checker and type-checker executors, including their object- and Query-rooted required selections.
- Mutations, subscriptions, and custom scalars, which remain outside the current qplan scope.

The test-only adapter uses `runBlocking` to cross the suspend executor SPI. That is acceptable for this synchronous feature-test surface and is not a proposed production scheduling design.

## Testing

Tests under `src/test/kotlin/execution` exercise the GraphQL boundary, resolver semantics, completion, and the executor adapter. `EngineTestModuleQPlanFeatureTest` covers adapter-specific behavior and rejection boundaries. Ports of production Viaduct runtime feature tests live separately under `src/test/kotlin/execution/viaductfeaturetests` in the `execution.viaductfeaturetests` package.

### Source-Faithful Feature-Test Migration

The migration unit is an entire production feature-test file. Once a source file is brought into qplan, copy every test in source order together with its fixture structure, local helpers, behavior, and assertions; do not select only the tests expected to pass. Preserve the source filename and test names. The only permitted changes inside the migrated source are the package/import plumbing required by qplan, replacement of each production `runFeatureTest` call with `runQPlanFeatureTest`, source-path/count metadata, and coded `@Disabled` annotations. In particular, do not substitute `EngineTestModule` for `MockTenantModuleBootstrapper`, add null assertions such as `!!`, alter a resolver or assertion, replace an unsupported call with `error(...)`, or delete a source helper to make the port compile.

If an untouched source-faithful test does not compile or cannot execute through qplan, fix the migration fixture or runner boundary, or leave the migration blocked; do not rewrite the test body to fit qplan. No copied test in this package, enabled or disabled, may execute through the production feature-test harness. A file with omitted source tests is an unfinished migration, not a partial port that may be treated as complete.

Production helpers are copied only when the migrated tests actually use them. Tests that require production `KeyTree` or `KeyTreeBuilder` utilities are outside this migration surface and remain file- or test-level N/A until that infrastructure is deliberately brought into scope.

A copied production test that does not pass under qplan must remain in the port with its fixture, behavior, and assertions unchanged and be marked `@Disabled` with a short investigation reason. This applies whether the blocker is an implementation bug, an adapter gap, or a documented qplan restriction. Never omit or rewrite an unsupported production behavior into a different passing test.

When the reason is a deliberate incompatibility with qplan's assumptions, add an enabled test immediately after the disabled production test whose name is exactly `ALTERNATIVE ` followed by the production test name. The alternative preserves the same scenario and makes the smallest possible adjustment needed to state the corresponding qplan behavior. It is qplan-specific coverage rather than a copied production test, so it does not contribute to the copied/source count in the file header.

Immediately after the package declaration, every migrated file records its source path from the repository root and its copied/source test count as of the review date:

```kotlin
package execution.viaductfeaturetests

// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/execution/RequiredSelectionsTest.kt
// Copied 60 out of 60 tests as of 2026-08-20
```

Update both metadata lines whenever source location or test counts change. Count source-level test declarations consistently, including disabled tests, and use an ISO date. A completed migration always records equal copied and source counts; unequal counts expose unfinished legacy migration work and must not be normalized as the steady state. The current inventory and next whole-file migrations are tracked in [`viaduct-feature-test-inventory.md`](./viaduct-feature-test-inventory.md).

Run the adapter, RSS-recovery tests, and every ported production feature-test file with:

```shell
./gradlew :execution:test \
  --tests execution.EngineTestModuleQPlanFeatureTest \
  --tests 'execution.viaductfeaturetests.*' \
  --tests execution.testing.RequiredSelectionSetVariableRecoveryTest
```

Run the complete execution suite with `./gradlew :execution:test`, and run every qplan validation gate with `./gradlew check`.

## Next Steps

Nested input-object argument paths need deliberate adapter decoding before multi-segment `FromArgument.path` values can be recovered into qplan's existing canonical path representation. Custom or mock `VariablesResolver` implementations should remain explicit rejection cases until each has both a model and adapter tests.

After variables, useful incremental steps are structured executor error metadata beyond the retained causal throwable, asynchronous EOD support, requested-selection plumbing for selective executors, and a deliberate batching design. Dispatcher and data-loader integration should remain a separate decision because Resolver26 already owns dependency scheduling and should not accidentally inherit a second scheduler.

[Future work: From Qplan Execution Harness to an Engine Implementation](https://slate.airbnb.tools/zGyuI7hCin) analyzes the gap between the current execution harness and a production implementation of the three `Engine` API methods, including the recommended implementation sequence.
