# Query Plan Research

## Purpose

This document records durable context about Viaduct query planning and selective resolver demand. It is intended to be useful as input to future design sessions without silently importing any current design as settled architecture.

The emphasis is therefore on:

- findings supported by concrete counterexamples or code investigation;  
- correctness properties that any solution should satisfy;  
- difficult cases that a design must address or explicitly exclude;  
- validation techniques that can falsify an incorrect design;  
- James Bellenger's MAT and `KeyTree` work as relevant prior art;  
- open questions whose answers may change as the design evolves.

Current proposals, class shapes, implementation milestones, and migration sequences are intentionally not summarized as conclusions. They are linked in References as evolving artifacts.

## Executive summary

Viaduct currently discovers much of its resolver demand during execution. Fields and child plans begin running as traversal encounters them, while promises, OER cells, and memoization impose ordering through later reads. This makes complete demand aggregation difficult for selective resolvers: multiple plan occurrences can converge on the same producer after its invocation has already started.

The strongest finding is not that selective resolvers must always run multiple times. It is that local, lazy demand discovery cannot guarantee one-shot completeness merely by waiting on promises or recursively walking the plan occurrence that reached the resolver first.

Any design that promises one-shot selective resolution must establish, before dispatch, that the demand supplied to a producer covers every in-scope use that will share that producer identity. If the system permits demand that cannot be known at that point, it needs an explicit alternative such as isolated execution, delayed execution, conservative over-selection, or additional materialization. Which mechanism to use is a design choice, not a settled finding.

Several other conclusions are durable:

- Demand and ownership are different. Query selections and RSSes describe what data is needed; a resolver's OSS describes which paths that resolver owns.  
- One-shot execution correctness is producer-specific. Eventual union coverage of an OER does not prove that the invocation that produced it received complete demand. This is an execution/materialization proof obligation, distinct from the extensional definition of a perfect OER.  
- Plan occurrence is not producer identity. Static paths are templates; runtime identity also depends on target OER, field identity and arguments, concrete type, ancestry, and execution epoch.  
- Runtime type, request variables, and object identity prevent execution from being wholly static. A useful plan may still eagerly bound all possible dependencies while binding concrete instances at runtime.  
- Query plan ownership, dependency target, concrete applicability, and OSS root shape must be explicit. Reconstructing them from incidental type equality or a single plan index has produced real bugs.  
- Validation must be capable of distinguishing the producer from cache hits and must exercise actual execution, not only a second interpreter of planning semantics.

## Epistemic status

### Established by counterexample or code investigation

The following findings have direct evidence:

1. A closure rooted in one `QueryPlan` occurrence can miss demand from a sibling occurrence that later shares its memoized producer.  
2. Unioning predictions attached to a shared OER can hide an incomplete producer invocation.  
3. A runtime registration barrier cannot generally know that no future contributor exists when executing a producer may itself reveal another contributor.  
4. The initial `ExecutionParameters.queryPlanIndex` does not contain every plan that execution may use. Lazily materialized type plans are absent until materialization.  
5. An owning plan's index is also not universally self-contained. Cycle backedges may refer to plans absent from that nested index.  
6. Selection traversal type and variable-resolution target are different. A Query-typed child plan can still have a variable-provider RSS targeting the current object.  
7. Field-resolver and node-resolver OSSes have different root shapes. Parent type and return type equality cannot determine whether an OSS has already been descended.  
8. Abstract recursive OSS traversal must retain concrete ancestor masks needed by `@cycle`.  
9. Canonical, alias-free dependency demand is not automatically the same artifact as the public output selection fragment.  
10. Prior selection reconstruction has lost named fragments, type constraints, target scope, and other execution context in multiple independent bugs.

### Strongly supported, but scoped

The research strongly supports these statements within a bounded, non-incremental query scope:

