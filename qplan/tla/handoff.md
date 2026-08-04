# TLA+ Adversarial Audit Handoff

## Purpose

This handoff records the adversarial review of the current TLA+ baseline so a future session can strengthen it without reconstructing the audit. It concerns only the project's present Resolver01 through Resolver04 scope; `@parent`, checkers, lazy values, cyclic resolver demand, and other future features are not part of these findings.

## Bottom Line

The TLAPS proofs appear valid over the finite atoms and assumptions they currently state, but the composed theorems are not yet refinement proofs of the Kotlin resolver constructors or `correctResolution`. Several assumptions directly supply the result that the Kotlin algorithm is supposed to derive, important structural distinctions may collapse into the same opaque atom, and the returned result is not constructed by the modeled fold. The appropriate confidence assessment is:

- Proof validity within the stated atomic model: high.
- Refinement to the Kotlin model: low.
- Adversarial TLC coverage: weak.

Passing TLAPS and TLC currently establishes consistency of the chosen construction calculus under strong premises. It must not be described as proving Resolver04 correct, proving the Kotlin implementation correct, or proving the full one-shot claim.

## Kotlin Counterexamples The TLA+ Baseline Missed

Three deterministic Resolver04 counterexamples now live in [`ResolverDemandSealingTest`](../semantics/src/test/kotlin/semantics/resolver04/ResolverDemandSealingTest.kt) and [`ResolverWideningTest`](../semantics/src/test/kotlin/semantics/resolver04/ResolverWideningTest.kt). All are within current scope and currently fail with `MissingFieldException`.

1. A variable provider resolves `source { narrow }` before the defining resolver's variable-bearing demand `source { broad computed(value: $later) }` is instantiated. Resolver04 seals the shared `source` occurrence too early, so later materialization cannot read `broad`.
2. A provider reads `source(k: 1) { narrow }`, producing `$k = 2`, while the defining resolver requires `source(k: $k) { broad }`. `matchingAmbientDemand` retargets the symbolic `broad` demand onto the distinct `source(k: 1)` occurrence, so projection asks a permitted partial output for a field it does not contain.
3. A provider resolves a nested `child { field2(arg: "literal") }` occurrence while binding a variable, after which the defining resolver requires `child { field2(arg: $value) }`. Resolver04 does not widen the already resolved nested object to include the newly instantiated argument tuple.

The TLA+ theorem does not detect these defects because ambient contribution discovery, exact coverage, argument identity, and supplied demand are assumed rather than derived from Resolver04's filtering and retargeting operations. These tests should become explicit TLC countermodels for any replacement refinement layer.

## Blocking Findings

### 1. The Returned Result Is Assumed Rather Than Constructed

[`OccurrenceFolds.tla`](OccurrenceFolds.tla) stipulates `WorkCell` and defines `PresentCells` as its image. Its state machine removes work but does not construct cells through analogues of Kotlin `resolveKey`, recursive `resolveValue`, or `union`. Consequently, terminal fold completion does not imply that the returned OER contains the values produced by those folds.

Countermodel: retain an ideal observation saying a user name is `"Raymie"`, but put a different value in the stipulated final cell. The composed TLA+ premises and `CorrectResolution` can still hold because returned cells and ideal observations are independent, while Kotlin resolver comparison would fail.

### 2. Resolver Observations Are Caller-Chosen And May Be Incomplete

[`ResultTree.tla`](ResultTree.tla) requires observations for active resolvers but does not derive the complete scalar, null, shape, list-position, and passive-descendant observations traversed by Kotlin `conformsToResolvers`. [`ReturnedResult.tla`](ReturnedResult.tla) then defines the actual observation from the ideal projection instead of extracting it from the returned cell.

Countermodel: a resolver returns `{name: "wrong"}`, while `Observations` contains only an agreeing object-shape atom. TLA+ passes and Kotlin comparison fails.

### 3. Operation And Resolver Demand Are Not Extracted From Fragments

