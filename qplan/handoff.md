# Correct OER Specification Handoff

## Purpose

This is the handoff for continuing work on the query-planning model. It should let a new agent understand the current semantic boundary, what the repository already establishes, and the next reasoning task without reconstructing the chronology that produced those decisions.

Read [Query Plan Research](./evergreen.md) for the durable production evidence, vocabulary, hard cases, correctness obligations, and validation strategy. [An Idealized Viaduct Query Execution Model](./viaduct-execution.md) for a description of Viaduct's execution model.  [AGENTS.md](./AGENTS.md), [`model/AGENTS.md`](./model/AGENTS.md), and [`semantics/AGENTS.md`](./semantics/AGENTS.md) describe the current state of our modeling effort plus guidelines and procedures for updating that state; read these files before making any modifications.

Compiling Kotlin is the project's present mathematical modeling language, serving the same kind of specification role that TLA+ could serve rather than describing a JVM implementation. The model should also be maintained as a blueprint for a possible future translation into TLA+: its carrier sets, functions, relations, invariants, domain assumptions, and theorem statements should be explicit enough to become a formal specification with corresponding machine-checked proof obligations.

A scoped TLA+ construction-calculus translation and machine-checked proof baseline now exists in [`tla`](./tla). TLAPS proves finite least demand closure, dependency-order safety and termination, one mathematical application position per exact resolver key and concrete OER occurrence, Resolver03 guarded producer completeness under the exact registry-extension assumption, and Resolver04 provider ordering and ambient-demand sealing under its explicit assumptions. It now also proves a finite extensional result-tree model of every `correctResolution` conjunct, occurrence-indexed Resolver01/02 demand lifting, finite observation projection coherence, and simultaneous termination and completion of every reachable occurrence fold. [`tla/README.md`](./tla/README.md) records the remaining theorem boundary: structural extraction of Kotlin schema, selection, value, list, materialization, union, and resolver-comparison operations into those finite atoms is not yet proved, so this baseline must not be quoted as a complete TLAPS proof of the Kotlin model.

## TLA+ Toolchain

The repository has a project-local TLA+ toolchain declared in [`mise.toml`](./mise.toml) and resolved in [`mise.lock`](./mise.lock). It includes Amazon Corretto Java 21.0.4.7.1; TLA+ Tools 1.7.4, whose `tla2tools.jar` supplies the SANY parser and TLC model checker; and TLAPS 1.5.0, whose `tlapm` proof manager can use the bundled Z3, Zenon, Isabelle, and LS4 backends. TLAPS 1.5.0 is selected because the newer 1.6.0 rolling Linux build requires glibc 2.38 while the current host provides glibc 2.35; the configured TLAPS installer is consequently restricted to Linux x86-64.

Run `mise install` from this directory to install the pinned tools. The project defines these wrappers:

- `mise run tla:parse -- path/to/Spec.tla` parses and semantically checks a module with SANY.
- `mise run tla:check -- path/to/Spec.tla` model-checks a specification with TLC, using `Spec.cfg` by default.
- `mise run tla:prove -- path/to/Spec.tla` checks the module's proofs with TLAPS.

The SANY and TLC wrappers add TLAPS's standard-module directory to the TLA+ library path automatically. Installation has been validated by parsing the bundled `Euclid.tla` example and having TLAPS prove all 37 of its obligations. This establishes that the proof toolchain runs; it does not establish any theorem about the resolver model.

## Long Term Goal

The long-term goal is a query-plan and query-execution design that supports one-shot resolver execution. One-shot is defined per resolver-bearing OER occurrence: for each field cell in the resolved result tree whose value is supplied by a resolver, all in-scope demand for that cell is aggregated before the resolver is applied, and that resolver is applied exactly once for that occurrence. Multiple client selections, RSS paths, or aliases that converge on the same alias-free OER cell must contribute to that one application.

One-shot does not require cross-tree coalescing. Distinct OER occurrences remain distinct even when they contain the same node identifier, use the same resolver coordinate and arguments, or produce structurally equal values; resolving each occurrence separately is expected. Node caching, request deduplication, and batching may share underlying work across those occurrences, but that is a separate execution layer and is not part of the one-shot claim. Distinct list items and execution epochs likewise remain distinct occurrences.

