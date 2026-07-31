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

The current validated constructors are [`semantics.resolver01`](./semantics/src/main/kotlin/semantics/resolver01/Resolver.kt) and [`semantics.resolver02`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt). The canonical model exposes only field resolvers and field resolver coordinates. Raw selection-independent field functions remain private behind `Resolver.Field`, while raw node lookup functions exist only as external test-fixture inputs that are lowered before the reasoning world is constructed.

Resolver01 now performs generic exact-key local closure, dependency ordering, and input materialization because fixture-generated node loaders have nonempty object fragments requiring synthetic sibling bridge fields. Ordinary source field resolvers in its test domain still have empty object fragments. Resolver02 uses one uniform private `outputSelectionForest(demand)` rule for every canonical field resolver; before applying the public selective API, it adds the resolver's demand-relative OSS to the supplied selections. Resolver01 and Resolver02's focused model, arbitrary, correctness, world-injection, deterministic, recursive, and mutation-control suites pass.

[`semantics.resolver03`](./semantics/src/main/kotlin/semantics/resolver03/Resolver.kt) is the cloned selective experiment. It retains Resolver02's local demand closure and dependency ordering but omits the private OSS expansion, so it supplies only currently known selections to each resolver. Its copied deterministic and arbitrary tests intentionally fail with missing passive values; this is the counterexample state from which the selective one-shot cycle must be solved. Consequently the full `./gradlew check` is intentionally red, while the Resolver01 and Resolver02 focused suites remain green.

The immediate next step is to make Resolver03 aggregate complete transitive demand before applying a genuinely selective resolver. That work must not restore raw-function access, move demand closure into `snipToDemand`, or reuse Resolver02's private non-selective helper as the selective solution.

Start with a pure semantic operation that transforms an initial `SelectionForest` into a guarded transitive resolver-demand forest without receiving a `Value.Output`. The operation must rebase each activated field resolver's object-relative `objectFragment` onto the path from the producer to that resolver occurrence. While lifting the fragment, preserve its exact `Value.Key` coordinates and arguments, the ancestor and local `possibleTypes` guards at the coordinates they constrain, and the ownership boundary at which each obligation can be fulfilled. The key open design question is what explicit path, ancestry, and ownership context this recursive operation needs in addition to the forest itself.

Compute the result as a finite least fixed point: begin with the producer's requested selections, add the guarded object-fragment requirements of every resolver occurrence those selections can activate, traverse the newly added requirements for further resolver occurrences, and stop when no new obligations remain. Follow only paths introduced by the operation or by resolver fragments; do not enumerate the producer's OSS or unfold a recursive schema independently of those finite paths. `ExecutorRegistry.mayDemandFrom` may bound or order this process, but its field-coordinate relation is not a sufficient representation because the result must remain path-shaped and retain guards, arguments, and target ownership.

Pre-dispatch demand need not be minimal as an unguarded field set when the producer determines the concrete type. The intended criterion is guarded minimality: preserve the finite union of conditionally necessary alternatives, and require specialization under each concrete runtime type assignment to yield exactly the demand needed for that assignment. If such guarded alternatives cannot be bounded without observing the result, the remaining choices are explicit conservative over-selection, separating type discovery from materialization, or excluding the shape from the one-shot domain; applying the same producer again is not the target.

Drive the first derivation with the deterministic Resolver03 counterexamples before using the arbitrary corpus:

- `viewer { greeting }`: `greeting` needs `displayName`, which needs passive `firstName` and `lastName`.
- `viewer { message }`: `message` needs `profile { rendered }`, and `rendered` needs passive `raw`.
- `chain { computed }`: `computed` needs `next { label }`; closure must discover this finite recursive path without unfolding `Chain.next` indefinitely.

Test the pure guarded-demand operation directly on those shapes, then apply it before each Resolver03 field-resolver call, rerun the deterministic tests, and finally run the arbitrary property test covering interfaces, unions, lists, arguments, ordinary field resolvers, and fixture-lowered node loaders. Preserve synthetic bridge coordinates and their exact argument tuples, but do not introduce node-specific semantic handling. Keep Resolver02 unchanged, keep `snipToDemand` a projection of exactly its supplied demand, do not weaken correctness predicates to infer missing values, and defer Resolver03 theorem claims until the constructor is true. Keep the eventual one-shot claim scoped to runtime producer identity rather than plan occurrence.

