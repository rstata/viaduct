# Model Domain Guidance

## Purpose

This project defines the carrier algebra for reasoning about Viaduct field resolution. Follow the repository-wide mathematical interpretation in [`../AGENTS.md`](../AGENTS.md) and the concrete API rules in [`guidelines.md`](./guidelines.md).

The carrier defines values that may occur in an OER tree; it does not define which OER is correct for an operation. Keep correctness, demand derivation, executor attribution, and interpretation of checker results in separate semantic domains.

## Domain Assumptions

`EngineResult` values are finite, inductively defined algebraic values. Kotlin object sharing, reference identity, self-reference, and cyclic runtime object graphs are outside the model.

Schema definitions form reciprocal graphs: type definitions contain fields, while fields and arguments navigate back to their containing definitions. `Assumptions.schema` externally stipulates one complete canonical graph; schema decoding and reciprocal graph assembly are not semantic model operations.

Nesting declarations under `Schema` provides namespacing only. A nested schema definition does not retain an enclosing `Schema` instance; canonical ownership follows from the one-world assumption and the definitions carried by values.

Input-object fields and output-field arguments share `InputLikeField`. Input-object values and output-field argument tuples share `InputLikeValue`. Every argumentless output field uses the canonical `Schema.NoArguments`, while empty `ArgumentsValue` instances remain ordinary structural values rather than singletons.

Kotlin inheritance and generic variance classify carrier values but do not define GraphQL interface implementation, output subtyping, input coercion, or type variance. Use canonical schema relations and documented carrier invariants for those facts.

`FieldValues`, `ObjectFieldValues`, and `VariableBindings` deliberately throw when lookup is outside their domain. Check `containsKey` before lookup when absence is possible; generic `Map` helpers such as `getOrElse` may call the throwing `get`.

## Output Representations

`ObjectEngineResult` and other `EngineResult` values represent Viaduct field-resolution results. Each object field and list element has an `EngineResult.Cell` containing its value and retained check value; list results carry their element `typeExpr` even when empty.

`Schema.OutputValue` values represent outputs of executors such as resolvers and checkers. Executors yield GraphQL values rather than value/check pairs. Do not collapse these representations or infer executor semantics from the structure of an OER cell.

`Schema.ObjectValue` fields and OER cells are keyed by exact `Schema.ObjectKey` coordinates, preserving distinct coerced argument tuples for one field.

The `Schema.Value` model is incomplete: lazy values, node references, and similar engine-specific intermediate values are not yet represented.

## Intentional Differences From Existing Viaduct

`Schema.ObjectKey` does not include a response alias. Canonical output fields and fully coerced arguments determine field-resolution identity; aliases and response ordering belong to external field completion.

Every key present in an `ObjectEngineResult` or `Schema.ObjectValue` belongs to that value's concrete `Schema.ObjectType` and contains no unresolved variables. Keys used by selections may instead carry abstract fields or unresolved variables and must be specialized and instantiated before entering either value.
