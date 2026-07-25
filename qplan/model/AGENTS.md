# Model Domain Guidance

## Purpose

Treat the files in this directory as a baseline data model for reasoning about the results of Viaduct query executions. They define the carrier values that may appear in an OER tree, but they do **not** define which OER is correct for an operation.

Follow the repository-wide purpose and modeling discipline in [`../AGENTS.md`](../AGENTS.md).

Keep definitions of correctness in other packages. In particular, do not use this package to define:

- the perfect or correct OER for an operation;
- how demand is derived;
- how executors produce cells;
- what checked and unchecked fields mean; or
- how a cell's check component is produced or interpreted.

Those concepts belong to separate reasoning domains. Do not project them back into the carrier types in this package.

## Modeling Discipline

Some Java and Kotlin runtime conventions are deliberately replaced by mathematical assumptions:

- For value objects, Java and Kotlin normally interpret `Object.equals` as strict equality: `true` means definitely equal and `false` means definitely unequal. As documented in `Schema.kt`, equality is conservative when assumptions permit unbound variables: `true` still means definitely equal, while `false` means the values might be unequal rather than definitely unequal.
- In our models, we use value equality to mean _semantic_ equality, not syntactic equality.  In the case of `Selection` for example, data-class-style equality would capture syntactic equality of `Selection`s but not true runtime equivalence.  We do not use value equality unless there's a clear semantic meaning for it.  At the same time, this is supposed to be a "mathematical model," which begs the question: what does NON-value equality mean. As of now we don't have a great answer, hopefully we'll tighten this up over time.
- Treat `EngineResult` values as finite, inductively defined algebraic values. Kotlin reference identity, object sharing, self-reference, and cyclic runtime object graphs are outside the model and must not be inferred from the use of Kotlin interfaces.
- Schema definitions intentionally form reciprocal graphs: type definitions contain their fields, while fields and arguments navigate back to their containing definitions. Ordinary immutable Kotlin construction cannot instantiate these classes while simultaneously satisfying those invariants. `Assumptions.schema` stipulates the complete canonical definition graph; the classes in `Schema.kt` are not an executable construction API.
- Input-object fields and output-field arguments share the `InputLikeField` abstraction. Each output field's complete argument definition is a `FieldArguments`; every field with no arguments refers to the identity singleton `Schema.NoArguments`.
- Each reasoning exercise has exactly one `Assumptions` and one `Schema`. Construct every `Schema.Value` through that schema's instance factories; those factories enforce canonical schema references at the construction boundary, so reasoning code may assume that definitions carried by values belong to the one schema without repeating ownership checks.
- Nesting definition declarations under `Schema` provides namespacing only; a nested definition object does not retain an enclosing `Schema` instance. The canonical graph and one-schema discipline establish ownership, while value factories validate any definition objects supplied when constructing values.
- Schema definition classes denote canonical mathematical entities. Their default Kotlin identity equality is intentional: two references to the same schema coordinate denote the same object. Structural equality is intended only for acyclic value objects that explicitly define it, such as `TypeExpr`, `DefaultValue`, `ObjectEngineResult.Key`, and `ObjectEngineResult.Cell`.
- Sealed interfaces and classes describe exhaustive mathematical categories. They are not runtime registration or extension mechanisms, and their lack of constructible leaf implementations does not imply that the modeled sets are empty.
- Kotlin inheritance and generic variance classify host-language values; they do not by themselves define GraphQL interface implementation, output subtyping, input coercion, or type variance. Use the schema invariants and declarative relations for those facts.
- Maps, sets, and lists denote finite mathematical collections. Do not infer mutation, collection implementation, iteration order, allocation, or performance properties. Names inside the canonical schema graph and its relation results resolve against the schema supplied by `Assumptions`; correctness predicates separately constrain names carried by operations, values, and results.
- Methods and derived properties may specify partial mathematical functions or declarative relations. A nullable result and a thrown exception have the distinct meanings documented by the declaration. Do not infer an implementation, search procedure, cache, index, or complexity from their Kotlin signatures.
- `suspend` permits an executable implementation to suspend but introduces no scheduling, concurrency, blocking, or nondeterminism into the model. Modeled lookups terminate with one of their documented outcomes.
- `FieldValues` and `VariableBindings` deliberately throw from `get` when a field or variable is missing. Map extension helpers such as `getOrElse` may call that throwing operation rather than applying their fallback. Check `containsKey` explicitly when absence is possible.

Keep package-wide assumptions and scope boundaries in this file. Keep KDoc focused on invariants and semantics specific to the type it documents.

## Output Representations

There are two representations of GraphQL outputs:

- `ObjectEngineResult` and the other `EngineResult` values represent the results of Viaduct executions. Each object field produces an `ObjectEngineResult.Cell`, which contains a pair: the field value and its check value.
- `Schema.OutputValue` values represent the outputs of executors, such as resolvers and checkers. Executors produce individual GraphQL values rather than value/check pairs.

Do not collapse these representations or infer executor semantics from the structure of an OER cell.

The `Schema.Value` model is not yet complete. In particular, it does not represent lazy values, node references, or similar engine-specific values that resolvers may return before those values are normalized into ordinary GraphQL outputs. Future work will extend the model to account for these values.

## Intentional Differences From Existing Viaduct

Unlike the existing Viaduct implementation, `ObjectEngineResult.Key` does **not** include a response alias. OER field identity is determined by the schema field name and its fully coerced arguments. Selections of that field with different aliases therefore address the same OER cell; aliases remain relevant when constructing the external GraphQL response, not when identifying data within an OER.
