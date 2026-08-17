# Model Design Guidelines

## Scope

These are the canonical concrete design and implementation rules for public semantic model types and semantic model logic.

For example, `EngineResult`, `Value`, and `Selection` are semantic model types because reasoning is defined over their values. An exception class, a dependency-injection qualifier, or `TestWorld` is not a semantic model type merely because it occurs in the same Gradle project.

Exceptions, annotations, dependency-injection qualifiers, test utilities, parsing, schema decoding, registry assembly, and other composition infrastructure are outside these policies unless a rule explicitly includes them.

## Carrier Boundary

`EngineResult` values are finite and well-founded. `Value.Simple` results use structural equality, cells and OERs use reference equality, and lists use structural equality over their type expression and positional cell identity. Use `sameCompletedResultAs` for explicit extensional comparison of completed result trees.

Mutable OERs may gain validated exact cells monotonically. Each cell has independent write-once value and `accessAccepted` promises, and a written parent cell may retain a mutable child OER while that child gains cells. Self-reference and cyclic result graphs are outside the result domain.

`Schema` and `ResolverRegistry` are externally supplied canonical worlds. Test-fixture composition may decode schemas, lower source node resolvers, canonicalize variables, validate provider paths, and assemble registries; semantic code receives only the resulting interfaces and model-owned `FieldResolver` values.

Use canonical schema relations for GraphQL semantics rather than Kotlin inheritance. In particular, use schema-canonical definitions, `Schema.TypeRelation`, and `CompositeType.possibleTypes` as appropriate for field ownership, type overlap, applicability, and subtype reasoning.

## Variables And Keys

`Value.Variable.Template` is identified by its local name and defining concrete resolver field. Stamping at an exact OER path creates an occurrence-specific variable. Request-local `Assumptions` stores one declared promise per stamped variable: synchronous semantic operations read completed bindings, while coroutine operations may suspend for them.

Registry assembly compiles `FromObjectField` declarations to contained canonical key paths and validates an acyclic provider/use order before reasoning. Resolver25 and Resolver26 evaluate those providers at runtime; older maintained resolvers support only `FromArgument`.

`Value.Key` is an open selection key. `Value.ObjectKey` refines it to a concrete object field while retaining open arguments. `Value.GroundKey` additionally requires ground arguments and is the only key admitted to `Value.Object`, OER cells, exact paths, materialization, dependency ordering, and resolver application.

Ground inputs implement the opaque `OpenValue` and `OpenArguments` interfaces. Grounding throws on an unbound stamped variable or an unstamped template.

## Result Representation

`EngineResult.Cell` represents one object-field or list-element occurrence with independent value and `accessAccepted` promises. A completed access result of true means access is accepted and false means it is rejected. Field and type checks are represented by that one result.

`Value.Output` represents resolver output without result cells or access decisions. Do not collapse resolver output and engine result into one carrier merely because they can contain corresponding values.

`Value.Fields` and `Value.ObjectFields` deliberately throw when indexed outside their lookup domain, including when a missing entry would otherwise be indistinguishable from a present null. Test membership before an optional lookup; use direct indexing only when presence is a precondition.

Cells are allocated by their containing OER or LER and use reference identity as their occurrence ID. Object construction is immutable by default. Opt-in mutable objects atomically install each absent exact cell once and throw on unset reads, repeated claims, or repeated writes. Lists have immutable positions and may opt into mutable cell slots.

An exact result path contains only `Value.GroundKey` object steps and `Value.ListIndex` list steps. Open keys, schema fields, response keys, and aliases are not path components.

Response aliases and response ordering remain outside field-resolution identity. Canonical object fields plus ground arguments identify object cells; aliases belong to source/response processing and must not create parallel semantic field identities.

The fixture retains an unchanged GraphQL-Java source schema for validation and derives a separate model-only lowered schema. Source Node-valued fields are absent from that model schema: `foo: W<T>` is represented only by `foo_V_A_node: W<T_V_A_Bridge>`, and each concrete bridge has ordinary `id` and `node` fields with no generated hierarchy. Source schema names containing `V_A` are rejected. Because there is no bridge hierarchy, fixture composition also rejects object implementations that narrow the named return type of a Node-valued interface field.

Raw node references exist only as fixture inputs. Source-facing object construction and resolver adaptation lower them through `foo_V_A_node` producers and argumentless `T_V_A_Bridge.node` loaders before semantic reasoning. `Value.Object` itself stores only canonical lowered fields.

