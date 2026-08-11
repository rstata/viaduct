# Correct OER Specification Handoff

## Purpose

This is the volatile handoff for the query-planning model. It records the current semantic boundary, active implementation shape, and next work; completed milestones and detailed chronology belong in Git history. Read [Query Plan Research](./evergreen.md) for durable production evidence and lessons, [An Idealized Viaduct Query Execution Model](./viaduct-execution.md) for the source-world model, and [Resolution Algorithms By Example](./examples.md) for current demand-closure and projection examples.

[`notes.md`](./notes.md) is protected personal working material maintained elsewhere. It is useful background but is not authoritative for current code behavior.

Compiling Kotlin is the primary specification language. A scoped Resolver01-03 TLA+ construction-calculus baseline also exists in [`tla`](./tla); it proves its finite atomic model under explicit extraction and alignment assumptions, not a refinement of the Kotlin implementation. [`tla/README.md`](./tla/README.md) defines the proved results and boundary, while [`tla/handoff.md`](./tla/handoff.md) records the active refinement work.

## Goal

The long-term goal is a query-plan and executor that support one resolver application per resolver-bearing OER occurrence after all in-scope demand for that exact field has been aggregated. Distinct result-tree occurrences remain distinct even when node IDs, resolver coordinates, arguments, or values are equal; caching, batching, and request deduplication are separate execution layers.

The intended execution state is monotonic. Each OER value, field check, type check, or variable binding moves only from absent to one immediate or deferred promise, and each deferred promise completes once. A parent value may publish mutable child OERs before those children are complete, so descendants can gain promises without rebuilding ancestors. Resolver09 models readiness-driven scheduling over the value-presence state; a future concurrent executor should preserve the same publication discipline.

## Current Model

Each reasoning exercise fixes one `Assumptions`, one canonical `Schema`, and one canonical field-resolver registry. The model distinguishes open selection expressions from ground semantic values: `Value.Key` may contain open arguments, `Value.ObjectKey` has a concrete object field but may remain open, and `Value.GroundKey` is the exact key admitted to OERs, `Value.Object` values, paths, materialization, dependency ordering, and resolver application.

`EngineResult.Object` supports independent opt-in monotonic value, field-check, and type-check promises. `EngineResult.List` contains positional values; each object element carries its own type-check promise. Resolver01-03 allocate empty mutable root and child OERs, close local demand, order exact sibling dependencies, materialize inputs from completed value promises, and publish every exact value once. `ResolveValue.kt` creates passive result structure, retains each child OER occurrence requiring active work, and populates those targets deepest first without replacing parent promises or immutable list positions. `EngineResult.Object.materialize` is suspending and awaits present deferred value promises; Resolvers01-10 call it through `runBlocking` at the shared synchronous resolver-application boundary, where their completed-promise invariant prevents suspension.

The recursive resolvers share this constructor and differ only at output boundaries:

| Resolver | Supported user fragment domain | Output policy |
| --- | --- | --- |
| Resolver01 | Empty object fragments, plus generated `$node { $id }` loaders | Complete output |
| Resolver02 | Nonempty fragments, including `FromArgument` | Complete output with `successorBoundaryDemand()` |
| Resolver03 | Nonempty fragments, including `FromArgument` | Selective output with full `successorDemand()` |

Resolver01/02 require non-selective worlds, while Resolver03 requires a selective world. Their
queue and readiness counterparts enforce the corresponding mode through
`Assumptions.selectiveResolvers`; selection completion carries only boundary-dependent demand and
complete-output retention.

Resolver06-08 mirror Resolver01-03 through a single-threaded `DepthFirstReactor`. `SlotOrchestrator` tasks close local demand and enqueue dependency-ordered slots; `SlotResolver` tasks execute one slot, publish its passive result tree, and enqueue its fringe. A stable priority queue orders tasks by longest containing-OER path, resolver-before-orchestrator task kind, and insertion order, reproducing the recursive constructor's depth-first traversal without recursive scheduling. Resolver08 uses selective output with full `successorDemand()`.

Resolver09 covers Resolver08's feature domain and selective policy through its own single-threaded `resolver09.Reactor`. Every unresolved exact slot is represented by one stable `SlotResolver`, registered by its root-relative `List<PathComponent>` coordinate. Persistent OER-local orchestrators retain `MutableMap<SlotResolver, Set<SlotResolver>>`, refresh dependency coordinates through the shared `resolverDependencies` walk, and launch every candidate whose dependency objects are finished. Refreshing progressively discovers descendant and list-element instances after active ancestors publish their shape. Resolver tasks publish their value and register active fringe orchestrators before setting their own `isFinished` state. A greedy FIFO resolver queue and repeated orchestrator scans have no depth priority; quiescent unfinished work fails with dependency-cycle or missing-producer diagnostics.

