# Correct OER Specification Handoff

## Purpose

This is the handoff for continuing work on the query-planning model. It should let a new agent understand the current semantic boundary, what the repository already establishes, and the next reasoning task without reconstructing the chronology that produced those decisions.

Read [Query Plan Research](./evergreen.md) for the durable production evidence, vocabulary, hard cases, correctness obligations, and validation strategy. [AGENTS.md](./AGENTS.md), [`model/AGENTS.md`](./model/AGENTS.md), and [`semantics/AGENTS.md`](./semantics/AGENTS.md) describe the current state of our modeling effort plus guidelines and procedures for updating that state; read these files before making any modifications.

## Long Term Goal

The long-term goal is a query-plan and query-execution design that supports one-shot resolver execution. For every demanded runtime producer identity in the supported scope, the design should aggregate complete in-scope demand before dispatch and invoke that producer at most once. One-shot is per runtime producer identity, not per schema coordinate: distinct objects, list items, argument tuples, concrete types, or execution epochs may still require distinct invocations. Repeatedly invoking or materializing the same producer identity as later demand appears is a contrasting approach, not the target.

The current model is building a plan-independent correctness judgment over `ObjectEngineResult`. That judgment should characterize valid field-resolution results without mentioning planner nodes, readiness, dependency counts, or execution order, so the eventual one-shot plan and executor can be judged against it. OER coordinates are `Schema.ObjectKey` values carrying canonical schema output fields and fully coerced arguments; response aliases, response keys, response ordering, and external response assembly belong to field completion and remain outside the model. [Query Plan Research](./evergreen.md) records the production motivation, one-shot proof obligations, and hard cases.

## Next Step Goal

We have a fairly complete algebra at this point and a definition of correct resolution.  At this point we're a little stuck as to large next steps, so in each session we've just been experimenting with various ideas.  What we have identified is that the predicate `isClosedUnderResolverDemand` (`isClosed` for short) -- a subpredicate of `correctResolution` -- is what makes our problem a difficult one.  In particular, we are trying to understand how demand "flows" into the resolvers that are implied by an correctly-resolved `ObjectEngineResult`.

## Current Model

### World And Schema

Each reasoning exercise fixes exactly one `Assumptions` and one canonical `Schema`. `Assumptions` supplies the schema, variable bindings, executor registry, concrete-field `behavioral` predicate, and parsing of validated named fragments into `SpecSelection` values. Dependency-injection composition scopes the public world bindings as singletons; there are no JVM-global schema or variable declarations.

Every schema definition has one canonical object, so ordinary `==` means that two definitions denote the same schema element. Every non-error `Schema.Value`, `Schema.ArgumentsValue`, and `Schema.ObjectKey` is constructed through that schema. `Schema.ErrorValue` is schema-independent.

`Schema.ObjectKey` is the shared alias-free coordinate for selections, resolved object values, and OER cells. It contains a canonical output field and its coerced arguments. `Schema.ObjectValue.fieldValues` is a `Schema.ObjectFieldValues` map keyed by `Schema.ObjectKey`, while `ObjectEngineResult.keys` is the set of `Schema.ObjectKey` coordinates whose cells are present. Every key present in either value carries a field owned by that value's concrete `Schema.ObjectType` and contains no unresolved variables; keys outside those values may carry abstract-type fields or unresolved variables. A `Schema.ObjectValue` can therefore contain multiple values for one output field under distinct argument tuples.

`ObjectEngineResult` is a finite value tree. A present object field has one `Cell` containing a nullable value and a check result; absence differs from a present null. The carrier need not expose a public construction mechanism for the semantic layer to define a relation over its stipulated values; the absence of constructible OER witnesses is intentional for this step, not a gap to fill.

### Selections And Fragments

`SpecSelection` represents GraphQL-shaped, post-validation selections. [`SpecSelectionFlattener.kt`](./semantics/src/main/kotlin/semantics/spec/SpecSelectionFlattener.kt) flattens them into field-resolution `Selection` occurrences.

A `Selection` carries a canonical `Schema.ObjectKey`, nominal composite type, possible concrete parent types, and nested selections. Inline fragment structure has been flattened into the nominal and possible-type information. A `SelectionForest` is a Guava `Multiset`: source order is erased while occurrence multiplicity is preserved.

Semantic equality for `Selection` is intentionally undefined. Host-language equality used to construct a multiset is bookkeeping and must not be used for semantic deduplication, intersection, or membership reasoning.

A `Fragment` contains a nominal composite type and a flattened `SelectionForest`. A field resolver's `objectFragment` describes the object-valued input that must be resolved before invoking its selection-independent function.

### Resolver Interpretation

The executor registry fixes node and field resolvers for the world. A field resolver stores its required `objectFragment` and a function from the resolved fragment value and coerced arguments to a nullable, selection-independent `Schema.OutputValue`. A node resolver stores a selection-independent lookup from `Schema.IDValue` to `Schema.ObjectValue`.

