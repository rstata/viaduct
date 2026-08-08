# Query Plan Research

## Purpose

This document records durable evidence and design obligations for one-shot Viaduct field resolution. Current implementation state belongs in [`handoff.md`](./handoff.md); completed milestones and migration chronology belong in Git history.

Within a declared feature scope, every resolver-bearing OER occurrence should receive complete in-scope demand before its resolver is applied, and that resolver should be applied once while constructing that occurrence. A future design may exclude, isolate, or conservatively cover difficult features, but it must state the choice.

## Executive Summary

Viaduct discovers substantial resolver demand during execution. Independently reached plan occurrences can converge on one memoized producer after its application has begun. Waiting on promises or recursively following the first occurrence does not establish that every contributor has been found.

One-shot resolution therefore requires a producer-specific argument: all in-scope demand targeting one resolver-bearing OER cell is aggregated before that cell's resolver application. Eventual union coverage, cache-hit predictions, or a correct final response do not prove that the producing application received complete demand.

One-shot identity is result-tree identity, not entity identity. Distinct OER occurrences remain distinct when they carry the same node ID, resolver coordinate, arguments, or structural value. Caching, request deduplication, and batching may share underlying work without changing those semantic identities.

Demand and ownership are different. Client selections and resolver requirements say what data is needed; a resolver's output selection set says which portion that resolver supplies. Complete demand must be projected through the actual producer's ownership boundary.

The intended execution state is monotonic. Cells, bindings, and completion facts move only from absent to one value. Published facts are never replaced; later progress fills previously absent state. This makes one-shot application, concurrent claiming, failure completion, and schedule reasoning explicit instead of reconstructing them from overwritten trees or incidental caches.

## Semantic Boundary

The project models field resolution rather than GraphQL field completion. Response aliases, response ordering, external error shaping, and response serialization remain outside field-resolution identity.

Selections may carry open `Value.Key` values. Concrete-parent selections use `Value.ObjectKey`, whose arguments may still contain variables. Exact OER and `Value.Object` cells use `Value.GroundKey`: one canonical concrete object field plus fully coerced ground arguments.

Every argument-bearing output field is assumed to have an explicit resolver. Production namespace exceptions are outside the model. A canonical field is behavioral exactly when it is engine-supplied `__typename` or has a registered field resolver.

Source node resolvers are lowered before semantic reasoning. A source `foo(args): W<T>` becomes `foo$bridge(args): W<T$Bridge>`; every non-null bridge object passively contains `$id`, and the generated argumentless `T$Bridge.$node` resolver loads the node from `{ $id }`. Lists retain separate bridge and loader occurrences at every position. The canonical registry consequently contains field resolvers only.

`FromArgument` variables are exact-occurrence inputs available during local demand closure. Runtime `FromObjectField` providers are different: their values require reading resolved OER state and may reveal exact argument keys later. The latter remain a future execution concern even though their declarations, paths, and ordering constraints are validated before reasoning.

## Established Findings

The following findings are supported by production code investigation, focused counterexamples, or failed model designs:

1. A closure rooted at one `QueryPlan` occurrence can miss demand from a sibling occurrence that later shares its producer.
2. Unioning predictions associated with an OER can hide an incomplete producing application.
3. A runtime registration barrier cannot generally know that no future contributor exists when executing one producer can reveal another contributor.
4. Lazy concrete-type plans and cycle backedges may be absent from the plan index available at the initial execution site.
5. Selection traversal type and variable-provider target are different dimensions.
6. Production field-resolver and node-resolver ownership roots differ in the source world; canonical bridge lowering removes that distinction before reasoning.
7. Static paths and schema coordinates do not identify runtime OER occurrences, list positions, concrete types, arguments, or execution epochs.
8. Alias-free internal demand is not automatically a valid tenant-facing GraphQL fragment.
9. Broad random campaigns can pass an invalid oracle or miss a decisive adversarial shape.
10. Exact variable identity belongs to the defining resolver occurrence, and nested variables must be stamped and instantiated one demand layer at a time.

## Core Lessons

### Aggregate Every Contributor

The smallest decisive shape has two independently reached requirements for one memoized resolver-bearing cell:

```text
client demand: Query.a
Query.a object requirement: foo { x z }
Query.a query requirement:  foo { x y }
```

Whichever occurrence reaches `Query.foo` first can apply it with only local demand. A later cache hit can claim the other field, making the final OER look covered even though the producer was under-supplied.

The durable rule is:

> Aggregate every in-scope demand occurrence targeting one resolver-bearing OER cell before applying its resolver.

