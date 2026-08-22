# Qplan Current Handoff

## Current State

Qplan uses `viaduct.graphql.schema.ViaductSchema` and `ViaductSchema.TypeExpr` directly throughout model, fixtures, arbitrary generation, semantics, and execution. The former qplan-owned `Schema`, recursive `TypeExpr`, GraphQL-Java attachment adapter, and `GJSchemaDecoder` have been deleted.

Property-test campaigns now use versioned serialized campaign and generator configurations shared by JUnit and a standalone launcher. Each resolver's 100 rounds are represented by one compact campaign resource with reusable profiles, phases, and consecutive seed ranges. The generic `run-property-test-campaign.sh` builds the launcher once and then executes each round in a fresh JVM after Gradle exits, eliminating per-round Gradle and JUnit startup. See [`semantics/property-test-rounds.md`](./semantics/property-test-rounds.md).

Every reasoning world uses one canonical lowered `ViaductSchema`. Fields, arguments, enum values, object types, type conditions, possible-object-type sets, type expressions, resolver keys, selections, and EOD schema types all come from that schema instance.

The execution module now has an executor-backed feature-test path. `EngineTestModule.runQPlanFeatureTest` translates an in-memory engine module's pre-dispatcher field and node executor maps into the ordinary `TestWorld` fixture inputs, then runs GraphQL operations through `QPlanExecutionStrategy`. It does not construct runtime dispatchers or data loaders.

## Schema Boundaries

Fixture composition retains two distinct schemas:

- The source `GraphQLSchema` owns external GraphQL parsing, validation, source spread rules, source output types, coercion where required, and response completion.
- The canonical lowered `ViaductSchema` owns qplan fields, types, selections, resolver registry entries, conformance checks, values, and subtype reasoning. Source-backed definitions retain exact source GraphQL-Java EOD witnesses; synthetic bridge definitions retain generated internal witnesses.

`GJSchema.fromSDL` parses the source schema, enforces qplan's query-only and built-in-scalar restrictions, and lowers it to the final canonical `ViaductSchema`. It converts that schema to GraphQL Java only to validate the lowered graph, then discards the reconstruction. Copied source holders therefore continue to expose exact source objects, while the Node bridge rule attaches generated internal witnesses to synthetic bridge objects.

`SourceSchemaAdapter` is the explicit source-to-lowered boundary. It requires the canonical source/lowered fixture pair and has no identity fallback for a bare lowered schema. `QPlanWiringFactory` receives this adapter explicitly.

## Executor-Backed Execution

The feature-test bootstrap path is `EngineTestModule -> source schema SDL -> TestWorld.fromSDL -> ResolverRegistry -> Assumptions -> ExecutionTestFixture`. Registry construction remains centralized in `TestWorld` and `resolverRegistryOf`; the execution adapter only converts Engine API executors into field resolver definitions and raw node lookup functions.

Field executor coordinates and required selections are mapped through `SourceSchemaAdapter` into the canonical lowered schema. Complete object-RSS documents, including named fragment definitions, cross intact into a semantics-owned conversion that currently lowers named spreads into the normalized selection carrier. Resolver invocation uses one selector containing resolved arguments, the resolved object value, and a synchronous Query root value. Source-shaped outputs are normalized before fixture lowering.

Node executors remain a fixture-composition input rather than a second resolver algebra. Existing `NodeResolverLowering` creates bridge producers and payload resolvers, adapts `NodeReference` values, and verifies canonical Node types. Local built-in `Query.node` and `Query.nodes` resolvers produce the same source-shaped node references when the engine module does not supply those fields.

Typename also remains shared lowering. The schema lowerer owns synthetic typename fields, `resolverRegistryOf` owns their generated resolvers, and GraphQL-Java completion maps source `__typename` through `SourceSchemaAdapter`.

The current executor slice supports unbatched, non-selective field and node executors; field arguments; object required selections; top-level from-argument variables; object response-path variables through singular fields to simple or simple-list terminals; synchronous scalar, enum, list, object, and node-reference outputs; built-in node lookup; and typename completion. Object paths support aliases, nullable traversal, multiple variables, non-root resolver owners, and argument-bearing provider keys grounded from literals, defaults, owner arguments, or other acyclic object-path bindings. It rejects batching, selectivity, query required selections, nested input-object argument paths, from-Query-field/arbitrary callback variable providers, checkers, and asynchronous EOD outputs. Current node lowering requires every Node value to be resolved by its node resolver and requires node executor output to repeat `id`; direct inline Node materialization is outside the modeled scope. Registry composition synthesizes null fallbacks for missing nullable Query fields and error fallbacks for missing non-null Query fields. Executor failures cross qplan as reference-equal `EngineErrorData` carriers that retain the exact causal `Throwable`; derived resolver failures preserve that cause through resolver-input reads and GraphQL completion.

`RequiredSelectionSetVariableRecovery` reverse engineers the production RSS compilation recipes into `TestWorld.fromSDL(variableProviders = ...)`. It recursively unwraps `Validated`, accepts concrete Engine API `FromArgument` values with one path segment, and accepts `FromFieldVariablesResolver` only when its nested RSS proves a path in the root object RSS. It recursively validates provider dependencies, requires repeated nested recipes to agree, associates each provider with the exact variable occurrence owned by the decoded resolver fragment, and preserves renamed bindings and response-key aliases. It rejects missing, unused, duplicate, or inconsistent providers and every unsupported resolver shape rather than invoking callbacks or approximating semantics. See [`execution/README.md`](./execution/README.md) for production-code pointers, architecture, and testing details.

