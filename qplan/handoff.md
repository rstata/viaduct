# Correct OER Specification Handoff

## Purpose

This is the handoff for continuing work on the query-planning model. It should let a new agent understand the current semantic boundary, what the repository already establishes, and the next reasoning task without reconstructing the chronology that produced those decisions.

Read [Query Plan Research](./evergreen.md) for the durable production evidence, vocabulary, hard cases, correctness obligations, and validation strategy. [An Idealized Viaduct Query Execution Model](./viaduct-execution.md) for a description of Viaduct's execution model.  [AGENTS.md](./AGENTS.md), [`model/AGENTS.md`](./model/AGENTS.md), and [`semantics/AGENTS.md`](./semantics/AGENTS.md) describe the current state of our modeling effort plus guidelines and procedures for updating that state; read these files before making any modifications.

## Long Term Goal

The long-term goal is a query-plan and query-execution design that supports one-shot resolver execution. For every demanded runtime producer identity in the supported scope, the design should aggregate complete in-scope demand before dispatch and invoke that producer at most once. One-shot is per runtime producer identity, not per schema coordinate: distinct objects, list items, argument tuples, concrete types, or execution epochs may still require distinct invocations. Repeatedly invoking or materializing the same producer identity as later demand appears is a contrasting approach, not the target.

The current model is building a plan-independent correctness judgment over `EngineResult.Object`. That judgment should characterize valid field-resolution results without mentioning planner nodes, readiness, dependency counts, or execution order, so the eventual one-shot plan and executor can be judged against it. OER coordinates are `Value.Key` values carrying canonical schema output fields and fully coerced arguments; response aliases, response keys, response ordering, and external response assembly belong to field completion and remain outside the model. [Query Plan Research](./evergreen.md) records the production motivation, one-shot proof obligations, and hard cases.

## Next Step Goal

The latest experiment completed [`semantics.resolver02`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt), extending the naive depth-first constructor to field resolvers with nonempty `objectFragment`s. At each concrete object, it closes the local selection forest under resolver demand, orders exact sibling `Value.Key` coordinates by their dependencies, resolves them in that order, and materializes each resolver's fragment input from the partial OER established by its predecessors. Recursive descent handles demand introduced below a sibling independently at that nested object.

The next step is not `resolver03`. Before beginning adversarial validation, complete two quick preparation tasks:

- Have the project owner manually review the latest `resolver02`, sibling-demand, and fragment-materialization code.
- Investigate lightweight DSLs for constructing schemas, selections, fragments, resolver registries, values, and expected OERs so subsequent test cases can state their semantic scenario fluently without repetitive fixture mechanics.

Then begin adversarial validation of `resolver02` and the model around it. The validation plan is intentionally not yet complete, but should combine three mutually supporting lines of work:

- Add focused unit tests that deliberately stress the boundaries of the current scope and the interactions among exact argument tuples, duplicate selection occurrences, type conditions, variables and argument errors, nulls, lists, passive nested objects, node references, and transitive object-fragment demand.
- Investigate porting or adapting Viaduct's `arb` system so property tests can generate coherent schemas, registries, fragments, resolver values, and runtime types. The central generated property should be that resolving an in-domain Query fragment yields an OER satisfying `correctResolution`; generators should also shrink failures into useful counterexamples.
- Establish a rigorous argument, with an exact statement of the constructor's domain, that `resolver02` produces a correct resolution. Likely intermediate obligations include termination of local demand closure, existence of the dependency order under registry acyclicity, completeness of each materialized resolver input, preservation of external fragment coverage, closure under transitive resolver demand, and conformance to every resolver. Record stable propositions in `claims.md` and substantive arguments in `arguments/`, explicitly distinguishing proof from finite test evidence.

This phase should be willing to find that either the constructor or the current correctness judgment is wrong or underspecified. Counterexamples should first sharpen the theorem's domain or expose a missing invariant; only then should the implementation or model be changed. One-shot planning remains the long-term destination, but `resolver02` should become a trustworthy reference construction before it is used as an oracle or comparison point.

## Current Model

### World And Schema

Each reasoning exercise fixes exactly one `Assumptions` and one canonical `Schema`. `Assumptions` supplies the schema, variable bindings, executor registry, and concrete-field `behavioral` predicate. Parsing validated named fragments into a nominal type and flattened `SelectionForest` is test-fixture or composition infrastructure outside the semantic model. There are no JVM-global schema or variable declarations.