`OperationDemand`, `ResolverDemand`, and related maps are typed functions, not derivations from structural fragments, exact keys, and the registry. [`TreeConstruction.tla`](TreeConstruction.tla) therefore proves closure over whichever demand the caller supplies, including demand that omits required Kotlin selections.

Countermodels: map a nonempty Kotlin operation `{f}` to empty `OperationDemand`, or give an active resolver whose Kotlin object fragment requires `x` an empty `DirectDemandByKey`. TLA+ can pass while Kotlin `conformsToFragment` or `isClosedUnderResolverDemand` fails.

### 4. Resolver04 Projection Coverage Assumes The Desired Demand

[`Resolver04Projection.tla`](Resolver04Projection.tla) assumes that each active cell receives the completed direct, guarded, and ambient union and that every result observation already has a covering requirement in one of those sources. It does not derive those facts from Kotlin's variable filtering, `matchingAmbientDemand`, exact argument instantiation, or provider-resolution sequence.

Countermodel: omit a needed ambient selection in the Kotlin-style algorithm, but assign its token directly to `ContributionDemand` and the ideal `SuppliedDemand`. Every projection premise still holds. Deleting or breaking Kotlin ambient-demand logic is therefore invisible to this theorem.

### 5. Required Fragment Variables Need Not Have Bindings

Application correctness quantifies over the supplied `VariableBindings`; it does not require every variable in every activated resolver fragment to have exactly one owned binding. `ConstructedStoredVariableNames` is derived from that potentially incomplete binding set, making variable conformance vacuous when a binding is absent.

Countermodel: set `VariablesInFragment[f] = {v}` and order `<<v, f>>`, but set `VariableBindings = {}`. TLA+ variable conformance passes vacuously, while Kotlin variable substitution performs a required lookup for `v` and is outside its domain.

### 6. Provider Reads Are Detached From Provider Coordinates And Paths

[`ProviderReads.tla`](ProviderReads.tla) accepts an arbitrary `Values -> Values` provider-read function and constrains its result at the supplied root to equal [`ProviderPathEvaluation.tla`](ProviderPathEvaluation.tla). This proves agreement with a supplied conforming trace, but the binding's registered variable coordinate, owner site, provider selection, root key, path tail, dependency bindings, and trace are not structurally derived from one another. `PathTrace` is itself supplied under `TraceConforms`, so the model may choose a different valid path and matching read than the Kotlin registry specifies.

Countermodel: choose a supplied trace from provider root `"Query"` to `"Raymie"` and make both the provider-read function and stored binding return `"Raymie"`, while the omitted Kotlin-to-TLA extraction would have registered a different provider path. The current composed premises can hold because they prove the chosen trace internally consistent, not that it is the trace induced by Kotlin `readVariable`.

### 7. Materialization And Resolver Application Are Detached From Returned Cells

[`Materialization.tla`](Materialization.tla) treats `CellValue` as a typed map without requiring it to equal the corresponding returned OER cell. [`ResolverApplication.tla`](ResolverApplication.tla) uses an arbitrary total `ResolverFunction` and assumes the raw observation agrees with its prefix result. Missing-key failures, resolver partiality, object/list union failures, and the structural result of materialization are not represented.

Countermodel: let a returned dependency cell contain `B`, let `CellValue` claim `A`, and use an identity resolver whose modeled projected result is `A`. Prefix/final agreement passes in TLA+, while Kotlin materializes `B` and resolver conformance fails.

### 8. Atomic Extraction Can Collapse Unequal Kotlin Structures

The atomic carriers do not formally preserve or distinguish containing-object paths, concrete guards, exact fields and arguments, provider paths, list positions, or OER occurrences. Comments saying that token identity includes these components are not injectivity constraints.

Countermodels: map `f(a: 1)` and `f(a: 2)` to one key token, map two guarded branches to one requirement token, or map two list-element OER occurrences to one occurrence. A single TLA+ contribution or work item can then satisfy obligations that Kotlin keeps distinct.

