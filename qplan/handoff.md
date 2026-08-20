# Qplan Current Handoff

## Current State

Qplan uses `viaduct.graphql.schema.ViaductSchema` and `ViaductSchema.TypeExpr` directly throughout model, fixtures, arbitrary generation, semantics, and execution. The former qplan-owned `Schema`, recursive `TypeExpr`, GraphQL-Java attachment adapter, and `GJSchemaDecoder` have been deleted.

Every reasoning world uses one canonical lowered `ViaductSchema`. Fields, arguments, enum values, object types, type conditions, possible-object-type sets, type expressions, resolver keys, selections, and EOD schema types all come from that schema instance.

## Schema Boundaries

Fixture composition retains two distinct schemas:

- The source `GraphQLSchema` owns external GraphQL parsing, validation, source spread rules, source output types, coercion where required, and response completion.
- The canonical lowered `ViaductSchema` owns qplan fields, types, selections, resolver registry entries, conformance checks, values, and subtype reasoning. Source-backed definitions retain exact source GraphQL-Java EOD witnesses; synthetic bridge definitions retain generated internal witnesses.

`GJSchema.fromSDL` parses the source schema, enforces qplan's query-only and built-in-scalar restrictions, and lowers it to the final canonical `ViaductSchema`. It converts that schema to GraphQL Java only to validate the lowered graph, then discards the reconstruction. Copied source holders therefore continue to expose exact source objects, while the Node bridge rule attaches generated internal witnesses to synthetic bridge objects.

`SourceSchemaAdapter` is the explicit source-to-lowered boundary. It requires the canonical source/lowered fixture pair and has no identity fallback for a bare lowered schema. `QPlanWiringFactory` receives this adapter explicitly.

## Lowered Representation

The lowerer lives under `model/src/testFixtures/kotlin/model/lowering`. It copies ordinary definitions with `ViaductSchemaBuilder.filteredCopy` and runs one symbolic `SchemaValidator` phase containing modular rules for reserved names, Node bridge types, Node bridge fields, rewritten Node-valued producers, and typename proxies.

Source Node-valued fields are replaced by bridge-valued fields while preserving wrappers, nullability, arguments, defaults, directives, and metadata. Runtime source-shaped Node outputs are adapted to concrete bridge objects before entering semantic reasoning.

Internal typename demand uses ordinary synthetic fields. Objects and interfaces own `V_A_typename`; union-scoped typename maps to `V_A_AllSourceObjects.V_A_typename`; synthetic Node bridges own no typename proxy.

## Representation Rules

Built-in scalars are schema-owned. Code reaches them through the active canonical schema or an expected type expression rather than global singleton definitions.

Type expressions use Viaduct's flat wrapper representation. Recursive operations peel one list layer with `unwrapList()` and use `listDepth`, `nullableAtDepth`, `baseTypeNullable`, and `unwrapLists()` where appropriate.

Checked extensions in `SchemaLookups.kt` narrow broad Viaduct type expressions to input, output, and simple types. `ViaductSchema.CompositeTypeDef` is used for applicability and possible objects; direct field lookup requires `OutputRecord`; concrete object fields use `Object` and `ObjectField`.

Schema defaults remain syntactic `ViaductSchema.Literal` values. `coercedDefaultValue()` performs schema-directed conversion to runtime `EngineInputData`, and callers check `hasDefault` before reading `defaultValue` so absence remains distinct from explicit null.

## Validation Evidence

The following gates pass from `qplan`:

```shell
./gradlew :model:test --tests 'model.lowering.*'
./gradlew :model:test
./gradlew :arbitrary:test
./gradlew :semantics:test
./gradlew :execution:test
./gradlew check
```

The full `check` passed on 2026-08-20. The model suite ran 237 tests, the semantics suite ran 458 tests with 3 existing skips, and generated resolver profiles passed.

The final source audits return no references to qplan's retired schema representation:

```shell
rg -n 'import model\.Schema|\bSchema\.' qplan --glob '*.kt'
rg -n 'import model\.TypeExpr|model\.TypeExpr|TypeExpr\.(Named|List)' qplan --glob '*.kt'
rg -n 'GJSchemaDecoder|graphQLJavaDefinition' qplan --glob '*.kt'
```

Remaining `.gjDef` uses are intentional: source-backed qplan objects obtain exact witnesses from the retained source schema, while synthetic bridge EODs use their qplan-only internal witness. Focused tests verify source identity, synthetic isolation, and tenant-visible field shape.

## Scope

The schema-representation migration is complete. Future work must preserve selection occurrence identity, resolver scheduling, response-key materialization, OER identity, variable occurrence identity, and the explicit source/lowered schema boundary. Custom scalars, mutations, subscriptions, and production `execution2` integration remain separate work.
