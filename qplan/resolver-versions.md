# Maintained Resolver Versions

## Purpose

Resolver26 is the productionization target, but it is intentionally not the only maintained implementation. The earlier versions form three matched proof progressions that separate semantic capability from execution structure, while Resolver25 preserves an alternate answer to the general one-shot producer-completeness problem.

## The Three Proof Progressions

| Semantic stage | Recursive construction | Explicit depth-first tasks | Structured coroutines | Capability |
| --- | --- | --- | --- | --- |
| Base | Resolver01 | Resolver06 | Resolver21 | Empty user object fragments and complete resolver output |
| Object fragments | Resolver02 | Resolver07 | Resolver22 | Nonempty fragments and `FromArgument`, still with complete output |
| Selective resolution | Resolver03 | Resolver08 | Resolver23 | The same fragment domain with selective resolver output and full successor demand |

Each column holds semantic capability roughly constant while changing the execution structure. Each row holds the semantic stage roughly constant while changing from recursive construction to an explicit task queue to structured suspension. This makes the nine versions useful as a refinement grid rather than as nine competing production implementations.

### Resolver01

Resolver01 is the smallest recursive construction. It supports empty user object fragments, generated node-loader bridges, and complete resolver output. Maintain it as the base case for result-tree construction, exact-key publication, and proofs that do not yet need resolver-input closure.

### Resolver02

Resolver02 adds nonempty object fragments and variables bound from resolver arguments while retaining complete resolver output. Maintain it to isolate object-fragment demand closure and argument grounding from selective-output reasoning.

### Resolver03

Resolver03 adds selective output and full successor-demand closure. It is the principal compact semantic reference, the subject of the current TLA+ and written correctness arguments, and the first implementation to consult when a Resolver25 or Resolver26 failure may be independent of concurrency and object-path variables.

### Resolver06

Resolver06 expresses Resolver01's domain through explicit orchestrator and resolver tasks scheduled by `DepthFirstReactor`. Maintain it as the base refinement from recursive calls to explicit work items and queue ordering.

### Resolver07

Resolver07 is the task-based counterpart of Resolver02. Maintain it to separate object-fragment and `FromArgument` semantics from the mechanics of recursive execution.

### Resolver08

Resolver08 is the task-based counterpart of Resolver03. Maintain it as the simplest operational model with explicit task identity, publication order, and selective output. When Resolver03 succeeds and a later resolver fails, Resolver08 helps determine whether the defect appears when recursive continuation becomes scheduled work.

### Resolver21

Resolver21 is the structured-coroutine counterpart of Resolver01. Maintain it as the smallest model of deferred value promises, install-before-publication, request-root structured concurrency, and suspension-based materialization.

### Resolver22

Resolver22 is the structured-coroutine counterpart of Resolver02. Maintain it to introduce object fragments and `FromArgument` while preserving complete output, before selective producer demand is added.

### Resolver23

Resolver23 is the structured-coroutine counterpart of Resolver03 and the direct conceptual ancestor of Resolver25. Maintain it as the clean coroutine baseline: it has structured suspension and exact promises without Resolver25's preparation graph or Resolver26's occurrence-stamped symbolic activation.

## Advanced Implementations

### Resolver25

Resolver25 is the alternate construction for the general one-shot problem. It separates structural closure, exact-instance preparation, promise installation, and launch so equal exact keys can merge before one producer runs. Its current implementation still documents restrictions on provider paths and mixed-variable ordering, but its producer-completeness model, lifecycle instrumentation, witnesses, and broad campaigns make it an essential independent comparison for Resolver26.

### Resolver26

Resolver26 is the implementation to harden and productionize. It gives variable-bearing selections occurrence-stamped identity, closes symbolic demand without waiting for bindings, and uses one request-root structured-concurrency scope. Production API integration, multithreaded validation, benchmarks, failure semantics, and performance work should center on Resolver26.

## Debugging With Earlier Versions

Start with Resolver03 when investigating demand closure, exact application count, selective output, passive deepening, argument grounding, or completed-result correctness without object-path variables. Move to Resolver08 when explicit task order, child publication, or queue scheduling may matter. Move to Resolver23 when promise installation, structured suspension, cancellation, or coroutine ownership may matter. Compare Resolver25 and Resolver26 only after the failure survives those simpler models, or immediately when the behavior depends on `FromObjectField`, late symbolic demand, equal-key convergence, or occurrence-stamped identity.

The base and middle rows are useful when a failure can be reduced further. Resolver01/06/21 remove nonempty object fragments; Resolver02/07/22 add object fragments and `FromArgument` without selective-output pressure. A counterexample that first appears in one row or one column gives a much sharper starting hypothesis than a Resolver26-only failure.

Cross-version agreement is not an independent correctness proof because the implementations share model carriers, registry construction, generators, and parts of the correctness oracle. Use the progression to localize defects and test refinement claims, while retaining independent application, binding, lifecycle, and occurrence-aware witnesses for advanced behavior.

## Testing Policy

Resolvers01-23 should have no resolver-specific test logic. They opt into shared deterministic, generated, policy, task-ordering, mutation, witness, stress, and list-passive-deepening contracts according to capability. The selective endpoints Resolver03, Resolver08, and Resolver23 share list-passive-deepening and deep-stress coverage with Resolver25 and Resolver26. Bespoke tests are reserved for behavior unique to Resolver25 or Resolver26.

Every maintained version compiles and runs its ordinary contracts under `./gradlew check`. Keep generated profiles and contract assertions shared so a model or carrier migration changes one test definition rather than eleven copies. Production-facing Viaduct adapters belong to Resolver26 or shared carrier boundaries; older versions should migrate through common model APIs rather than acquiring separate execution2 integrations.

## Retired Versions

Resolver09, Resolver10, Resolver24, and Resolver24i are intentionally absent from the maintained source tree. Resolver09 explored readiness scanning and dynamic dependency reconstruction. Resolver10 added pending symbolic demand, runtime provider traversal, late grounding, convergence, and sealing to that reactor. Resolver24 translated open late-demand acceptance into persistent coroutine orchestrators, and Resolver24i presented a narrow single-file specialization. Their useful lessons remain in Resolver25's design history and Git history, but their divergent schedulers, complete-output retention, duplicated tests, and large migration surfaces did not justify carrying them through future model refactors.