Every schema definition has one canonical object, so ordinary `==` means that two definitions from that schema denote the same schema element; cross-schema equality is outside the model. Every non-error `Value`, `Value.Arguments`, and `Value.Key` is constructed through a factory on its precise semantic category. `Value.Error` is schema-independent. Reusable schema-conformance relations live in [`model.invariants`](./model/src/main/kotlin/model/invariants/SchemaConformance.kt), and factory KDocs state the carrier-invariant postconditions they establish; because carrier implementations are sealed behind those factories, these postconditions are universally quantified over constructed values in the fixed world.

`Value.Key` is the shared alias-free coordinate for selections, resolved object values, and OER cells. It contains a canonical output field and its coerced arguments. `Value.Object.fieldValues` is a `Value.ObjectFields` map keyed by `Value.Key`, while `EngineResult.Object.keys` is the set of `Value.Key` coordinates whose cells are present. Every key present in either value carries a field owned by that value's concrete `Schema.ObjectType` and contains no unresolved variables; keys outside those values may carry abstract-type fields or unresolved variables. A `Value.Object` can therefore contain multiple values for one output field under distinct argument tuples.

`EngineResult.Object`, `EngineResult.List`, and `EngineResult.Cell` are logic-constructible model types backed by private data-class implementations and constructed by their respective factories; they are not externally supplied implementation points. An `EngineResult.Object` is a finite, structurally comparable value tree. A present object field and each list element has one `EngineResult.Cell` containing a nullable value and a check result; absence differs from a present null. Object and list factories eagerly establish recursive schema conformance, and list results carry their element `typeExpr` even when empty. `EngineResult.Object.nodeRef(idField, id)` constructs the one-cell OER node reference after checking that the field is the canonical `id` field of a concrete Node type.

`EngineResult?.union` is a partial structural operation. Object union requires equal object types, retains cells found in only one operand, and recursively unions cells found in both. List union is positional and requires equal element `typeExpr` values and lengths. Shared cells require equal check values; simple values union only when equal, and null unions only with null. An undefined union is represented by `IllegalArgumentException`.

### Selections And Fragments

`SpecSelection` represents GraphQL-shaped, post-validation selections solely to state their parity with the field-resolution model. [`SpecSelectionFlattener.kt`](./model/src/main/kotlin/model/spec/SpecSelectionFlattener.kt) maps them into field-resolution `Selection` occurrences. Ordinary test fixtures perform this conversion internally and expose only the nominal type and flattened `SelectionForest`.

A `Selection` carries a canonical `Value.Key`, nominal composite type, possible concrete parent types, and nested selections. Inline fragment structure has been flattened into the nominal and possible-type information. A `SelectionForest` is an equality-free finite occurrence family: source order is erased while occurrence multiplicity is preserved.

Semantic equality for `Selection` is intentionally undefined. `SelectionForest` therefore exposes permutation-invariant occurrence operations without membership, deduplication, hashing, equality-based counting, or forest equality.

A `Fragment` contains a nominal composite type and a flattened `SelectionForest`. A field resolver's `objectFragment` describes the object-valued input that must be resolved before invoking its selection-independent function.

### Resolver Interpretation

The executor registry fixes node and field resolvers for the world. A field resolver stores its required `objectFragment` and a function from the resolved fragment value and coerced arguments to a nullable, selection-independent `Value.Output`. A node resolver stores a selection-independent lookup from `Value.ID` to `Value.Object`; every returned object contains the canonical argumentless `id` field equal to the lookup ID.

`snip` supplies the conceptual additional selection input by projecting a resolver's fixed output result. Holding the ordinary function inputs fixed therefore produces coherent projections for different requested selections.

For a concrete field, `behavioral(field)` is true for engine-supplied `__typename`, an explicit field resolver, and every non-`id` field on a type with a node resolver. Field-resolver projection retains selected passive fields and stops at behavioral boundaries, leaving only the `id` bridge of a nested node reference. Node-resolver projection has a distinct root rule: it omits root `id`, `__typename`, and explicit field-resolver fields while retaining fields supplied by that node resolver. The raw node-resolver value repeats its input `id`, but that normalization does not attribute the root `id` OER cell to the node resolver.

### Naive Depth-First Resolution