The current model is building a plan-independent correctness judgment over `EngineResult.Object`. That judgment should characterize valid field-resolution results without mentioning planner nodes, readiness, dependency counts, or execution order, so the eventual one-shot plan and executor can be judged against it. OER coordinates are `Value.Key` values carrying canonical schema output fields and fully coerced arguments; response aliases, response keys, response ordering, and external response assembly belong to field completion and remain outside the model. [Query Plan Research](./evergreen.md) records the production motivation, one-shot proof obligations, and hard cases.

## Next Step Goal

The current validated constructors are [`semantics.resolver01`](./semantics/src/main/kotlin/semantics/resolver01/Resolver.kt), [`semantics.resolver02`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt), [`semantics.resolver03`](./semantics/src/main/kotlin/semantics/resolver03/Resolver.kt), and [`semantics.resolver04`](./semantics/src/main/kotlin/semantics/resolver04/Resolver.kt). The canonical model exposes field resolvers plus field-relative variable providers. Raw selection-independent field functions remain private behind `Resolver.Field`, while raw node lookup functions exist only as external test-fixture inputs that are lowered before the reasoning world is constructed.

Resolver03 now aggregates transitive guarded demand before each selective resolver application. Registry assembly processes resolver coordinates in dependency-first `mayDemandFrom` order and attaches an `extendedFragment` to each field resolver. The extension begins with `objectFragment`, walks every finite object path in that fragment, explores each possible concrete type of polymorphic outputs separately, and roots each encountered resolver field's already-computed extension at the encounter path. The exact-arguments accessor performs the same construction from `objectFragment(arguments)`, preserving argument tuples on fixture-lowered `foo$id(args)` bridges.

Before Resolver03 applies a resolver, it walks the supplied output demand and adds the exact extended fragment of each resolver occurrence at its containing-object path. This leaves `snipToDemand` as a projection of exactly its supplied demand, leaves Resolver02 unchanged, and does not inspect a producer result while deriving demand. The deterministic flat, nested, and recursive counterexamples now pass, as do the arbitrary properties covering interfaces, unions, lists, arguments, ordinary field resolvers, and fixture-lowered node loaders. The mutation-control world substitutes immediate `objectFragment` demand and remains detectably false.

Within its current feature set, Resolver03 satisfies the scoped [`resolver03-one-shot-construction`](./claims.md) claim: it groups all applicable demand for each concrete `Value.Key` at one OER object occurrence, computes the transitive guarded requirements before resolver projection, orders required sibling cells first, and applies the resolver once while constructing that cell. Distinct recursive objects and list elements are distinct OER occurrences and therefore receive their own applications. This is a scoped design result supported by deterministic and arbitrary tests and the argument in [`arguments/resolver03-one-shot-construction.md`](./arguments/resolver03-one-shot-construction.md), not a machine-checked theorem or a claim about the deferred features below.

Resolver04 extends that construction with `fromObjectField`-style execution variables. Each globally named variable has one `VariableCoordinate` pairing it with the concrete object field whose resolver defines it, and maps to one provider path relative to that field's containing OER. Before closing or materializing a defining resolver's object fragment, Resolver04 recursively resolves provider dependencies, reads each provider path from the current OER, stores the resulting binding in that OER's `variableValues`, and substitutes those bindings throughout the complete fragment, including nested keys. Intermediate null and error values propagate, terminal lists become input lists, and provider paths may neither traverse lists nor terminate at objects.

Variable providers and field resolvers share one acyclic demand graph over `Schema.ResolverSite`. This lets registry extension include a provider's field requirements before demand is sealed at a selective producer boundary. Deterministic tests cover direct, nested selective, recursive, list, null, equal-valued convergence, and overlapping provider/operation demand cases. Resolver04's arbitrary generator can produce globally unique variables, type-compatible provider paths, argument-bearing fragments, aliased query selections, and deep transitive dependencies; the regular property requires at least one generated result to contain a resolved variable binding, while the gated stress property requires bindings in at least ten percent of 10,000 or more cases at a minimum query depth of four.

