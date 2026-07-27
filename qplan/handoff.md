# Correct OER Specification Handoff

## Purpose

This is the working handoff for continuing the query-planning model. It should let a new agent understand the current semantic boundary, what the repository already establishes, and the next reasoning task without reconstructing the chronology that produced those decisions.

Read [Query Plan Research](./evergreen.md) for the durable production evidence, vocabulary, hard cases, correctness obligations, and validation strategy. Read [AGENTS.md](./AGENTS.md), [`model/AGENTS.md`](./model/AGENTS.md), and [`semantics/AGENTS.md`](./semantics/AGENTS.md) before changing their respective domains.

## Long Term Goal

The long-term goal is a query-plan and query-execution design that supports one-shot resolver execution. For every demanded runtime producer identity in the supported scope, the design should aggregate complete in-scope demand before dispatch and invoke that producer at most once. One-shot is per runtime producer identity, not per schema coordinate: distinct objects, list items, argument tuples, concrete types, or execution epochs may still require distinct invocations. Repeatedly invoking or materializing the same producer identity as later demand appears is a contrasting approach, not the target.

The current model is building a plan-independent correctness judgment over `ObjectEngineResult`. That judgment should characterize valid field-resolution results without mentioning planner nodes, readiness, dependency counts, or execution order, so the eventual one-shot plan and executor can be judged against it. OER coordinates use canonical schema output fields and fully coerced arguments; response aliases, response keys, response ordering, and external response assembly belong to field completion and remain outside the model. [Query Plan Research](./evergreen.md) records the production motivation, one-shot proof obligations, and hard cases.

## Next Step Goal

The concrete next step is to define:

```text
world |- correctResolution(oer, fragment)
```

Here `fragment` is a post-validation, flattened `Fragment` whose nominal type is `world.schema.query`. It represents the external query demand. The judgment should determine whether `oer` is a correct field-resolution result under `world`, including demand induced transitively through registered resolvers' object fragments.

The `oer` argument is a stipulated element of the `ObjectEngineResult` carrier: for this exercise it may be understood as having been constructed magically. The task is to define a predicate on such OERs, not an algorithm or API that produces them. Do not add an OER factory, map-backed OER implementation, builder, or test fixture in order to make candidate OERs constructible.

This step should define correctness directly, supported by focused examples of polymorphic and converging demand. It does not need separate minimal or maximal predicates. It should preserve unresolved type conditions as guards and remain entirely plan-independent; planner state, readiness, and scheduling belong only after `correctResolution` provides a stable target. In the current checker-free scope, `correctResolution` must not observe `ObjectEngineResult.Cell.check`: replacing only check components while preserving the OER's types, present keys, and cell values cannot change the judgment.

For now, the objective is the definition itself. Do not add executable tests for `correctResolution`; careful human inspection of the definition and its worked examples is the validation strategy at this stage. The repository's existing test volume and its general use of finite checks do not imply that this judgment needs constructible OER witnesses.

## Repository

The repository has two Gradle projects:

- [`model/src/main/kotlin/model/`](./model/src/main/kotlin/model/) defines carrier values, one-world assumptions, resolver registrations, and their invariants.
- [`model/src/test/kotlin/model/`](./model/src/test/kotlin/model/) contains model examples and finite checks.
- [`semantics/src/main/kotlin/semantics/`](./semantics/src/main/kotlin/semantics/) defines transformations, predicates, and reasoning over the model.
- [`semantics/src/test/kotlin/semantics/`](./semantics/src/test/kotlin/semantics/) contains semantic examples and finite checks.

Run `./gradlew check` for the complete repository verification.

## Current Model

### World And Schema

Each reasoning exercise fixes exactly one `Assumptions` and one canonical `Schema`. `Assumptions` supplies the schema, variable bindings, executor registry, concrete-field `behavioral` predicate, and parsing of validated named fragments into `SpecSelection` values. Dependency-injection composition scopes the public world bindings as singletons; there are no JVM-global schema or variable declarations.

Every schema definition has one canonical object, so ordinary `==` means that two definitions denote the same schema element. Every non-error `Schema.Value`, argument tuple, and OER key is constructed through that schema. `Schema.ErrorValue` is schema-independent.

`ObjectEngineResult` is a finite value tree. A present object field has one `Cell` containing a nullable value and a check result; absence differs from a present null. Every key present in an OER carries a field owned by a concrete `Schema.ObjectType`. Keys outside an OER may carry abstract-type fields or unresolved variables. The carrier need not expose a public construction mechanism for the semantic layer to define a relation over its stipulated values; the absence of constructible OER witnesses is intentional for this step, not a gap to fill.

### Selections And Fragments

`SpecSelection` represents GraphQL-shaped, post-validation selections. [`SpecSelectionFlattener.kt`](./semantics/src/main/kotlin/semantics/spec/SpecSelectionFlattener.kt) flattens them into field-resolution `Selection` occurrences.

