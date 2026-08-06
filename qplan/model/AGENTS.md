# Model Domain Guidance

## Purpose

This project defines the carrier algebra for reasoning about Viaduct field resolution. Follow the repository-wide mathematical interpretation in [`../AGENTS.md`](../AGENTS.md) and the concrete API rules in [`guidelines.md`](./guidelines.md).

The carrier defines values that may occur in an OER tree; it does not define which OER is correct for an operation. Keep correctness, demand derivation, executor attribution, and interpretation of checker results in separate semantic domains.

The `model.invariants` package defines reusable relations used to state carrier and world invariants precisely. Factory KDocs state the invariant postconditions established for every constructed result; because carrier implementations are sealed behind those factories, these postconditions are universally quantified over carrier values in the fixed reasoning world.

## Domain Assumptions

`EngineResult` values are finite, inductively defined algebraic values. Kotlin object sharing, reference identity, self-reference, and cyclic runtime object graphs are outside the model.  ("OER" which stands for object engine-result is a common shorthand for the `EngineResult.Object` type.)

Schema definitions form reciprocal graphs: type definitions contain fields, while fields and arguments navigate back to their containing definitions. `Assumptions.schema` externally stipulates one complete canonical graph; schema decoding and reciprocal graph assembly are not semantic model operations.

Nesting declarations under `Schema` provides namespacing only. A nested schema definition does not retain an enclosing `Schema` instance; canonical ownership follows from the one-world assumption and the definitions carried by values.

The resolver registry exposes demand between concrete `Schema.ObjectField` coordinates. During registry assembly, a private dependency graph also contains `Value.Variable` vertices so providers can be ordered before variable uses and cycles through variables can be rejected. A variable is structurally identified by its resolver-local name, the canonical concrete object field whose resolver defines it, and a nullable exact OER path reserved for occurrence-specific alpha-renaming; registry assembly currently constructs resolver-level variables with null paths and establishes canonical ownership.

Test-fixture composition represents raw resolver declarations as `FieldResolverDefinition` values while it lowers node coordinates, rewrites output and demand, canonicalizes variables, and attaches observers. Registry assembly consumes those definitions only after validation and dependency analysis, producing canonical `FieldResolver` values with their variables and predecessor demand already attached; semantic code never receives a definition or a resolver-building operation.

External field-relative variable-provider declarations pair alias-preserving GraphQL object-fragment source with a nonempty response-key path. Pre-reasoning composition validates the production `fromObjectField` restrictions and compiles each declaration to one alias-free `List<Value.Key>`. Every resulting provider is one structurally contained path in its defining resolver's object fragment. Argument-dependent exact fragments are constructed by retargeting arguments in one fixed selection template, and registry assembly validates provider containment in the representative fragment and each exact fragment observed by semantic reasoning.

Every canonical registry is also depth-first variable-stratified. For each concrete object type, registry assembly conservatively collapses argument-distinct occurrences of one field into one structural branch, combines ordinary sibling resolver dependencies with provider-production-before-use edges, closes variable production transitively, and rejects a self-edge or longer branch cycle.

Input-object fields and output-field arguments share `InputLikeField`. Input-object values and output-field argument tuples share `InputLikeValue`. Every argumentless output field uses the canonical `Schema.NoArguments`, while empty `ArgumentsValue` instances remain ordinary structural values rather than singletons.

Kotlin inheritance and generic variance classify carrier values but do not define GraphQL interface implementation, output subtyping, input coercion, or type variance. Use canonical schema relations and documented carrier invariants for those facts.

`FieldValues` and `ObjectFieldValues` deliberately throw when lookup is outside their domain. Check `containsKey` before lookup when absence is possible; generic `Map` helpers such as `getOrElse` may call the throwing `get`.

## Output Representations

`EngineResult.Object` and other `EngineResult` values represent Viaduct field-resolution results. Each object field and list element has an `EngineResult.Cell` containing its value and retained check value; each object result also carries its resolved nullable input values for execution variables, distinguishing absence from a null binding. List results carry their element `typeExpr` even when empty.

`Value.Output` values represent outputs of executors such as resolvers and checkers. Executors yield GraphQL values rather than value/check pairs. Do not collapse these representations or infer executor semantics from the structure of an OER cell.

`Value.Object` fields and OER cells are keyed by exact `Value.ObjectKey` coordinates, preserving distinct coerced argument tuples for one field. `Value.Key` remains the broader selection-key category and may carry an abstract-type field.

The `Value` model is incomplete: lazy values and similar engine-specific intermediate values are not represented. Raw node references exist only as external test-fixture inputs and are lowered to synthetic ID bridge values before semantic reasoning; the canonical value algebra has no distinct node-reference variant.

## Intentional Differences From Existing Viaduct

`Value.Key` does not include a response alias. Canonical output fields and fully coerced arguments determine field-resolution identity; aliases and response ordering belong to external field completion.

Every key present in an `EngineResult.Object` or `Value.Object` belongs to that value's concrete `Schema.ObjectType` and contains no unresolved variables. Keys used by selections may instead carry abstract fields or unresolved variables and must be specialized and instantiated before entering either value.

`PathComponent` is the exact OER-tree path category. Its variants are `Value.ObjectKey` for an object-cell step and `Value.ListIndex` for a list-element step.
