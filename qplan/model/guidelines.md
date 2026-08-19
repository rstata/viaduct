# Model Design Guidelines

## Scope

These are the canonical concrete design and implementation rules for public semantic model types and semantic model logic.

For example, `EngineResult`, `Value`, and `Selection` are semantic model types because reasoning is defined over their values. An exception class, a dependency-injection qualifier, or `TestWorld` is not a semantic model type merely because it occurs in the same Gradle project.

Exceptions, annotations, dependency-injection qualifiers, test utilities, parsing, schema decoding, registry assembly, and other composition infrastructure are outside these policies unless a rule explicitly includes them.

## Carrier Boundary

The active carrier target treats `EngineInputData`, `EngineOutputData`, and `EngineResult` as distinct checked semantic unions represented by Kotlin `Any` typealiases. Their members are defined by domain-specific conformance relations rather than Kotlin subtyping. Nullable uses add GraphQL null. The aliases cannot distinguish overloads or prevent arbitrary `Any` values from crossing unchecked programming boundaries, so construction, publication, conversion, and observation operations enforce the appropriate domain relation.

`EngineResult` values are finite and well-founded. Pre-domain scalar values use structural equality, cells and OERs use reference equality, and lists use structural equality over their type expression and positional cell identity. Use `sameCompletedResultAs` for explicit extensional comparison of completed result trees.

Mutable OERs may gain validated exact cells monotonically. Each cell has independent write-once value and access-result promises, and a written parent cell may retain a mutable child OER while that child gains cells. Self-reference and cyclic result graphs are outside the result domain.

`Schema` and `ResolverRegistry` are externally supplied canonical worlds. Test-fixture composition may decode schemas, lower source node resolvers, canonicalize variables, validate provider paths, and assemble registries; semantic code receives only the resulting interfaces and model-owned `FieldResolver` values.

Use canonical schema relations for GraphQL semantics rather than Kotlin inheritance. In particular, use schema-canonical definitions, `Schema.TypeRelation`, and `CompositeType.possibleTypes` as appropriate for field ownership, type overlap, applicability, and subtype reasoning.

## Variables And Keys

A variable template is identified by its local name and defining concrete resolver field. Its `stamp`
is null. Stamping at an exact OER path creates an occurrence-specific variable carrying a
`Stamp.Occurrence`. Resolver1 through Resolver25 use an empty occurrence lineage; Resolver26 adds
selection-occurrence lineage as it crosses ungrounded resolver boundaries. Request-local
`Assumptions` stores one declared promise per stamped variable: synchronous semantic operations read
completed bindings, while coroutine operations may suspend for them.

Registry assembly compiles `FromObjectField` declarations to contained canonical key paths and validates an acyclic provider/use order before reasoning. Resolver25 and Resolver26 evaluate those providers at runtime; older maintained resolvers support only `FromArgument`.

`ObjectEngineResult.Key` is an open selection key. `ObjectEngineResult.ObjectKey` refines it to a concrete object field while retaining open arguments. `ObjectEngineResult.GroundKey` additionally requires ground arguments and is the only key admitted to OER cells, exact paths, materialization lookup, dependency ordering, and resolver application.

`ObjectEngineResult.Key.stamp` distinguishes three states. A variable-bearing registry template has
a null stamp. An ordinary concrete key carries `Stamp.VariableFreeOccurrence`, meaning that its
selection occurrence needs no variable-derived identity. Resolver26 explicitly assigns
`Stamp.Occurrence` to a variable-bearing resolver-fragment selection; every occurrence-stamped
variable in that key's arguments carries the same stamp. Ordinary key factories never infer this
identity from stamped variables. Specialization, localization, and grounding preserve an explicit
occurrence stamp.

Keys belong exclusively to the engine-result domain. `Value.Object` and `Value.ObjectFields` use string keys. Passive source and resolver-produced objects use canonical argumentless field names. Resolver inputs materialized from object fragments use GraphQL response keys, including aliases. Those response keys select entries in the resolver-visible value only; exact grounded and localized OER keys continue to address cells.

`Value.Object.FieldValue` carries a string key, canonical object field, and value only through object construction so the factory can validate schema conformance before retaining the string map. A completed object does not retain hidden OER keys or schema-field metadata. Argument-bearing passive fields are outside the source and resolver-output domain and must be rejected.

