# Variable Invariants And Resolver04 Simplification Handoff

## Purpose

This handoff records a possible simplification of the execution-variable model. Resolver04 currently supports a more general relationship between a resolver's object fragment and its variable providers than the intended Viaduct model appears to require. Before extending Resolver04 or translating more of its machinery into another execution design, we should recover the intended invariants, enforce them at registry construction, and remove every accommodation that exists only for unsupported worlds.

The goal is not merely to document additional preconditions. The goal is to make the smallest Resolver04 that faithfully models the intended domain.

## The Missing Provider-Containment Invariant

The most consequential missing invariant is:

> Every provider path for a field-relative variable is already selected by the defining field resolver's object fragment.

Suppose field resolver `f` defines `$v`, and its provider reads `source { value }` relative to `f`'s containing OER. The intended model requires `f.objectFragment` to include that provider path. The provider describes how to read one value from the resolver input; it does not introduce an independent source of demand outside the input the resolver already declared.

The current executor registry does not enforce this. Instead, registry assembly treats a variable provider as a separate resolver site, computes an `extendedVariables` fragment for it, and roots that extension at the defining fragment's root whenever it encounters a use of the variable. Resolver04 consequently has to reconcile provider demand with the rest of the defining resolver's demand at runtime.

The provider-containment rule applies to exact resolver fragments, not only the representative `Resolver.Field.objectFragment`. An argument-dependent resolver exposes `objectFragment(arguments)`, and the provider must be present whenever the corresponding exact fragment can use the variable.

Containment is structural. The provider selection must occur at the same root, with compatible type guards and field paths, and with argument distinctions preserved. It is not enough that an unrelated selection happens to terminate at a field with the same name.

## The Missing Fixed-Shape Invariant

Provider containment is paired with a fixed-shape restriction on argument-dependent fragments:

> Every `objectFragment(arguments)` has the same selection shape as the representative `objectFragment`.

After argument values are erased, exact fragments must preserve the representative fragment's nominal type, field-coordinate occurrences, type guards, nesting, and occurrence multiplicity. Argument-dependent functions may only retarget, substitute, or forward values through argument positions already present in that fixed shape. They may not choose different fields, paths, or guards from runtime argument values.

The current `Resolver.Field` API accepts an arbitrary Kotlin function and cannot establish this universal property by inspection. The canonical registry should eventually represent exact fragments as a fixed template plus explicit argument substitution, or stipulate fixed shape as an external world invariant at construction. Lazy discovery of a different fragment shape during execution is outside the intended model.

Fixed shape makes the representative object fragment a finite structural envelope for every exact argument tuple. Registry validation can establish provider containment and transitive demand against that envelope before execution rather than trying to validate an unbounded family of unrelated fragment structures.

## Invariants Already Present Or Intended

The current model already establishes several useful restrictions:

- Each variable has one globally unique name and one `VariableCoordinate` identifying the concrete object field whose resolver defines it.
- Every use of a registered variable belongs to that defining field resolver.
- A provider path is rooted at the defining resolver's containing OER.
- Provider paths cannot traverse lists, cannot continue below simple values, and cannot terminate at objects.
- Provider and field-resolver dependencies share one acyclic `Schema.ResolverSite` graph.
- A variable used directly or transitively while producing itself creates a cycle and is rejected.
- Variables occur in input values carried by field arguments. The current scope has no `@skip` or `@include`, so variables do not control whether a structural selection exists.
- The semantic scope stipulates that every argument-bearing output field has a field resolver. Variable substitution therefore occurs at a behavioral boundary where output projection can stop before materializing the exact key.

Some of these are explicit registry checks, while others remain stipulated scope assumptions. A simplification should promote every assumption it relies on to the closest practical construction boundary rather than leave it implicit in `snipToDemand` or Resolver04.

The new invariants must also become part of the repository's consistent written model. [`evergreen.md`](./evergreen.md), all KDoc that describes variable providers, resolver fragments, registry extension, demand closure, or Resolver04, and the relevant `AGENTS.md`, handoff, claim, and argument documents should be updated to assume provider containment and fixed fragment shape explicitly. Documentation should no longer describe a provider as an independent source of demand that registry assembly may add to a defining fragment, or an exact fragment as having runtime-dependent structure. The code, world invariants, correctness statements, generators, and prose must all describe the same restricted domain.

## Why This Should Simplify Registry Assembly

Today `TestExecutorRegistry` constructs two kinds of extension:

- `extendedResolvers`, which roots transitive field-resolver requirements at each occurrence in a resolver fragment.
- `extendedVariables`, which separately extends provider paths and injects those extensions at the defining fragment's root when a variable use is encountered.