- Eagerly bounding all possible dependencies can avoid some repeated selective resolver execution, even when exact minimal demand depends on runtime conditions.  
- Conservative over-selection can preserve completeness when exact conditions or concrete types are unavailable before dispatch.  
- Runtime object and list instances can be created from precomputed dependency templates without treating their appearance as new dependency discovery.  
- Ordinary object-local RSS dependencies appear substantially more tractable than query-root re-entrancy, explicit-target subqueries, or demand controlled by values produced in the same cycle.

These statements should not be generalized automatically to mutations, subscriptions, incremental delivery, dynamic subqueries, or arbitrary cross-OER targets.

### Not settled

The research does not settle:

- the final query plan representation;  
- whether the solution should be a new plan, an overlay on `QueryPlan`, or an evolution of existing structures;  
- whether every supported selective resolver should run once;  
- the boundary between conservative over-selection and repeated materialization;  
- whether `KeyTree` should be reused as a planning representation;  
- how paths should be canonicalized across interfaces and concrete implementations;  
- how parent traversal and cross-scope dependencies should be represented;  
- the production migration or rollout sequence.

## Architecture-neutral vocabulary

Using stable terms helps separate findings from one proposal's class names.

- **Producer:** A resolver or engine mechanism that supplies one or more OER values.  
- **Consumer:** A resolver, checker, completion step, or other operation that reads those values.  
- **Occurrence:** One appearance of a field or dependency in a client selection or child plan.  
- **Producer identity:** The execution identity used to decide whether occurrences share one invocation or result. It is more specific than a schema coordinate and may differ from a response path.  
- **Demand:** The fields and paths consumers may need from a producer.  
- **Ownership:** The fields and paths a producer is responsible for supplying, represented by its OSS.  
- **Coverage:** The fields and paths a particular materialization actually supplied.  
- **Dependency:** A requirement that one producer or phase complete enough work before a consumer can proceed.  
- **Static template:** Cached operation structure that describes possible work and dependencies.  
- **Runtime instance:** Request-local work bound to concrete objects, arguments, types, conditions, list items, and ancestry.  
- **Execution epoch:** A boundary across which work must not be silently coalesced, such as separate mutation roots or later incremental work.

## Core findings

### Lazy local closure is insufficient

