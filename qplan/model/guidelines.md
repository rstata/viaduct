# Model Design Guidelines

## Scope

These are the canonical concrete design and implementation rules for public semantic model types and semantic model logic.

For example, `EngineResult`, `Value`, and `Selection` are semantic model types because reasoning is defined over their values. An exception class, a dependency-injection qualifier, or `TestWorld` is not a semantic model type merely because it occurs in the same Gradle project.

Exceptions, annotations, dependency-injection qualifiers, test utilities, parsing, schema decoding, registry assembly, and other composition infrastructure are outside these policies unless a rule explicitly includes them.

## Working Vocabulary

A semantic category is a modeled set of values represented by an interface hierarchy, such as `EngineResult`, `Value`, or `Schema.Type`.

A concrete variant is one particular form of value in a category, such as `EngineResult.Object` within `EngineResult` or `TypeExpr.List` within `TypeExpr`.

A logic-constructible type is a concrete semantic type that reasoning code is allowed to create through a model factory. `EngineResult.Object`, `EngineResult.Cell`, `Value.Key`, and `FieldResolver` are examples.

An externally supplied type is a semantic input that reasoning code may inspect but does not construct. `Schema` and `ResolverRegistry` are examples. An externally supplied registry may contain logic-constructible model values such as `FieldResolver`.

Pre-reasoning infrastructure is code that prepares externally supplied inputs before semantic reasoning begins. SDL decoding, GraphQL parsing, registry assembly, and private test-fixture implementations are examples.

An equality-free occurrence family is a finite collection of occurrences that supports traversal without comparing its payload values. `SelectionForest` is the motivating example because selection equality is undefined.

## Mathematical Function Signatures

Every function declared in the model main source set has a mathematical signature, regardless of its visibility or whether it is a member, extension, factory, or implementation helper. Each receiver, context parameter, ordinary parameter, and return value must denote an input or output of the modeled mathematical operation.

Do not add parameters or results solely for programming concerns such as improving an exception message, retaining a source path, labeling a call site, logging, tracing, formatting, debugging, or selecting an implementation strategy. In particular, recursive semantic functions must not thread diagnostic context that does not affect their mathematical result. A partial function may throw when its input is outside its domain; that exception is not a modeled output. Diagnostics may be derived from inputs already present in the mathematical signature, but otherwise use a less specific message or no message.

Keep functions requiring non-mathematical inputs or producing non-mathematical outputs in pre-reasoning infrastructure outside the model main source set.

## Public Type Forms

Public semantic categories are sealed interfaces unless the category itself is intentionally supplied by external composition code. For example, `EngineResult` and `Schema.Type` are sealed, while externally supplied roots such as `Schema` and `ResolverRegistry` are open interfaces.

Public leaf interfaces are also sealed unless their implementations are intentionally supplied by external composition code. For example, a logic-constructible `EngineResult.Object` is sealed around its private implementation, while externally implemented `Schema.ObjectType` is an open leaf. Its enclosing category, `Schema.Type`, remains sealed.

Public singleton semantic values are `data object` declarations. `Value.Error`, `Value.Default.Absent`, and built-in scalar type definitions are examples.

Public enums represent finite scalar sets of unique values, such as the five possible results of `Schema.TypeRelation`. They are not used as substitutes for algebraic categories with variants such as `EngineResult`.

Do not expose public data classes or public sealed classes. For example, expose `EngineResult.Cell` as a sealed interface backed by a private data class rather than exposing the data class and its generated destructuring operations.

## Equality

Document equality at the highest semantic category that defines it. Subtypes inherit that contract unless they explicitly refine it.

Every public semantic category has one of three equality modes: structural equality, schema-canonical equality, or undefined equality.

Structural equality means that two values are equal exactly when they have the same semantic constructor and their corresponding components are recursively equal. For example, two object keys are equal when their fields and argument values are equal, while a named type expression is never equal to a list type expression.

Schema-canonical equality applies only to `Schema` and schema-definition graph elements: `Schema.Type`, `Schema.OutputField`, `Schema.InputLikeField`, and `Schema.FieldArguments`. Two schemas are equal exactly when they denote the same canonical schema. Two schema elements from that schema are equal exactly when they denote the same canonical element. Applying `==` to elements from different schemas is outside the modeled equality domain, regardless of the host-language result.

Undefined equality means that Kotlin `==`, `equals`, hashing, membership in equality-based collections, map-key use, deduplication, and other equality-dependent operations have no semantic interpretation for that category. `Selection`, resolver values, resolver functions, and `Assumptions` are examples.

Semantic logic must not apply equality-dependent operations to undefined-equality values. For example, key a resolver-demand graph by canonical `Schema.ObjectField` elements rather than resolver objects, and represent selections with an equality-free occurrence family rather than a `Multiset<Selection>`.