A `Selection` carries a canonical OER key, nominal composite type, possible concrete parent types, and nested selections. Inline fragment structure has been flattened into the nominal and possible-type information. A `SelectionForest` is a Guava `Multiset`: source order is erased while occurrence multiplicity is preserved.

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

`correctResolution` is defined only when every variable needed by an applicable required-cell key can be instantiated from `world.variableValues`. Variables occurring only in guards or branches inapplicable to the judged runtime object do not restrict the judgment's domain. After instantiation, an argument tuple containing `Schema.ErrorValue` requires the error cell mandated by the OER carrier invariant and does not invoke the registered field resolver.

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

## Continuation

Implement the Query-rooted `correctResolution(oer, fragment)` judgment in the semantic layer:

```text
world |- correctResolution(oer, fragment) :=
    rootedAndWellTyped(oer, fragment)
    && oer.conformsToSchema()
    && oer.conformsToFragment(fragment)
    && oer.isClosedUnderResolverDemand()
    && oer.conformsToResolvers()
    && oer.conformsToTypename()
```

- `rootedAndWellTyped(oer, fragment)` establishes the judgment's domain: `fragment.nominalType == world.schema.query`, and `oer` is an object result whose canonical concrete type is `world.schema.query`.
- `oer.conformsToSchema()` establishes recursive schema conformance independently of demand: every present result conforms to its declared output type, including nullability, list structure, and concrete object type.
- `oer.conformsToFragment(fragment)` establishes correct shape: after specializing type conditions to each runtime concrete object and instantiating applicable keys, the OER contains every cell required by the external fragment. Null and error results stop recursive requirements, and an applicable key whose arguments contain `Schema.ErrorValue` requires its error cell without invoking its field resolver. This predicate is schema-blind: the input fragment is post-validation, so its compatibility with the schema is assumed rather than revalidated here.
- `oer.isClosedUnderResolverDemand()` establishes sufficient content: every activated resolver occurrence has the resolved input it requires. For a field resolver, its parent OER conforms to the resolver's `objectFragment`; these requirements apply transitively under the same type guards. An activated node resolver has its required `id` addressing bridge even though that bridge is not a resolver-demand edge.
- `oer.conformsToResolvers()` establishes resolver consistency: each activated field or node resolver agrees with the values attributed to it in the OER. Field-resolver inputs are the `Schema.ObjectValue` materialized from the parent according to its `objectFragment`; comparison with resolver output is recursive and ownership-aware, preserving exact scalar, null, error, list, and concrete-type structure while stopping where ownership passes to another resolver. This conjunct never observes `ObjectEngineResult.Cell.check`.
- `oer.conformsToTypename()` establishes engine-supplied type-name consistency: every present `__typename` cell contains the name of its OER object's canonical concrete type.

1. Establish the judgment's domain: `fragment.nominalType == world.schema.query`, and `oer` is the Query-rooted result being judged.
2. Define concrete type applicability for flattened selections and conversion of an applicable abstract selection key to its concrete OER field coordinate, including variable instantiation and the non-invoking error-cell rule for arguments containing `Schema.ErrorValue`.
3. Define the least guarded required-cell closure seeded by `fragment.subselections` and extended transitively through demanded resolvers' `objectFragment`s.
4. Define correctness recursively over object, list, and simple results, specializing guarded requirements with each concrete `Schema.ObjectType` and relating resolver-owned values to the registered resolver interpretations while never inspecting `ObjectEngineResult.Cell.check`.
5. Decide and document directly which additional OER cells, if any, `correctResolution` permits; do not introduce a separate minimality judgment first.

Keep these definitions in the semantic layer. They should consume the established model and trust its construction invariants rather than revalidating schema ownership or registry coherence.

Do not extend the model with OER construction support or add executable correctness tests as part of this continuation. If the predicate itself requires an OER observation that the carrier does not currently expose, identify and justify that requirement independently; test convenience is not sufficient justification for a model change.

Use focused worked examples as aids to human inspection, not as executable tests or proofs. The first examples should cover:

- a type-conditioned passive field;
- the guarded `B/helper` object-fragment case above;
- sibling object-fragment consumers whose demand converges on one producer;
- concrete implementations that induce different guarded demand at the same result position;
- a list containing more than one concrete object type; and
- required cells versus additional cells accepted or rejected by correctness; and
- otherwise identical OERs whose check components differ but receive the same correctness verdict.

## Open Questions

- What additional semantically valid cells, if any, should `correctResolution` permit?
- How should recursive correctness relate a field resolver's resolved `objectFragment` input to the output returned by its registered function?
- How should the later model admit legal demand cycles without confusing coordinate-level recursion with runtime occurrence identity?
- Which deferred conditions, especially directives and variable providers, must become guards in the same requirement representation?

Do not introduce planner state or scheduler transitions to answer these questions. The immediate objective is a plan-independent specification of correct field-resolution results.