Useful verification commands are:

```sh
./gradlew :model:test :arbitrary:test :semantics:test \
  --tests 'semantics.correctresolution.*' \
  --tests semantics.WorldInjectionTest \
  --tests semantics.resolver01.ResolverTest \
  --tests semantics.resolver02.ResolverTest

./gradlew :semantics:test --tests semantics.resolver03.ResolverTest
```

At this checkpoint the first command is green. The second compiles Resolver03 and reports six tests completed with five `MissingFieldException` failures; only the `__typename` test passes. The node-lowering change did not fix that selective transitive-demand experiment, and the full `./gradlew check` is therefore intentionally red.

The next-next step, after Resolver03 works for selective resolvers, is to return to the theorem program in [`semantics/theorems.md`](./semantics/theorems.md): establish the remaining `correctResolution` conjuncts for the relevant resolver constructors and then assemble those results into overall correctness theorems. The existing theorem remains specifically scoped to Resolver02's resolver-demand closure and is not evidence that Resolver03 is correct.

## Current Model

### World And Schema

Each reasoning exercise fixes exactly one `Assumptions` and one canonical `Schema`. `Assumptions` supplies the schema, variable bindings, executor registry, and concrete-field `behavioral` predicate. Parsing validated named fragments into a nominal type and flattened `SelectionForest` is test-fixture or composition infrastructure outside the semantic model. There are no JVM-global schema or variable declarations.

Every schema definition has one canonical object, so ordinary `==` means that two definitions from that schema denote the same schema element; cross-schema equality is outside the model. Every non-error `Value`, `Value.Arguments`, and `Value.Key` is constructed through a factory on its precise semantic category. `Value.Error` is schema-independent. Reusable schema-conformance relations live in [`model.invariants`](./model/src/main/kotlin/model/invariants/SchemaConformance.kt), and factory KDocs state the carrier-invariant postconditions they establish; because carrier implementations are sealed behind those factories, these postconditions are universally quantified over constructed values in the fixed world.

`Value.Key` is the shared alias-free coordinate for selections, resolved object values, and OER cells. It contains a canonical output field and its coerced arguments. `Value.Object.fieldValues` is a `Value.ObjectFields` map keyed by `Value.Key`, while `EngineResult.Object.keys` is the set of `Value.Key` coordinates whose cells are present. Every key present in either value carries a field owned by that value's concrete `Schema.ObjectType` and contains no unresolved variables; keys outside those values may carry abstract-type fields or unresolved variables. A `Value.Object` can therefore contain multiple values for one output field under distinct argument tuples.

`EngineResult.Object`, `EngineResult.List`, and `EngineResult.Cell` are logic-constructible model types backed by private data-class implementations and constructed by their respective factories; they are not externally supplied implementation points. An `EngineResult.Object` is a finite, structurally comparable value tree. A present object field and each list element has one `EngineResult.Cell` containing a nullable value and a check result; absence differs from a present null. Object and list factories eagerly establish recursive schema conformance, and list results carry their element `typeExpr` even when empty. There is no distinct OER node-reference constructor in the canonical model.

`EngineResult?.union` is a partial structural operation. Object union requires equal object types, retains cells found in only one operand, and recursively unions cells found in both. List union is positional and requires equal element `typeExpr` values and lengths. Shared cells require equal check values; simple values union only when equal, and null unions only with null. An undefined union is represented by `IllegalArgumentException`.

### Selections And Fragments

`SpecSelection` represents GraphQL-shaped, post-validation selections solely to state their parity with the field-resolution model. [`SpecSelectionFlattener.kt`](./model/src/main/kotlin/model/spec/SpecSelectionFlattener.kt) maps them into field-resolution `Selection` occurrences. Ordinary test fixtures perform this conversion internally and expose only the nominal type and flattened `SelectionForest`.

A `Selection` carries a canonical `Value.Key`, nominal composite type, possible concrete parent types, and nested selections. Inline fragment structure has been flattened into the nominal and possible-type information. A `SelectionForest` is an equality-free finite occurrence family: source order is erased while occurrence multiplicity is preserved.

Semantic equality for `Selection` is intentionally undefined. `SelectionForest` therefore exposes permutation-invariant occurrence operations without membership, deduplication, hashing, equality-based counting, or forest equality.

