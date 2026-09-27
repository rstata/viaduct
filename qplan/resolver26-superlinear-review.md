# Resolver26 adversarial growth review

The singular Query scope removes the demonstrated duplication of Query roots, but it does not establish a non-exponential bound for Resolver26. I reproduced four exponential mechanisms: expansion of successor-demand forests, expansion of nonempty parent requests, enumeration of inclusion alternatives, and recursive hashing of symbolic keys. The first three can occur with only linearly many distinct required fields; hashing can be exponential even in a linear dependency chain. I would resolve these before treating Resolver26 as production-ready on complexity grounds.

Confidence in these findings is high: the reproductions count selections, alternatives, or hash operations rather than infer asymptotics from timings. Implementation confidence for the requested growth property is low; existing test evidence is weak for complexity despite substantial semantic coverage; architectural fit is adequate, but construction-demand and identity representations need explicit cost contracts. This is not a general assessment of functional correctness.

Reviewed revision: `98fddc20b14b1ee1345ae85a5660d8e9fda01cc3`, in `/home/raymie_stata/repos/4rv/qplan`. The worktree was initially clean. All commands ran from qplan; no everst information was used. No runtime implementation was changed. The review includes pre-existing machinery used by Resolver26, not just lines introduced by the singular-scope branch. In particular, `SuccessorDemand.kt`, the model identity/condition/selection carriers, and `CycleCheckState.kt` are unchanged from the branch base `a7f780a18`.

## What the ownership argument establishes

One orchestration closes an object OER and its associated Query OER together. A map of exact symbolic keys prevents repeated creation of the same resolver context. Query-side resolver inputs feed back into the shared Query scope. Source ownership and occurrence identity keep distinct returned objects and independently requested executions separate.

That argument bounds producer multiplicity per exact key. It does not bound how many raw selections represent those keys, how many Boolean alternatives a key accumulates, how expensive hashing a key is, how many times closure scans existing demand, or how many symbolic keys a compact registry generates. Those are independent claims, and the following witnesses separate them.

Here, depth means dependency depth in a family of registries whose declarations and fragment selections grow linearly. These are small acyclic schemas with constant-size client selections, not exponentially large client queries, returned lists, or dynamically recursive tenant output. Counts below are structural observations, not latency benchmarks.

## Blocking findings

### 1. Successor-demand memoization retains an exponentially expanded forest

The combination at [SuccessorDemand.kt:112](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/SuccessorDemand.kt:112) concatenates an argumentless resolver selection with its transitive passive predecessors. The cache at line 79 saves recomputation of each resolver's forest, but every incoming edge still copies the cached forest into a larger flat list. [Selection.kt:471](/home/raymie_stata/repos/4rv/qplan/model/src/main/kotlin/model/Selection.kt:471) implements addition by list concatenation, without key normalization.

Witness: `field_i` has object input `{ field_(i+1) field_(i+2) }`, truncated at the last field. Every field is an argumentless scalar resolver. Successor demand for `{ field0 }` has Fibonacci-sized multiplicity even though it contains only `depth + 1` different fields.

| Depth | Raw successor selections | Distinct fields |
| --- | ---: | ---: |
| 5 | 20 | 6 |
| 10 | 232 | 11 |
| 15 | 2,583 | 16 |
| 20 | 28,656 | 21 |

This is on the runtime path: [FieldResolutionLogic.kt:117](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/FieldResolutionLogic.kt:117) computes successor demand before invoking an object producer. An end-to-end witness with `Query.root: Box` and the depth-15 diamond on `Box` completed with 17 resolver invocations and 16 Box cells, while the root producer received 2,583 selections. Thus an invocation-count regression can pass while request preparation remains exponential. Subsequent traversal/normalization pays for the expanded forest even if it eventually coalesces the keys.

Suggested resolution: represent transitive construction/successor demand as a shared, normalized demand graph or keyed union, preserving concrete-type applicability and guards. Memoizing already-expanded lists is insufficient. Keep aliases in owner materialization rather than requiring construction demand to retain semantically duplicate paths.