Resolver10 extends the readiness model with runtime `FromObjectField` provider evaluation, pending symbolic selections, late grounding and convergence, and demand sealing before launch. It remains a synchronous worklist rather than the coroutine executor.

Resolver21-23 use the shared structured-coroutine constructor in `CoroutineResolve.kt`. Resolver21 covers Resolver01's empty-fragment domain with identity completion, Resolver22 covers Resolver02's nonempty fragments and `FromArgument` with complete non-selective output through `successorBoundaryDemand()`, and Resolver23 covers Resolver03's feature domain with selective output through `successorDemand()`. Each OER orchestration installs and registers all local deferred value promises before launching producers in the inherited single-threaded context. Resolver materialization suspends directly on those promises; active child promises are installed before their containing value publishes, and structured scopes keep the root open until every descendant completes. Runtime writer registration and exact reader coordinates detect resolver-read cycles without a reactor, readiness scan, dependency order, polling loop, or escaping job.

Resolver24 adds persistent coroutine orchestrators for open demand. Each OER orchestrator exclusively fetches stamped variables used in key arguments, interns the resulting exact key, and owns one producer per `GroundKey`. Exact resolver expansion declares all `FromObjectField` promises and launches definition-owned provider readers that traverse and await provider values through the same OER orchestrators. Conservative launch demand and complete-output retention preserve late key convergence and late demand into published child OERs without a readiness scan or pending-binding loop.

Resolver24i is the presentation version specialized to one case: selective object-fragment resolvers using both `FromArgument` and `FromObjectField` variables and returning selective output. Its single `semantics/resolver24i/Resolve.kt` colocates the wrapper, OER coroutine runtime, binding expansion, demand normalization, provider evaluation, materialization, and passive result construction; shared carrier, promise, cycle-checking, and registry-demand primitives remain external. Resolver values are retained completely inside the coroutine graph so late path readers can observe passive providers, then each OER's accepted exact demand projects the completed graph to the query's transitive resolver-input closure. Its focused deterministic and generated tests coactivate both variable sources.

Resolver25 is an experimental strict one-shot alternative based on Resolver23 rather than Resolver24. Its constructor and wrapper are colocated in `semantics/resolver25/Resolver.kt` because the phase planner and restrictions are specific to this experiment. Each OER has one value-bearing `sealedDemand: Deferred<Map<GroundKey, ObjectSelection>>` per canonical object field. A static two-phase field graph distinguishes resolver-instance preparation from launch: demand contributors prepare before the fields they may contribute to, incoming path-variable provider promises are installed before their consumers prepare, and resolver input promises are installed before their consumers launch. Preparation grounds and merges equal keys, prepares each resulting resolver instance exactly once, and completes `sealedDemand` with the immutable exact-key map. Launch eagerly installs every promise before starting one coroutine per map entry. The exact value promise acts as that resolver instance's completion latch and is awaited by provider reads and materialization. The OER-level `orchestrationReady` latch preserves Resolver23's install-before-parent-publication discipline without conflating promise visibility with preparation.

Resolver25 marks every component of a stamped `FromObjectField` provider path. OER-aware demand merging observes promises without awaiting them, defers non-null objects to the next marked component, and reports terminal or prematurely null/error bindings after active or passive values are published.

Resolver25 deliberately supports argument-free `FromObjectField` provider paths and direct sibling resolver-key uses. Provider paths may cross nested objects but not lists; each component propagates the stamped marker, including early termination at null or error. Resolver25 still rejects provider chains, nested variable uses, and cycles in the combined prepare/launch graph. Within that subset, focused tests cover direct and nested binding, `complete B -> prepare C -> prepare/launch A -> complete C`, late equality merging before one launch, and rejection of a phase cycle that the older branch-only graph accepts. Resolver23's variable-free and `FromArgument` deterministic and generated contracts also pass. Resolver25 does not use persistent OER demand acceptance, a projection envelope, or complete-output retention.