### 9. Semantic Classifications Are Arbitrary

`ResolverCells`, `ErrorCells`, `TypenameCells`, behavioral boundaries, and similar classifications are constrained mainly by carrier membership. They are not derived from registry membership, exact argument errors, the `__typename` coordinate, or `world.behavioral`.

Countermodels: omit an incorrectly resolved registered cell from `ResolverCells`, omit an incorrect typename cell from `TypenameCells`, or classify a bad passive descendant as behavioral. The corresponding Kotlin correctness conjunct fails while the TLA+ obligation ignores it.

### 10. One Application Per Occurrence Is Not In The Composed Result

The local one-application predicate exists in the core construction work, but the final Resolver04 application theorem neither includes the occurrence world nor concludes one application per exact resolver-bearing OER occurrence. Setting the application model's local one-application constant to `FALSE` does not invalidate the final composed correctness property.

This is a theorem-composition gap even before considering refinement to JVM invocation counts. The intended mathematical one-shot statement should be an explicit conjunct of the final occurrence-indexed theorem.

## Additional Model Weaknesses

### Demand-Independent Output Facts

[`Projection.tla`](Projection.tla) retains an observation only when its coverage intersects demand. Kotlin `snipToDemand` leaves simple, null, and error outputs unchanged and preserves object/list shape under empty nested demand. The TLA+ model currently needs to invent a demand token to retain a scalar or shape observation that is demand-independent in Kotlin.

### Producer Attribution

`CellProducer` is typed but not tied to the resolver represented by the cell's exact key or construction work item. A model can attribute an observation from resolver `f` to resolver `g` and use `g`'s contribution to prove coverage for `f`.

### Site Order Versus Work Order

The dependency-first `SiteOrder` and the provider-root-before-owner `WorkOrder` are independently assumed. `BindingSite`, `ProviderRootWork`, the provider root key, and the provider path are not connected. A completely unrelated earlier work item can satisfy the provider ordering premise.

### Resolver04 TLC Does Not Exercise The Resolver04 Module

[`Resolver04MC.tla`](Resolver04MC.tla) extends `Resolver03MC` and duplicates selected Resolver04 predicates while running the inherited core `Spec`. Changes to [`Resolver04.tla`](Resolver04.tla) can therefore be invisible. Its configuration also uses `CONSTRAINT World`, which can prune every invalid state and make a run vacuous instead of surfacing a broken fixture as an invariant failure.

### Resolver04 Application TLC Is Too Narrow

[`Resolver04ApplicationMC.tla`](Resolver04ApplicationMC.tla) fixes one variable, one binding, one active resolver, no activated nested occurrence, an identity provider read, and ambient demand already present in `SuppliedDemand`. It does not challenge missing bindings, multiple or recursive providers, distinct argument tuples, guarded paths, nested paths, null/error/list reads, wrong producer attribution, or either known Kotlin counterexample.

## Recommended Repair Order

### Phase 1: Turn Every Countermodel Into A Failing TLC Fixture

Before strengthening proofs, create small finite models that intentionally admit each countermodel above and assert the intended invariant. A useful model should fail before the corresponding specification link is added and pass afterward. Keep world validity as an `INVARIANT`, not only a `CONSTRAINT`, so malformed or vacuous fixtures fail visibly.

At minimum, add TLC cases for:

- a wrong returned cell paired with an ideal observation;
- an omitted scalar or passive-descendant observation;
- nonempty structural fragment mapped to empty demand;
- a required variable with no binding;
- an unrelated constant provider read;
- returned `CellValue` disagreement;
- two exact argument tuples and two list occurrences forced to remain distinct;
- wrong resolver, typename, error, and behavioral classification;
- wrong `CellProducer`;
- local one-application set to false;
- all three Resolver04 Kotlin counterexamples.