`snip` supplies the conceptual additional selection input by projecting a resolver's fixed output result. Holding the ordinary function inputs fixed therefore produces coherent projections for different requested selections.

For a concrete field, `behavioral(field)` is true for engine-supplied `__typename`, an explicit field resolver, and every non-`id` field on a type with a node resolver. Field-resolver projection retains selected passive fields and stops at behavioral boundaries, leaving only the `id` bridge of a nested node reference. Node-resolver projection has a distinct root rule: it omits root `id`, `__typename`, and explicit field-resolver fields while retaining fields supplied by that node resolver.

### Resolver Demand

The executor registry derives an acyclic resolver-demand graph. Its nodes are registered `Resolver` objects, and every resolver object occurs at exactly one resolver coordinate.

For a field resolver `R`, `R.mayDemandFrom` contains exactly the resolvers directly implicated by selections reachable from `R.objectFragment`. A selection directly implicates:

- the node resolver, when present, for every object type in `selection.possibleTypes`; and
- the field resolver, when present, at each concrete possible type combined with `selection.key.field.fieldName`.

`R.mayBeDemandedBy` is the exact transpose of `mayDemandFrom`. The factory rejects a self-cycle or longer demand cycle with `IllegalArgumentException`.

The intended demand interpretation starts with the resolvers directly implicated by an external selection forest and takes the least superset closed under `mayDemandFrom`. The relation is a conservative possibility relation because the selections that induce an edge retain type conditions that may not apply to the runtime concrete object.

This is a resolver-demand graph, not a graph of every invocation input, value provenance fact, or scheduling prerequisite. In particular, the `id` passed to a node resolver is an engine-supplied addressing input and does not create a demand edge. Node resolvers have no `objectFragment`, so their `mayDemandFrom` sets are empty even though other resolvers may demand from them.

The graph and its checks are implemented in [`ExecutorRegistry.kt`](./model/src/main/kotlin/model/registry/ExecutorRegistry.kt). [`ResolverDemandTest.kt`](./model/src/test/kotlin/model/registry/ResolverDemandTest.kt) exercises nested reachability, polymorphic node and field implication, transposition, empty node demand, and cycle rejection.

## Current Scope

Inputs are post-validation and all named fragment spreads are assumed to have been inlined. `Assumptions.selectionsFrom` accepts one named fragment definition as a parsing envelope, ignores its name, and rejects nested named spreads.

`@skip` and `@include` belong to the eventual field-resolution model but are deferred. Applied directives are currently rejected. Query fragments, variable providers, `@parent`, lazy executor values, checkers, and raw-versus-checked dependency distinctions are also not yet modeled. `ObjectEngineResult.Cell.check` remains in the carrier algebra for that future work, but the initial `correctResolution` judgment is explicitly check-insensitive.

`correctResolution` is defined only when every variable needed by an applicable required-cell `Schema.ObjectKey` can be instantiated from `world.variableValues`. Variables occurring only in guards or branches inapplicable to the judged runtime object do not restrict the judgment's domain. After instantiation, an argument tuple containing `Schema.ErrorValue` requires the error cell mandated by the OER carrier invariant and does not invoke the registered field resolver.

Every argument-bearing output field is currently assumed to have an explicit field resolver. Production namespace exceptions are outside the model.

The registry currently rejects resolver-demand cycles. `evergreen.md` records legal production RSS cycles as an eventual hard case, so cycle rejection is a present scope constraint rather than a general claim about Viaduct.

Every non-`__typename` field on `Query` has an explicit field resolver. Field resolvers are registered only at concrete object-field coordinates, and node resolvers are registered only for object types that nominally implement the canonical `Node` interface.

## Design Constraints

Demand aggregates from consumers toward producers, while concrete type information becomes available from producers toward consumers. Do not make demand depend on first completing a scheduler decision about which concrete branch will execute. Preserve unresolved type conditions as guards until the concrete result can specialize them.

For example, if `expensive` applies only when `p` is a `B` and its object fragment requires passive field `helper`, the demand on `p` should retain the same guard:

```graphql
p {
  ... on B {
    helper
  }
}
```

This separates the semantic question, "What values must a correct result contain for each concrete type?", from the operational question, "How should an implementation schedule the work?" The current continuation addresses the semantic question first.

Requirements should be result-shaped rather than identified only by root-to-leaf syntax. Conceptually, guarded requirements describe a family:

```text
Demand : RuntimeTypeAssignment -> RequirementTree
```

Each complete runtime type assignment yields an ordinary monomorphic requirement tree. A compact implementation may share equivalent continuations, but sharing is a representation choice and is valid only when the continuation no longer depends on ancestry, target scope, ownership, or an ancestor type condition.