### 2. The parent-lifting fix still expands nonempty parent requests exponentially

[ParentConstructionDemand.kt:72](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/ParentConstructionDemand.kt:72) concatenates `parentRequests`; line 213 copies and guards every request whenever a cached analysis is used. At the matching producer boundary, line 165 appends every request's demand individually. The resolver-field memo prevents recursive re-analysis but does not prevent the result from becoming a dependency-path tree.

Witness: place the same diamond on `Child`, set the terminal resolver's object fragment to `parent { seed }`, and select `{ root { child { field0 } } }`. `Child.parent` is the backedge across `Parent.child`. Only one additional `Parent.seed` field is needed.

| Depth | Lifted `seed` occurrences | Distinct lifted fields |
| --- | ---: | ---: |
| 5 | 8 | 1 |
| 10 | 89 | 1 |
| 15 | 987 | 1 |
| 20 | 10,946 | 1 |

The request list follows the Fibonacci recurrence. There is also quadratic copying in the number of expanded requests when `localDemand += ...` repeatedly copies the accumulated forest. The existing [complexity regression](/home/raymie_stata/repos/4rv/qplan/semantics/src/test/kotlin/semantics/resolver26/ParentConstructionDemandComplexityRegressionTest.kt:13) has an unused parent relation and asserts that the lifted result is empty. It passes alongside this witness: bounded registry lookups do not bound a nonempty memoized result.

Suggested resolution: merge equivalent parent requests before propagation and combine their demand using a shared representation. Preserve checked/unchecked provenance, parent-field identity, and inclusion semantics. Add a nonempty-parent diamond budget on intermediate/result size, not just resolver-template lookups.

### 3. Shared Query closure enumerates exponentially many inclusion alternatives

[ConstructionDemandClosure.kt:200](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/ConstructionDemandClosure.kt:200) expands each new satisfiable conjunction separately. [InclusionCondition.kt:122](/home/raymie_stata/repos/4rv/qplan/model/src/main/kotlin/model/InclusionCondition.kt:122) distributes conjunction over disjunction, and [Selection.kt:435](/home/raymie_stata/repos/4rv/qplan/model/src/main/kotlin/model/Selection.kt:435) distributes guards into forest occurrences. Exact-key sharing does not share the Boolean expression that activates the key.

Witness: each `field_i` has two Query-fragment aliases of `field_(i+1)`, one guarded by its provider variable `a_i`, the other by `b_i`. Each resolver declares just those two variables. There is one exact key per level. The last field's activation is the compact formula `(a_0 OR b_0) AND ... AND (a_(d-1) OR b_(d-1))`, but closure stores its `2^d` conjunctions.

| Depth | Query keys | Final-key alternatives |
| --- | ---: | ---: |
| 3 | 3 | 8 |
| 6 | 6 | 64 |
| 9 | 9 | 512 |
| 12 | 12 | 4,096 |

This work happens before provider values are available. It is unnecessary to enumerate every satisfying path to decide whether the shared producer should run. Runtime then compounds the cost: [FieldResolutionLogic.kt:245](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/FieldResolutionLogic.kt:245) creates an `async` for every alternative.

Suggested resolution: retain factored/shared Boolean conditions and propagate demand without enumerating DNF terms. The replacement must preserve independently ready true alternatives, failed-provider isolation, and cancellation behavior; replacing the loop with sequential Boolean evaluation would lose existing readiness guarantees.

### 4. Symbolic-key hashing is exponential in a linear dependency chain

[VariableInstanceIdImpl.hashCode](/home/raymie_stata/repos/4rv/qplan/model/src/main/kotlin/model/OccurrenceIds.kt:115) recursively hashes its owning resolver occurrence. The occurrence is a data class containing the exact path; path keys hash their argument expressions, which hash their variable instances. None of these identity layers memoizes that recursive hash.

