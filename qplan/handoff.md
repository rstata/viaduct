# Correct OER Specification Handoff

## Purpose

This is the handoff for continuing work on the query-planning model. It should let a new agent understand the current semantic boundary, what the repository already establishes, and the next reasoning task without reconstructing the chronology that produced those decisions.

Read [Query Plan Research](./evergreen.md) for the durable production evidence, vocabulary, hard cases, correctness obligations, and validation strategy. [An Idealized Viaduct Query Execution Model](./viaduct-execution.md) for a description of Viaduct's execution model.  [AGENTS.md](./AGENTS.md), [`model/AGENTS.md`](./model/AGENTS.md), and [`semantics/AGENTS.md`](./semantics/AGENTS.md) describe the current state of our modeling effort plus guidelines and procedures for updating that state; read these files before making any modifications.

Compiling Kotlin is the project's present mathematical modeling language, serving the same kind of specification role that TLA+ could serve rather than describing a JVM implementation. The model should also be maintained as a blueprint for a possible future translation into TLA+: its carrier sets, functions, relations, invariants, domain assumptions, and theorem statements should be explicit enough to become a formal specification with corresponding machine-checked proof obligations.

No TLA+ translation or machine-checked proof currently exists. The theorem statements and supporting arguments in [`semantics/theorems.md`](./semantics/theorems.md) are informal mathematical reasoning about the Kotlin model, while compilation, examples, and tests provide only finite consistency and counterexample-finding evidence; a future TLA+ translation and its mechanically checked proofs would be separate artifacts.

## Long Term Goal

The long-term goal is a query-plan and query-execution design that supports one-shot resolver execution. For every demanded runtime producer identity in the supported scope, the design should aggregate complete in-scope demand before dispatch and invoke that producer at most once. One-shot is per runtime producer identity, not per schema coordinate: distinct objects, list items, argument tuples, concrete types, or execution epochs may still require distinct invocations. Repeatedly invoking or materializing the same producer identity as later demand appears is a contrasting approach, not the target.

The current model is building a plan-independent correctness judgment over `EngineResult.Object`. That judgment should characterize valid field-resolution results without mentioning planner nodes, readiness, dependency counts, or execution order, so the eventual one-shot plan and executor can be judged against it. OER coordinates are `Value.Key` values carrying canonical schema output fields and fully coerced arguments; response aliases, response keys, response ordering, and external response assembly belong to field completion and remain outside the model. [Query Plan Research](./evergreen.md) records the production motivation, one-shot proof obligations, and hard cases.

## Next Step Goal

The current validated constructors are [`semantics.resolver01`](./semantics/src/main/kotlin/semantics/resolver01/Resolver.kt) and [`semantics.resolver02`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt). Raw selection-independent resolver functions are private to the model module. Resolver01 applies only the public selective `resolve(..., transitiveDemand)` operation, and its tests pass because every activated field-resolver object fragment is empty.

Resolver02 deliberately models non-selective resolvers through two private `outputSelectionForest(demand)` extensions, one for field resolvers and one for node resolvers. Before applying the public selective API, it adds the resolver's demand-relative OSS to the supplied selections. The OSS includes every owned field along acyclic paths and uses locally closed supplied demand to bound recursive ownership paths. Resolver02's deterministic, recursive, arbitrary, and mutation-control tests pass, and the full `./gradlew check` is green at this handoff.

The immediate next step is to clone Resolver02 into Resolver03 without this OSS demand expansion and solve pre-application transitive demand aggregation for genuinely selective resolvers. That work must not restore raw-function access, move demand closure into `snipToDemand`, or reuse Resolver02's private non-selective helper as the selective solution.

The next-next step, after Resolver03 works for selective resolvers, is to return to the theorem program in [`semantics/theorems.md`](./semantics/theorems.md): establish the remaining `correctResolution` conjuncts for the relevant resolver constructors and then assemble those results into overall correctness theorems.

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

The executor registry fixes node and field resolvers for the world. A field resolver stores its required `objectFragment` and privately stores a function from the resolved fragment value and coerced arguments to a nullable, selection-independent `Value.Output`. A node resolver privately stores a selection-independent lookup from `Value.ID` to `Value.Object`; every returned object contains the canonical argumentless `id` field equal to the lookup ID. Semantic resolver algorithms can apply only the public `resolve(..., transitiveDemand)` operations, which apply the private function and project its result with `snipToDemand`.

`snipToDemand` supplies the conceptual additional selection input by projecting a resolver's fixed output result to its demand. Holding the ordinary function inputs fixed therefore produces coherent projections for different demands. It does not close or otherwise expand that demand.