The initial TLA+ workstream translated the shared construction calculus and the Resolver01 through Resolver04 deltas into scoped modules, prioritizing Resolver03 and Resolver04 while retaining explicit weaker statements for Resolver01 and Resolver02. The baseline made partial progress against this proof program:

1. Define TLA+ carrier sets, world assumptions, schema and value invariants, selection and fragment operations, resolver-demand relations, and the plan-independent correctness judgments corresponding to the canonical Kotlin model.
2. Translate each resolver algorithm as a mathematical operator or state machine without introducing implementation behavior absent from the Kotlin semantics.
3. State separate, scoped theorems for demand closure, sound dependency discovery, producer completeness, one application per resolver-bearing OER occurrence, result correctness, and termination; do not collapse those claims into response equality or one blanket theorem.
4. Use TLC over deliberately small finite schemas, values, fragments, polymorphic branches, lists, argument tuples, lowered node bridges, and variable-provider graphs to find counterexamples to the translation and theorem statements.
5. Use TLAPS to prove the supporting lemmas and final theorems once the TLC models stabilize, recording exactly which assumptions and deferred features delimit each result.
6. Keep the Kotlin model, informal arguments, TLA+ specification, TLC configurations, and TLAPS proofs aligned as the model changes; compilation, Kotlin tests, and TLC exploration remain finite evidence rather than substitutes for TLAPS proofs.

The next proof workstream is to prove that the structural Kotlin schema/value carriers, selection and fragment recursion, `materialize`, `resolveValue`, object/list union, `observedDemand`, and resolver-value comparison induce the finite atoms and alignment relations already used by the extensional TLA+ result-tree, product-fold, and projection modules. In particular, returned OER `PresentKeys` must align with terminal product-fold `BuiltKeys`. After that refinement baseline, continue the feature roadmap:

1. Add `@parent`, preserving the exact ancestor OER occurrence through object and list ancestry and rooting its demand at that ancestor.
2. Add checkers, including field and type checker ordering, checker results, and the distinction between raw-slot dependencies and policy-checked values.

For each stage, preserve the scoped one-shot rule per resolver-bearing OER occurrence, extend the deterministic and arbitrary counterexamples, and revise both the informal and machine-checked theorem assumptions before moving to the next stage. The existing theorem in [`semantics/theorems.md`](./semantics/theorems.md) remains specifically scoped to variable-free Resolver02 and is not by itself evidence for Resolver03 or Resolver04.

Use `./gradlew check` for complete validation. Focused checks are `./gradlew :model:test --tests model.registry.ExecutorRegistryTest --tests model.registry.ResolverDemandTest` and `./gradlew :semantics:test --tests semantics.resolver04.ResolverTest`.

## Current Model

### World And Schema

Each reasoning exercise fixes exactly one `Assumptions` and one canonical `Schema`. `Assumptions` supplies the schema, executor registry, and concrete-field `behavioral` predicate. Parsing validated named fragments into a nominal type and flattened `SelectionForest`, including substitution of operation-variable bindings, is test-fixture or composition infrastructure outside the semantic model. Operation variables are distinct from the field-relative execution variables registered in `ExecutorRegistry`. There are no JVM-global schema declarations.

Every schema definition has one canonical object, so ordinary `==` means that two definitions from that schema denote the same schema element; cross-schema equality is outside the model. Every non-error `Value`, `Value.Arguments`, and `Value.Key` is constructed through a factory on its precise semantic category. `Value.Error` is schema-independent. Reusable schema-conformance relations live in [`model.invariants`](./model/src/main/kotlin/model/invariants/SchemaConformance.kt), and factory KDocs state the carrier-invariant postconditions they establish; because carrier implementations are sealed behind those factories, these postconditions are universally quantified over constructed values in the fixed world.