### Waiting Is Not Completeness

A barrier can wait for contributors already known to exist, but it cannot prove that execution will not reveal another contributor. A correct design must bound possible contributors before dispatch, conservatively include guarded alternatives, isolate late work into another occurrence or epoch, or reject the shape.

Repeatedly materializing missing coverage is a valid multi-shot strategy and useful prior art. It is not the one-shot endpoint for one resolver-bearing OER occurrence.

### Attribute Demand To The Producer

This relation is too weak:

```text
claimed(OER) subset-of union(all predictions later associated with OER)
```

The one-shot relation is producer-specific:

```text
consumed producer-owned fields
    subset-of demand supplied to the producing application
    subset-of that producer's ownership envelope
```

Validation must distinguish the producing application from cache hits, later readers, and other materializations.

### Separate Demand, Ownership, And Representation

Client selections, resolver object fragments, checker requirements, and variable providers contribute demand. Output selection sets determine ownership. Internal dependency demand, resolver-visible selections, serializable GraphQL fragments, completion plans, and materialization coverage are related artifacts, not interchangeable representations.

For a selective resolver, different demand may select different output subsets, but overlapping coordinates must remain coherent: holding ordinary inputs fixed, projected results agree in value, null/error status, list structure, and concrete type wherever both demands select the same coordinate.

### Separate Static Possibility From Runtime Identity

A static plan can bound possible work and guards. Runtime execution binds concrete OER occurrences, list indices, arguments, variable values, types, nullability outcomes, and epochs. Aggregation must combine demand only after those dimensions identify the same exact cell, without conflating result identity with node-cache identity.

### Make Progress Monotonic

Mutable execution state should expose progress without permitting correction by replacement. Every OER cell, variable binding, obligation claim, and completion signal has an absent state and at most one terminal value. A parent may publish a reference to an incomplete child OER, after which only the child's absent cells become present.

Monotonicity does not by itself prove complete demand, readiness, or liveness. It makes violations observable: duplicate writers conflict, missing writers remain absent, and a quiescent state with unresolved demanded cells is a deadlock or invalid-state witness rather than an apparently completed result.

Post-application widening cannot repair a selective producer that discarded required output. Monotonic construction must therefore be paired with demand sealing before application.

## Correctness Obligations

### Producer Completeness

For every resolver-bearing occurrence in scope, all producer-owned values later consumed from that occurrence are covered by the demand supplied to its sole resolver application. Work that cannot satisfy this relation must be conservatively covered, isolated, or excluded.

### Sound Dependency Discovery

Every resolver, checker, provider, concrete-type step, or other prerequisite that execution may require must be represented before dispatch. Runtime may activate bounded alternatives; it must not silently introduce an unbounded dependency for an already-applied producer.

### Identity Agreement

The identity used for aggregation must agree with actual OER occurrence construction. Multiple paths to one exact cell converge; separate objects, list positions, argument tuples, and epochs remain separate.

### Ownership Soundness

Demand supplied to a producer stays within its output ownership, apart from explicit engine bridges. Traversal stops at behavioral boundaries and attributes successor work to the successor.

### Monotonic Safety

Each exact cell and binding has at most one writer and one value. A resolver-bearing cell has at most one application. Published parent structure remains valid while descendants gain cells.

### Termination And Liveness

Demand closure, dependency ordering, and execution must terminate over the accepted domain. Every claimed unit completes successfully or exceptionally so dependents are released. Missing writers and deadlock must fail explicitly rather than hang.

### Concurrency

Correct aggregation must not require global barriers across unrelated objects or list items. Independent ready work remains concurrent and compatible resolver applications remain batchable below their per-occurrence semantic identities.

## Hard Cases

### Converging Demand

Sibling selections or resolver requirements may reach one producer with overlapping but unequal demand. This is the minimum test of operation-wide aggregation.

### Runtime Variable Providers

A `fromObjectField` variable can feed an argument elsewhere in the defining resolver's fixed object fragment. The provider path is structural and known, but its value and resulting exact key are not available until prerequisite OER cells exist. Symbolic and concrete occurrences may later become equal, so complete demand for every possible convergence must be accounted for before any affected producer runs.

The canonical registry restricts provider/use shapes with one argument-insensitive branch order combining ordinary dependencies and provider-production-before-use edges. This rejects cycles and overlaps conservatively; it is a domain restriction, not a runtime provider algorithm.

### Query Re-entrancy And Parent Targets

Nested work may target Query, an explicit scope, or an ancestor through `@parent`. These dependencies can move outside ordinary descendant traversal and must retain occurrence-specific target identity, especially through lists.