Ordinary model and semantics tests use canonical `Schema.field` and `Schema.objectField` coordinates, including explicit synthetic names for lowered Node fields. Source-name translation is confined to explicit pre-reasoning boundaries: GraphQL parsing, source-facing object construction, source declaration compilation, resolver adaptation, arbitrary source-recipe materialization, and focused tests of those adapters. Canonical assertions and resolver oracles must not use source-name lookup. Fixture code exposes this translation through `SourceSchemaAdapter`, not generic extensions on `Schema`.

## Engine API Alignment

Viaduct's `EngineObjectData.Sync` is the target synchronous partial-object boundary for the current migration. It is name-keyed and distinguishes absent selections from present-null values through `isPresent`.

Align storage and access with that API where it preserves the modeled meaning, but retain explicit qplan structure for schema-canonical keys, occurrence identity, write-once ownership, and access decisions when EOD does not represent those facts directly. Record current migration choices in [`../handoff.md`](../handoff.md); update this guide only when a choice becomes a stable model rule.

## Working Vocabulary

A semantic category is a modeled set of values represented by an interface hierarchy, such as `EngineResult`, `Value`, or `Schema.Type`.

A concrete variant is one particular form of value in a category, such as `EngineResult.Object` within `EngineResult` or `TypeExpr.List` within `TypeExpr`.

A logic-constructible type is a concrete semantic type that reasoning code is allowed to create through a model factory. `EngineResult.Object`, `Promise`, `Value.Key`, and `FieldResolver` are examples.

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

Do not expose public data classes or public sealed classes. For example, expose `Promise` as a sealed interface backed by private implementations rather than exposing their generated component operations.

## Equality

Document equality at the highest semantic category that defines it. Subtypes inherit that contract unless the category explicitly assigns different equality modes to its variants.

Every public semantic category or variant has one of four equality modes: structural equality, reference equality, schema-canonical equality, or undefined equality.

Structural equality means that two values are equal exactly when they have the same semantic constructor and their corresponding components are recursively equal. For example, two object keys are equal when their fields and argument values are equal, while a named type expression is never equal to a list type expression.

Reference equality means that two values are equal exactly when they are the same runtime occurrence. `EngineResult.Cell` and `EngineResult.Object` use reference equality because their promise state is monotonically mutable. Their identity hashes are stable while cells or promises are installed and completed, so either may be used safely as a map key. A cell is allocated by its containing OER or LER, and its reference identity is its occurrence ID. `EngineResult.List` remains structural over its type expression and positional cell identity. Use `sameCompletedResultAs` when an explicit extensional comparison of completed result trees is required.

Schema-canonical equality applies only to `Schema` and schema-definition graph elements: `Schema.Type`, `Schema.OutputField`, `Schema.InputLikeField`, and `Schema.FieldArguments`. Two schemas are equal exactly when they denote the same canonical schema. Two schema elements from that schema are equal exactly when they denote the same canonical element. Applying `==` to elements from different schemas is outside the modeled equality domain, regardless of the host-language result.

Undefined equality means that Kotlin `==`, `equals`, hashing, membership in equality-based collections, map-key use, deduplication, and other equality-dependent operations have no semantic interpretation for that category. `Selection`, resolver values, resolver functions, and `Assumptions` are examples.

Semantic logic must not apply equality-dependent operations to undefined-equality values. For example, key a resolver-demand graph by canonical `Schema.ObjectField` elements rather than resolver objects, and represent selections with an equality-free occurrence family rather than a `Multiset<Selection>`.

`SelectionForest` supports current-member count, emptiness, permutation-invariant traversal, filtering, transformation, and concatenation. The one-member-per-source-field property belongs specifically to the corresponding forests returned by GraphQL selection flattening; it is not a carrier invariant. No operation internally compares whole `Selection` values. `merge(type)` filters and specializes occurrences to one concrete parent type, coalesces ordinary-equal open `ObjectKey` values, and returns an `ObjectSelectionForest`. `ObjectSelectionForest.instantiateBindings()` is the separate grounding boundary: it throws unless every current argument expression can be grounded and coalesces keys that become equal after substitution. Checked ground-key views are required before exact OER operations. Neither forest exposes selection membership, equality-based selection counting, hashing, forest equality, or observable ordering.