A `Fragment` contains a nominal composite type and a flattened `SelectionForest`. A field resolver's `objectFragment(arguments)` describes the object-valued input that must be resolved before invoking its selection-independent function for that exact argument tuple. Ordinary resolvers return one fixed fragment; fixture-generated loaders use argument-dependent fragments so `foo(args)` requires the matching `foo$id(args)`.

### Resolver Interpretation

The executor registry fixes only field resolvers for the canonical reasoning world. A field resolver stores a representative `objectFragment`, provides its exact `objectFragment(arguments)`, and privately stores a function from the resolved fragment value and coerced arguments to a nullable, selection-independent `Value.Output`. Semantic resolver algorithms can apply only the public `resolve(..., transitiveDemand)` operation, which applies the private function and projects its result with `snipToDemand`.

`snipToDemand` supplies the conceptual additional selection input by projecting a resolver's fixed output result to its demand. Holding the ordinary function inputs fixed therefore produces coherent projections for different demands. It does not close or otherwise expand that demand.

For a canonical concrete field, `behavioral(field)` is true exactly when the field is engine-supplied `__typename` or has a registered field resolver. `snipToDemand` retains demanded passive fields and stops at those uniform behavioral boundaries. Synthetic `$id` bridges are ordinary internal fields: they are demanded explicitly by generated loader fragments and are not retained through an implicit node ownership rule.

### Fixture Node Lowering

`TestWorld.fromSDL` is the compiler boundary from ordinary GraphQL SDL and fragments plus raw field and node resolver functions into the canonical field-only world. GraphQL validation and parsing use the external schema, while the decoded internal schema additionally contains a synthetic `foo$id` field for every source field `foo` whose declared base output type is `Node` or implements `Node`. The bridge repeats `foo`'s arguments and replaces the named node type with `ID` while preserving every list and nullability layer.

For a source node-valued field `foo(args)` with a raw field resolver, fixture composition moves that producer to `foo$id(args)` and adapts the output from node references to typed IDs; passive nested node references are rewritten to bridge coordinates in their containing resolver's output. It installs a generated field resolver at every lowered `foo(args)` whose argument-dependent object fragment requires exactly `foo$id(args)` and whose function dispatches each typed ID to the raw node lookup. Arguments remain on both coordinates so distinct `Value.Key` argument tuples never collapse, and typed fixture IDs carry the concrete object type needed for abstract outputs and list elements.

The lowering recursively rewrites nested raw resolver outputs and translates resolver demand between source node fields and bridge fields. It rejects a field whose possible types mix node-resolved and inline objects, and it requires every possible type of a lowered abstract node output to have a raw node resolver. Arbitrary generation therefore registers every generated `Node` implementation and keeps generated non-`Node` interfaces and unions disjoint from `Node` objects.

### Naive Depth-First Resolution

[`resolver01`](./semantics/src/main/kotlin/semantics/resolver01/Resolver.kt) groups applicable selection occurrences by their specialized concrete `Value.Key`, closes exact local field-resolver demand, topologically orders sibling dependencies, and materializes each argument-dependent object fragment from the resolved prefix. Argument errors produce `EngineResult.Cell.Error`; an unregistered field is read from the current resolver value because it remains in the producing resolver's output selection set. Nullable values, simple values, lists, and objects are traversed structurally, including objects returned by generated node loaders.

This constructor is intentionally depth-first and may apply a resolver again when recursive demand reaches it again; it is not the one-shot execution design. [`resolver01/ResolverTest.kt`](./semantics/src/test/kotlin/semantics/resolver01/ResolverTest.kt) starts from an empty Query object and demonstrates that the construction satisfies `correctResolution` for its ordinary empty-fragment source resolvers together with nonempty fixture-generated bridge fragments.

[`resolver02`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt) closes each concrete object's selections under the top-level, argument-dependent object-fragment demand of activated field resolvers. [`Value.Key.demandsFromSibling`](./model/src/main/kotlin/model/registry/SiblingDemand.kt) relates an exact consumer key to an exact sibling key, preserving argument tuples while interpreting type conditions and variable bindings. A local topological order makes already-discovered sibling inputs available to [`materialize`](./semantics/src/main/kotlin/semantics/Materialize.kt). Before applying any field resolver, Resolver02 adds one uniform private, demand-bounded `outputSelectionForest` to its supplied demand, making the selective model API expose the complete output of the deliberately non-selective resolver case.