A valid chain can forward one variable into two argument positions: `field_i(x, y)` requests `field_(i+1)(x: $x, y: $x)`. Each next key has two references to a variable owned by the preceding key. There are only linearly many keys and variable instances, but one final-key hash satisfies `H(d) = 2 H(d-1) + O(1)`.

The counted carrier probe builds exactly that shared identity shape, wraps the base schema field with an equal-hash delegating counter, resets the counter after construction, and calls the final key's `hashCode()` once:

| Depth | Distinct keys | Base-field hash calls |
| --- | ---: | ---: |
| 4 | 5 | 16 |
| 8 | 9 | 256 |
| 12 | 13 | 4,096 |
| 16 | 17 | 65,536 |

This is exponential CPU cost without an exponentially large stored graph. Closure maps, inclusion sets, binding maps, and OER lookup all rely on these hashes, so reducing closure to a work queue alone will not solve it. Even one forwarded argument leaves depth-dependent hash cost.

Suggested resolution: cache structural hashes at immutable identity/argument boundaries or intern occurrence identities into stable compact handles while preserving the required structural equality. Review equality separately when adopting interning; changing identities to reference equality without canonicalization is not equivalent.

## Important polynomial findings

### 5. Closure rescans and recopies already-expanded demand

The loop at [ConstructionDemandClosure.kt:101](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/ConstructionDemandClosure.kt:101) calls `newResolverInputDemand` on each entire accumulated forest, and line 179 remerges it. A simple ground, unguarded chain reveals one additional resolver each round, so the total scan is quadratic. Immutable `objectDemand += ...` and `queryDemand += ...` add prefix copying. This remains after fixing the exponential representations.

Counting only registry membership checks during closure gave 187, 627, 2,275, and 8,643 checks at depths 16, 32, 64, and 128, with respectively 17, 33, 65, and 129 final keys. These counts exclude world construction and do not depend on timing or symbolic hashing.

Suggested resolution: use a work queue driven by newly added keys or condition contributions, retain the normalized accumulated state, and avoid rebuilding both sides on every round. Preserve the prepare-before-dispatch lifecycle; incremental closure computation does not require accepting new demand after freezing.

### 6. Runtime cycle checking can traverse the whole dependency graph per read

[CycleCheckState.kt:91](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/shared/CycleCheckState.kt:91) performs a fresh reachability search after every read. The result of the edge-set insertion is ignored, so repeated reads of the same edge also repeat the search. All this runs under the synchronized `addRead` method.

Register a ground-key chain's writers, then add reads from the tail toward the head. Each insertion walks the already-installed suffix. Counts of schema-field hash operations during read insertion were 440, 1,648, 6,368, and 25,024 for 16, 32, 64, and 128 edges. The chain has no cycle, and each edge is inserted once. The per-search visited set avoids exponential path enumeration, but aggregate work is quadratic even for this sparse graph; the straightforward general bound is `O(R(V + E))` for R reads with constant-cost keys.

Suggested resolution: at minimum skip reachability work for existing edges; use an incremental cycle/topological-order algorithm or establish an explicit acceptable graph-size bound for production. Deduplicating edges alone does not fix the chain witness.

### 7. Several local loops are quadratic even without dependency expansion

These are direct code-level bounds, not separately instrumented timing results:

| Component | Small witness and cost | Suggested resolution |
| --- | --- | --- |
| [Resolver-input filtering](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/MaterializeResolverInput.kt:99) | M included sibling occurrences are appended one at a time to an immutable forest; [forest addition](/home/raymie_stata/repos/4rv/qplan/model/src/main/kotlin/model/MaterializeSelection.kt:256) copies the growing list: quadratic reference copying. | Collect into a builder and construct the forest once. |
| [Passive-field matching](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/shared/SharedPassiveValueResolutionLogic.kt:230) | Each of P supplied fields scans all D demand keys: `O(PD)`, quadratic for a fully supplied wide object. | Build a field-to-keys index once. |
| [Alternative completion](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/FieldResolutionLogic.kt:255) | A false/failing alternatives trigger select loops over A, A-1, ..., 1 remaining deferreds: quadratic builder-loop iterations. | Consume completions through a queue/channel or another once-registered completion mechanism, preserving cancellation and error semantics. |