`Value.Key` is the shared alias-free coordinate for selections, resolved object values, and OER cells. It contains a canonical output field and its coerced arguments. `Value.Object.fieldValues` is a `Value.ObjectFields` map keyed by `Value.Key`, while `EngineResult.Object.keys` is the set of `Value.Key` coordinates whose cells are present. Every key present in either value carries a field owned by that value's concrete `Schema.ObjectType` and contains no unresolved variables; keys outside those values may carry abstract-type fields or unresolved variables. A `Value.Object` can therefore contain multiple values for one output field under distinct argument tuples.

`EngineResult.Object`, `EngineResult.List`, and `EngineResult.Cell` are logic-constructible model types backed by private data-class implementations and constructed by their respective factories; they are not externally supplied implementation points. An `EngineResult.Object` is a finite, structurally comparable value tree whose variable-value map distinguishes an absent variable from one bound to GraphQL null. A present object field and each list element has one `EngineResult.Cell` containing a nullable value and a check result; absence differs from a present null. Object and list factories eagerly establish recursive schema conformance, and list results carry their element `typeExpr` even when empty. There is no distinct OER node-reference constructor in the canonical model.

`EngineResult?.union` is a partial structural operation. Object union requires equal object types, retains cells and variable bindings found in only one operand, recursively unions cells found in both, and requires equal values for shared variable bindings. List union is positional and requires equal element `typeExpr` values and lengths. Shared cells require equal check values; simple values union only when equal, and null unions only with null. An undefined union is represented by `IllegalArgumentException`.

### Selections And Fragments

`SpecSelection` represents GraphQL-shaped, post-validation selections solely to state their parity with the field-resolution model. [`SpecSelectionFlattener.kt`](./model/src/main/kotlin/model/spec/SpecSelectionFlattener.kt) maps them into field-resolution `Selection` occurrences. Ordinary test fixtures perform this conversion internally and expose only the nominal type and flattened `SelectionForest`.

A `Selection` carries a canonical `Value.Key`, nominal composite type, possible concrete parent types, and nested selections. Inline fragment structure has been flattened into the nominal and possible-type information. A `SelectionForest` is an equality-free finite occurrence family: source order is erased while occurrence multiplicity is preserved.

Semantic equality for `Selection` is intentionally undefined. `SelectionForest` therefore exposes permutation-invariant occurrence operations without membership, deduplication, hashing, equality-based counting, or forest equality.

A `Fragment` contains a nominal composite type and a flattened `SelectionForest`. A field resolver's `objectFragment(arguments)` describes the object-valued input that must be resolved before invoking its selection-independent function for that exact argument tuple. Its `extendedFragment(arguments)` additionally contains the transitively required fragments of resolver occurrences reachable inside that input, rooted along their guarded object paths. Ordinary resolvers return one fixed object fragment; fixture-generated loaders use argument-dependent fragments so `foo(args)` requires the matching `foo$id(args)`.

### Resolver Interpretation

The executor registry fixes field resolvers and field-relative variable providers for the canonical reasoning world. A field resolver stores representative `objectFragment` and `extendedFragment` values, provides their exact argument-dependent forms, and privately stores a function from the resolved object-fragment value and coerced arguments to a nullable, selection-independent `Value.Output`. Semantic resolver algorithms can apply only the public `resolve(..., transitiveDemand)` operation, which applies the private function and projects its result with `snipToDemand`.

`snipToDemand` supplies the conceptual additional selection input by projecting a resolver's fixed output result to its demand. Holding the ordinary function inputs fixed therefore produces coherent projections for different demands. It does not close or otherwise expand that demand. Projection stops at a behavioral field before materializing its key, so symbolic arguments are permitted at that boundary while passive keys must already be instantiated.

For a canonical concrete field, `behavioral(field)` is true exactly when the field is engine-supplied `__typename` or has a registered field resolver. `snipToDemand` retains demanded passive fields and stops at those uniform behavioral boundaries. Synthetic `$id` bridges are ordinary internal fields: they are demanded explicitly by generated loader fragments and are not retained through an implicit node ownership rule.

### Fixture Node Lowering

`TestWorld.fromSDL` is the compiler boundary from ordinary GraphQL SDL and fragments plus raw field and node resolver functions into the canonical field-only world. GraphQL validation and parsing use the external schema, while the decoded internal schema additionally contains a synthetic `foo$id` field for every source field `foo` whose declared base output type is `Node` or implements `Node`. The bridge repeats `foo`'s arguments and replaces the named node type with `ID` while preserving every list and nullability layer.

