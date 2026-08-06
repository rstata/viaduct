# Depth-First Variable Stratification Handoff

## Purpose

This handoff defines the intended direction for execution variables in the query-planning model. The target is a variable-aware extension of Resolver03 that retains Resolver03's depth-first construction and topological sibling ordering. Resolver04's symbolic coverage, speculative projection, raw-output provenance, and widening of already-visited OER subtrees are not the basis for the new design.

The central design choice is to restrict field-relative variable providers and uses statically. Registry construction should reject worlds whose variable data flow cannot be ordered as a depth-first traversal of complete OER subtrees. In every accepted world, each subtree is entered only after every execution variable used by field arguments anywhere in that subtree has been bound to a concrete input value.

## Current Baseline

Resolver03 accepts variable-free selections. At each concrete `Value.Object`, it gathers applicable selections, adds each activated resolver's exact registry-computed `predecessorDemand`, groups all occurrences by `Value.ObjectKey`, topologically orders sibling keys by resolver demand, and resolves each key completely. Object and list outputs are traversed recursively before resolution returns to the containing OER.

This gives Resolver03 its useful depth-first property: once resolution returns from a field cell's output subtree, all demand for that subtree has already been concrete, aggregated, and resolved. No later operation resumes that subtree or reapplies its producer.

The current registry and carrier model already establish several prerequisites for extending this construction:

- Every execution variable has one globally unique `Value.Variable` and one `VariableCoordinate` whose concrete object field is its defining resolver.
- Every variable provider is one field-cell path rooted at the defining resolver's containing OER.
- Every provider path is structurally contained in the defining resolver's representative object fragment and in every exact fragment observed by semantic reasoning.
- Provider paths cannot traverse lists, cannot continue below simple values, and cannot terminate at objects.
- Every argument-dependent object fragment is produced by retargeting argument values in one fixed selection shape.
- Field resolvers and variables participate in one conservative private dependency graph during registry assembly.
- Registry-computed `predecessorDemand` supplies the guarded, path-rooted transitive field-resolver requirements of an exact object fragment.

Resolver04 supports a broader variable domain. It may resolve a provider by entering an OER subtree that also contains a symbolic use of that variable, return from that subtree, bind the variable, and then widen the existing subtree with newly concrete demand. It preserves raw resolver output and speculative symbolic coverage so that widening does not reapply a producer or lose output demand when symbolic and concrete keys converge.

The new direction deliberately excludes worlds that need those mechanisms. Resolver04 remains useful evidence about the broader domain, but its widening behavior is not a requirement for the replacement design.

## Goal

The desired construction has this property:

> Before resolution enters a field-cell subtree, every execution variable occurring in any field argument in that subtree is already bound on its defining containing OER.

After those bindings exist, the construction substitutes them throughout the subtree's selections and predecessor demand, groups occurrences by fully concrete `Value.Key`, merges demand for equal argument tuples, and resolves the subtree exactly as Resolver03 does.

This is stronger than merely requiring a variable's provider to precede the exact field whose argument uses it. The provider must precede entry into the entire OER subtree containing that use. Otherwise resolution could enter the subtree for provider-related work, encounter an unresolved symbolic key within it, return to bind the variable, and need to enter the same subtree again.

The static restriction must account for all variables together. Two variable definitions that are individually provider/use-separated can still impose contradictory subtree orders. The accepted domain therefore needs one unified, conservative ordering relation rather than one independent prefix check per variable.

## Structural Branches

Fix one possible concrete OER object type `T`. A structural branch is the complete subtree rooted at one immediate field-cell coordinate of an OER of type `T`.

For initial validation, branch identity is deliberately conservative:

- The branch uses the canonical concrete output-field coordinate on `T`.
- Response aliases do not distinguish branches.
- Argument values do not distinguish branches. Two selections of the same concrete field under different concrete, symbolic, or retargeted argument tuples belong to the same structural branch.
- Two guarded occurrences can belong to the same branch whenever their possible concrete parent types overlap at `T`.
- A branch rooted at a list-valued field contains every possible list position and every object occurrence below those positions.
- Runtime nulls, errors, empty lists, resolver return values, and concrete type choices do not make a statically possible branch disappear.

Ignoring argument values is stricter than OER cell identity, which includes fully coerced arguments. This is intentional. The registry currently represents argument retargeting with an opaque Kotlin callback, so argument-sensitive non-aliasing cannot be established uniformly at world construction. A future declarative argument-pattern model may accept additional provably disjoint branches without changing the invariant's structure.

## Provider Production

For a registered variable `v`, `productionBranches(v)` is the set of structural branches at its defining containing OER that may be entered while producing its binding.

The set is the conservative transitive closure of:

