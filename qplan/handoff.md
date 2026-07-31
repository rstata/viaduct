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

Resolver02 deliberately models non-selective resolvers through two private `outputSelectionForest(demand)` extensions, one for field resolvers and one for node resolvers. Before applying the public selective API, it adds the resolver's demand-relative OSS to the supplied selections. The OSS includes every owned field along acyclic paths and uses locally closed supplied demand to bound recursive ownership paths. Resolver02's deterministic, recursive, arbitrary, and mutation-control tests pass.

[`semantics.resolver03`](./semantics/src/main/kotlin/semantics/resolver03/Resolver.kt) is the cloned selective experiment. It retains Resolver02's local demand closure and dependency ordering but omits the private OSS expansion, so it supplies only currently known selections to each resolver. Its copied deterministic and arbitrary tests intentionally fail with missing passive values; this is the counterexample state from which the selective one-shot cycle must be solved. Consequently the full `./gradlew check` is intentionally red, while the Resolver01 and Resolver02 focused suites remain green.

The immediate next step is to make Resolver03 aggregate complete transitive demand before applying a genuinely selective resolver. That work must not restore raw-function access, move demand closure into `snipToDemand`, or reuse Resolver02's private non-selective helper as the selective solution.

Start with a pure semantic operation that transforms an initial `SelectionForest` into a guarded transitive resolver-demand forest without receiving a `Value.Output`. The operation must rebase each activated field resolver's object-relative `objectFragment` onto the path from the producer to that resolver occurrence. While lifting the fragment, preserve its exact `Value.Key` coordinates and arguments, the ancestor and local `possibleTypes` guards at the coordinates they constrain, and the ownership boundary at which each obligation can be fulfilled. The key open design question is what explicit path, ancestry, and ownership context this recursive operation needs in addition to the forest itself.

Compute the result as a finite least fixed point: begin with the producer's requested selections, add the guarded object-fragment requirements of every resolver occurrence those selections can activate, traverse the newly added requirements for further resolver occurrences, and stop when no new obligations remain. Follow only paths introduced by the operation or by resolver fragments; do not enumerate the producer's OSS or unfold a recursive schema independently of those finite paths. `ExecutorRegistry.mayDemandFrom` may bound or order this process, but its schema-site relation is not a sufficient representation because the result must remain path-shaped and retain guards, arguments, and target ownership.

Pre-dispatch demand need not be minimal as an unguarded field set when the producer determines the concrete type. The intended criterion is guarded minimality: preserve the finite union of conditionally necessary alternatives, and require specialization under each concrete runtime type assignment to yield exactly the demand needed for that assignment. If such guarded alternatives cannot be bounded without observing the result, the remaining choices are explicit conservative over-selection, separating type discovery from materialization, or excluding the shape from the one-shot domain; applying the same producer again is not the target.

Drive the first derivation with the deterministic Resolver03 counterexamples before using the arbitrary corpus:

- `viewer { greeting }`: `greeting` needs `displayName`, which needs passive `firstName` and `lastName`.
- `viewer { message }`: `message` needs `profile { rendered }`, and `rendered` needs passive `raw`.
- `chain { computed }`: `computed` needs `next { label }`; closure must discover this finite recursive path without unfolding `Chain.next` indefinitely.

Test the pure guarded-demand operation directly on those shapes, then apply it before each Resolver03 field and node resolver call, rerun the deterministic tests, and finally run the arbitrary property test covering interfaces, unions, lists, arguments, field resolvers, and node resolvers. Retain nested node `id` bridges and the distinct node-resolver root ownership rule. Keep Resolver02 unchanged, keep `snipToDemand` a projection of exactly its supplied demand, do not weaken correctness predicates to infer missing values, and defer Resolver03 theorem claims until the constructor is true. Keep the eventual one-shot claim scoped to runtime producer identity rather than plan occurrence.

Useful verification commands are:

```sh
./gradlew :model:test :arbitrary:test :semantics:test \
  --tests 'semantics.correctresolution.*' \
  --tests semantics.WorldInjectionTest \
  --tests semantics.resolver01.ResolverTest \
  --tests semantics.resolver02.ResolverTest

./gradlew :semantics:test --tests semantics.resolver03.ResolverTest
```

At this counterexample checkpoint the first command is green. The second compiles Resolver03 and reports six tests completed with five `MissingFieldException` failures; only the `__typename` test passes. The full `./gradlew check` is therefore intentionally red.

The next-next step, after Resolver03 works for selective resolvers, is to return to the theorem program in [`semantics/theorems.md`](./semantics/theorems.md): establish the remaining `correctResolution` conjuncts for the relevant resolver constructors and then assemble those results into overall correctness theorems. The existing theorem remains specifically scoped to Resolver02's resolver-demand closure and is not evidence that Resolver03 is correct.

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

[`resolver03`](./semantics/src/main/kotlin/semantics/resolver03/Resolver.kt) is structurally identical through local closure, dependency ordering, materialization, and recursive resolution. Its field and node resolver applications omit Resolver02's OSS expansion and pass only the selections known at that point. Demand discovered after entering a produced object can therefore refer to passive values already removed by `snipToDemand`, which is why Resolver03's tests are intentionally red.

### Correctness Theorems

[`semantics/theorems.md`](./semantics/theorems.md) records informal mathematical claims and supporting arguments; it is not a machine-checked proof artifact. Its current theorem is specific to Resolver02 and claims that, in its stated domain, Resolver02 produces a result satisfying `isClosedUnderResolverDemand`.

That theorem does not concern Resolver03, does not establish full `correctResolution`, and does not prove selective one-shot execution. Further theorem work remains deferred until Resolver03's selective constructor is no longer false.

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