### Phase 2: Define A Structural Extraction Layer

Introduce finite structural carriers, or explicit refinement maps with proved preservation and injectivity, for schemas, concrete object occurrences, exact keys with argument tuples, selections and type guards, fragments, values, object cells, list positions, variable coordinates, and provider paths. Derive classifications and demand maps from those structures instead of accepting arbitrary sets and functions.

Required properties include:

- unequal exact keys remain unequal after extraction;
- unequal OER and list-element occurrences remain unequal;
- path and concrete-type guards are preserved;
- resolver sites come exactly from registry membership;
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

### Phase 5: Integrate Variables And Provider Paths

Require a total, unique owned binding for every variable appearing in each activated resolver fragment. Connect each binding to its `VariableCoordinate`, owner field, structural provider selection, provider-root work item, exact path trace, dependency bindings, evaluated input value, and stored OER binding.

Derive rather than assume:

- provider dependencies precede the binding site;
- provider-root work precedes the defining field's work;
- path evaluation reads the actual provider-root cell;
- null and error absorption and terminal list conversion agree with structural values;
- substitution instantiates every variable recursively in arguments;
- exact symbolic keys are matched only after binding and never retargeted onto unequal argument tuples.

This phase must reproduce all three known Resolver04 failures in TLC before it should be considered aligned with the Kotlin algorithm.

### Phase 6: State The Final Theorems Without Result-Shaped Premises

Compose the occurrence construction, structural extraction, demand derivation, provider evaluation, and projection results into separate explicit theorems for:

- termination;
- demand closure;
- input availability;
- producer completeness;
- variable binding totality and conformance;
- returned-result correctness;
- exactly one mathematical application position per exact resolver key and concrete OER occurrence.

Audit each premise by asking whether a deliberately broken resolver algorithm could still choose values satisfying it. Premises may constrain valid worlds and executor functions, but they should not directly choose the algorithm's supplied demand, returned cells, actual observations, provider reads, or application count.

## Suggested Immediate Session Plan

1. Preserve the currently passing proof baseline as a reference point and tighten claims in documentation before changing modules.
2. Build a new adversarial refinement MC module containing the wrong-cell and missing-observation countermodels.
3. Add structural exact-key and occurrence identities, including an explicit unequal-arguments model.
4. Connect constructed fold state to returned cells and make the first countermodels pass only because the new relation rules them out.
5. Add complete structural observations and demand-independent projection facts.
6. Add fragment-to-demand extraction and classification derivation.
7. Rework provider bindings and paths, then encode all three Resolver04 regression tests as finite models.
8. Only after the TLC suite is adversarially useful, repair and extend the TLAPS proof composition.

## Validation Guidance

Run TLAPS serially because its cache is shared. Run TLC models concurrently only with distinct metadata directories. Record explored state counts and treat very small counts as a prompt to inspect over-constraint and symmetry, not as evidence of strength.

For every new world predicate:

- include a negative fixture that violates it;
- check it as an invariant so invalid fixtures fail;
- avoid relying solely on a state constraint;
- include at least one mutation that breaks the modeled algorithm while preserving carrier validity;
- confirm the relevant correctness invariant fails for that mutation.

The existing proof matrix in [`README.md`](README.md) is useful as a regression suite for the internal calculus, but passing it is not sufficient validation of the refinement work described here.

## Documentation Corrections To Make With The Proof Work

[`README.md`](README.md) currently says opaque token identity includes path, guard, key, and argument information, but this is not formalized. Reword that statement as an intended extraction obligation until injectivity is proved. Claims that provider traversal behavior, final variable conformance, composed `correctResolution`, or one application per occurrence are proved should identify the assumptions that currently supply the structural correspondence and should not imply a Kotlin refinement.

The root [`../handoff.md`](../handoff.md) also contains a stale statement that Resolver04 theorem work remains outstanding despite describing the existing theorem earlier. Reconcile that wording when the proof status is next updated.