The decisive counterexample found while reviewing [PR \#1090492](https://git.musta.ch/airbnb/treehouse/pull/1090492) is:

```
client demand: Query.a

Query.a object RSS: foo { x z }
Query.a query RSS:  foo { x y }
```

Both RSS occurrences reach the same memoized `Query.foo`. Whichever occurrence wins invokes the resolver with its local demand. The sibling later reuses the same result and claims an additional field. In an observed execution, the winning invocation received `{x, y}` and execution later claimed `z`.

The failure does not depend on fragments and is not inherently limited to the query root. It arises whenever independently reached plan occurrences converge on one producer identity with different demand.

The durable lesson is:

> A one-shot completeness argument must aggregate over the producer's complete in-scope equivalence class, not merely over dependencies reachable from the first plan occurrence.

### Waiting for contributors is not a completeness proof

A runtime barrier can wait for contributors that are already known, but it cannot generally prove that no future contributor will appear. Consider a recursive shape where executing `foo` is needed before a descendant can reveal additional demand for `foo`. Waiting for that demand can deadlock; dispatching before it is known can under-supply the resolver.

This creates a fundamental design fork:

- bound possible contributors before dispatch;  
- conservatively include demand whose activation is not yet known;  
- separate the late work into another execution scope;  
- allow additional materialization;  
- reject or validate away the shape.

A design should state which choice it makes for each supported feature.

### Demand must be attributed to the actual producer

The original differential probe predicted demand before `computeIfAbsent`, attached predictions from both the cache winner and later cache hits to the returned OER, and accepted a field if any attached prediction contained it.

That checks:

```
claimed(OER) is covered by union(all predictions later associated with OER)
```

The one-shot contract requires:

```
claimed(OER) is covered by the demand supplied to the invocation that produced OER
```

These properties are not equivalent. A synthetic OER with claimed `{a, b}` and two predictions `{a}` and `{b}` demonstrates the false positive: union coverage succeeds even though neither invocation was complete.

Producer attribution is also required for node resolution and any future materialization mechanism. An OER occurrence alone is not a substitute for invocation provenance.

### Demand and ownership must remain distinct

The resolver OSS is a static ownership envelope. Client selections, resolver RSSes, checker RSSes, variable-provider RSSes, and concrete-type child plans introduce operation demand.

For a producer, useful demand is conceptually:

```
reachable operation demand projected through the producer's ownership envelope
```

Fields outside the producer's OSS may still introduce dependencies on the current object, but their returned subtrees belong to another producer. A design that simply unions child query plans without ownership projection will over-attribute demand. A design that treats OSS itself as operation demand will include unrequested owned fields.

### Static structure and runtime identity are different layers

A cached plan cannot generally know:

- concrete OER occurrences;  
- the number and identity of list items;  
- request and derived variable values;  
- coerced argument values;  
- concrete implementations of abstract types;  
- runtime execution conditions;  
- whether an ancestor returned null.

This does not imply that dependencies must be discovered lazily. It implies that a static plan describes possible work and guards, while runtime binds concrete instances and activates applicable alternatives.

A static path or schema coordinate is not a resolver invocation. Runtime producer identity may include:

- execution epoch;  
- target or parent OER occurrence;  
- resolver coordinate and concrete dispatcher variant;  
- exact field key and canonical arguments;  
- concrete type;  
- ancestry and list indices;  
- applicable conditions or variant identity.

Any aggregation algorithm must use an identity compatible with execution's actual memoization and batching behavior.

### Plan semantics cannot be reconstructed from incidental state

The lazy closure work exposed several concrete examples:

- lazy `FieldTypeChildPlans` and their dependencies were absent from the original execution index;  
- cycle backedges were absent from the nested owner's index;  
- Query-typed plan selections and object-scoped variable providers had different targets;  
- field-resolver and node-resolver OSS roots required different handling;  
- same parent and return type names did not imply the same root shape;  
- abstract OSS traversal needed a concrete ancestor mask.

The general lesson is that source plan, dependency owner, target scope, concrete applicability, and ownership root must be represented explicitly or obtained from authoritative shared logic. Inferring them from type names, selection object identity, or whichever index is nearby is unsafe.

### Internal demand and tenant-facing selections may differ

The prototype closure emitted canonical fields without aliases, arguments, directives, variables, or fragment spreads. That is useful for internal dependency analysis and set algebra.

The tenant-facing output selection API may need to preserve caller syntax or a valid forwardable fragment. A union of selections that were individually valid can also become invalid as one GraphQL AST because of alias or argument conflicts.

Future designs should state whether they are producing:

- an internal demand/coverage representation;  
- a resolver-visible semantic view;  
- a serializable GraphQL selection set;  
- a completion plan.

Those artifacts may be related, but they are not automatically interchangeable.

## Correctness criteria

These criteria are execution-algorithm proof obligations that are intentionally independent of a particular query-plan representation. They are distinct from the extensional predicate that defines the perfect OER itself.

### Producer completeness

For every producer invocation `P`, every in-scope, producer-owned field that execution consumes from `P` must be covered by the demand supplied to `P` or by an explicitly modeled later materialization.

For a one-shot producer:

```
ConsumedOwnedFields(P) subset-of SuppliedDemand(P) subset-of OSS(P)
```

Permitted engine bridges such as `Node.id` should be modeled explicitly rather than hidden as exceptions.

### Sound dependency discovery

Every producer, checker, variable provider, runtime type step, or other prerequisite that execution may require in the supported scope must be represented by the planning or fallback mechanism.

Runtime may bind or activate pre-bounded alternatives. If runtime can introduce an unbounded new dependency, the one-shot completeness claim no longer applies unless a fallback handles it.

### Identity agreement

The identity used to aggregate demand must agree with the identity used to memoize, cache, batch, or otherwise coalesce execution. Two occurrences must not share demand if execution treats them as different producers, and must not dispatch independently with partial demand if execution treats them as one producer.

Tests should use the production OER key and argument-coercion logic, not an approximation based on field name.

### Ownership soundness

Demand supplied to a selective resolver must stay within its OSS, except for explicitly defined engine obligations. Traversal must stop when ownership transfers to another resolver or engine mechanism.

### Conservative completeness before minimality

When an exact branch cannot be chosen before dispatch, a safe superset is preferable to an under-approximation if the resolver contract permits it. For an execution algorithm, completeness takes priority over avoiding extra work.

Minimality is nevertheless part of the exact perfect-OER predicate. Conservative over-materialization would need to satisfy a separate, more permissive coverage predicate. The amount of over-selection should still be observable and bounded.

### Termination

Dependency discovery and demand aggregation must terminate in the presence of:

- RSS cycles that are legal in execution;  
- recursive OSSes represented by `@cycle`;  
- repeated schema types reached through independent paths;  
- abstract and covariant recursion;  
- checker raw-slot dependencies.

Termination keys must preserve distinctions that affect target, ownership, type applicability, or producer identity. Deduplicating only by selection-set object or type name can collapse meaningful paths.

### Liveness and failure completion

No dependency should wait forever because a producer, checker, or materialization failed before completing its published state. Every scheduled or claimed unit must complete successfully or exceptionally in a way that releases dependents.

"Does not hang" is a correctness property, not merely an operational nicety. Selective OER key mismatches and missing writers have historically manifested as hangs.

### Concurrency preservation

Correct aggregation should not require global barriers across unrelated objects or list items. Independent ready work should remain concurrent, and compatible instances should remain batchable.

A design should distinguish a static template from its per-object runtime instances so one slow list item does not block every instance of the same path.

### Existing execution semantics

Any replacement or overlay must preserve, or deliberately redefine:

- raw versus policy-checked reads;  
- field and type checker ordering;  
- query- versus object-scoped RSS targets;  
- alias and argument behavior;  
- fragment applicability;  
- concrete-type dispatch;  
- error paths and attribution;  
- mutation serialization;  
- node loading and batching;  
- field completion behavior.

Response equality alone may not detect violations of these semantics.

## Hard cases to retain in future designs

### Converging sibling occurrences

Two sibling RSSes or selections may independently reach the same producer with overlapping but unequal demand. This is the smallest known test of operation-wide aggregation.

### Query-root re-entrancy and explicit targets

A deeply nested resolver can issue demand against Query or another explicit target. This can move execution "backward" into an already visited scope and may converge with earlier root work.

These cases should not be assumed equivalent to ordinary current-object RSSes. A design may isolate them, rerun them, or represent them explicitly, but must state the choice.

### Variables whose values require execution

A selective field's argument or condition can depend on a variable provider, whose own RSS may require object or query data. The variable-resolution target may differ from the child plan's selection type.

If the value needed to decide producer identity or demand depends on the same producer's output, exact pre-dispatch merging may be cyclic. Conservative demand, isolated execution, or later materialization may be required.

### Lazy concrete-type plans

Type-checker plans can be materialized only for applicable concrete types in the current executor. A complete pre-execution model must either bound all possible concrete alternatives or have an explicit runtime mechanism that does not violate demand-sealing assumptions.

Broad interfaces and unions may make conservative expansion expensive even when it is correct.

### Field and node OSS root shapes

A field-resolver OSS is rooted on the parent type and includes the resolver field wrapper. A node-resolver OSS is already rooted on the node type. Recursive same-type fields make type equality useless as a discriminator.

### Abstract and covariant recursion

An abstract root may project to a concrete recursive type whose `@cycle` marker refers to the concrete ancestor. Implementations must preserve both abstract applicability and the concrete ancestor mask.

### Independent repeated-type paths

The same type reached through two different paths may have different ownership, demand, arguments, or ancestry. Cycle prevention must not merge those paths merely because their terminal type matches.

### Lists

One static field path can produce many runtime objects, including nested lists. Every item has a distinct runtime position, with its own index, ancestry, error path, and readiness. Two positions may still contain structurally equal OER values. Batching may group compatible work, but correctness and failure are per occurrence.

### Aliases, arguments, directives, and fragments

Resolvers should not see aliases as semantic demand. Aliases remain important to external response paths, but they do not participate in OER keys in the current model. OER field identity is the schema field name plus fully coerced arguments. Directives and variables control applicability. Named and inline fragments carry both syntax and type conditions.

Selection normalization must preserve every distinction execution uses while avoiding invalid merged ASTs.

### Checker raw-slot semantics

Resolver RSSes normally consume visible, policy-checked values. Checker RSSes intentionally consume raw values to avoid checker dependency cycles. A dependency model that has only "field A before field B" edges is too coarse to preserve this distinction.

### Parent traversal

`@parent` demand targets an ancestor OER rather than a descendant path. List ancestry makes the target occurrence-specific. Parent-mediated and direct demand can be merged only after they resolve to the same ancestor producer identity.

### Nodes and repeated IDs

Different paths can produce distinct runtime OER occurrences for the same node ID. Node data loaders may batch, cache, cover, or re-execute those requests according to their own identity rules. Static path identity and node cache identity should not be conflated.

### Mutations, subscriptions, and incremental work

Mutation root fields execute serially. Subscriptions and incremental delivery create later execution epochs. Demand must not be aggregated across epochs merely because the schema path or object identity looks similar.

## Validation strategy

### Correct the oracle first

All completeness validation must identify the invocation that actually produced the result. Cache-hit predictions must not be unioned into that producer's record.

For nodes or ledgers with multiple real materializations, preserve each materialization's demand, coverage, timing, and provenance separately.

### Deterministic acceptance tests

At minimum, retain these tests:

1. **Split prediction oracle:** claimed `{a, b}` with predictions `{a}` and `{b}` must fail one-shot producer validation.  
2. **Two-RSS convergence:** the `Query.a` / `Query.foo` counterexample must either supply the union before one invocation or exercise the design's explicit fallback.  
3. **Same-type OSS root:** a field such as `Profile.profile: Profile` must descend through the field wrapper, while a `Profile` node resolver must not.  
4. **Lazy type-plan dependency:** a type-checker plan with a variable-provider or nested RSS must remain discoverable.  
5. **Cycle backedge:** a legal checker cycle must terminate and resolve the correct plan owner.  
6. **Abstract concrete cycle:** a concrete covariant `@cycle` must find its concrete ancestor mask.  
7. **List independence:** separate items must activate and fail independently without a global path barrier.  
8. **Raw checker dependency:** checker RSSes must observe raw values without waiting on the selected field's checker.  
9. **Alias and argument identity:** aliases never distinguish OER cells; selections with the same schema field and equal fully coerced arguments merge, while unequal arguments remain separate.  
10. **Failure liveness:** every failed producer/checker path must release dependents with an error rather than hang.

### Differential execution

A strong validation compares a candidate mechanism with actual execution or a trusted non-selective baseline.

For each generated operation and runtime configuration, compare:

- response data and errors;  
- resolver and checker invocation counts;  
- invocation arguments and concrete targets;  
- producer-specific supplied demand;  
- actual consumed producer-owned fields;  
- materialization coverage and missing-demand requests;  
- ordering events where raw/checked semantics matter;  
- completion and failure of all published OER state.

A useful completeness relation is:

```
actual producer-owned consumption
    subset-of producer-specific supplied demand or explicit later coverage
    subset-of OSS plus named engine obligations
```

The candidate should also be run with resolvers returning only the coverage they were told to produce. Returning a full object can mask incomplete demand calculations.

### Property-based testing

The existing arbitrary Viaduct generators and `DeepArbSuite` can exercise combinations that example tests miss. Generated coverage should deliberately include:

- sibling and diamond convergence;  
- nested resolver and checker RSSes;  
- query- and object-scoped dependencies;  
- abstract types and covariant fields;  
- lists and nested lists;  
- aliases, arguments, variables, and directives;  
- named and inline fragments;  
- legal cycles;  
- node resolvers and repeated IDs;  
- failures and null ancestors.

Preserve seeds and full generated descriptors. Shrink failing cases. Distinguish generator timeouts or unrelated stack overflows from completed oracle runs.

Random volume does not compensate for a weak oracle. The earlier ten 1,000-case runs passed the invalid union oracle and therefore are not evidence of one-shot completeness.

### Plan-level assertions

In addition to end-to-end parity, inspect the planned result directly:

- every dependency has an owner, target, and provenance;  
- every producer's demand is stable before dispatch if one-shot execution is claimed;  
- concrete alternatives are bounded;  
- cycles have useful diagnostics;  
- identity keys explain why occurrences merge or remain separate;  
- excluded or fallback features are visible rather than silently approximated.

### Observability for validation and production

Useful measurements include:

- planning time and plan size;  
- number of possible versus activated alternatives;  
- demand before and after ownership projection;  
- over-selection ratio;  
- producer invocation and materialization count;  
- missing-demand or fallback frequency;  
- batching quality;  
- number and duration of blocked dependencies;  
- hangs prevented by explicit missing-writer detection;  
- discrepancies between predicted demand, actual coverage, and consumed fields.

## James Bellenger's MAT and KeyTree work

### Motivation and current model

James's [Selective Resolvers discussion](https://github.com/airbnb/viaduct/discussions/399) describes a pivot away from selection-shaped OER keys. The earlier keying approach could re-run an expensive resolver for the entire new selection, had difficulty re-running parent producers after traversal reached a dead end, did not generalize cleanly to nodes, and created missing-writer hangs.

The MAT direction models selective output as materialization coverage:

- `KeyTree` is a normalized, typed tree of exact OER keys.  
- `Mat` materializes a requested `KeyTree`.  
- `MatResult` records the coverage actually supplied and its source EOD or failure.  
- `MatLedger` records results, determines whether requested demand is already covered, and requests missing coverage.  
- `MatPath` identifies nested runtime objects using concrete types, exact field keys, and list indices.

### KeyTree properties

`KeyTree` is keyed by concrete `GraphQLObjectType` and `ObjectEngineResult.Key`. It provides durable operations for reasoning about demand and coverage:

- union to combine selection shapes;  
- difference to compute uncovered demand;  
- exact key membership;  
- child-subtree navigation;  
- response-key inspection at one object level;  
- wrapping missing nested demand in its parent path;  
- recursive filtering at ownership boundaries.

This is useful prior art regardless of the final query-plan design. It demonstrates that demand and coverage benefit from a typed path algebra rather than flat field coordinates or arbitrary GraphQL strings.

### Relationship to query planning

MAT and query planning answer different questions.

`KeyTree` represents what is requested or covered at runtime. It does not by itself encode:

- which consumer introduced demand;  
- which producer owns it;  
- which prerequisite must execute first;  
- whether raw or checked data is required;  
- which target scope applies;  
- which guarded plan alternative is active;  
- whether two paths should share one producer invocation.

It is therefore a demand/coverage representation, not a complete dependency graph or scheduler.

MAT's ability to fetch only the missing difference is valuable when demand is genuinely late or when conservative planning would be too broad. An eager aggregation design may reduce repeated materialization in its supported scope, while MAT can remain a fallback, defensive repair layer, or independent solution for dynamic cases.

The final relationship is unresolved. Future designs should evaluate reuse based on semantics rather than vocabulary similarity.

### Durable lessons from MAT

- Record actual coverage, not merely requested demand.  
- Make missing demand a first-class difference operation.  
- Preserve exact OER key identity, concrete type, and list path.  
- Distinguish multiple materializations and their failures.  
- Detect missing writers explicitly instead of allowing indefinite waits.  
- Avoid re-fetching already covered subtrees.  
- Do not assume a runtime coverage tree also supplies dependency provenance or scheduling.

## Questions every future design should answer

1. What exact scope receives a one-shot completeness guarantee?  
2. What mechanisms handle demand outside that scope?  
3. What identity determines whether two occurrences share one producer?  
4. Does that identity exactly match OER memoization, node caching, and batching behavior?  
5. Which dependencies are bounded statically, and which values are bound at runtime?  
6. Can runtime introduce new dependency edges, or only activate bounded alternatives?  
7. How are variable providers and their targets represented?  
8. How are concrete type alternatives bounded without excessive over-selection?  
9. How are query-root re-entrancy, explicit targets, and `ctx.query()` isolated or integrated?  
10. How are `@parent` and list ancestry normalized to producer identity?  
11. How are checker raw-slot and visible-value dependencies distinguished?  
12. What representation is used for internal demand, actual coverage, resolver-visible selections, and completion?  
13. How are aliases, arguments, directives, and fragments preserved where required?  
14. How are cycles classified, terminated, and diagnosed?  
15. How are failures guaranteed to release all dependents?  
16. How is concurrency preserved across unrelated objects and list items?  
17. What evidence will establish equivalence with existing execution?  
18. What measurements will reveal over-selection, repeated work, and fallback frequency?

## References

The links below preserve the research trail. Proposal and implementation documents are useful historical context but should not be treated as evergreen conclusions.

### Evolving proposals and research summaries

-   
- [Query Execution Revisited session index](https://docs.google.com/document/d/1L8oGjvvcSMZNkY6ooL78l0K_92f84SLUuGFdf3S3cA8/edit?tab=t.0)  
- [RFC-254: ctx-selections and alternative tabs](https://docs.google.com/document/d/1aXmtEPIQx0xD35kBYyePb2sqnzOjI5GQSB-5STkxHVk/edit)  
- [RFC-246: Selective vs Non-Selective Resolvers](https://docs.google.com/document/d/1rr1KSMe4okF3C_mci17GO4vnP5kCbZ049TbJ5IDC_jI/edit)  
- [Selective Resolvers discussion \#399](https://github.com/airbnb/viaduct/discussions/399)

### OSS research, reviews, and handoffs

- [Resolver Output Selection Sets: Build-Time Registry Plan](https://slate.airbnb.tools/9nREbww0kY)  
- [Initial Correctness Review: OSS-Bounded Selective Resolver Demand](https://slate.airbnb.tools/Z8YyTAUXin)  
- [Review of PR \#1090492](https://slate.airbnb.tools/mKRh5cyEBw)  
- [Debugging Handoff: PR \#1090492](https://slate.airbnb.tools/FDSAzYZcCL)  
- [OSS-Bounded Selective Resolver Demand Closure Handoff](https://slate.airbnb.tools/iDijcqjfQ6)  
- [Re-review of OSS-Bounded Selective Resolver Demand Closure](https://slate.airbnb.tools/B3yGRvkoZq)  
- [Re-review Handoff](https://slate.airbnb.tools/Ovijg147kY)  
- [Diagnostic Edit Diffs for PR \#1090492](https://slate.airbnb.tools/llAXBd1MCN)  
- [ExecutionSelectionSet Benchmarking](https://slate.airbnb.tools/FzP5emjgGy)  
- [Viaduct Modern Tenant API Spec](https://docs.google.com/document/d/1DSsqbNKAMAKTxn2QdSdOQYX4QJcrtX__PQrycRNeGcQ/edit)

### Core OSS and demand-closure PRs

- [\#1076008: Require resolvers for argument fields](https://git.musta.ch/airbnb/treehouse/pull/1076008)  
- [\#1085244: Record resolver output selection sets at build time](https://git.musta.ch/airbnb/treehouse/pull/1085244)  
- [\#1087924: Narrative review of build-time resolver OSS](https://git.musta.ch/airbnb/treehouse/pull/1087924)  
- [\#1088747: Project resolver-owned output selections at runtime](https://git.musta.ch/airbnb/treehouse/pull/1088747)  
- [\#1090492: Compute OSS-bounded resolver demand](https://git.musta.ch/airbnb/treehouse/pull/1090492)

### James Bellenger's MAT work

- [\#1067105: WIP selective resolvers dev](https://git.musta.ch/airbnb/treehouse/pull/1067105)  
- [\#1079953: ExecutionSelectionSet](https://git.musta.ch/airbnb/treehouse/pull/1079953)  
- [\#1081468: MAT plumbing](https://git.musta.ch/airbnb/treehouse/pull/1081468)  
- [\#1084881: Preserve reconstructable child-plan context](https://git.musta.ch/airbnb/treehouse/pull/1084881)  
- [\#1085565: ExecutionSelectionSet, take 2](https://git.musta.ch/airbnb/treehouse/pull/1085565)  
- [\#1086036: Move helpers to FieldExecutionHelpers](https://git.musta.ch/airbnb/treehouse/pull/1086036)  
- [\#1086215: QueryPlan KeyTree projection and filtering](https://git.musta.ch/airbnb/treehouse/pull/1086215)  
- [\#1086532: Add MAT ledger implementation](https://git.musta.ch/airbnb/treehouse/pull/1086532)  
- [\#1089236: Remove selective OER keys](https://git.musta.ch/airbnb/treehouse/pull/1089236)  
- [\#1089960: Add deep arbitrary test suite](https://git.musta.ch/airbnb/treehouse/pull/1089960)

### Earlier selective resolver and OER-key lineage

- [\#1024607: Add isSelective to @resolver](https://git.musta.ch/airbnb/treehouse/pull/1024607)  
- [\#1024608: Wiring for selective field resolvers](https://git.musta.ch/airbnb/treehouse/pull/1024608)  
- [\#1025743: Wire isSelective through the field resolver runtime](https://git.musta.ch/airbnb/treehouse/pull/1025743)  
- [\#1025745: Use selection-set-aware OER keys for selective fields](https://git.musta.ch/airbnb/treehouse/pull/1025745)  
- [\#1029725: Revert selective keying for selective fields](https://git.musta.ch/airbnb/treehouse/pull/1029725)  
- [\#1031398: Selective OER key wiring, take 2](https://git.musta.ch/airbnb/treehouse/pull/1031398)

### QueryPlan correctness and execution context

- [\#1059619: Fix conditional dropped fragments](https://git.musta.ch/airbnb/treehouse/pull/1059619)  
- [\#1059898: Handle pruned variable RSSes](https://git.musta.ch/airbnb/treehouse/pull/1059898)  
- [\#1060622: Selective resolver fixes](https://git.musta.ch/airbnb/treehouse/pull/1060622)  
- [\#1060646: Selective resolver execution tests](https://git.musta.ch/airbnb/treehouse/pull/1060646)  
- [\#1061282: Preserve type constraints for widened selections](https://git.musta.ch/airbnb/treehouse/pull/1061282)  
- [\#1063355: RSS QueryPlan reproduction](https://git.musta.ch/airbnb/treehouse/pull/1063355)  
- [\#1064433: Normalized child plans](https://git.musta.ch/airbnb/treehouse/pull/1064433)  
- [\#1079007: Retain skipped fragments in Viaduct query plans](https://git.musta.ch/airbnb/treehouse/pull/1079007)

### Tracking and specifications

- [Resolver OSS parent tracking task](https://app.asana.com/1/150975571430/project/1207604899751448/task/1216397253264972)  
- [Build-time OSS task](https://app.asana.com/1/150975571430/task/1216732516507794)  
- [Runtime OSS task](https://app.asana.com/1/150975571430/task/1216732516507795)  
- [Classic resolver-boundaries task](https://app.asana.com/1/150975571430/task/1216732516507796)  
- [Viaduct output selection set documentation](https://viaduct.airbnb.tech/docs/developers/resolvers/?h=output+selection#output-selection-sets)  
- [Viaduct node responsibility set documentation](https://viaduct.airbnb.tech/docs/developers/resolvers/node_resolvers/#responsibility-set)  
- [GraphQL CollectFields](https://spec.graphql.org/draft/#CollectFields)  
- [GraphQL fragment applicability](https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible)  
- [GraphQL variables](https://spec.graphql.org/draft/#sec-Language.Variables)