### Abstract Types And Recursion

Lazy concrete-type plans, interfaces, unions, covariant recursion, and `@cycle` require guarded alternatives and exact ancestor context. Type equality alone cannot identify ownership or a cycle target.

### Lists And Repeated IDs

One static path can produce many runtime object occurrences. Each list item has independent ancestry, readiness, failure, and application identity. Equal node IDs may be cached or batched but do not merge result positions.

### Aliases, Arguments, Directives, And Fragments

Aliases do not distinguish OER cells, while concrete fields and unequal ground arguments do. Type conditions and directives govern applicability. Named and inline fragments carry syntax that may matter to a tenant-facing selection even when internal demand uses a normalized representation.

### Checkers And Execution Epochs

Checker requirements may intentionally read raw values where ordinary resolvers read checked values. Mutations, subscriptions, and incremental delivery introduce ordering and epoch boundaries across which work must not be silently coalesced.

## Validation Strategy

### Use A Producer-Specific Oracle

Record the application that produced each resolver-bearing cell, its exact occurrence, ordinary inputs, supplied demand, actual output coverage, and consumed producer-owned fields. Do not union cache-hit predictions into that record.

Run selective producers so they return only requested coverage; full outputs can mask incomplete demand. Compare against actual execution or a trusted complete-output baseline, including data, errors, arguments, concrete targets, application counts, and completion of all published state.

### Retain Focused Acceptance Cases

At minimum retain: split-prediction rejection; sibling convergence; `foo$bridge` plus per-occurrence `$node` lowering; lazy type-plan dependencies; legal cycle backedges; abstract concrete recursion; independent list items; raw checker reads; alias/field/`GroundKey` identity; null and error ancestry; explicit missing-writer failure; and variable late-equality rejection or conservative coverage.

### Use Generated Tests Carefully

Generated coverage should combine convergence, nested requirements, query and object targets, abstract types, lists, aliases, arguments, variables, directives, fragments, cycles, nodes, failures, and nulls. Preserve explicit seeds and full generated descriptors, shrink failures, and keep focused mutations capable of invalidating the oracle.

Random volume is finite evidence. Resolver04 and Resolver05 both passed broad or 10,000-case campaigns before focused counterexamples invalidated their designs.

### Inspect Plans And State

Assert that dependencies have explicit owners, targets, guards, and provenance; producer demand is sealed before dispatch; alternatives are bounded; cycles are diagnosed; occurrence identities explain convergence; and excluded features are visible.

Useful measurements include plan size, possible versus activated alternatives, over-selection, applications per exact occurrence, missing-demand or excluded-scope frequency, batching quality, blocked durations, and discrepancies among supplied demand, coverage, and consumption.

## Multiple-Materialization Prior Art

The MAT and `KeyTree` work model runtime demand and coverage as typed trees of exact OER keys. Union combines demand, difference finds missing coverage, and paths preserve concrete types, arguments, and list positions. This is valuable infrastructure and a useful contrast to flat coordinates or GraphQL strings.

`KeyTree` does not by itself encode consumer provenance, producer ownership, scheduling prerequisites, raw-versus-checked reads, target scope, guarded alternatives, or whether two paths share one producing application. MAT can fetch missing coverage after demand arrives; one-shot planning must instead seal complete in-scope demand before the first application.

Durable lessons are to record actual coverage, make missing demand explicit, preserve exact path identity, distinguish materializations and failures, detect missing writers, and avoid assuming that a coverage tree is also a dependency graph.

## Resolver04 And Resolver05 Retrospective

Resolver04 explored runtime `FromObjectField` evaluation, symbolic demand, retained raw sources, and widening of already-built subtrees. Widening could add a newly distinct descendant key after a binding, but it could not repair late equality: if `field(arg: "literal") { forProvider }` ran before `field(arg: $value) { forConsumer }` became the same exact key, the first selective projection had already discarded `forConsumer`. A second application violated one-shot; post-application union could not recover the missing value.

Resolver05 tried to reconstruct occurrence aliases and passed a 10,000-case variable-enabled campaign. Focused recursive, passive-deepening, and late-binding cases showed that alias reconstruction was unstable: variable identity belongs to the exact defining resolver occurrence, and nested variables must be stamped and instantiated as each direct fragment layer becomes active.

The project retained declaration compilation, provider containment, exact occurrence paths, template-to-stamp variable identity, branch-order validation, and the counterexamples. It removed runtime provider evaluation, source-retention widening, and the Resolver04/05 constructors. Passing stress campaigns remain useful coverage evidence, not correctness arguments.