If every provider path is already in the defining object fragment, the second source of structural demand is redundant. Ordinary traversal of the defining fragment already encounters the provider path, and ordinary resolver extension already adds the transitive object fragments of resolver fields on that path. Variables still participate in dependency ordering because their values must be bound before exact argument keys can be formed, but their providers need not be inserted as an independent demand forest.

Fixed shape means that this traversal can be performed once over the representative structural envelope. Exact argument tuples change key values within that structure but cannot introduce new provider locations, field paths, guards, or transitive-demand sites.

A first concrete simplification experiment should therefore remove the variable-rooting branch in `collectExtensions` and replace it with validation that each provider path is contained in its owner's fixed fragment shape. Tests and generators that currently rely on implicit provider insertion or argument-dependent structural changes should be updated or rejected.

## Why This Should Simplify Resolver04

Under provider containment, one symbolic defining fragment contains both:

- Every provider path needed to bind its variables.
- Every location at which those variables will later instantiate resolver arguments.

Because the model has no directive-controlled selection and variables occur only at resolver boundaries, that fragment is a complete structural demand envelope before any variable value is known. Binding a variable changes an exact argument tuple; it does not reveal a previously unknown branch of the selection tree. Fixed shape extends the same guarantee across every argument-dependent exact fragment.

This suggests that Resolver04 should be expressible in terms of two artifacts:

- A symbolic envelope used to preserve all structurally possible producer-owned demand before bindings are known.
- Concrete demand obtained by substituting bindings and grouping exact `Value.Key` values.

The current implementation instead threads `ambientSelections`, `includeAmbientRoots`, required and speculative forests, matching symbolic demand, variable-free filtering, raw output provenance, and recursive widening through one algorithm. Some distinction between symbolic coverage and concrete work is essential, but provider containment and fixed shape should let us replace much of the dynamic ambient-demand reconciliation with one explicit envelope.

Candidate machinery to re-examine includes:

- Root insertion of `extendedVariables` during registry extension.
- `matchingAmbientDemand`.
- `withoutVariableSubselections` and `withoutVariableKeys`.
- `passiveDemand`.
- The repeated threading of `ambientSelections` and `includeAmbientRoots`.
- Whether required and speculative selections need to remain separate at every recursive level.

These are candidates, not established deletions. Each currently protects a concrete counterexample, and simplification is valid only after the replacement invariants or envelope explain that counterexample.

## Provider Containment Does Not Restore Depth-First Execution

The regression in `semantics/resolver04/ResolverGeneratedRegressionTest.kt` remains valid even when provider containment holds. Its defining resolver requires:

```graphql
child {
  field2(arg: $value)
}
common
```

The provider reads `$value` from `common`, which is explicitly part of the fragment. Resolving `common` requires:

```graphql
child {
  field2(arg: "literal")
}
```

Producing the provider value therefore enters the same `child` OER occurrence that later receives `field2(arg: $value)`. Once `common` returns `"bound"`, the symbolic selection becomes `field2(arg: "bound")`. Provider containment makes all of this structural demand visible in advance, but it does not provide a depth-first execution order in which the `child` subtree can be completed once and never revisited.

Resolver04's `widened` pass repairs this mismatch for immutable result trees by re-entering an existing cell and adding newly concrete descendants without applying its producer again. The planned Resolver05 worklist model in [`execution-handoff.md`](./execution-handoff.md) should make the same behavior ordinary write-once population of an existing child OER rather than recursive reconstruction.

This distinction is central:

> Provider containment can simplify demand collection. It does not eliminate the non-tree execution dependencies introduced by variable values.

## Suggested Validation Work

The next investigation should proceed in small, falsifiable steps:

1. Inventory every deterministic Resolver04 fixture and generated variable plan whose provider path is absent from its defining object fragment.
2. Add a registry-level containment check and negative tests for absent, wrongly rooted, guard-incompatible, and argument-distinct provider paths.
3. Replace or constrain arbitrary argument-dependent fragment functions so every exact fragment has the representative fragment's fixed shape.
4. Update the arbitrary generator so provider paths are selected from, or inserted explicitly into, the defining fragment before variable uses are emitted.
5. Remove implicit provider-root extension and run the focused registry, Resolver04 property, and stress suites.
6. Update `evergreen.md` and every relevant KDoc and supporting document so provider containment and fixed fragment shape are assumed consistently throughout the repository.
7. Re-express Resolver04 around one symbolic demand envelope, deleting ambient helpers only as replacement tests remain green.
8. Preserve the provider-containment regression above to demonstrate that simplification does not accidentally reintroduce strict depth-first execution.

The desired endpoint is a registry whose invariants make illegal variable worlds unrepresentable and a Resolver04 whose remaining complexity corresponds directly to unavoidable variable data flow.
