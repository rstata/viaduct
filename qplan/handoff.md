# Correct OER Specification Handoff

## Purpose

This is the volatile handoff for the query-planning model. It records the current semantic boundary, active implementation shape, and next work; completed milestones and detailed chronology belong in Git history. Read [Query Plan Research](./evergreen.md) for durable production evidence and lessons, [An Idealized Viaduct Query Execution Model](./viaduct-execution.md) for the source-world model, and [Resolution Algorithms By Example](./examples.md) for current demand-closure and projection examples.

[`notes.md`](./notes.md) is protected personal working material maintained elsewhere. It is useful background but is not authoritative for current code behavior.

Compiling Kotlin is the primary specification language. A scoped Resolver01-03 TLA+ construction-calculus baseline also exists in [`tla`](./tla); it proves its finite atomic model under explicit extraction and alignment assumptions, not a refinement of the Kotlin implementation. [`tla/README.md`](./tla/README.md) defines the proved results and boundary, while [`tla/handoff.md`](./tla/handoff.md) records the active refinement work.

## Goal

The long-term goal is a query-plan and executor that support one resolver application per resolver-bearing OER occurrence after all in-scope demand for that exact cell has been aggregated. Distinct result-tree occurrences remain distinct even when node IDs, resolver coordinates, arguments, or values are equal; caching, batching, and request deduplication are separate execution layers.

The intended execution state is monotonic. An OER cell or variable binding moves only from absent to one validated value and is never replaced. Parent cells may publish mutable child OERs before those children are complete, so descendants can gain cells without rebuilding ancestors. Resolver09 models readiness-driven scheduling over this `unset -> set` state; a future concurrent executor should preserve the same publication discipline.

## Current Model

Each reasoning exercise fixes one `Assumptions`, one canonical `Schema`, and one canonical field-resolver registry. The model distinguishes open selection expressions from ground semantic values: `Value.Key` may contain open arguments, `Value.ObjectKey` has a concrete object field but may remain open, and `Value.GroundKey` is the exact key admitted to OERs, `Value.Object` values, paths, materialization, dependency ordering, and resolver application.

`EngineResult.Object` supports opt-in monotonic mutation. Resolver01-03 allocate empty mutable root and child OERs, close local demand, order exact sibling dependencies, materialize inputs from cells already written, and publish every exact cell once. `ResolveValue.kt` creates passive result structure, retains each child OER occurrence requiring active work, and populates those targets deepest first without replacing parent cells or immutable list positions.

The recursive resolvers share this constructor and differ only at output boundaries:

| Resolver | Supported user fragment domain | Output policy |
| --- | --- | --- |
| Resolver01 | Empty object fragments, plus generated `$node { $id }` loaders | Complete output |
| Resolver02 | Nonempty fragments, including `FromArgument` | Complete output with `successorBoundaryDemand()` |
| Resolver03 | Nonempty fragments, including `FromArgument` | Selective output with full `successorDemand()` |

Resolver06-08 mirror Resolver01-03 through a single-threaded `DepthFirstReactor`. `SlotOrchestrator` tasks close local demand and enqueue dependency-ordered slots; `SlotResolver` tasks execute one slot, publish its passive result tree, and enqueue its fringe. A stable priority queue orders tasks by longest containing-OER path, resolver-before-orchestrator task kind, and insertion order, reproducing the recursive constructor's depth-first traversal without recursive scheduling. Resolver08 uses selective output with full `successorDemand()`.

Resolver09 covers Resolver08's feature domain and selective policy through its own single-threaded `resolver09.Reactor`. Every unresolved exact slot is represented by one stable `SlotResolver`, registered by its root-relative `List<PathComponent>` coordinate. Persistent OER-local orchestrators retain `MutableMap<SlotResolver, Set<SlotResolver>>`, refresh dependency coordinates through the shared `resolverDependencies` walk, and launch every candidate whose dependency objects are finished. Refreshing progressively discovers descendant and list-element instances after active ancestors publish their shape. Resolver tasks publish their cell and register active fringe orchestrators before setting their own `isFinished` state. A greedy FIFO resolver queue and repeated orchestrator scans have no depth priority; quiescent unfinished work fails with dependency-cycle or missing-producer diagnostics.

`DepthFirstReactor` and `resolver09.Reactor` share `ReactorInstrumentation`, not scheduler internals. Each reactor reports orchestrator and slot-resolver launch, start, and finish events through the instrument. The instrument supplies test observations, rejects duplicate lifecycle transitions, and validates at successful completion that every launched lifecycle finished and every sealed OER demand was published. Resolver09 additionally reports readiness evaluations and the exact dependency coordinates committed when each resolver launches, while retaining its slot registry and failure analysis locally. Resolver09 tests independently reconstruct the dependencies after completion by materializing each resolver occurrence's stamped object fragment, walking only that materialized value, and using the schema registry to identify resolver-owned fields and exact occurrence coordinates. Neither reactor retains `Assumptions` or `SelectionCompleter`; initialization and execution receive them as context parameters from the resolver entry point.

