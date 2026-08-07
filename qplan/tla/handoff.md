# TLA+ Adversarial Audit Handoff

## Purpose

This handoff records the adversarial review of the surviving Resolver01-03 TLA+ baseline so a future session can strengthen it without reconstructing the audit. Field-relative variables, `@parent`, checkers, lazy values, cyclic resolver demand, and other future features are outside the current proof scope.

The removed Resolver04 and provider-path proof experiments exposed important failures in contribution discovery, late equality, provider correspondence, and proof premises. Those historical findings now live in the root [Resolver04 retrospective](../evergreen.md#appendix-resolver04-retrospective); they are not active modules or repair phases in this baseline.

## Bottom Line

The TLAPS proofs appear valid over the finite atoms and assumptions they state, but the composed theorems are not yet refinement proofs of the Kotlin resolver constructors or `correctResolution`. Several assumptions directly supply relations the Kotlin algorithm is supposed to derive, important structural distinctions may collapse into the same opaque atom, and the returned result is not constructed by the modeled fold.

- Proof validity within the stated atomic model: high.
- Refinement to the Kotlin model: low.
- Adversarial TLC coverage: weak.

Passing TLAPS and TLC establishes consistency of the chosen Resolver01-03 construction calculus under strong premises. It must not be described as proving the Kotlin implementation correct or proving the full one-shot claim.

## Blocking Findings

### 1. The Returned Result Is Assumed Rather Than Constructed

[`OccurrenceFolds.tla`](OccurrenceFolds.tla) stipulates `WorkCell` and defines `PresentCells` as its image. Its state machine removes work but does not construct cells through analogues of Kotlin `resolveKey`, recursive `resolveValue`, or `union`. Consequently, terminal fold completion does not imply that the returned OER contains the values produced by those folds.

Countermodel: retain an ideal observation saying a user name is `"Raymie"`, but put a different value in the stipulated final cell. The composed TLA+ premises and `CorrectResolution` can still hold because returned cells and ideal observations are independent, while Kotlin resolver comparison would fail.

### 2. Resolver Observations Are Caller-Chosen And May Be Incomplete

[`ResultTree.tla`](ResultTree.tla) requires observations for active resolvers but does not derive the complete scalar, null, shape, list-position, and passive-descendant observations traversed by Kotlin `conformsToResolvers`. [`ReturnedResult.tla`](ReturnedResult.tla) then defines the actual observation from the ideal projection instead of extracting it from the returned cell.

Countermodel: a resolver returns `{name: "wrong"}`, while `Observations` contains only an agreeing object-shape atom. TLA+ passes and Kotlin comparison fails.

### 3. Operation And Resolver Demand Are Not Extracted From Fragments

`OperationDemand`, `ResolverDemand`, and related maps are typed functions, not derivations from structural fragments, exact keys, and the registry. [`TreeConstruction.tla`](TreeConstruction.tla) therefore proves closure over whichever demand the caller supplies, including demand that omits required Kotlin selections.

Countermodels: map a nonempty Kotlin operation `{f}` to empty `OperationDemand`, or give an active resolver whose Kotlin object fragment requires `x` an empty `DirectDemandByKey`. TLA+ can pass while Kotlin selection conformance or resolver-demand closure fails.

### 4. Materialization And Resolver Application Are Detached From Returned Cells

[`Materialization.tla`](Materialization.tla) treats `CellValue` as a typed map without requiring it to equal the corresponding returned OER cell. [`ResolverApplication.tla`](ResolverApplication.tla) uses an arbitrary total `ResolverFunction` and assumes the raw observation agrees with its prefix result. Missing-key failures, resolver partiality, object/list union failures, and the structural result of materialization are not represented.

Countermodel: let a returned dependency cell contain `B`, let `CellValue` claim `A`, and use an identity resolver whose modeled projected result is `A`. Prefix/final agreement passes in TLA+, while Kotlin materializes `B` and resolver conformance fails.

### 5. Atomic Extraction Can Collapse Unequal Kotlin Structures

The atomic carriers do not formally preserve or distinguish containing-object paths, concrete guards, exact fields and arguments, list positions, or OER occurrences. Comments saying that token identity includes these components are intended extraction obligations, not injectivity constraints.

Countermodels: map `f(a: 1)` and `f(a: 2)` to one key token, map two guarded branches to one requirement token, or map two list-element OER occurrences to one occurrence. A single TLA+ contribution or work item can then satisfy obligations that Kotlin keeps distinct.

### 6. Semantic Classifications Are Arbitrary

`ResolverCells`, `ErrorCells`, `TypenameCells`, behavioral boundaries, and similar classifications are constrained mainly by carrier membership. They are not derived from registry membership, exact argument errors, the `__typename` coordinate, or `world.behavioral`.

Countermodels: omit an incorrectly resolved registered cell from `ResolverCells`, omit an incorrect typename cell from `TypenameCells`, or classify a bad passive descendant as behavioral. The corresponding Kotlin correctness conjunct fails while the TLA+ obligation ignores it.

### 7. One Application Per Occurrence Is Not In The Composed Result

The local one-application predicate exists in the core construction work, but the final Resolver03 application theorem does not compose the occurrence world's one-position result into its conclusion. The intended mathematical one-shot statement should be an explicit conjunct of the final occurrence-indexed theorem.

This is a theorem-composition gap even before considering refinement to JVM invocation counts.

## Additional Model Weaknesses

### Demand-Independent Output Facts

[`Projection.tla`](Projection.tla) retains an observation only when its coverage intersects demand. Kotlin `snipToDemand` leaves simple, null, and error outputs unchanged and preserves object/list shape under empty nested demand. The TLA+ model currently needs to invent a demand token to retain a scalar or shape observation that is demand-independent in Kotlin.

### Producer Attribution

`CellProducer` is typed but not tied to the resolver represented by the cell's exact key or construction work item. A model can attribute an observation from resolver `f` to resolver `g` and use `g`'s contribution to prove coverage for `f`.

## Recommended Repair Order

### Phase 1: Turn Every Countermodel Into A Failing TLC Fixture

Before strengthening proofs, create small finite models that intentionally admit each countermodel above and assert the intended invariant. A useful model should fail before the corresponding specification link is added and pass afterward. Keep world validity as an `INVARIANT`, not only a `CONSTRAINT`, so malformed or vacuous fixtures fail visibly.

At minimum, add TLC cases for:

- a wrong returned cell paired with an ideal observation;
- an omitted scalar or passive-descendant observation;
- nonempty structural fragment mapped to empty demand;
- returned `CellValue` disagreement;
- two exact argument tuples and two list occurrences forced to remain distinct;
- wrong resolver, typename, error, and behavioral classification;
- wrong `CellProducer`;
- local one-application set to false.

### Phase 2: Define A Structural Extraction Layer

Introduce finite structural carriers, or explicit refinement maps with proved preservation and injectivity, for schemas, concrete object occurrences, exact keys with argument tuples, selections and type guards, fragments, values, object cells, and list positions. Derive classifications and demand maps from those structures instead of accepting arbitrary sets and functions.

Required properties include:

- unequal exact keys remain unequal after extraction;
- unequal OER and list-element occurrences remain unequal;
- path and concrete-type guards are preserved;
- resolver coordinates come exactly from registry membership;
- typename, argument-error, and behavioral classifications agree with Kotlin definitions;
- operation and resolver demand are extracted from the corresponding fragments.

Opaque tokens can remain as proof abbreviations only after these preservation obligations exist.

### Phase 3: Model Construction Of The Returned OER

Replace stipulated `WorkCell` and `PresentCells` with state produced by the occurrence folds. Model the mathematical equivalents of `resolveKey`, recursive object/list `resolveValue`, materialization, and partial `union`. Make undefined materialization, resolver application, projection, or union exclude the transition or produce an explicit domain violation that TLC can detect.

The terminal theorem should derive:

- present cells equal the cells actually constructed by the fold;
- each cell value is the value produced by its resolver or passive source;
- recursive object and list results are built from their child occurrence folds;
- terminal built keys and returned OER keys agree as a theorem, not an alignment assumption.

### Phase 4: Derive Complete Observations And Projection

Define observations by structural traversal of actual raw resolver outputs and actual returned values. Separate demand-dependent passive observations from demand-independent scalar, null, error, object-shape, list-shape, and list-position facts. Derive `Project` from the same structural relation represented by Kotlin `snipToDemand`.

Then prove:

- the observation set is complete for Kotlin resolver comparison;
- actual observations come from actual returned cells;
- ideal observations come from the raw resolver result on the materialized final input;
- producer attribution follows from the exact resolver-bearing cell;
- projection coverage is derived from structurally computed demand.

### Phase 5: State Final Resolver01-03 Theorems Without Result-Shaped Premises

Compose occurrence construction, structural extraction, demand derivation, and projection into separate explicit theorems for termination, demand closure, input availability, producer completeness, returned-result correctness, and exactly one mathematical application position per exact resolver key and concrete OER occurrence.

Audit each premise by asking whether a deliberately broken resolver algorithm could still choose values satisfying it. Premises may constrain valid worlds and executor functions, but they should not directly choose the algorithm's supplied demand, returned cells, actual observations, or application count.

Variable/provider refinement should begin as a separate future scope with its own structural oracle and the historical Resolver04 counterexamples as required negative fixtures; it should not be added back into this baseline piecemeal.

## Suggested Immediate Session Plan

1. Preserve the passing proof baseline and its explicit claim limits.
2. Build a new adversarial refinement MC module containing the wrong-cell and missing-observation countermodels.
3. Add structural exact-key and occurrence identities, including an explicit unequal-arguments model.
4. Connect constructed fold state to returned cells and make the first countermodels pass only because the new relation rules them out.
5. Add complete structural observations and demand-independent projection facts.
6. Add fragment-to-demand extraction and classification derivation.
7. Compose one-application explicitly into the final Resolver03 theorem.

## Validation Guidance

Run TLAPS serially because its cache is shared. Run TLC models concurrently only with distinct metadata directories. Record explored state counts and treat very small counts as a prompt to inspect over-constraint and symmetry, not as evidence of strength.

For every new world predicate:

- include a negative fixture that violates it;
- check it as an invariant so invalid fixtures fail;
- avoid relying solely on a state constraint;
- include at least one mutation that breaks the modeled algorithm while preserving carrier validity;
- confirm the relevant correctness invariant fails for that mutation.

The proof matrix in [`README.md`](README.md) is a regression suite for the internal calculus. Passing it is necessary but not sufficient validation of the refinement work described here.