For a source node-valued field `foo(args)` with a raw field resolver, fixture composition moves that producer to `foo$id(args)` and adapts the output from node references to typed IDs; passive nested node references are rewritten to bridge coordinates in their containing resolver's output. It installs a generated field resolver at every lowered `foo(args)` whose argument-dependent object fragment requires exactly `foo$id(args)` and whose function dispatches each typed ID to the raw node lookup. Arguments remain on both coordinates so distinct `Value.Key` argument tuples never collapse, and typed fixture IDs carry the concrete object type needed for abstract outputs and list elements.

The lowering recursively rewrites nested raw resolver outputs and translates resolver demand between source node fields and bridge fields. It rejects a field whose possible types mix node-resolved and inline objects, and it requires every possible type of a lowered abstract node output to have a raw node resolver. Arbitrary generation therefore registers every generated `Node` implementation and keeps generated non-`Node` interfaces and unions disjoint from `Node` objects.

### Naive Depth-First Resolution

[`resolver01`](./semantics/src/main/kotlin/semantics/resolver01/Resolver.kt) groups applicable selection occurrences by their specialized concrete `Value.Key`, closes exact local field-resolver demand, topologically orders sibling dependencies, and materializes each argument-dependent object fragment from the resolved prefix. Argument errors produce `EngineResult.Cell.Error`; an unregistered field is read from the current resolver value because it remains in the producing resolver's output selection set. Nullable values, simple values, lists, and objects are traversed structurally, including objects returned by generated node loaders.

This constructor is intentionally depth-first and may apply a resolver again when recursive demand reaches it again; it is not the one-shot execution design. [`resolver01/ResolverTest.kt`](./semantics/src/test/kotlin/semantics/resolver01/ResolverTest.kt) starts from an empty Query object and demonstrates that the construction satisfies `correctResolution` for its ordinary empty-fragment source resolvers together with nonempty fixture-generated bridge fragments.

[`resolver02`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt) closes each concrete object's selections under the top-level, argument-dependent object-fragment demand of activated field resolvers. [`Value.Key.demandsFromSibling`](./model/src/main/kotlin/model/registry/SiblingDemand.kt) relates an exact consumer key to an exact sibling key, preserving argument tuples while interpreting type conditions. A local topological order makes already-discovered sibling inputs available to [`materialize`](./semantics/src/main/kotlin/semantics/Materialize.kt). Before applying any field resolver, Resolver02 adds one uniform private, demand-bounded `outputSelectionForest` to its supplied demand, making the selective model API expose the complete output of the deliberately non-selective resolver case.

[`resolver03`](./semantics/src/main/kotlin/semantics/resolver03/Resolver.kt) is structurally identical through local closure, dependency ordering, materialization, and recursive resolution. Before applying a field resolver, it walks the output demand and roots each encountered resolver field's exact `extendedFragment(arguments)` at that occurrence. This guarded expansion makes passive transitive inputs visible to the producing resolver before `snipToDemand` projects its output. Generated node loaders use the same operation, including exact synthetic bridge argument tuples, and do not introduce a distinct semantic rule.

[`resolver04`](./semantics/src/main/kotlin/semantics/resolver04/Resolver.kt) adds field-relative variables while preserving Resolver03's construction. When an activated resolver fragment contains variables, it resolves their registered provider paths against the current OER, recursively resolving provider variables and field demand first. It unions those bindings into the current OER, instantiates the resolver's complete fragment, and then uses the concrete fragment for closure, dependency ordering, materialization, and resolver comparison. The same bindings therefore apply to variable references at every depth of that resolver fragment without being copied into descendant OERs.

### Correctness Theorems

[`semantics/theorems.md`](./semantics/theorems.md) records informal mathematical claims and supporting arguments; it is not a machine-checked proof artifact. Its current theorem is specific to Resolver02 and claims that, in its stated domain, Resolver02 produces a result satisfying `isClosedUnderResolverDemand`.

