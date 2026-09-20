# Qplan Execution

The execution module is qplan's GraphQL-Java execution harness. It converts a validated query into qplan selections, starts Resolver26, and gives its live promise-backed `ObjectEngineResult` tree back to GraphQL Java for ordinary or incremental response completion. This module's main integration surface is a feature-test adapter that runs real Engine API mock executors against qplan.

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
  -> Resolver26.startResolve under Assumptions and a request-owned coroutine scope
  -> live ObjectEngineResult tree with a frozen key shape and possibly pending values
  -> QPlanWiringFactory immediate values or request-owned CompletionStage bridges
  -> GraphQL Java output completion and @defer payload splitting
  -> QPlanInstrumentation incremental publisher lifetime cleanup
  -> ExecutionResult or IncrementalExecutionResult
```

`QPlanExecutionStrategy` establishes the request's `Assumptions` context and invokes Resolver26 once for the complete query demand, including selections inside deferred fragments. `QPlanWiringFactory` does not resolve tenant fields; it projects completed OER cells immediately and exposes pending promises as request-owned completion stages while preserving GraphQL null, list, abstract-type, and error completion. Each completion bridge is coupled to its coroutine job's terminal state and prefers a promise's recorded terminal failure over the parent-job cancellation that failure initiates, so cancellation before coroutine entry cannot strand a future and deferred errors retain their originating cause. List elements are awaited concurrently, and a terminal non-cancellation element failure takes precedence over sibling cancellations regardless of list order. `QPlanInstrumentation` must be installed with the strategy because graphql-java adds its incremental publisher after the query strategy returns; the instrumentation keeps the Resolver26 request alive until that publisher completes, fails, or is cancelled. (You can think of `Assumptions` as basically the top-level request execution context, containing for example the schema under which an operation is being executed.)

Feature tests may provide a scoped executable schema distinct from the full schema used to build the reasoning world and executor registry. GraphQL Java validates and completes the public operation against the scoped schema, while Resolver26 retains private fields from the full schema for resolver-required selections.

## Executor-Backed Feature Tests

`EngineTestModule.runQPlanFeatureTest` is defined in `src/testFixtures/kotlin/execution/testing/EngineTestModuleQPlanFeatureTest.kt`. It consumes the pre-dispatcher field and node executor maps exposed by `EngineTestModule`.

The adapter translates field executors into qplan `FieldResolverDefinition` values. It maps source field coordinates through `SourceSchemaAdapter`, decodes object and Query required selections into the canonical schema, recovers supported variable declarations across both executor fragments, passes resolved arguments plus synchronous object and occurrence-specific Query data through a one-element `FieldResolverExecutor.Selector`, and normalizes source-shaped executor outputs before they enter qplan. Selective field executors are assembled with `selectiveFieldResolverOf`; their Resolver26 successor demand is converted to an `EngineSelectionSet` for composite outputs, while scalar outputs receive no selection set. The conversion restores qplan's lowered typename field to source `__typename` before crossing the Engine API boundary.

The adapter also honors `EngineConfiguration.fieldSelectivityProvider` when executor metadata itself does not declare a field selective. Other engine configuration remains production-runtime input and is ignored unless a supported adapter behavior explicitly consumes it.

Resolver26 passes its concrete `FieldResolverTask` to each suspending registry function as its `ResolutionExecutionContext`. The adapter constructs an invocation-local `EngineExecutionContext` from that explicit capability; `EngineExecutionContext.resolveSelectionSet` converts a Query selection set into canonical qplan selections and delegates directly to the task. The task creates a fresh Query-rooted OER, derives a dispatcher from its own field-task scope, and materializes the requested response-key shape as structured child work. The nested execution retains the parent operation's world, variable bindings, cycle checker, binding-declaration state, and observer; it does not create another top-level request.

The mock field-executor surface returns `Any?`, permits a raw map or source-shaped EOD as the source for a concrete GraphQL object field, and relies on GraphQL completion to serialize built-in scalar results. Qplan's `EngineOutputData` contract is stricter: object output must be a conforming `EngineObjectData.Sync`, and scalar output must already inhabit its canonical runtime domain. The adapter therefore uses the declared concrete object type to recursively materialize those object sources and applies the source scalar's GraphQL-Java serialization before values cross into qplan. Nested `EngineErrorData` values are preserved during this normalization so Resolver26 can attribute dependency failures at their consumers. The adapter does not accept raw maps for interface or union outputs because those values do not provide the concrete runtime type needed for an unambiguous conversion.

Production `RootFieldReference` values are normalized recursively into qplan-owned `RootFieldReferenceData`, including direct executor results and references nested in EOD fields or lists. `ResolverOutputData` is the resolver-facing union of ordinary `EngineOutputData` and this symbolic reference carrier; references are not members of the engine-data domain supplied as resolver input. The adapter does not call production root-reference resolution. It supplies dependency-free empty objects for unsupplied namespace fields so ordinary Query fragments may traverse namespace paths. Resolver26 gives every reference occurrence and direct-result tail hop its own fresh empty Query-rooted identity OER; those roots contain no namespace execution, are distinct from resolver Query-fragment roots, and are not shared across equivalent descriptors. A referenced target with object RSS is rejected; tenant code must express the corresponding dependency as Query RSS with its namespace path prefixed.

In keeping with qplan's root-field-reference architecture, Node-valued fields retain their source coordinates and their `NodeReference` outputs normalize to root-field references targeting the built-in `Query.node`. The reference's internal ID encoding preserves both the concrete object type and the original authoritative resolver ID. `Query.node` recognizes that encoding and dispatches directly to the corresponding node executor; ordinary client calls to `Query.node` continue through its normal field resolver. `Query.nodes` returns a list of `Query.node` references, so Resolver26 resolves each non-null element as an independent occurrence. Selective node executors receive Resolver26's one-shot node-owned demand. Before entering qplan, selective node output is projected to demanded top-level fields, fields owned by registered field resolvers are removed, and omitted demanded nullable fields are represented explicitly as null. This preserves production's ownership and nullable-coverage boundary without weakening qplan's surplus- or missing-output rejection. A raw node-executor payload may omit the repeated `id`; the originating reference ID remains authoritative and is restored only when `id` is demanded. If converted demand contains only that engine-managed `id`, the adapter returns an empty source payload without invoking the selective node executor; `__typename` is likewise completed by qplan's generated resolver. The adapter supplies local equivalents of built-in `Query.node` and `Query.nodes` when the module does not provide those executors.

### Required-Selection Variables

`RequiredSelectionSetVariableRecovery` is the boundary that converts supported Engine API RSS variable resolvers back into qplan `VariableDeclaration` values. This intentionally reverse engineers the production compilation path rather than invoking `VariablesResolver.resolve`: [`RequiredSelectionSetSupport`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/bootstrap/executionregistry/RequiredSelectionSetSupport.kt) turns execution-registry declarations into selection-set variables, [`VariablesResolver.Builder.buildOne`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/VariablesResolver.kt) compiles those declarations into resolver recipes, and [`RequiredSelectionSetFactory`](../../core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/bootstrap/RequiredSelectionSetFactory.kt) validates and installs them on each executor [`RequiredSelectionSet`](../../core/engine/api/src/main/kotlin/viaduct/engine/api/RequiredSelectionSet.kt).

`FromArgument(name, path)` recovery retains the complete argument and nested input-object path. Recovery recursively unwraps production `Validated` decorators, associates each provider name with the exact variable occurrence decoded across its owning resolver's object and Query fragments, and emits `schema.fromArgument(ownerField, path)`. This preserves renamed bindings such as `$vary` sourced from argument `y`; it also prevents same-named variables owned by different resolvers from being conflated. Canonical qplan path evaluation propagates null through nullable intermediate input objects.

`FromFieldVariablesResolver(name, path, requiredSelectionSet)` recovery treats `path` as an alias-preserving response-key path. Because the Engine API type does not retain whether it came from `fromObjectField` or `fromQueryField`, recovery reconstructs the origin by requiring its nested RSS to equal exactly one of the executor's object or Query RSSes filtered to that path. It recursively checks nested RSS variable dependencies against both root fragments, requires repeated provider recipes to agree, and emits `schema.fromObjectField(objectFragment, path)` or `schema.fromQueryField(queryFragment, path)`. A provider that matches both fragments is rejected as ambiguous rather than guessed.

When canonical path compilation rejects traversal through a lossy abstract type condition, recovery reports the production-facing `InvalidVariableException` with the owning coordinate and variable name. This preserves the bootstrap validation contract while retaining qplan's stricter compiled provider path internally.

All disjoint no-RSS `VariablesResolver` callbacks that remain after unwrapping production `Validated` decorators are composed as the field resolver's tenant variables provider. The composed suspending function is attached directly to the qplan resolver, receives the occurrence's grounded arguments, and is invoked once even when both object and Query fragments consume its bindings. Recovery requires exact agreement between both fragments' variable occurrences and all provider names. It rejects missing, unused, duplicate, inconsistent, or ambiguous providers; nested argument paths; field paths not proven by either root RSS; and overlapping callback variable names. Legacy arbitrary callbacks with their own required selections are intentionally not recovered: the declarative executor variable SPI introduced by [treehouse PR #1116337](https://git.musta.ch/airbnb/treehouse/pull/1116337) admits declarative argument/object-field/Query-field definitions and a function provider without a provider-owned RSS, so feature tests specifically exercising callback-owned RSS behavior are not applicable to qplan.

A nested `ctx.query` call is distinct from a resolver's declared Query fragment. A nested call executes a selection requested by resolver code through the owning field task, then uses `semantics.shared.materializeResult` to return response-keyed values from installed result cells, awaiting unfinished values or bindings as needed. This result projection uses the default no-op checker; runtime resolver inputs within the nested query still use the operation's checker. A declared Query fragment supplies resolver input through Resolver26's distinct runtime `materializeResolverInput`, which can reserve symbolic cells and value promises before producers install them. Correctness replay uses shared `materializeResult` for both object and Query-fragment inputs reconstructed from existing results, including Resolver26 results that retain symbolic keys.

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

- Selective and non-selective field and node resolvers.
- Field selectivity supplied through `EngineConfiguration.fieldSelectivityProvider`.
- Resolver-demand conversion to `EngineSelectionSet`, including concrete applicability, nested demand, resolved arguments, and lowered `__typename` restoration.
- Field arguments, including values supplied by GraphQL operation variables.
- Object required selections, including aliases, arguments, transitive requirements, repeated argumented fields, shared requirements, and multiple requirements.
- Query required selections, including aliases, arguments, fragments, transitive requirements, nested object access, null values, and combinations with object required selections.
- From-argument variables in object or Query required selections, including nested input-object paths, nullable traversal, and variable names that differ from their source argument names.
- From-object-field and from-Query-field paths through singular objects to scalar, enum, or scalar-list terminals, including aliases, nullable traversal, multiple variables, non-root resolver owners, cross-fragment consumption, and argument-bearing provider keys grounded from literals, defaults, owner arguments, or other acyclic from-field bindings.
- Synchronous scalar, enum, list, object, and `NodeReference` outputs, including raw map sources for concrete object fields.
- Direct and recursively nested `RootFieldReference` outputs, including namespace paths, arguments, lists, and referenced resolvers with Query required selections.
- Partially populated Query executor maps, with missing nullable fields resolving to null and missing non-null fields resolving to an error.
- Node-valued fields and built-in `Query.node` and `Query.nodes`.
- `__typename` through canonical qplan lowering and GraphQL-Java completion.
- GraphQL Java 26 `@defer` delivery for qplan-backed fields, including conditional defer, nested objects, deferred errors, and downstream cancellation. `@stream` remains outside this scope; lists are conservatively bridged as whole values.
- Distinct scoped executable schemas whose resolver-required selections read private fields from the full schema.
- Query selection execution through `ctx.query()`/`EngineExecutionContext.resolveSelectionSet`, including nested calls, aliases, arguments, variables, and field- or node-executor callers.

The adapter rejects or does not yet model:

- Callback providers with overlapping variable names, and from-field providers whose erased production representation ambiguously matches both resolver fragments. Legacy callbacks with their own required selections are deliberately outside the target executor SPI rather than an adapter backlog item.
- Batching field and node executors, including cross-occurrence coalescing and production batch scheduling.
- Inline object values from a Node-valued field; qplan currently requires every Node value to be resolved by its node resolver.
- Object required selections and `FromObjectField` variables on resolvers invoked as root-field-reference targets; use Query required selections with the namespace path prefixed.
- Checker and type-checker executors, including their object- and Query-rooted required selections.
- Mutations, including `ctx.mutation()`, subscriptions, and custom scalars, which remain outside the current qplan scope.

The test-only adapter preserves the suspend executor SPI through the qplan resolver function. Resolver21-23 and Resolver26 invoke the adapted executor without introducing a blocking boundary.

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

Callback resolvers with their own RSS remain explicit rejection cases until qplan models their additional object-data dependency.

After variables, useful incremental steps are structured executor error metadata beyond the retained causal throwable, asynchronous EOD support, and a deliberate batching design. Selective integration still has distinct follow-up work around production/rematerialization policy, custom selection-directive preservation, custom engine configuration, empty node-payload elision, and Resolver26 demand-shape differences; these are recorded as specific feature-test blockers rather than part of basic requested-selection plumbing. Dispatcher and data-loader integration should remain a separate decision because Resolver26 already owns dependency scheduling and should not accidentally inherit a second scheduler.

[Future work: From Qplan Execution Harness to an Engine Implementation](https://slate.airbnb.tools/zGyuI7hCin) analyzes the gap between the current execution harness and a production implementation of the three `Engine` API methods, including the recommended implementation sequence.

## Resolver Observation

`QPlanExecutionStrategy` accepts an optional `SharedResolverObserver` under the `SharedResolverObserver::class.java` GraphQL-context key and carries it into the operation; the default is a no-op. Execution fixtures expose the same choice through `runQuery` and `runQueryAsync`. This observes resolver invocations and declared Query-fragment preparation through the common semantics API, including resolver work nested under `ctx.query()`, without attaching callbacks to model resolvers.