The test-fixture `Fragment` carrier retains a nominal composite type and an unnormalized forest for parsed selection requirements and pre-reasoning transformations. It is not part of the production model artifact. Canonical field-resolver `objectFragment` values are open `SelectionForest`s whose top-level occurrences have been specialized to the resolver field's concrete containing type.

Prefer a private data-class implementation when its generated structural equality exactly matches the category's equality contract. `IntValueImpl`, `KeyImpl`, and `GroundKeyImpl` are representative examples. Use a private regular implementation for an undefined-equality category such as `SelectionImpl`, or whenever generated componentwise equality is otherwise not the category's modeled equality.

## Construction

Distinguish logic-constructible types from externally supplied types. OERs, schema values, and model-owned field-resolver wrappers are logic-constructible; `Schema` and `ResolverRegistry` are externally supplied. Field-resolver functions are supplied during pre-reasoning assembly and encapsulated by `FieldResolver` behind a model-owned factory and public demand-projection operation. External raw node lookups, when accepted by composition infrastructure, are lowered to field resolvers before the canonical registry is exposed.

Every independently constructible non-singleton semantic type has a public factory, conventionally named `of`. For example, `EngineResult.Object`, `Promise`, `Value.Int`, and `FieldResolver` have factories. `EngineResult.Cell` is deliberately not independently constructible: OER and LER factories allocate their cells so a cell cannot be shared by two containers. Abstract categories such as `EngineResult` and `Value` need no factory when their concrete variants provide the construction operations.

Logic-constructible types use private `FooImpl` classes by preference, such as `KeyImpl` implementing `Value.Key` and `GroundKeyImpl` implementing `Value.GroundKey`. Use an internal `FooImpl` only when cross-file implementation access is necessary. Anonymous implementations are not used.

Externally supplied types have no model construction factory or main-source implementation. For example, test-fixture code privately implements `Schema.ObjectType` and `ResolverRegistry` while semantic code sees only their public interfaces.

Keep schema decoding, GraphQL parsing, resolver-function definitions, registry assembly, dependency-injection modules, and other pre-reasoning composition outside production semantic source sets. The model-owned resolver wrappers are the boundary that hides those functions from semantic algorithms. Tests that need a complete reasoning world construct it through `model.testing.TestWorld`; ordinary test sources do not decode schemas or assemble registries directly.

Constructors are private where possible and otherwise internal. Internal model code may call an internal constructor directly, but factory use remains preferred.

Factories return the most precise public type available. For example, a factory that always creates `Value.Default.Present` returns `Present`, not the broader `Value.Default`.

Place a factory on the highest semantic category where its meaning remains coherent and Kotlin overload resolution remains unambiguous. For example, `Value.Default.of(value)` belongs on `Value.Default`, while scalar factories should not be collapsed into an ambiguous `Value.of`. Prefer overloads that select precise variants when their parameter types are unambiguous.

An `of` factory normally accepts already semantic components. For example, `Promise.of` accepts the semantic value it immediately contains. Parsing GraphQL text and decoding SDL are pre-reasoning infrastructure rather than `of` factory behavior.

GraphQL coercion may be a semantic function. For example, construction of an argument-bearing object key may apply argument coercion, but the coercion relation should be independently defined rather than embedded only inside `Value.Key.of`. Each coercion function separately specifies whether coercion failure is a modeled result or an input outside its domain.

Factories establish all carrier invariants available at their construction boundary eagerly and document those postconditions on the factory. Reusable invariant relations live in `model.invariants`. For example, `EngineResult.Object.of` validates every initially present value coordinate, nullability, and nested result type, while `EngineResult.Cell.setValue` and validating deferred value promises enforce the same invariant before completion. Every observable completed value therefore satisfies its documented carrier invariants, and `correctResolution` does not need schema conformance as a separate conjunct.

Use compositional validation for nested typed values. For example, a list factory validates its elements, and an enclosing OER factory checks that the list's declared element type is compatible with the field type rather than traversing and revalidating the entire list.

## Type Expressions

Every property whose value is a `TypeExpr` is named `typeExpr`, such as `Schema.OutputField.typeExpr` and `EngineResult.List.typeExpr`. Properties containing named schema definitions remain `type`, such as `Value.Object.type` and `EngineResult.Object.type`. Private parameters may use the shorter name `type` when the local type is unambiguous.