That theorem does not concern Resolver03 or Resolver04, does not establish full `correctResolution`, and does not prove selective one-shot execution. Resolver04's now-green finite tests are consistency and counterexample-finding evidence; its theorem work remains outstanding.

### Resolver Demand

The externally supplied canonical executor registry contains an acyclic resolver-demand graph over `Schema.ResolverSite` elements. A site is either a registered concrete `Schema.ObjectField` resolver coordinate or a registered `VariableCoordinate` pairing a globally unique variable name with its defining field. Object types and bare `Value.Variable` values are never resolver sites.

For a registered field `f`, `registry.mayDemandFrom(f)` contains the registered output fields directly implicated by selections reachable from its representative object fragment, the variable coordinates referenced anywhere in those selections' keys, and lowering-supplied bridge edges omitted from that representative. For a variable coordinate, the relation contains the corresponding sites implicated by its provider path. A selection directly implicates the registered output-field coordinate, when present, at each concrete possible type combined with `selection.key.field.fieldName`.

`registry.mayBeDemandedBy(site)` is the exact transpose of `mayDemandFrom`. Pre-reasoning registry assembly rejects a self-cycle or longer field-variable demand cycle with `IllegalArgumentException`.

Registry assembly uses the acyclic relation to process dependencies before their consumers. For each resolver, it extends the representative and exact object fragments by walking their selection occurrences, tracking the root-to-current object path, and adding every encountered resolver's already-computed extension rooted through that path. A referenced variable contributes its provider's extension at the defining fragment's root because the provider is relative to the defining field's containing OER, even when the reference occurs in a nested key. Polymorphic outputs are traversed once per possible concrete object so their local guards and concrete resolver coordinates remain distinct.

The intended demand interpretation starts with the registered fields directly implicated by an external selection forest and takes the least superset closed under `mayDemandFrom`. The relation is a conservative possibility relation because the selections that induce an edge retain type conditions that may not apply to the runtime concrete object.

This is a resolver-demand graph, not a graph of every invocation input, value provenance fact, or scheduling prerequisite. Fixture lowering makes node loading explicit as an edge from generated `foo` to registered bridge producer `foo$id` when that bridge has its own resolver. A passive bridge remains an exact object-fragment requirement without becoming a registry vertex.

The graph API is defined in [`ExecutorRegistry.kt`](./model/src/main/kotlin/model/registry/ExecutorRegistry.kt), while assembly and invariant checks are pre-reasoning fixture infrastructure. [`ResolverDemandTest.kt`](./model/src/test/kotlin/model/registry/ResolverDemandTest.kt) exercises nested reachability, polymorphic lowered fields, bridge edges, transposition, and cycle rejection.

## Current Scope

Inputs are post-validation and all named fragment spreads are assumed to have been inlined. The `TestWorld` parsing fixture accepts one named fragment definition as a parsing envelope, ignores its name, rejects nested named spreads, and supplies its nominal type and flattened `SelectionForest` to semantic reasoning.

`@skip` and `@include` belong to the eventual field-resolution model but are deferred. Applied directives are currently rejected. Query fragments, `fromQueryField`, `@parent`, lazy executor values, checkers, and raw-versus-checked dependency distinctions are also not yet modeled. Execution variables are intentionally limited to `fromObjectField` paths rooted at the defining resolver's current OER occurrence. `fromArgument` and `VariablesProvider` are excluded from the modeling roadmap because they do not require OER fields to be resolved in order to produce their values and therefore add no interesting demand dependency for the present problem. The roadmap next addresses `@parent` and then checkers. `EngineResult.Cell.check` remains in the carrier algebra for that future work, but the initial `correctResolution` judgment is explicitly check-insensitive.

The current `correctResolution` judgment requires its supplied operation fragment to contain no unresolved variables; operation-variable substitution is pre-reasoning parsing or composition work. Registered execution variables may occur in resolver fragments and provider paths, and the judgment instantiates those fragments from each OER's stored bindings before comparing demand or resolver output. An argument tuple containing `Value.Error` requires the error cell mandated by the OER carrier invariant and does not invoke the registered field resolver.

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