`DepthFirstReactor` and `resolver09.Reactor` share `ReactorInstrumentation`, not scheduler internals. Each reactor reports orchestrator and slot-resolver launch, start, and finish events through the instrument. The instrument supplies test observations, rejects duplicate lifecycle transitions, and validates at successful completion that every launched lifecycle finished and every sealed OER demand was published. Resolver09 additionally reports readiness evaluations and the exact dependency coordinates committed when each resolver launches, while retaining its slot registry and failure analysis locally. Resolver09 tests independently reconstruct the dependencies after completion by materializing each resolver occurrence's stamped object fragment, walking only that materialized value, and using the schema registry to identify resolver-owned fields and exact occurrence coordinates. Neither reactor retains `Assumptions` or `RuntimeSupport`; initialization and execution receive them as context parameters from the resolver entry point.

Resolver03's scoped one-shot construction claim is recorded in [`claims.md`](./claims.md) and [`arguments/resolver03-one-shot-construction.md`](./arguments/resolver03-one-shot-construction.md). Resolver03 remains a mathematical depth-first construction, while Resolver09 is a single-threaded readiness model rather than the future concurrent executor sketched in [`execution-handoff.md`](./execution-handoff.md).

Stamped variables occupy request-local binding promises in `Assumptions`; `getBinding` reads synchronously and `fetchBinding` suspends. `FromArgument` promises are declared at the defining resolver occurrence, completed immediately from its exact arguments, and grounded during layer-by-layer local demand closure. Resolver10 declares the same promise kind for compiled `FromObjectField` providers, completes them after evaluating published OER values, and grounds the resulting pending selections.

Fixture composition lowers node-valued source fields before semantic reasoning. A source `foo(args): W<T>` becomes `foo$bridge(args): W<T$Bridge>`, each bridge object contains passive `$id`, and the generated argumentless `T$Bridge.$node` resolver loads the node from `{ $id }`. Lists retain one bridge and one `$node` resolver-bearing OER occurrence per non-null element.

The plan-independent `correctResolution` judgment checks a completed Query OER against ground selections. It is check-insensitive and does not prove execution order, application count, provider binding, or concurrency. Resolver witnesses provide separate finite evidence about application identities and supplied demand.

## Scope

Inputs are post-validation; named fragment spreads are inlined and operation variables are substituted before semantic reasoning. Applied directives, `fromQueryField`, `@parent`, lazy executor values, checker execution, raw-versus-checked dependencies, mutations, subscriptions, and incremental execution are deferred. Every argument-bearing output field is assumed to have an explicit resolver. The canonical registry conservatively rejects every coordinate-level resolver-demand cycle, including some exact worlds that would execute acyclically.

Canonical node lowering requires every possible concrete type of a node-valued source field to have a raw node resolver and rejects mixed node-resolved and inline possible-type sets. Canonical field resolvers exist only at concrete object-field coordinates.

## Active Work

Resolver21-24 establish coroutine-driven resolution over deferred value and binding promises through the full Resolver10 value-resolution domain. Resolver24 preserves one exact producer under late key convergence, child registration before publication, exact reader coordinates, root cancellation, and structured quiescence while replacing Resolver10's readiness scans with suspended key activations and provider readers. Resolver25 tests whether stricter variable rules can recover one-shot construction through an explicit prepare/launch phase order; the next question is whether its restrictions and phase graph should become registry invariants rather than resolver-local validation. Arbitrary-dispatcher behavior remains outside this coroutine family. Cycle detection diagnoses violated dependency assumptions rather than general coroutine deadlock.

The previously observed `ResolverWitnessBoundExceededException` remains unexplained. Generated failures now report explicit seeds, `S:R:Q` coordinates, and full schema/registry/query inputs through the replay workflow in [`semantics/testing-contracts.md`](./semantics/testing-contracts.md); use that evidence to distinguish unintended growth from an overly broad generated resource envelope.

### Cleanup TODOs

* Move variable binding-state out of `model` project -- along with `fetchBindings` -- other than `Promise` don't want suspend fn's in there
* `ObjectSelectionForest.get` is an operator but throws on absence; reconsider whether that is idiomatic.
* Make `snip` explicit in resolution rather than implicit in the resolver wrapper.
* Move recursive input-error detection from the semantics layer to an input-like model operation.
* Decide whether `FieldArgument.name` and `argumentName` should both exist.

## Validation

Run `./gradlew check` for the Kotlin model and documentation labels. Resolver03, Resolver08, Resolver09, Resolver10, Resolver23, and Resolver24 have opt-in fixed-seed stress tasks named `<resolver>Stress`; `run-resolver-stress.sh` launches all six. Generated tests choose and report explicit seeds; a green run is finite evidence, not a timeless repository fact. The TLA+ toolchain and complete validation matrix are documented in [`tla/README.md`](./tla/README.md).