`OpenValue.Ground` wraps canonical `EngineInputData`; other open values contain variables. Engine input data has no error sentinel. `OpenArguments.Ground` is either an error-free `Value.Arguments` or one tuple-level argument-resolution error, and variable bindings likewise distinguish error-free engine input data from an error outcome. Grounding throws on an unbound stamped variable or an unstamped template.

## Result Representation

The target `EngineResult` domain admits Kotlin `Int`, finite `Double`, `Boolean`, and `String`, structural `Schema.ID`, canonical `Schema.EnumValue`, `ObjectEngineResult`, `ListEngineResult`, and the singleton `ErrorEngineResult`. These are semantic union members rather than implementations of a common nominal result interface.

Every object-field and list-element value slot contains `EngineResult?`. Null represents GraphQL null. Every non-null child is another member of the result domain. Schema conformance admits `ErrorEngineResult` at every output type expression, including non-null types, so an error may occupy any value location in a result tree. `ErrorEngineResult` is an inhabited sentinel rather than Kotlin's bottom type and exposes no scalar, object, or list properties.

`EngineResultCell` represents one object-field or list-element occurrence with independent value and access-result promises. Its value promise contains `EngineResult?` and obeys the corresponding field or element schema type. Its non-null access-result promise contains `EngineResult` constrained to either `Boolean` or `ErrorEngineResult`; true means accepted, false means rejected, and the sentinel means access evaluation failed. This is an independent conformance relation rather than a GraphQL field-type relation. Factories, direct setters, and deferred promises validate their respective slot relation before making a completion observable.

`ListEngineResult` remains a nominal typed result wrapper and implements `List<EngineResultCell>` by delegation to a private backing list. The wrapper carries the element `typeExpr`; its equality is structural over that type expression and positional cell identities. A builder may transfer a mutable list into the wrapper without copying only when ownership transfer is exclusive and the builder retains no post-publication mutation path. The read-only `List` interface and a private delegate do not by themselves make an aliased mutable list immutable.

`EngineOutputData` represents resolver output without result cells or access decisions. Its target members are production-compatible scalar representations, recursive `List<EngineOutputData?>`, the temporarily retained `Value.Object`, and the singleton `EngineErrorData`. `EngineErrorData` belongs to the broad output domain but not to narrower simple, list, or object categories. Do not collapse resolver output and engine result into one carrier merely because they contain corresponding successful values.

Current production engine input and output data use Kotlin `String` for GraphQL String, ID, and enum values. Qplan preserves that representation in `EngineInputData` and `EngineOutputData`. The result domain instead admits `String` only for GraphQL String, `Schema.ID` for ID, and the canonical `Schema.EnumValue` owned by the expected enum type. Schema-directed publication converts output strings to those result representations, and resolver-input materialization converts them back to strings. `EngineIDData` and `EngineEnumValueData` are not part of the target model.

`Schema.EnumType` owns a name-keyed map of canonical `Schema.EnumValue` definitions. Each enum value exposes its name and containing enum type and uses the schema-canonical equality documented by `Schema`; same-named values of different enum types are distinct. `Schema.ID` is a structurally equal runtime value containing one string, not a canonical schema definition.

Input-object and argument field values use ordinary maps. Use `getValue` when presence is a
precondition and test membership before an optional lookup so an absent entry remains distinct from
a present null. `Value.ObjectFields` deliberately throws when indexed outside its lookup domain.

Cells are allocated by their containing OER or LER and use reference identity as their occurrence ID. Object construction is immutable by default. Opt-in mutable objects atomically install each absent exact cell once and throw on unset reads, repeated claims, or repeated writes. Lists have immutable positions and may opt into mutable cell slots.

An exact result path contains only `ObjectEngineResult.GroundKey` object steps and `ListEngineResult.Index` list steps. Open keys, schema fields, response keys, and aliases are not path components.

Response aliases and response ordering remain outside field-resolution identity. Canonical object fields plus ground arguments identify object cells; aliases belong to resolver-input materialization and must not create parallel OER or exact-path identities.