Resolver26 now retains occurrence-stamped provider paths, including provider arguments sourced by another object-path binding. From-Query paths and arbitrary providers remain later work.

The semantic registry now represents `FromArgument` as a canonical path rooted at a resolver argument. Every maintained resolver traverses input objects and returns null when an intermediate object is null; list traversal remains invalid. The executor feature-test adapter still recovers only one-segment Engine API `FromArgument` paths, so production-derived nested-input cases remain blocked at that pre-reasoning boundary until its owner lifts the restriction.

## Lowered Representation

The lowerer lives under `model/src/testFixtures/kotlin/model/lowering`. It copies ordinary definitions with `ViaductSchemaBuilder.filteredCopy` and runs one symbolic `SchemaValidator` phase containing modular rules for reserved names, Node bridge types, Node bridge fields, rewritten Node-valued producers, and typename proxies.

Source Node-valued fields are replaced by bridge-valued fields while preserving wrappers, nullability, arguments, defaults, directives, and metadata. The current lowering models every runtime Node value as a reference, adapts it to a concrete bridge object, and delegates materialization to the corresponding node resolver before semantic reasoning.

Internal typename demand uses ordinary synthetic fields. Objects and interfaces own `V_A_typename`; union-scoped typename maps to `V_A_AllSourceObjects.V_A_typename`; synthetic Node bridges own no typename proxy.

## Representation Rules

Built-in scalars are schema-owned. Code reaches them through the active canonical schema or an expected type expression rather than global singleton definitions.

Type expressions use Viaduct's flat wrapper representation. Recursive operations peel one list layer with `unwrapList()` and use `listDepth`, `nullableAtDepth`, `baseTypeNullable`, and `unwrapLists()` where appropriate.

Checked extensions in `SchemaLookups.kt` narrow broad Viaduct type expressions to input, output, and simple types. `ViaductSchema.CompositeTypeDef` is used for applicability and possible objects; direct field lookup requires `OutputRecord`; concrete object fields use `Object` and `ObjectField`.

Schema defaults remain syntactic `ViaductSchema.Literal` values. `coercedDefaultValue()` performs schema-directed conversion to runtime `EngineInputData`, and callers check `hasDefault` before reading `defaultValue` so absence remains distinct from explicit null.

## Validation Evidence

The following gates pass from `qplan`:

```shell
./gradlew :arbitrary:test :semantics:test
./gradlew :semantics:resolver25BroadStressCampaign -Presolver25BroadStressCampaignRound=1 -Presolver25BroadStressCampaignProfile=balanced -PresolverPropertyCase=1:1:1
./gradlew :semantics:resolver26BroadStressCampaign -Presolver26BroadStressCampaignRound=1 -Presolver26BroadStressCampaignProfile=balanced -PresolverPropertyCase=1:1:1
./gradlew :semantics:installPropertyTestRoundLauncher
./gradlew :model:test --tests 'model.lowering.*'
./gradlew :model:test
./gradlew :arbitrary:test
./gradlew :semantics:test
./gradlew :execution:test --tests execution.EngineTestModuleQPlanFeatureTest --tests execution.viaductfeaturetests.EngineFeatureTestExample --tests execution.viaductfeaturetests.NodeResolverTest --tests execution.viaductfeaturetests.RequiredSelectionsTest --tests execution.viaductfeaturetests.FromFieldVariablesFeatureTest --tests execution.testing.RequiredSelectionSetVariableRecoveryTest
./gradlew :execution:test
./gradlew check
```

The executor-focused gate contains 141 tests: 57 pass and 84 preserved production tests are disabled. Its inputs are 6 adapter tests, all 124 production tests from four complete `core/engine/runtime` source-file ports, 2 qplan-specific `ALTERNATIVE` tests, and 9 direct RSS-recovery tests. Production-derived tests live in `execution/viaductfeaturetests`, retain their originating filenames and source order, and record equal copied/source test counts at the top of each file. Confirmed incompatibilities preserve the production test under `@Disabled` and add an enabled `ALTERNATIVE` with the smallest qplan-compatible adjustment. The complete execution suite contains 165 tests, with 81 passing and the same 84 production tests disabled. The four argument-bearing object-path provider feature tests pass enabled. The focused node-error tests and full `check` passed on 2026-08-22. The model suite last ran 247 tests, the semantics suite last ran 475 tests with 3 existing skips, and generated resolver profiles passed. See [`execution/viaduct-feature-test-inventory.md`](./execution/viaduct-feature-test-inventory.md) for migration state, disabled-test evidence, alternatives, and recommended whole-file candidates.

The final source audits return no references to qplan's retired schema representation:

```shell
rg -n 'import model\.Schema|\bSchema\.' qplan --glob '*.kt'
rg -n 'import model\.TypeExpr|model\.TypeExpr|TypeExpr\.(Named|List)' qplan --glob '*.kt'
rg -n 'GJSchemaDecoder|graphQLJavaDefinition' qplan --glob '*.kt'
```

Remaining `.gjDef` uses are intentional: source-backed qplan objects obtain exact witnesses from the retained source schema, while synthetic bridge EODs use their qplan-only internal witness. Focused tests verify source identity, synthetic isolation, and tenant-visible field shape.

## Scope

The schema-representation migration is complete. The executor feature-test adapter is a pre-dispatcher integration surface, not a production `execution2` implementation. Future work must preserve selection occurrence identity, resolver scheduling, response-key materialization, OER identity, variable occurrence identity, and the explicit source/lowered schema boundary. Custom scalars, mutations, subscriptions, dispatcher and data-loader integration, and production `execution2` integration remain separate work.