[`resolver03`](./semantics/src/main/kotlin/semantics/resolver03/Resolver.kt) is structurally identical through local closure, dependency ordering, materialization, and recursive resolution. Its field-resolver applications omit Resolver02's OSS expansion and pass only the selections known at that point. Demand discovered after entering a produced object can therefore refer to passive values already removed by `snipToDemand`, which is why Resolver03's tests remain intentionally red. Generated node loaders use the same path and do not introduce a distinct failure mode or semantic rule.

### Correctness Theorems

[`semantics/theorems.md`](./semantics/theorems.md) records informal mathematical claims and supporting arguments; it is not a machine-checked proof artifact. Its current theorem is specific to Resolver02 and claims that, in its stated domain, Resolver02 produces a result satisfying `isClosedUnderResolverDemand`.

That theorem does not concern Resolver03, does not establish full `correctResolution`, and does not prove selective one-shot execution. Further theorem work remains deferred until Resolver03's selective constructor is no longer false.

### Resolver Demand

The externally supplied canonical executor registry contains an acyclic resolver-demand graph over canonical `Schema.OutputField` elements. Membership in the registry makes an output field a resolver coordinate; object types are never resolver coordinates.

For a registered field `f`, `registry.mayDemandFrom(f)` contains exactly the registered output fields directly implicated by selections reachable from its representative object fragment, plus lowering-supplied bridge edges omitted from that representative. A selection directly implicates the registered output-field coordinate, when present, at each concrete possible type combined with `selection.key.field.fieldName`.

`registry.mayBeDemandedBy(field)` is the exact transpose of `mayDemandFrom`. Pre-reasoning registry assembly rejects a self-cycle or longer demand cycle with `IllegalArgumentException`.

The intended demand interpretation starts with the registered fields directly implicated by an external selection forest and takes the least superset closed under `mayDemandFrom`. The relation is a conservative possibility relation because the selections that induce an edge retain type conditions that may not apply to the runtime concrete object.

This is a resolver-demand graph, not a graph of every invocation input, value provenance fact, or scheduling prerequisite. Fixture lowering makes node loading explicit as an edge from generated `foo` to registered bridge producer `foo$id` when that bridge has its own resolver. A passive bridge remains an exact object-fragment requirement without becoming a registry vertex.

The graph API is defined in [`ExecutorRegistry.kt`](./model/src/main/kotlin/model/registry/ExecutorRegistry.kt), while assembly and invariant checks are pre-reasoning fixture infrastructure. [`ResolverDemandTest.kt`](./model/src/test/kotlin/model/registry/ResolverDemandTest.kt) exercises nested reachability, polymorphic lowered fields, bridge edges, transposition, and cycle rejection.

## Current Scope

Inputs are post-validation and all named fragment spreads are assumed to have been inlined. The `TestWorld` parsing fixture accepts one named fragment definition as a parsing envelope, ignores its name, rejects nested named spreads, and supplies its nominal type and flattened `SelectionForest` to semantic reasoning.

`@skip` and `@include` belong to the eventual field-resolution model but are deferred. Applied directives are currently rejected. Query fragments, variable providers, `@parent`, lazy executor values, checkers, and raw-versus-checked dependency distinctions are also not yet modeled. `EngineResult.Cell.check` remains in the carrier algebra for that future work, but the initial `correctResolution` judgment is explicitly check-insensitive.

`correctResolution` is defined only when every variable needed by an applicable required-cell `Value.Key` can be instantiated from `world.variableValues`. Variables occurring only in guards or branches inapplicable to the judged runtime object do not restrict the judgment's domain. After instantiation, an argument tuple containing `Value.Error` requires the error cell mandated by the OER carrier invariant and does not invoke the registered field resolver.

Every argument-bearing output field is currently assumed to have an explicit field resolver. Production namespace exceptions are outside the model.

The registry currently rejects resolver-demand cycles. `evergreen.md` records legal production RSS cycles as an eventual hard case, so cycle rejection is a present scope constraint rather than a general claim about Viaduct.

Every non-`__typename`, non-synthetic field on `Query` has an explicit canonical field resolver. Canonical field resolvers are registered only at concrete object-field coordinates. Fixture inputs may provide raw node resolvers only for object types that nominally implement the canonical `Node` interface, and lowering rejects mixed node-resolved and inline possible-type sets.

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
