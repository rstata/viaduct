# Maintained Resolver Versions

## Purpose

Every maintained resolver uses the aligned Engine API carrier boundary. The versions form a comparison grid that separates semantic capability from execution structure; they are not eleven production candidates. Resolver26 is the primary algorithm, while earlier versions make its essential ideas easier to isolate and verify.

## Comparison Grid

| Semantic stage | Recursive construction | Explicit depth-first tasks | Structured coroutines | Capability |
| --- | --- | --- | --- | --- |
| Base | Resolver01 | Resolver06 | Resolver21 | Empty user object fragments and complete output |
| Object fragments | Resolver02 | Resolver07 | Resolver22 | Nonempty fragments and `FromArgument`, with complete output |
| Selective resolution | Resolver03 | Resolver08 | Resolver23 | The same fragment domain with selective output and full successor demand |

Each row changes semantic capability while holding the execution family roughly constant. Each column changes execution structure while holding capability roughly constant.

### Recursive Reference: Resolver01-03

Resolver01 is the smallest result-tree constructor. Resolver02 adds object-fragment closure and `FromArgument`. Resolver03 adds selective projection and full successor demand.

Resolver03 is the principal compact semantic reference. Start there when reasoning about demand closure, exact-key publication, passive deepening, argument grounding, or completed-result correctness that does not require runtime object-field variables.

### Explicit Work: Resolver06-08

Resolver06-08 express the same three stages through `DepthFirstReactor` tasks. Resolver08 is especially useful after Resolver03 passes: it exposes task identity, queue ordering, and publication as explicit mechanics without adding `FromObjectField`.

### Structured Suspension: Resolver21-23

Resolver21-23 express the same stages through request-owned structured coroutines and exact promises. Resolver23 is the clean coroutine baseline for comparing promise installation, suspension, child publication, and request quiescence with Resolver25 or Resolver26.

## Advanced Resolvers

### Resolver25

Resolver25 merges actual demand by grounded key. One orchestrator owns each OER occurrence, closes a conservative potential-demand envelope by activated field, grounds actual selections independently, and merges equal keys until a key seals for launch. Source presence lets ancestor outputs own argumentless fields that otherwise have standard resolvers; absent fields use standard resolution. Published output can accept descendant demand through its output and fringe state without reapplying the containing key.

This current activation implementation is authoritative; the retired static preparation graph is not a target to restore. Resolver25 remains an alternate experiment, not the primary blueprint. It is useful when comparing late equality, potential versus actual demand, provider traversal, or merged-ground-key identity with Resolver26.

### Resolver26

Resolver26 retains variable-bearing resolver-fragment selections as symbolic OER keys. Variables are instantiated once per resolver occurrence, so equal symbolic keys coalesce within an OER while separate containing OERs remain distinct. It synchronously closes symbolic demand before local installation, uses source presence to let ancestor outputs own argumentless fields that otherwise have standard resolvers, prepares every binding required by the remaining work, reserves active cells once their symbolic keys are contextually grounded, freezes the OER key set, and runs field resolution under one request-owned coroutine scope.

Resolver26 is the primary algorithm and eventual implementation blueprint. Its aligned qplan shape remains close to what a future Viaduct query executor can use, but that future executor is not part of an ordinary qplan refactor.

## Debugging Reduction

Use this order unless the failing feature requires a later version:

1. Resolver03 for compact selective semantics.
2. Resolver08 for explicit work ordering and publication.
3. Resolver23 for structured suspension and promise ownership.
4. Resolver25 or Resolver26 for runtime `FromObjectField`, late symbolic keys, or their distinct identity policies.

Reduce further to Resolver01/06/21 to remove object fragments, or Resolver02/07/22 to retain object fragments and `FromArgument` without selective-output pressure.

Cross-version agreement is not independent proof because versions share carriers, fixture construction, generators, and parts of the oracle. Use the grid to localize differences, then rely on independent application, binding, lifecycle, and occurrence-aware evidence.

## Refactoring Policy

Shared carrier and API migrations must update every maintained version. Prefer one shared model operation or adapter boundary over resolver-specific compatibility code. Older versions should not acquire separate `execution2` integrations.

Resolvers01-23 gain behavior through shared contracts. Bespoke tests remain appropriate only for Resolver25/26 policies and implementation-specific lifecycle or concurrency behavior.

## Resolver10 As A Lesson

Resolver10 is not maintained, but comparing its abandoned approach with Resolver03, Resolver08, and Resolver26 is useful. It combined readiness rescanning, persistent late-demand acceptance, provider traversal, late grounding, and complete-output retention. That machinery increased the state space and could conceal incomplete demand supplied to the original producer.

Use Resolver10 to recognize paths that can be simplified, not as code to revive or a complete version to document.