The durable rules are:

- Symbolic selections and exact OER cells are different domains.
- Substitution precedes exact-key grouping.
- Late equality is a producer-demand problem, not only key deduplication.
- Selective producer demand is sealed before application.
- Contained provider paths add value-flow order without inventing structural branches.
- Provider production and ordinary resolver dependencies need one diagnosable ordering relation.
- Occurrence identity includes recursive objects and list positions.
- Extensional result correctness and one-shot producer completeness are separate obligations.
- Runtime bindings and pending work belong in explicit monotonic execution state when needed.

## Questions For Future Designs

1. What exact feature scope receives the one-shot guarantee?
2. How is out-of-scope demand rejected or isolated?
3. Which demand paths converge on one exact OER cell?
4. Which dependencies are bounded statically and which values bind at runtime?
5. How are provider targets, concrete alternatives, and ancestor targets represented?
6. How are aliases, directives, fragments, internal demand, resolver-visible demand, and completion kept distinct?
7. How are cycles classified and diagnosed?
8. Why must every unfinished valid state have ready work?
9. How do failures complete every dependent?
10. What schedule-independence result justifies concurrency?
11. What evidence compares the design with existing execution?
12. What measurements expose over-selection, repeated work, and fallback?

## References

These links preserve the research trail; proposals and implementation reviews are historical evidence rather than current conclusions.

### Research And Proposals

- [Query Execution Revisited session index](https://docs.google.com/document/d/1L8oGjvvcSMZNkY6ooL78l0K_92f84SLUuGFdf3S3cA8/edit?tab=t.0)
- [RFC-254: ctx-selections and alternative tabs](https://docs.google.com/document/d/1aXmtEPIQx0xD35kBYyePb2sqnzOjI5GQSB-5STkxHVk/edit)
- [RFC-246: Selective vs Non-Selective Resolvers](https://docs.google.com/document/d/1rr1KSMe4okF3C_mci17GO4vnP5kCbZ049TbJ5IDC_jI/edit)
- [Selective Resolvers discussion #399](https://github.com/airbnb/viaduct/discussions/399)
- [Resolver OSS build-time plan](https://slate.airbnb.tools/9nREbww0kY)
- [Initial OSS correctness review](https://slate.airbnb.tools/Z8YyTAUXin)
- [Review of PR #1090492](https://slate.airbnb.tools/mKRh5cyEBw)
- [OSS demand-closure handoff](https://slate.airbnb.tools/iDijcqjfQ6)
- [Viaduct Modern Tenant API Spec](https://docs.google.com/document/d/1DSsqbNKAMAKTxn2QdSdOQYX4QJcrtX__PQrycRNeGcQ/edit)

### Implementation Lineage

- [#1085244: Record resolver OSS at build time](https://git.musta.ch/airbnb/treehouse/pull/1085244)
- [#1088747: Project resolver-owned output selections](https://git.musta.ch/airbnb/treehouse/pull/1088747)
- [#1090492: Compute OSS-bounded resolver demand](https://git.musta.ch/airbnb/treehouse/pull/1090492)
- [#1086215: QueryPlan KeyTree projection](https://git.musta.ch/airbnb/treehouse/pull/1086215)
- [#1086532: MAT ledger](https://git.musta.ch/airbnb/treehouse/pull/1086532)
- [#1089236: Remove selective OER keys](https://git.musta.ch/airbnb/treehouse/pull/1089236)
- [#1089960: Deep arbitrary suite](https://git.musta.ch/airbnb/treehouse/pull/1089960)
- [#1061282: Preserve type constraints](https://git.musta.ch/airbnb/treehouse/pull/1061282)
- [#1064433: Normalized child plans](https://git.musta.ch/airbnb/treehouse/pull/1064433)
- [#1079007: Retain skipped fragments](https://git.musta.ch/airbnb/treehouse/pull/1079007)

### Specifications

- [Viaduct output selection sets](https://viaduct.airbnb.tech/docs/developers/resolvers/?h=output+selection#output-selection-sets)
- [Viaduct node responsibility sets](https://viaduct.airbnb.tech/docs/developers/resolvers/node_resolvers/#responsibility-set)
- [GraphQL CollectFields](https://spec.graphql.org/draft/#CollectFields)
- [GraphQL fragment applicability](https://spec.graphql.org/draft/#sec-Fragment-Spread-Is-Possible)
- [GraphQL variables](https://spec.graphql.org/draft/#sec-Language.Variables)