The same immutable-concatenation pattern appears in closure fragment accumulation and parent-demand folding. Fixing individual builders removes polynomial amplification but does not remove the four exponential mechanisms above.

## Important semantic limit: singular scopes do not bound the number of symbolic keys

Occurrence-owned variables intentionally distinguish otherwise equal grounded calls. In a diamond where each resolver forwards `$x` into both downstream resolvers, closure produced 12 keys for 5 coordinates, 88 for 9 coordinates, and 609 for 13 coordinates, starting from `field0(x: 1)`. Every eventual argument value is 1, but variables from different owner paths yield different symbolic keys, and those new keys instantiate further owner variables. This is exponential key multiplicity, not just a slow representation of a linear key set.

That behavior follows the documented [Resolver26 identity contract](/home/raymie_stata/repos/4rv/qplan/semantics/src/main/kotlin/semantics/resolver26/Resolver.kt:14), so I am not proposing an unqualified merge by grounded value. It is a production design limit that must be explicit: the guarantee is one producer per exact symbolic key, not one producer per coordinate/grounded argument tuple. Decide whether to preserve the semantics with an expansion budget or introduce a proven normalization/coalescing rule, at least for forwarded argument identities. This limit is independent of the four implementation defects.

## Validation and limits

The final focused run passed nine tests: eight diagnostic probes plus the existing parent-complexity regression, with no skips, failures, or errors. The diagnostic tests assert the current counts/growth to make the observations reproducible; they are not acceptable performance budgets and should not be merged unchanged into the normal regression suite. Their source is retained outside the normal source sets at [SuperlinearGrowthReviewProbeTest.kt](/home/raymie_stata/repos/4rv/qplan/review/superlinear-growth/SuperlinearGrowthReviewProbeTest.kt), with [captured output](/home/raymie_stata/repos/4rv/qplan/review/superlinear-growth/results.txt).

Run from `/home/raymie_stata/repos/4rv/qplan`, temporarily placing the probe in the test source set:

```sh
cp review/superlinear-growth/SuperlinearGrowthReviewProbeTest.kt semantics/src/test/kotlin/semantics/resolver26/SuperlinearGrowthReviewProbeTest.kt
./gradlew :semantics:test --tests 'semantics.resolver26.SuperlinearGrowthReviewProbeTest' --tests 'semantics.resolver26.ParentConstructionDemandComplexityRegressionTest' --console=plain
rm semantics/src/test/kotlin/semantics/resolver26/SuperlinearGrowthReviewProbeTest.kt
```

The runtime witness checks produced values' container shape and exact application counts; other probes isolate helpers/carriers so fixture replay and scheduling do not contaminate complexity counts. The hash counter wrappers preserve the wrapped field's hash value and do not replace the algorithms under test. The guard witness uses ordinary validated Query-fragment resolvers with provider variables. The parent and successor witnesses use validated DSL registries. The reviewed production sources remained at the recorded revision; only the diagnostic test source was temporarily added for compilation.

I did not rerun the full correctness/stress suites or collect JMH/JFR measurements; no throughput comparison is claimed. Existing stress evidence establishes many semantic interactions, but it does not establish bounds for long diamonds, guard products, identity-graph hashing, or wide-object copying. After repairs, turn these witnesses into size/work-budget regressions and retain the existing readiness, provenance, ownership, and cancellation contracts.

Provider-path traversal itself is linear in path length plus terminal-list materialization, excluding key hashing and cycle-check costs identified above. Field/binding installation iterates prepared occurrences and declarations. Reference-tail execution is proportional to emitted hops times each nested execution's cost; returned lists/objects and owner projections contribute real occurrence/output work. This inspection does not prove a global linear bound for every combination, and no such bound is claimed.