1. The branches traversed by `provider(v)`.
2. Every branch required by a field resolver encountered while traversing that provider, using the resolver's fixed structural object-fragment envelope and transitive predecessor demand.
3. Every branch required to produce another variable referenced by the provider path or by resolver requirements needed during that production.
4. Every traversed prefix branch, whether the provider ultimately succeeds, yields null, or yields an error.

Variable-provider dependencies remain permitted when they can be stratified. If producing `v` uses `w`, the branches producing `w` are part of the work that must precede any use of `v`.

## Variable Uses

For a registered variable `v`, `useBranches(v)` is the set of structural branches at its defining containing OER whose subtree contains a selection occurrence with `v` anywhere in its argument values.

Uses inside nested input objects and input lists count. A use is assigned to the immediate root branch containing it, not merely to the terminal field whose key contains the variable. Thus a use at:

```graphql
other {
  nested {
    field(arg: $value)
  }
}
```

belongs to the `other` branch of the defining containing OER.

Fixed-shape object fragments make these use locations structurally finite even though binding changes their exact argument values.

## Unified Branch-Order Invariant

For each possible concrete OER object type `T`, registry construction forms one directed graph whose vertices are the structural branches on `T`.

The graph contains:

- Every ordinary resolver-demand edge required by Resolver03, directed from a sibling branch that supplies resolver input to the consuming resolver branch.
- For every variable `v`, an edge `p -> u` for every `p` in `productionBranches(v)` and every `u` in `useBranches(v)`.

The registry accepts the world only when:

> For every possible concrete OER object type, the conservative unified branch-order graph is acyclic.

This one condition includes two important cases:

- A provider-production branch that is also a use branch creates a self-edge and is rejected.
- Cross-variable ordering contradictions create a longer cycle and are rejected.

The graph is conservative in the same spirit as the existing resolver-demand graph. An edge remains when guards may overlap or symbolic and concrete argument tuples might never coincide at runtime. A valid execution must not be used to justify a registry that would be unsafe for another valid execution in the same world.

## Why This Is Sufficient

An acyclic branch graph has a topological order. Resolve the branches of each concrete OER in any such order.

Consider a branch `b` when it is reached. If a selection anywhere in `b` uses variable `v`, every branch that may be needed to produce `v` has an edge to `b` and therefore occurs earlier in the order. Those production branches have been resolved completely, so the provider path is readable and `v` has a stored binding. The same argument applies simultaneously to every variable used in `b`, including variables with transitively dependent providers.

The construction can therefore instantiate every variable in `b` before forming any OER key in that branch. Occurrences that instantiate to the same fully coerced argument tuple are grouped into one `Value.ObjectKey` before resolver application. Registry-computed predecessor demand and successor-demand lifting then provide the same complete input and output demand used by Resolver03.

After key formation, the branch is variable-free and can be resolved recursively with Resolver03's existing construction. Because every symbolic use in the branch was instantiated before entry and fixed-shape fragments cannot reveal another structural location later, no later binding can add work beneath the completed branch.

The proof shape is induction over the topological branch order, nested inside induction over the finite OER tree. The branch-order induction establishes binding availability before key formation. Resolver03's existing depth-first argument then establishes complete resolution before returning from each branch.

## Rejected Shapes

The established widening example is rejected:

```graphql
child {
  field2(arg: $value)
}
common
```

If producing `$value` from `common` requires:

```graphql
child {
  field2(arg: "literal")
}
```

then `child` belongs to both `productionBranches(value)` and `useBranches(value)`. The variable edge is `child -> child`, so registry construction rejects the world before resolution.

The rejection does not depend on whether `$value` becomes `"literal"`, `"bound"`, null, or an error. It also does not depend on whether the two terminal `field2` occurrences would become the same exact OER key. Entering the shared `child` branch before the binding exists is itself outside the depth-first domain.

Cross-variable cycles are also rejected. For example:

```text
producing v enters branch B; v is used in branch A
producing w enters branch A; w is used in branch B
```

The graph contains `B -> A` and `A -> B`, so neither branch can be entered first with all of its argument variables bound.

Selections of `child(id: 1)` and `child(id: 2)` are conservatively treated as the same structural branch. A provider/use overlap between them is rejected even when their current concrete arguments differ.

## Accepted Shapes

This shape is accepted when producing `$value` never enters `other`:

```graphql
common
other {
  field(arg: $value)
}
```

The branch graph contains `common -> other`. Resolution completes `common`, reads and stores `$value`, substitutes it throughout `other`, and then enters `other` once with concrete demand.

Acyclic chains of variable dependencies can also be accepted:

```text
branch A produces w
branch B uses w and produces v
branch C uses v
```

The graph contains `A -> B -> C`. Each branch is variable-free by the time resolution enters it.

Independent provider and use branches remain parallel in the partial order. The semantic construction may choose any topological order; an implementation may execute unrelated ready branches concurrently without changing the depth-first completeness requirement within each branch.