[`resolver01`](./semantics/src/main/kotlin/semantics/resolver01/Resolver.kt) groups applicable selection occurrences by their specialized concrete `Value.Key`, yielding one OER cell per merged field. Argument errors produce `EngineResult.Cell.Error`. A registered field resolver is applied to an empty object of the containing type and the field's coerced arguments; an unregistered field is read from the current resolver value because it remains in the producing resolver's output selection set. Nullable values, simple values, lists, and passive objects are traversed structurally.

When traversal encounters an object type with a node resolver, that value is interpreted as a node reference containing its canonical non-error `id`. The node resolver is applied to that ID, its selection-independent result is recursively resolved, and the result is unioned with `EngineResult.Object.nodeRef`. The node resolver's raw object repeats the input ID, so a selected `id` must agree under union, while the explicit node reference retains the addressing cell even when `id` was not selected.

This constructor is intentionally depth-first and may apply a resolver again when recursive demand reaches it again; it is not the one-shot execution design. [`resolver01/ResolverTest.kt`](./semantics/src/test/kotlin/semantics/resolver01/ResolverTest.kt) starts from an empty Query object and demonstrates that the initial construction satisfies `correctResolution` when all activated field-resolver object fragments are empty.

[`resolver02`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt) closes each concrete object's selections under the top-level object-fragment demand of activated field resolvers. [`Schema.OutputField.demandsFromSibling`](./model/src/main/kotlin/model/registry/SiblingDemand.kt) relates a consumer field to an exact sibling `Value.Key`, preserving argument tuples while interpreting type conditions and variable bindings. A local topological order ensures every required sibling subtree is present before [`materialize`](./semantics/src/main/kotlin/semantics/Materialize.kt) projects the resolver's exact input from the partial OER. Its tests cover transitive sibling dependencies and resolver demand nested below a required sibling.

### Resolver Demand

The externally supplied executor registry contains an acyclic resolver-demand graph over canonical `Schema.ResolverSite` elements. `Schema.ObjectType` and `Schema.OutputField` are site candidates; membership in one registry makes a candidate an actual resolver coordinate.

For a registered field site `f`, `registry.mayDemandFrom(f)` contains exactly the registered schema sites directly implicated by selections reachable from its resolver's object fragment. A selection directly implicates:

- the registered object-type site, when present, for every object type in `selection.possibleTypes`; and
- the registered output-field site, when present, at each concrete possible type combined with `selection.key.field.fieldName`.

`registry.mayBeDemandedBy(site)` is the exact transpose of `mayDemandFrom`. Pre-reasoning registry assembly rejects a self-cycle or longer demand cycle with `IllegalArgumentException`.

The intended demand interpretation starts with the registered sites directly implicated by an external selection forest and takes the least superset closed under `mayDemandFrom`. The relation is a conservative possibility relation because the selections that induce an edge retain type conditions that may not apply to the runtime concrete object.

This is a resolver-demand graph, not a graph of every invocation input, value provenance fact, or scheduling prerequisite. In particular, the `id` passed to a node resolver is an engine-supplied addressing input and does not create a demand edge. Object-type sites have no outgoing adjacency because node resolvers have no `objectFragment`, though field sites may demand from them.

The graph API is defined in [`ExecutorRegistry.kt`](./model/src/main/kotlin/model/registry/ExecutorRegistry.kt), while assembly and invariant checks are pre-reasoning fixture infrastructure. [`ResolverDemandTest.kt`](./model/src/test/kotlin/model/registry/ResolverDemandTest.kt) exercises nested reachability, polymorphic node and field implication, transposition, empty node demand, and cycle rejection.

## Current Scope

Inputs are post-validation and all named fragment spreads are assumed to have been inlined. The `TestWorld` parsing fixture accepts one named fragment definition as a parsing envelope, ignores its name, rejects nested named spreads, and supplies its nominal type and flattened `SelectionForest` to semantic reasoning.

`@skip` and `@include` belong to the eventual field-resolution model but are deferred. Applied directives are currently rejected. Query fragments, variable providers, `@parent`, lazy executor values, checkers, and raw-versus-checked dependency distinctions are also not yet modeled. `EngineResult.Cell.check` remains in the carrier algebra for that future work, but the initial `correctResolution` judgment is explicitly check-insensitive.

`correctResolution` is defined only when every variable needed by an applicable required-cell `Value.Key` can be instantiated from `world.variableValues`. Variables occurring only in guards or branches inapplicable to the judged runtime object do not restrict the judgment's domain. After instantiation, an argument tuple containing `Value.Error` requires the error cell mandated by the OER carrier invariant and does not invoke the registered field resolver.

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