For a concrete field, `behavioral(field)` is true for engine-supplied `__typename`, an explicit field resolver, and every non-`id` field on a type with a node resolver. Field-resolver projection retains demanded passive fields and stops at behavioral boundaries, retaining the `id` bridge whenever it reaches a nested node reference even when `id` is not explicitly demanded. Node-resolver projection has a distinct root rule: it omits root `id`, `__typename`, and explicit field-resolver fields while retaining fields supplied by that node resolver. The raw node-resolver value repeats its input `id`, but that normalization does not attribute the root `id` OER cell to the node resolver.

### Naive Depth-First Resolution

[`resolver01`](./semantics/src/main/kotlin/semantics/resolver01/Resolver.kt) groups applicable selection occurrences by their specialized concrete `Value.Key`, yielding one OER cell per merged field. Argument errors produce `EngineResult.Cell.Error`. A registered field resolver is applied to an empty object of the containing type and the field's coerced arguments; an unregistered field is read from the current resolver value because it remains in the producing resolver's output selection set. Nullable values, simple values, lists, and passive objects are traversed structurally.

When traversal encounters an object type with a node resolver, that value is interpreted as a node reference containing its canonical non-error `id`. The node resolver is applied to that ID, its selection-independent result is recursively resolved, and the result is unioned with `EngineResult.Object.nodeRef`. The node resolver's raw object repeats the input ID, so a selected `id` must agree under union, while the explicit node reference retains the addressing cell even when `id` was not selected.

This constructor is intentionally depth-first and may apply a resolver again when recursive demand reaches it again; it is not the one-shot execution design. [`resolver01/ResolverTest.kt`](./semantics/src/test/kotlin/semantics/resolver01/ResolverTest.kt) starts from an empty Query object and demonstrates that the initial construction satisfies `correctResolution` when all activated field-resolver object fragments are empty.

[`resolver02`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt) closes each concrete object's selections under the top-level object-fragment demand of activated field resolvers. [`Schema.OutputField.demandsFromSibling`](./model/src/main/kotlin/model/registry/SiblingDemand.kt) relates a consumer field to an exact sibling `Value.Key`, preserving argument tuples while interpreting type conditions and variable bindings. A local topological order makes already-discovered sibling inputs available to [`materialize`](./semantics/src/main/kotlin/semantics/Materialize.kt). Before applying a resolver, Resolver02 adds the resolver's private, demand-bounded `outputSelectionForest` to its supplied demand, making the selective model API expose the complete output of the deliberately non-selective resolver case.

### Correctness Theorems

[`semantics/theorems.md`](./semantics/theorems.md) is the working record for informal correctness theorem statements and supporting arguments about the semantic resolver functions. It is organized as one second-level section per theorem. Each theorem section begins with `### Claim`, which states the resolver, its domain assumptions, and the predicate established for its result; continues with `### Proof structure`, which names the supporting lemmas and explains how their conjunction supports the claim; and then gives one `### Lemma N: Descriptive Title` subsection per lemma, containing both the lemma claim and its argument. This decomposition should expose useful boundaries for a possible future TLA+ formalization, but it is not itself a machine-checked proof.

The proof program treats the conjuncts of [`correctResolution`](./semantics/src/main/kotlin/semantics/correctresolution/CorrectResolution.kt) independently before combining them. This keeps schema/root compatibility, fragment coverage, resolver-demand closure, resolver-value conformance, and `__typename` conformance from becoming branches of one oversized induction. A theorem may strengthen its induction hypothesis with auxiliary properties such as selection coverage or root-relaxed node closure when the target predicate depends on them.

The first recorded theorem states that `resolver02`, applied to an empty Query value and a Query-rooted fragment in its domain, yields an OER satisfying `isClosedUnderResolverDemand`. Its proof decomposition is:

- **Down, transitive demand inclusion:** closing an object's selection forest includes every applicable selection required by every activated field resolver, including requirements introduced transitively by added resolver selections.
- **Across, dependency availability:** before a key is resolved, every sibling subtree required by its object fragment is already resolved and present in the prefix OER, so materializing the resolver input is defined.
- **Up, recursive satisfaction:** each resolved cell value satisfies all subselections accumulated for its key, and every nested object or list is itself resolver-demand closed; node references receive their canonical `id` bridge through `resolveNode`.

The Across lemma establishes that `resolver02` can construct the result without reading absent input. The extensional `isClosedUnderResolverDemand` conclusion follows from the Down and Up lemmas: Down ensures every local resolver obligation is represented, while Up ensures those selections are satisfied and the same property holds recursively. The Across prefix induction and Up resolution-derivation induction are mutually supporting at a field-resolver key, and `theorems.md` states the decreasing measures that make this non-circular.

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