Resolver03's scoped one-shot construction claim is recorded in [`claims.md`](./claims.md) and [`arguments/resolver03-one-shot-construction.md`](./arguments/resolver03-one-shot-construction.md). Resolver03 remains a mathematical depth-first construction, while Resolver09 is a single-threaded readiness model rather than the future concurrent executor sketched in [`execution-handoff.md`](./execution-handoff.md).

`FromArgument` variables are stamped at the defining resolver occurrence, bound from its exact arguments in request-local monotonic state, and grounded during layer-by-layer local demand closure. `FromObjectField` declaration parsing, provider-path compilation, containment, generation, and branch-order validation exist only in pre-reasoning infrastructure; no current semantic resolver evaluates those providers.

Fixture composition lowers node-valued source fields before semantic reasoning. A source `foo(args): W<T>` becomes `foo$bridge(args): W<T$Bridge>`, each bridge object contains passive `$id`, and the generated argumentless `T$Bridge.$node` resolver loads the node from `{ $id }`. Lists retain one bridge and one `$node` resolver-bearing OER occurrence per non-null element.

The plan-independent `correctResolution` judgment checks a completed Query OER against ground selections. It is check-insensitive and does not prove execution order, application count, provider binding, or concurrency. Resolver witnesses provide separate finite evidence about application identities and supplied demand.

## Scope

Inputs are post-validation; named fragment spreads are inlined and operation variables are substituted before semantic reasoning. Applied directives, `fromQueryField`, runtime `FromObjectField`, `@parent`, lazy executor values, checkers, raw-versus-checked dependencies, mutations, subscriptions, and incremental execution are deferred. Every argument-bearing output field is assumed to have an explicit resolver. The canonical registry conservatively rejects every coordinate-level resolver-demand cycle, including some exact worlds that would execute acyclically.

Canonical node lowering requires every possible concrete type of a node-valued source field to have a raw node resolver and rejects mixed node-resolved and inline possible-type sets. Canonical field resolvers exist only at concrete object-field coordinates.

## Active Work

Resolver09 establishes the demand-availability worklist baseline for `FromArgument`: exact occurrence identity, sealed OER-local demand, readiness-driven dispatch, progressive runtime-shape discovery, and quiescent illegal-state detection. The next semantic resolver milestone is Resolver10 with runtime `FromObjectField` provider evaluation; [`pathvars-handoff.md`](./pathvars-handoff.md) records that deferred problem. A future concurrent executor still needs producer-demand sealing across symbolic provider discovery, fair scheduling, and schedule-independent results; [`execution-handoff.md`](./execution-handoff.md) remains the broader proposal.

The previously observed `ResolverWitnessBoundExceededException` remains unexplained. Generated failures now report explicit seeds, `S:R:Q` coordinates, and full schema/registry/query inputs through the replay workflow in [`semantics/testing-contracts.md`](./semantics/testing-contracts.md); use that evidence to distinguish unintended growth from an overly broad generated resource envelope.

### Small Things

* `ObjectSelectionForest.get` is an operator but throws on absence; reconsider whether that is idiomatic.
* Make `snip` explicit in resolution rather than implicit in the resolver wrapper.
* Move recursive input-error detection from the semantics layer to an input-like model operation.
* Decide whether `FieldArgument.name` and `argumentName` should both exist.
* Investigate the stochastic `object-fragment-from-argument` generated-profile activation guard: `./gradlew check --rerun-tasks` failed because Resolver03's 150-case batch activated no abstract implementation defaults, and the failure reproduced with `./gradlew :semantics:resolverPropertyReplay -PresolverPropertyClass=semantics.resolver03.ResolverGeneratedTest -PresolverPropertyProfile=object-fragment-from-argument -PresolverPropertySeed=-3493719699711548687 -PresolverPropertyCase=all`; determine whether the profile should force this feature structurally, use a deterministic corpus, or stop requiring unrelated implementation-default coverage in the FromArgument profile.

## Validation

Run `./gradlew check` for the Kotlin model and documentation labels. Resolver09's opt-in fixed-seed stress property is `./gradlew :semantics:resolver09Stress -Presolver09StressSeed=<long>`. Generated tests choose and report explicit seeds; a green run is finite evidence, not a timeless repository fact. The TLA+ toolchain and complete validation matrix are documented in [`tla/README.md`](./tla/README.md).
