# Resolver TLA+ Proof Baseline

## Scope

These modules prove the finite, occurrence-scoped construction properties shared by `semantics.resolver01` through `semantics.resolver04`. One `ResolverCore` instance denotes one concrete OER object occurrence. Its keys are already specialized to that occurrence's runtime object type and retain exact argument tuples. Recursive objects and list elements are separate instances, so structurally equal values and repeated node IDs are not coalesced.

The proof is intentionally factored at the same boundaries as the Kotlin model:

- `ResolverCore.tla` defines least resolver-demand closure and the dependency-first fold.
- `ResolverCoreProof.tla` proves closure, input availability, one sequence position per applied resolver key, safety, termination, and completed-result correctness.
- `Resolver01.tla` proves the empty-object-fragment stage.
- `Resolver02.tla` proves exact direct object-fragment closure and lifts local closure over finite OER occurrences.
- `Resolver03.tla` proves guarded producer completeness when registry extension covers every exact guarded requirement token.
- `Resolver04.tla` proves finite-ranked provider recursion, provider binding agreement, variables-before-field ordering, and ambient-demand sealing.
- `DependencyOrder.tla` separately models the Kahn-style `dependencyOrder` worklist and proves the semantic contract consumed by `ResolverCore`: dependencies are complete before each step, no key is reapplied, and construction terminates.
- `ResultTree.tla` gives finite OER object, cell, list-position, and resolver-observation occurrences an extensional carrier and states the six conjuncts of `correctResolution`.
- `TreeConstruction.tla` indexes least exact-key demand closure by every reachable object occurrence and lifts completed Resolver01/02 folds to whole-tree fragment and resolver-demand conformance.
- `Projection.tla` proves the finite observation semantics of `snipToDemand`, including behavioral boundaries and overlap coherence.
- `ValueConstruction.tla` combines projection, typename generation, and empty variable maps with the tree construction to derive conditional Resolver01 and Resolver02 `CorrectResolution` theorems.
- `OccurrenceFolds.tla` takes the product of every reachable occurrence's construction order and proves that arbitrary fair interleaving terminates with all local folds complete.

Requirement tokens in Resolver03 and demand tokens in Resolver04 are opaque on purpose. Their identity includes the containing-object path, concrete-type guard, exact key, and argument tuple, so a proof cannot satisfy coverage by silently erasing a guard or merging unequal arguments.

## Machine-Checked Results

TLAPS proves the following under each module's explicit world assumptions:

1. `ClosedDemand` is the least set containing external demand and closed under exact direct resolver demand.
2. Every demanded key appears exactly once in a valid dependency-first construction order.
3. Every resolver input dependency precedes its application.
4. The finite fold terminates and applies each activated resolver key at one unique sequence position per OER occurrence.
5. Resolver03's supplied producer demand contains every guarded requirement represented by each activated nested occurrence's exact extended fragment.
6. Resolver04's unified site order gives provider recursion a finite decreasing rank, binds every fragment variable before its field, stores the provider value, and seals every modeled converging ambient contribution into the exact field's demand.
7. The least root-reachable finite result-tree carrier turns recursive local judgments into the modeled whole-tree `correctResolution` conjunction.
8. Completed occurrence-indexed Resolver01 and Resolver02 folds establish whole-tree fragment and resolver-demand conformance.
9. Projecting one raw resolver output retains exactly demanded passive observations, stops at behavioral boundaries, and agrees on every observation shared by two demands.
10. Under explicit observation alignment, Resolver01 and Resolver02 value construction plus their completed folds imply all six modeled `correctResolution` conjuncts.
11. Every finite reachable object-occurrence fold can run in one interleaved product machine whose terminal built keys equal that occurrence's least closed demand.

TLC exhaustively checks small models containing transitive sibling demand, an argument-preserving bridge-shaped dependency, guarded nested requirements, a variable-provider dependency, and converging operation/provider/sibling demand.

## Assumptions

The proof fixes a finite canonical world and assumes:

