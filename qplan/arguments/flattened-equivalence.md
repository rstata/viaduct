# Argument for [flattened-equivalence]

This claim concerns the use of selection sets for Viaduct field resolution, not GraphQL response completion. Given fixed global assumptions and runtime object values, the observable behavior is the unordered forest of field-resolution obligations applicable to those object occurrences. Each obligation identifies its object occurrence, nominal field coordinate, OER key, and recursively the obligations for returned object occurrences. Aliases, response keys, source order, and the intermediate traversal of inline fragments are not observable at this boundary.

Consider an interpreter for spec selections. A field emits one resolution obligation when every enclosing type condition admits the concrete runtime type. Entering an inline fragment is a silent step: a fragment without a type condition leaves the context unchanged, while a typed fragment changes the nominal type and adds its possible runtime types to the conditions that must all hold. Descending through a field starts a new context based on that field's result type.

The flattened interpreter emits a selection exactly when the concrete runtime type belongs to its `possibleTypes`. It obtains the field coordinate from `nominalType` and the field name in `key`, then interprets `subselections` against returned object occurrences.

Flattening preserves the following invariant for every field occurrence:

- `key` preserves the schema field name and arguments while discarding the unobservable alias;
- `nominalType` is the innermost typed fragment since the last field boundary, or the type inherited at that boundary when there is no such fragment;
- `possibleTypes` is the intersection of the possible types admitted by every enclosing typed fragment and the type inherited at the field boundary; and
- `subselections` are recursively flattened after resetting the context to the field's result type.

For any concrete parent type, satisfying every nested spec type condition is equivalent to membership in their intersection. The interpreters therefore emit corresponding obligations for each field occurrence. The field case establishes the same key and nominal coordinate and invokes the induction hypothesis on its result; a typeless fragment preserves the hypothesis; and a typed fragment preserves it by adding one set to the intersection. Structural induction over each finite selection forest yields the same unordered forest of resolution obligations.

Field collection is deliberately downstream of this equivalence. Duplicate obligations remain distinct, so any future collector that depends only on these resolution observations may group conservative-equal OER keys, combine child demand, or retain duplicates without invalidating the argument. A collector that depends on erased information such as aliases or response order is outside the claim because it performs GraphQL response collection rather than OER-keyed field resolution.
