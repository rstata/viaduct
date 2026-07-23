# Model Domain Guidance

## Purpose

Treat the files in this directory as a baseline data model for reasoning about the results of Viaduct query executions. They define the carrier values that may appear in an OER tree, but they do **not** define which OER is correct for an operation.

Keep definitions of correctness in other packages. In particular, do not use this package to define:

- the perfect or correct OER for an operation;
- how demand is derived;
- how executors produce cells;
- what checked and unchecked fields mean; or
- how a cell's check component is produced or interpreted.

Those concepts belong to separate reasoning domains. Do not project them back into the carrier types in this package.

## Modeling Discipline

The Kotlin code in this directory must compile, but it is not intended to become running production code. Use Kotlin as a stand-in for a formal modeling language such as TLA+: it provides a more rigorous vocabulary than prose while remaining familiar. Some Java and Kotlin runtime conventions are deliberately replaced by mathematical assumptions:

- Whenever reasoning uses this model, its assumptions must state the expected contents of the global `variableValues` map. Those assumptions, rather than its nonexistent startup value, determine the values of variables.
- For value objects, Java and Kotlin normally interpret `Object.equals` as strict equality: `true` means definitely equal and `false` means definitely unequal. As documented in `GraphQLValue.kt`, equality is conservative when assumptions permit unbound variables: `true` still means definitely equal, while `false` means the values might be unequal rather than definitely unequal.
- Treat `EngineResult` values as finite, inductively defined algebraic values. Kotlin reference identity, object sharing, self-reference, and cyclic runtime object graphs are outside the model and must not be inferred from the use of Kotlin interfaces.
- For convenience, reasoning may use map helpers such as `getOrElse`. Assume that every absence-handling helper first checks `containsKey` and therefore does not throw when an element is missing, even when Kotlin's stock implementation would invoke the model's throwing `get` operation before applying its fallback.

Keep package-wide assumptions and scope boundaries in this file. Keep KDoc focused on invariants and semantics specific to the type it documents.

## Output Representations

There are two representations of GraphQL outputs:

- `ObjectEngineResult` and the other `EngineResult` values represent the results of Viaduct executions. Each object field produces an `ObjectEngineResult.Cell`, which contains a pair: the field value and its check value.
- `GraphQLOutputValue` values represent the outputs of executors, such as resolvers and checkers. Executors produce individual GraphQL values rather than value/check pairs.

Do not collapse these representations or infer executor semantics from the structure of an OER cell.

The `GraphQLValue` model is not yet complete. In particular, it does not represent lazy values, node references, or similar engine-specific values that resolvers may return before those values are normalized into ordinary GraphQL outputs. Future work will extend the model to account for these values.

## Intentional Differences From Existing Viaduct

Unlike the existing Viaduct implementation, `ObjectEngineResult.Key` does **not** include a response alias. OER field identity is determined by the schema field name and its fully coerced arguments. Selections of that field with different aliases therefore address the same OER cell; aliases remain relevant when constructing the external GraphQL response, not when identifying data within an OER.