- exact concrete-key dependency discovery is sound for every reached argument tuple;
- the resolver/site dependency graph is acyclic and its construction order is a duplicate-free dependency-first enumeration;
- resolver fragments, values, selection trees, lists, and the reachable exact-key universe are finite;
- Resolver01's scoped model has empty exact object fragments;
- Resolver03 registry extension preserves every exact path, type guard, field, and argument tuple represented by a requirement token;
- Resolver04 variable names are unique, provider paths are valid and input-compatible, substitution is structural, and ambient contribution discovery is complete before the target field is constructed;
- resolver functions are deterministic, defined on every materialized input in scope, schema-conformant, and contain every demanded passive output before a behavioral boundary;
- finite object, cell, list-position, exact-demand, and resolver-observation atoms faithfully extract the corresponding structural Kotlin values and relations;
- each modeled result observation is aligned with the exact passive observation copied by `snipToDemand` from the same raw resolver output used by the correctness judgment;
- terminal product-fold built keys align with the exact cells present in the returned Kotlin OER;
- `noTransitiveDemand` is false, except for intentionally weaker mutation-control models outside this proof.

The modules exclude directives, `@parent`, checkers, lazy values, cyclic resolver demand, mutations and execution epochs, and cross-tree coalescing.

## Proof Boundary

This is a machine-checked proof of the resolver construction calculus and a finite extensional model of every `correctResolution` conjunct, but not yet a complete refinement proof from the structural Kotlin carriers. Result-tree recursion, occurrence-indexed demand closure, simultaneous product-fold completion, projection coherence, typename, and empty variable maps are now represented and proved. Structural extraction of schemas, selection forests, objects, lists, materialized resolver inputs, observed demand, and resolver-value comparison into those atoms remains explicit, as does alignment between terminal product-fold built keys and returned OER cells. The conditional `Resolver01FullCorrectness` and `Resolver02FullCorrectness` theorems therefore must not be quoted as unconditional proofs of the Kotlin functions.

The distinction matters most for one-shot language. TLAPS proves one mathematical resolver application rule per exact key and concrete OER occurrence. It does not assert JVM side-effect counts, scheduling, caching, batching, or runtime invocation behavior.

## Validation

From `scratchpad/qplanning`:

```sh
mise run tla:prove -- tla/ResolverCoreProof.tla
mise run tla:prove -- tla/DependencyOrder.tla
mise run tla:prove -- tla/Resolver01.tla
mise run tla:prove -- tla/Resolver02.tla
mise run tla:prove -- tla/Resolver03.tla
mise run tla:prove -- tla/Resolver04.tla
mise run tla:prove -- tla/ResultTreeProof.tla
mise run tla:prove -- tla/TreeConstructionProof.tla
mise run tla:prove -- tla/ProjectionProof.tla
mise run tla:prove -- tla/ValueConstructionProof.tla
mise run tla:prove -- tla/OccurrenceFoldsProof.tla

mise run tla:check -- tla/DependencyOrderMC.tla -config tla/DependencyOrderMC.cfg
mise run tla:check -- tla/Resolver02MC.tla -config tla/Resolver02MC.cfg
mise run tla:check -- tla/Resolver03MC.tla -config tla/Resolver03MC.cfg
mise run tla:check -- tla/Resolver04MC.tla -config tla/Resolver04MC.cfg
mise run tla:check -- tla/ResultTreeMC.tla -config tla/ResultTreeMC.cfg
mise run tla:check -- tla/TreeConstructionMC.tla -config tla/TreeConstructionMC.cfg
mise run tla:check -- tla/ProjectionMC.tla -config tla/ProjectionMC.cfg
mise run tla:check -- tla/ValueConstructionMC.tla -config tla/ValueConstructionMC.cfg
mise run tla:check -- tla/OccurrenceFoldsMC.tla -config tla/OccurrenceFoldsMC.cfg
```

The wrappers add the repository root, its `tla` child, and TLAPS's bundled standard modules to the search path, so sibling imports resolve when commands run from the repository root.
