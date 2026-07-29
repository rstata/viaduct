# Model Design Guidelines

## Scope

These are the canonical concrete design and implementation rules for public semantic model types and semantic model logic.

For example, `EngineResult`, `Schema.Value`, and `Selection` are semantic model types because reasoning is defined over their values. An exception class, a dependency-injection qualifier, or `TestWorld` is not a semantic model type merely because it occurs in the same Gradle project.

Exceptions, annotations, dependency-injection qualifiers, test utilities, parsing, schema decoding, registry assembly, and other composition infrastructure are outside these policies unless a rule explicitly includes them.

## Working Vocabulary

A semantic category is a modeled set of values represented by an interface hierarchy, such as `EngineResult`, `Schema.Value`, or `Schema.Type`.

A concrete variant is one particular form of value in a category, such as `ObjectEngineResult` within `EngineResult` or `Schema.TypeExpr.List` within `Schema.TypeExpr`.

A logic-constructible type is a concrete semantic type that reasoning code is allowed to create through a model factory. `ObjectEngineResult`, `EngineResult.Cell`, `Schema.ObjectKey`, and ordinary schema values are examples.

An externally supplied type is a semantic input that reasoning code may inspect but does not construct. `Schema`, `ExecutorRegistry`, `NodeResolver`, and `FieldResolver` are examples.

Pre-reasoning infrastructure is code that prepares externally supplied inputs before semantic reasoning begins. SDL decoding, GraphQL parsing, registry assembly, and private test-fixture implementations are examples.

An equality-free occurrence family is a finite collection of occurrences that supports traversal without comparing its payload values. `SelectionForest` is the motivating example because selection equality is undefined.

## Public Type Forms

Public semantic categories are sealed interfaces unless the category itself is intentionally supplied by external composition code. For example, `EngineResult` and `Schema.Type` are sealed, while externally supplied roots such as `Schema` and `ExecutorRegistry` are open interfaces.

Public leaf interfaces are also sealed unless their implementations are intentionally supplied by external composition code. For example, a logic-constructible `ObjectEngineResult` is sealed around its private implementation, while externally implemented `Schema.ObjectType` and `FieldResolver` are open leaves. Their enclosing categories, such as `Schema.Type` and `Resolver`, remain sealed.

Public singleton semantic values are `data object` declarations. `Schema.ErrorValue`, `Schema.DefaultValue.Absent`, and built-in scalar type definitions are examples.

Public enums represent finite scalar sets of unique values, such as the five possible results of `Schema.TypeRelation`. They are not used as substitutes for algebraic categories with variants such as `EngineResult`.

Do not expose public data classes or public sealed classes. For example, expose `EngineResult.Cell` as a sealed interface backed by a private data class rather than exposing the data class and its generated destructuring operations.

## Equality

Document equality at the highest semantic category that defines it. Subtypes inherit that contract unless they explicitly refine it.

Every public semantic category has one of three equality modes: structural equality, schema-canonical equality, or undefined equality.

Structural equality means that two values are equal exactly when they have the same semantic constructor and their corresponding components are recursively equal. For example, two object keys are equal when their fields and argument values are equal, while a named type expression is never equal to a list type expression.

Schema-canonical equality applies only to `Schema` and schema-definition graph elements: `Schema.Type`, `Schema.OutputField`, `Schema.InputLikeField`, and `Schema.FieldArguments`. Two schemas are equal exactly when they denote the same canonical schema. Two schema elements from that schema are equal exactly when they denote the same canonical element. Applying `==` to elements from different schemas is outside the modeled equality domain, regardless of the host-language result.

Undefined equality means that Kotlin `==`, `equals`, hashing, membership in equality-based collections, map-key use, deduplication, and other equality-dependent operations have no semantic interpretation for that category. `Selection`, `Fragment`, resolver values, resolver functions, and `Assumptions` are examples.

Semantic logic must not apply equality-dependent operations to undefined-equality values. For example, key a resolver-demand graph by canonical `ResolverSite` schema elements rather than resolver objects, and represent selections with an equality-free occurrence family rather than a `Multiset<Selection>`.

`SelectionForest` supports occurrence count, emptiness, permutation-invariant traversal, filtering, transformation, and concatenation. It does not expose membership, deduplication, equality-based counting, hashing, forest equality, or observable ordering.

Prefer a private data-class implementation when its generated structural equality exactly matches the category's equality contract. `IntValueImpl`, `ObjectKeyImpl`, and `CellImpl` are representative examples. Use a private regular implementation for an undefined-equality category such as `SelectionImpl`, or whenever generated componentwise equality is otherwise not the category's modeled equality.

## Construction

Distinguish logic-constructible types from externally supplied types. OERs and schema values are logic-constructible; `Schema`, `ExecutorRegistry`, and resolver definitions are externally supplied.

Every non-singleton concrete logic-constructible type has a public factory, conventionally named `of`. For example, `ObjectEngineResult`, `EngineResult.Cell`, and `Schema.IntValue` have factories. Abstract categories such as `EngineResult` and `Schema.Value` need no factory when their concrete variants provide the construction operations.

Logic-constructible types use private `FooImpl` classes by preference, such as `ObjectKeyImpl` implementing `ObjectKey`. Use an internal `FooImpl` only when cross-file implementation access is necessary. Anonymous implementations are not used.

Externally supplied types have no model construction factory or main-source implementation. For example, test-fixture code privately implements `Schema.ObjectType`, `NodeResolver`, and `ExecutorRegistry` while semantic code sees only their public interfaces.

Keep schema decoding, GraphQL parsing, resolver implementations, registry assembly, dependency-injection modules, and other pre-reasoning composition outside production semantic source sets. Tests that need a complete reasoning world construct it through `model.testing.TestWorld`; ordinary test sources do not decode schemas or assemble registries directly.

Constructors are private where possible and otherwise internal. Internal model code may call an internal constructor directly, but factory use remains preferred.

Factories return the most precise public type available. For example, a factory that always creates `Schema.DefaultValue.Present` returns `Present`, not the broader `DefaultValue`.

Place a factory on the highest semantic category where its meaning remains coherent and Kotlin overload resolution remains unambiguous. For example, `Schema.DefaultValue.of(value)` belongs on `DefaultValue`, while scalar factories should not be collapsed into an ambiguous `Schema.Value.of`. Prefer overloads that select precise variants when their parameter types are unambiguous.

An `of` factory normally accepts already semantic components. For example, `EngineResult.Cell.of` accepts an engine-result value and check value that are already in the model. Parsing GraphQL text and decoding SDL are pre-reasoning infrastructure rather than `of` factory behavior.

GraphQL coercion may be a semantic function. For example, construction of an argument-bearing object key may apply argument coercion, but the coercion relation should be independently defined rather than embedded only inside `ObjectKey.of`. Each coercion function separately specifies whether coercion failure is a modeled result or an input outside its domain.

Factories establish all carrier invariants available at their construction boundary eagerly. For example, `ObjectEngineResult.of` validates its cell coordinates, nullability, and nested result typing so `correctResolution` does not need a separate `conformsToSchema` predicate.

Use compositional validation for nested typed values. For example, a list factory validates its elements, and an enclosing OER factory checks that the list's declared element type is compatible with the field type rather than traversing and revalidating the entire list.

## Type Expressions

Every property whose value is a `Schema.TypeExpr` is named `typeExpr`, such as `Schema.OutputField.typeExpr` and `ListEngineResult.typeExpr`. Properties containing named schema definitions remain `type`, such as `Schema.ObjectValue.type` and `ObjectEngineResult.type`. Private parameters may use the shorter name `type` when the local type is unambiguous.
