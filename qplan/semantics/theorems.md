# Semantic Theorems

This file records informal mathematical theorem statements and supporting arguments about the semantic Kotlin model. They are intended to clarify proof obligations and to provide structure for a possible future TLA+ translation, but they are not machine-checked proofs, and no such translation currently exists.

## Resolver 02 Produces a Resolver-Demand-Closed Result

### Claim

Fix an ordinary, variable-free reasoning world whose schema, values, selections, and resolver registry satisfy their documented invariants, including an acyclic resolver-demand graph. Assume the registry has no variable providers and resolver object fragments contain no variable references. For every Query-rooted fragment in the domain of `semantics.resolver02`, applying `Value.Object.resolve` to the empty Query value and the fragment's selection forest yields an `EngineResult.Object` that satisfies `isClosedUnderResolverDemand()`.

The theorem concerns the mathematical result of [`resolver02/Resolver.kt`](src/main/kotlin/semantics/resolver02/Resolver.kt), which applies each resolver non-selectively and treats its finite returned value as the complete output selection set. It does not establish the genuinely selective one-shot algorithm and does not assert an execution order, invocation schedule, or complexity bound.

### Proof structure

The proof separates demand discovery, same-object dependency availability, and recursive result construction. Lemma 1 establishes that the selection forest used to construct an object contains every transitively required resolver selection. Lemma 2 establishes that the ordered fold can supply each field resolver with the object fragment selected by those requirements. Lemma 3 establishes that resolving each selected value satisfies its accumulated subselections and recursively closes every nested result.

For the final Query result, consider any present key. If its arguments contain an error or its field has no registered field resolver, `isClosedUnderResolverDemand` imposes no field-resolver-fragment obligation at that key. Otherwise, Lemma 1 places every applicable selection from the field resolver's exact argument-dependent object fragment in the closed selection forest, and Lemma 3 shows that the completed OER satisfies those selections. Lemma 3 also shows that every value below the key is recursively closed. Synthetic `$id` and `$ids` bridge requirements introduced by fixture lowering are ordinary field-resolver fragment obligations, so the same argument covers generated node loaders without a separate correctness rule. Therefore every local resolver obligation observed by `isClosedUnderResolverDemand` holds throughout the result tree.

Lemma 2 is necessary to show that resolver 02 can construct the claimed result without materializing an absent input. Once a result is given, its ordering history is invisible to `isClosedUnderResolverDemand`; the extensional closure conclusion itself follows from Lemmas 1 and 3.

### Lemma 1: Transitive Resolver Demand Is Included

Let `closedSelections = closeResolverDemand(selections)` for a concrete object value. The returned selection forest contains every applicable occurrence from `selections`. For every concrete key in `closedSelections` whose arguments contain no error and whose field has a registered field resolver, `closedSelections` also contains every applicable selection from `resolver.objectFragment(key.arguments)`. The same statement holds transitively for resolver keys introduced by those added selections.

The argument is an induction over the finite set of reachable resolver keys not yet included in `expanded`. Each recursive step selects every currently present, unexpanded resolver key, adds its object-fragment selections, and records the key as expanded. A key is expanded at most once, and each resolver fragment and the reachable key universe are finite, so the measure strictly decreases until no unexpanded resolver key remains. At that fixed point, every activated resolver key has contributed its object fragment. Keys containing argument errors are deliberately excluded because `isClosedUnderResolverDemand` exempts them from resolver demand.

Filtering and concrete-key specialization through `SelectionForest.merge(type)` agree with the corresponding operations in `conformsToFragment`. Thus the closure contains the exact concrete keys whose cells are needed to satisfy each applicable resolver fragment, rather than merely abstract or inapplicable selections.

### Lemma 2: Dependency Order Makes Resolver Inputs Available

Before a key is resolved, every sibling subtree required by its exact argument-dependent object fragment has already been resolved and is present in the prefix OER.

The argument is an induction over the prefix of the ordering returned by `dependencyOrder`. A key is ready only when `dependenciesOf` finds no required sibling among the unresolved keys. Lemma 1 guarantees that every applicable required sibling belongs to the complete key set, so a required sibling absent from the unresolved suffix must belong to the resolved prefix. The prefix induction invariant, using Lemma 3 for previously resolved keys, says that each such sibling cell also satisfies its accumulated subselections. The prefix therefore conforms to the current resolver's complete object fragment, making `resolved.materialize(resolver.objectFragment(key.arguments))` defined. For a fixture-generated loader at singular `foo(args)`, this exact fragment requires `foo$id(args)`; a list-shaped `foo(args)` requires `foo$ids(args)`. Unequal argument tuples remain separate dependencies.

The acyclic resolver-demand invariant ensures that every nonempty unresolved set has a ready key. Independent ready keys may appear in any order because none requires another member of that ready set.

### Lemma 3: Recursive Resolution Satisfies Selections and Closes Descendants

After a key is resolved, its cell value satisfies every subselection accumulated for that key, and every object or list nested within that value satisfies `isClosedUnderResolverDemand`.

The argument is a mutual induction over passive `resolveValue` construction, recorded-path continuation, and object `resolve`. Null, error, and simple values satisfy the claim immediately because they create no descendant demand. A list applies the induction hypothesis independently to every element while treating list-transparent resolver-continuation paths uniformly across list positions. For an object, non-selective construction copies every actually supplied passive field recursively and stops at registered resolver boundaries, while resolver demand records the paths that must resume active resolution. The finite returned value itself bounds recursive construction. If newly closed incoming demand reaches beneath an existing passive key, Resolver02 revisits that key from the same source value. Resolver keys already present on the object currently being resumed are excluded from its fold; rebuilding the passive subtree can resolve descendants again, which is permitted because this theorem makes no invocation-schedule claim.

For an object at the root of an object `resolve` application, Lemmas 1 and 2 establish every activated field-resolver obligation, while the induction hypotheses establish closure below every cell. There is no object-type resolver activation or implicit root bridge obligation in the canonical model. A fixture-generated node loader is simply a field resolver whose input fragment activates its matching synthetic bridge coordinate, and recursive object resolution handles its loaded object by the same induction case as every other object value.

Lemma 2 and this induction are proved together at a field-resolver key: the prefix induction supplies the complete materializable input for the resolver, and the recursive induction applies to the resolver's complete finite output under the key's merged subselections. This is not circular because both uses concern strictly earlier keys in the finite prefix or strictly smaller subderivations of the current resolution derivation.