The fixture retains an unchanged GraphQL-Java source schema for validation and derives a separate model-only lowered schema. Source Node-valued fields are absent from that model schema: `foo: W<T>` is represented only by `foo_V_A_node: W<T_V_A_Bridge>`, and each concrete bridge has ordinary `id` and `node` fields with no generated hierarchy. Source schema names containing `V_A` are rejected. Because there is no bridge hierarchy, fixture composition also rejects object implementations that narrow the named return type of a Node-valued interface field.

Raw node references exist only as fixture inputs. Source-facing object construction and resolver adaptation lower them through `foo_V_A_node` producers and argumentless `T_V_A_Bridge.node` loaders before semantic reasoning. `Value.Object` itself stores only canonical lowered fields.

Ordinary model and semantics tests use canonical `Schema.field` and `Schema.objectField` coordinates, including explicit synthetic names for lowered Node fields. Source-name translation is confined to explicit pre-reasoning boundaries: GraphQL parsing, source-facing object construction, source declaration compilation, resolver adaptation, arbitrary source-recipe materialization, and focused tests of those adapters. Canonical assertions and resolver oracles must not use source-name lookup. Fixture code exposes this translation through `SourceSchemaAdapter`, not generic extensions on `Schema`.

## Engine API Alignment

Viaduct's `EngineObjectData.Sync` is the target synchronous partial-object boundary for the current migration. It is name-keyed and distinguishes absent selections from present-null values through `isPresent`.

Align storage and access with that API where it preserves the modeled meaning, but retain explicit qplan structure for schema-canonical keys, occurrence identity, write-once ownership, and access decisions when EOD does not represent those facts directly. Record current migration choices in [`../handoff.md`](../handoff.md); update this guide only when a choice becomes a stable model rule.

## Working Vocabulary

A semantic category is a modeled set of values. It may be represented nominally by an interface hierarchy, such as `Schema.Type`, or intensionally by a checked `Any` typealias, such as the target `EngineResult`.

A concrete variant is one particular form of value in a nominal category, such as `TypeExpr.List` within `TypeExpr`. A semantic union member is one admitted representation in an intentional `Any`-represented domain, such as `Schema.ID` within `EngineResult`.

A pre-domain type is an unambiguous runtime representation that may be admitted by one or more semantic domains. A pre-domain type does not inherit from or otherwise nominally belong to those domains. Kotlin primitives, `String`, `Schema.ID`, and `Schema.EnumValue` are examples.

A logic-constructible type is a concrete semantic type that reasoning code is allowed to create through a model factory. `ObjectEngineResult`, `Promise`, `ObjectEngineResult.Key`, and `FieldResolver` are examples.

An externally supplied type is a semantic input that reasoning code may inspect but does not construct. `Schema` and `ResolverRegistry` are examples. An externally supplied registry may contain logic-constructible model values such as `FieldResolver`.

Pre-reasoning infrastructure is code that prepares externally supplied inputs before semantic reasoning begins. SDL decoding, GraphQL parsing, registry assembly, and private test-fixture implementations are examples.

An equality-free occurrence family is a finite collection of occurrences that supports traversal without comparing its payload values. `SelectionForest` is the motivating example because selection equality is undefined.

## Mathematical Function Signatures

Every function declared in the model main source set has a mathematical signature, regardless of its visibility or whether it is a member, extension, factory, or implementation helper. Each receiver, context parameter, ordinary parameter, and return value must denote an input or output of the modeled mathematical operation.

Do not add parameters or results solely for programming concerns such as improving an exception message, retaining a source path, labeling a call site, logging, tracing, formatting, debugging, or selecting an implementation strategy. In particular, recursive semantic functions must not thread diagnostic context that does not affect their mathematical result. A partial function may throw when its input is outside its domain; that exception is not a modeled output. Diagnostics may be derived from inputs already present in the mathematical signature, but otherwise use a less specific message or no message.

Keep functions requiring non-mathematical inputs or producing non-mathematical outputs in pre-reasoning infrastructure outside the model main source set.

## Public Type Forms

Public nominal semantic categories are sealed interfaces unless the category itself is intentionally supplied by external composition code. For example, `Schema.Type` is sealed, while externally supplied roots such as `Schema` and `ResolverRegistry` are open interfaces. A performance-sensitive untyped union may instead be a documented `Any` typealias with explicit conformance operations.