## Construction Sketch

A variable-aware Resolver03 extension should operate as follows at each concrete `Value.Object`:

1. Gather the applicable fixed-shape symbolic selection envelope and registry-computed structural predecessor demand without requiring symbolic keys to become OER keys.
2. Specialize structural branches and their conservative ordering relation to the concrete object type.
3. Choose a ready branch from the topological order.
4. Read and store every variable whose complete provider path is available in the resolved prefix.
5. Require every variable used anywhere in the chosen branch to have a stored binding.
6. Substitute those bindings throughout the branch selections and exact predecessor demand.
7. Group the resulting occurrences by concrete `Value.Key`, merging equal argument tuples.
8. Resolve the branch completely using Resolver03's materialization, sibling ordering, resolver application, successor-demand lifting, and recursive value traversal.
9. Add the completed branch to the resolved prefix and continue.

The resolved prefix may contain variable bindings in addition to cells, but it does not need raw resolver-output provenance, speculative demand, or a way to reopen an existing cell. A completed branch is never selected again.

## Registry Validation

This is a world-construction invariant. The principal implementation boundary is `TestExecutorRegistry`, which already validates providers, builds the private dependency graph, and computes predecessor demand in dependency-first order.

Registry construction needs a finite structural analysis that preserves more information than field-resolver reachability:

1. Specialize each representative fixed-shape fragment over every possible concrete parent type.
2. Extract each variable's root use branches.
3. Walk each provider path and all transitive production requirements to extract production branches.
4. Collapse argument-distinct occurrences of one canonical concrete field into one conservative structural branch.
5. Add ordinary resolver-demand and provider-before-use edges.
6. Reject a self-edge or cycle.

The diagnostic should identify the concrete object type, variable or resolver dependency that introduced each relevant edge, provider path, production branch, use path, and resulting self-edge or cycle. A generic resolver-demand-cycle message is not sufficient for explaining this domain restriction.

The invariant belongs on the canonical `ExecutorRegistry` contract even though the current compiling construction is provided by test-fixture registry assembly. The KDoc should state the accepted mathematical world independently of the assembly implementation.

## Validation Strategy

Focused registry tests should cover:

- A direct provider/use self-edge.
- A shared passive prefix.
- A shared prefix introduced only by transitive resolver predecessor demand.
- A cross-variable two-branch cycle.
- An accepted linear variable dependency chain.
- Independent provider/use pairs with more than one valid topological order.
- Polymorphic guards that overlap on one concrete type.
- Polymorphic guards that are disjoint for every concrete type.
- Argument-distinct occurrences that are conservatively collapsed.
- Nested input-object and input-list variable uses.
- Provider null and error outcomes, which do not relax the static order.
- Fixture-lowered node branches and their synthetic bridge requirements.

Resolver tests should establish that every accepted variable-bearing example agrees with the intended `correctResolution`, each exact resolver-bearing OER cell has one application, and no completed branch is revisited. Variable-free generated worlds should continue to agree with Resolver03.

The arbitrary registry generator must construct branch-order-acyclic variable programs rather than generate unrestricted providers and rely on rejection. Its witness data should record provider-production edges, use branches, and at least one valid topological order so generated coverage can distinguish trivial disjoint cases from dependency chains.

Existing Resolver04 widening and demand-sealing tests should be classified rather than ported wholesale. Tests whose worlds violate branch stratification become negative registry tests. Tests that remain inside the restricted domain become behavior tests for the variable-aware Resolver03 extension.

## Documentation And Claims

Once implemented, update `ExecutorRegistry` KDoc, `model/AGENTS.md`, `semantics/AGENTS.md`, `handoff.md`, `evergreen.md`, `examples.md`, relevant claims and arguments, generator documentation, and the TLA+ boundary notes to describe the restricted variable domain consistently.

The resulting claim should be scoped explicitly: within the finite, fixed-shape, conservatively branch-stratified variable domain, every resolver-bearing OER occurrence is constructed by one field-resolver application after all applicable demand has been made concrete and aggregated.

Do not claim that provider/use overlap is impossible in Viaduct generally. It is intentionally excluded from this model so that execution variables can be added without abandoning Resolver03's depth-first construction.

## Work Sequence

1. Add the structural branch and edge extraction used only by registry validation.
2. Add focused positive and negative tests for the unified branch-order invariant.
3. Update the arbitrary generator to construct valid stratified variable worlds.
4. Implement the variable-aware Resolver03 extension without modifying Resolver04.
5. Compare variable-free behavior with Resolver03 and accepted variable behavior with `correctResolution`.
6. Reclassify Resolver04 tests into accepted behavior tests, rejected-world tests, or broader-domain evidence.
7. Retire Resolver04 widening machinery only after the restricted construction and its one-shot evidence are complete.