`SelectionForest` supports current-member count, emptiness, permutation-invariant traversal, filtering, transformation, concatenation, and structural key observation through `keys()`. The one-member-per-source-field property belongs specifically to the corresponding forests returned by GraphQL selection flattening; it is not a carrier invariant. No operation internally compares whole `Selection` values. Forest-returning operations other than `merge(type)` process members independently without coalescing them; observation and grouping operations may compare and deduplicate explicitly projected values. The explicit `merge(type)` normalization boundary specializes applicable members to one concrete parent type and returns an `ObjectSelectionForest` with one top-level `ObjectSelection` per field and argument tuple under the current variable bindings while concatenating their subselections. The forest does not expose selection membership, equality-based selection counting, hashing, forest equality, or observable ordering.

The test-fixture `Fragment` carrier retains a nominal composite type and an unnormalized forest for parsed selection requirements and pre-reasoning transformations. It is not part of the production model artifact. Canonical field-resolver `objectFragment` and `predecessorDemand` values are instead `ObjectSelectionForest`s normalized against the resolver field's concrete containing type.

Prefer a private data-class implementation when its generated structural equality exactly matches the category's equality contract. `IntValueImpl`, `KeyImpl`, `ObjectKeyImpl`, and `CellImpl` are representative examples. Use a private regular implementation for an undefined-equality category such as `SelectionImpl`, or whenever generated componentwise equality is otherwise not the category's modeled equality.

## Construction

Distinguish logic-constructible types from externally supplied types. OERs, schema values, and model-owned field-resolver wrappers are logic-constructible; `Schema` and `ResolverRegistry` are externally supplied. Field-resolver functions are supplied during pre-reasoning assembly and encapsulated by `FieldResolver` behind a model-owned factory and public demand-projection operation. External raw node lookups, when accepted by composition infrastructure, are lowered to field resolvers before the canonical registry is exposed.

Every non-singleton concrete logic-constructible type has a public factory, conventionally named `of`. For example, `EngineResult.Object`, `EngineResult.Cell`, `Value.Int`, and `FieldResolver` have factories. Abstract categories such as `EngineResult` and `Value` need no factory when their concrete variants provide the construction operations.

Logic-constructible types use private `FooImpl` classes by preference, such as `KeyImpl` implementing `Value.Key` and `ObjectKeyImpl` implementing `Value.ObjectKey`. Use an internal `FooImpl` only when cross-file implementation access is necessary. Anonymous implementations are not used.

Externally supplied types have no model construction factory or main-source implementation. For example, test-fixture code privately implements `Schema.ObjectType` and `ResolverRegistry` while semantic code sees only their public interfaces.

Keep schema decoding, GraphQL parsing, resolver-function definitions, registry assembly, dependency-injection modules, and other pre-reasoning composition outside production semantic source sets. The model-owned resolver wrappers are the boundary that hides those functions from semantic algorithms. Tests that need a complete reasoning world construct it through `model.testing.TestWorld`; ordinary test sources do not decode schemas or assemble registries directly.

Constructors are private where possible and otherwise internal. Internal model code may call an internal constructor directly, but factory use remains preferred.

Factories return the most precise public type available. For example, a factory that always creates `Value.Default.Present` returns `Present`, not the broader `Value.Default`.

Place a factory on the highest semantic category where its meaning remains coherent and Kotlin overload resolution remains unambiguous. For example, `Value.Default.of(value)` belongs on `Value.Default`, while scalar factories should not be collapsed into an ambiguous `Value.of`. Prefer overloads that select precise variants when their parameter types are unambiguous.

An `of` factory normally accepts already semantic components. For example, `EngineResult.Cell.of` accepts an engine-result value and check value that are already in the model. Parsing GraphQL text and decoding SDL are pre-reasoning infrastructure rather than `of` factory behavior.

GraphQL coercion may be a semantic function. For example, construction of an argument-bearing object key may apply argument coercion, but the coercion relation should be independently defined rather than embedded only inside `Value.Key.of`. Each coercion function separately specifies whether coercion failure is a modeled result or an input outside its domain.

Factories establish all carrier invariants available at their construction boundary eagerly and document those postconditions on the factory. Reusable invariant relations live in `model.invariants`. For example, `EngineResult.Object.of` validates its cell coordinates, nullability, and nested result typing, so every returned object satisfies its documented carrier invariants and `correctResolution` does not need schema conformance as a separate conjunct.

Use compositional validation for nested typed values. For example, a list factory validates its elements, and an enclosing OER factory checks that the list's declared element type is compatible with the field type rather than traversing and revalidating the entire list.

## Type Expressions

Every property whose value is a `TypeExpr` is named `typeExpr`, such as `Schema.OutputField.typeExpr` and `EngineResult.List.typeExpr`. Properties containing named schema definitions remain `type`, such as `Value.Object.type` and `EngineResult.Object.type`. Private parameters may use the shorter name `type` when the local type is unambiguous.