Public leaf interfaces are also sealed unless their implementations are intentionally supplied by external composition code. For example, a logic-constructible `ObjectEngineResult` is sealed around its private implementation, while externally implemented `Schema.ObjectType` is an open leaf. Its enclosing category, `Schema.Type`, remains sealed.

Public singleton semantic values are `data object` declarations. `ErrorEngineResult`, `EngineErrorData`, `Value.Default.Absent`, and built-in scalar type definitions are examples.

Public enums represent finite scalar sets of unique values, such as the five possible results of `Schema.TypeRelation`. They are not used as substitutes for algebraic categories or checked semantic unions.

Do not expose public data classes or public sealed classes. For example, expose `Promise` as a sealed interface backed by private implementations rather than exposing their generated component operations.

## Equality

Document equality at the highest semantic category that defines it. Subtypes inherit that contract unless the category explicitly assigns different equality modes to its variants.

Every public semantic category or variant has one of four equality modes: structural equality, reference equality, schema-canonical equality, or undefined equality.

Structural equality means that two values are equal exactly when they have the same semantic constructor and their corresponding components are recursively equal. For example, two object keys are equal when their fields and argument values are equal, while a named type expression is never equal to a list type expression.

Reference equality means that two values are equal exactly when they are the same runtime occurrence. `EngineResultCell` and `ObjectEngineResult` use reference equality because their promise state is monotonically mutable. Their identity hashes are stable while cells or promises are installed and completed, so either may be used safely as a map key. A cell is allocated by its containing OER or LER, and its reference identity is its occurrence ID. `ListEngineResult` remains structural over its type expression and positional cell identity. Use `sameCompletedResultAs` when an explicit extensional comparison of completed result trees is required.

Schema-canonical equality applies only to `Schema` and schema-definition graph elements: `Schema.Type`, `Schema.EnumValue`, `Schema.OutputField`, `Schema.InputLikeField`, and `Schema.FieldArguments`. Two schemas are equal exactly when they denote the same canonical schema. Two schema elements from that schema are equal exactly when they denote the same canonical element. Applying `==` to elements from different schemas is outside the modeled equality domain, regardless of the host-language result.

Undefined equality means that Kotlin `==`, `equals`, hashing, membership in equality-based collections, map-key use, deduplication, and other equality-dependent operations have no semantic interpretation for that category. `Selection`, resolver values, resolver functions, and `Assumptions` are examples.

Semantic logic must not apply equality-dependent operations to undefined-equality values. For example, key a resolver-demand graph by canonical `Schema.ObjectField` elements rather than resolver objects, and represent selections with an equality-free occurrence family rather than a `Multiset<Selection>`.

`SelectionForest` supports current-member count, emptiness, permutation-invariant traversal, filtering, transformation, and concatenation. The one-member-per-source-field property belongs specifically to the corresponding forests returned by GraphQL selection flattening; it is not a carrier invariant. No operation internally compares whole `Selection` values. `merge(type)` filters and specializes occurrences to one concrete parent type, coalesces ordinary-equal open `ObjectKey` values, and returns an `ObjectSelectionForest`. `ObjectSelectionForest.instantiateBindings()` is the separate grounding boundary: it throws unless every current argument expression can be grounded and coalesces keys that become equal after substitution. Checked ground-key views are required before exact OER operations. Neither forest exposes selection membership, equality-based selection counting, hashing, forest equality, or observable ordering.

`MaterializeSelection` is a separate undefined-equality source-occurrence category. It retains one GraphQL response key alongside the same canonical key, applicability guard, and recursive shape needed for construction. `MaterializeSelectionForest` is its equality-free occurrence family. `constructionSelections()` recursively erases only response keys and is the one ordinary `SelectionForest` view of those occurrences.

Concrete field collection is explicit. `MaterializeSelectionForest.collect(type)` first filters source occurrences by the concrete parent object type, then groups solely by response key. Co-applicable members of one group must have equal concrete fields and syntactically equal open arguments before variable binding. Their nested source occurrences are concatenated without premature child collection. Mutually exclusive alternatives may therefore retain different source field invocations under one response key, while `ObjectMaterializeSelection` represents the one group selected for a concrete parent. Distinct response keys may share one ordinary variable-free construction key.

The test-fixture `Fragment` carrier retains a nominal composite type and an unnormalized forest for parsed selection requirements and pre-reasoning transformations. It is not part of the production model artifact. Canonical field-resolver `objectFragment` values are open `SelectionForest`s whose top-level occurrences have been specialized to the resolver field's concrete containing type.

Prefer a private data-class implementation when its generated structural equality exactly matches the category's equality contract. `IntValueImpl`, `KeyImpl`, and `GroundKeyImpl` are representative examples. Use a private regular implementation for an undefined-equality category such as `SelectionImpl`, or whenever generated componentwise equality is otherwise not the category's modeled equality.

## Construction

Distinguish logic-constructible types from externally supplied types. OERs, schema values, and model-owned field-resolver wrappers are logic-constructible; `Schema` and `ResolverRegistry` are externally supplied. Field-resolver functions are supplied during pre-reasoning assembly and encapsulated by `FieldResolver` behind a model-owned factory and public demand-projection operation. External raw node lookups, when accepted by composition infrastructure, are lowered to field resolvers before the canonical registry is exposed.

Every independently constructible non-singleton semantic type has a public factory, conventionally named `of`. For example, `ObjectEngineResult`, `Schema.ID`, `Promise`, and `FieldResolver` have factories. `EngineResultCell` is deliberately not independently constructible: OER and LER factories allocate their cells so a cell cannot be shared by two containers. Pre-domain Kotlin values and abstract semantic domains need no domain-wide factory.

Logic-constructible types use private `FooImpl` classes by preference, such as `KeyImpl` implementing `ObjectEngineResult.Key` and `GroundKeyImpl` implementing `ObjectEngineResult.GroundKey`. Use an internal `FooImpl` only when cross-file implementation access is necessary. Anonymous implementations are not used.

Externally supplied types have no model construction factory or main-source implementation. For example, test-fixture code privately implements `Schema.ObjectType` and `ResolverRegistry` while semantic code sees only their public interfaces.

Keep schema decoding, GraphQL parsing, resolver-function definitions, registry assembly, dependency-injection modules, and other pre-reasoning composition outside production semantic source sets. The model-owned resolver wrappers are the boundary that hides those functions from semantic algorithms. Tests that need a complete reasoning world construct it through `model.testing.TestWorld`; ordinary test sources do not decode schemas or assemble registries directly.

Constructors are private where possible and otherwise internal. Internal model code may call an internal constructor directly, but factory use remains preferred.

Factories return the most precise public type available. For example, a factory that always creates `Value.Default.Present` returns `Present`, not the broader `Value.Default`.

Place a factory on the highest semantic category where its meaning remains coherent and Kotlin overload resolution remains unambiguous. For example, `Value.Default.of(value)` belongs on `Value.Default`, while scalar factories should not be collapsed into an ambiguous `Value.of`. Prefer overloads that select precise variants when their parameter types are unambiguous.

An `of` factory normally accepts already semantic components. For example, `Promise.of` accepts the semantic value it immediately contains. Parsing GraphQL text and decoding SDL are pre-reasoning infrastructure rather than `of` factory behavior.

GraphQL coercion may be a semantic function. For example, construction of an argument-bearing object key may apply argument coercion, but the coercion relation should be independently defined rather than embedded only inside `ObjectEngineResult.Key.of`. Each coercion function separately specifies whether coercion failure is a modeled result or an input outside its domain.

Factories establish all carrier invariants available at their construction boundary eagerly and document those postconditions on the factory. Reusable invariant relations live in `model.invariants`. For example, `ObjectEngineResult.of` validates every initially present value coordinate, nullability, and nested result type, while `EngineResultCell` setters and validating deferred promises enforce the value-slot and access-result relations before completion. Every observable completed value therefore satisfies its documented carrier invariants, and `correctResolution` does not need schema conformance as a separate conjunct.

Use compositional validation for nested typed values. For example, a list factory validates its elements, and an enclosing OER factory checks that the list's declared element type is compatible with the field type rather than traversing and revalidating the entire list.

## Type Expressions

Every property whose value is a `TypeExpr` is named `typeExpr`, such as `Schema.OutputField.typeExpr` and `ListEngineResult.typeExpr`. Properties containing named schema definitions remain `type`, such as `Value.Object.type` and `ObjectEngineResult.type`. Private parameters may use the shorter name `type` when the local type is unambiguous.
