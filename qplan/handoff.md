# Correct OER Specification Handoff

## Purpose

This is the handoff for continuing work on the query-planning model. It should let a new agent understand the current semantic boundary, what the repository already establishes, and the next reasoning task without reconstructing the chronology that produced those decisions.

Read [Query Plan Research](./evergreen.md) for the durable production evidence, vocabulary, hard cases, correctness obligations, and validation strategy. [An Idealized Viaduct Query Execution Model](./viaduct-execution.md) for a description of Viaduct's execution model.  [AGENTS.md](./AGENTS.md), [`model/AGENTS.md`](./model/AGENTS.md), and [`semantics/AGENTS.md`](./semantics/AGENTS.md) describe the current state of our modeling effort plus guidelines and procedures for updating that state; read these files before making any modifications.

[Resolution Algorithms By Example](./examples.md) provides worked GraphQL schemas and execution traces for registry-computed demand closure, successor-demand lifting and type-conditioned output projection, Resolver04 widening after variable binding, and late exact-key convergence.

Compiling Kotlin is the project's present mathematical modeling language, serving the same kind of specification role that TLA+ could serve rather than describing a JVM implementation. The model should also be maintained as a blueprint for a possible future translation into TLA+: its carrier sets, functions, relations, invariants, domain assumptions, and theorem statements should be explicit enough to become a formal specification with corresponding machine-checked proof obligations.

A scoped TLA+ construction-calculus translation and machine-checked proof baseline now exists in [`tla`](./tla). TLAPS proves finite least demand closure, dependency-order safety and termination, one mathematical application position per exact resolver key and concrete OER occurrence, Resolver03 guarded producer completeness under the exact registry-extension assumption, and Resolver04 provider ordering and ambient-demand sealing under its explicit assumptions. It also proves a finite extensional result-tree model of every `correctResolution` conjunct, occurrence-indexed demand lifting, finite observation projection coherence, simultaneous termination and completion of every reachable occurrence fold, equality of prefix and final materialization, Resolver03 derivation of projection coverage, finite validated provider-path traversal with null/error absorption and ranked terminal-list conversion, and Resolver04 final provider-read variable conformance. The composed Resolver03 and Resolver04 theorems each terminate in a result satisfying all six modeled `correctResolution` conjuncts. [`tla/README.md`](./tla/README.md) records the remaining theorem boundary: structural extraction of Kotlin schema, selection, value, list, provider-path traces, materialization, union, and resolver-comparison operations into those finite atoms is not yet proved, so this baseline must not be quoted as a complete TLAPS proof of the Kotlin model.

## TLA+ Toolchain

The repository has a project-local TLA+ toolchain declared in [`mise.toml`](./mise.toml) and resolved in [`mise.lock`](./mise.lock). It includes Amazon Corretto Java 21.0.4.7.1; TLA+ Tools 1.7.4, whose `tla2tools.jar` supplies the SANY parser and TLC model checker; and TLAPS 1.5.0, whose `tlapm` proof manager can use the bundled Z3, Zenon, Isabelle, and LS4 backends. TLAPS 1.5.0 is selected because the newer 1.6.0 rolling Linux build requires glibc 2.38 while the current host provides glibc 2.35; the configured TLAPS installer is consequently restricted to Linux x86-64.

Run `mise install` from this directory to install the pinned tools. The project defines these wrappers:

- `mise run tla:parse -- path/to/Spec.tla` parses and semantically checks a module with SANY.
- `mise run tla:check -- path/to/Spec.tla` model-checks a specification with TLC, using `Spec.cfg` by default.
- `mise run tla:prove -- path/to/Spec.tla` checks the module's proofs with TLAPS.

The SANY and TLC wrappers add TLAPS's standard-module directory to the TLA+ library path automatically. Installation has been validated by parsing the bundled `Euclid.tla` example and having TLAPS prove all 37 of its obligations. The complete resolver matrix currently has 23 proof-bearing modules accepted by TLAPS and 18 finite models accepted by TLC. Run TLAPM serially because its cache is working-directory-local, and give concurrent TLC runs distinct `-metadir` paths.

## Long Term Goal

The long-term goal is a query-plan and query-execution design that supports one-shot resolver execution. One-shot is defined per resolver-bearing OER occurrence: for each field cell in the resolved result tree whose value is supplied by a resolver, all in-scope demand for that cell is aggregated before the resolver is applied, and that resolver is applied exactly once for that occurrence. Multiple client selections, RSS paths, or aliases that converge on the same alias-free OER cell must contribute to that one application.

One-shot does not require cross-tree coalescing. Distinct OER occurrences remain distinct even when they contain the same node identifier, use the same resolver coordinate and arguments, or produce structurally equal values; resolving each occurrence separately is expected. Node caching, request deduplication, and batching may share underlying work across those occurrences, but that is a separate execution layer and is not part of the one-shot claim. Distinct list items and execution epochs likewise remain distinct occurrences.

The current model is building a plan-independent correctness judgment over `EngineResult.Object`. That judgment should characterize valid field-resolution results without mentioning planner nodes, readiness, dependency counts, or execution order, so the eventual one-shot plan and executor can be judged against it. OER coordinates are `Value.Key` values carrying canonical schema output fields and fully coerced arguments; response aliases, response keys, response ordering, and external response assembly belong to field completion and remain outside the model. [Query Plan Research](./evergreen.md) records the production motivation, one-shot proof obligations, and hard cases.

## Most Recent Step

Canonical registry construction now enforces the depth-first variable-stratification invariant documented in [`variable-handoff.md`](./variable-handoff.md). For each concrete object type, fixture assembly forms an argument-insensitive graph over immediate field branches, adds ordinary resolver-input edges, computes each variable's provider-production branches as the transitive prerequisites of its provider root, adds every production-before-use edge, and rejects the least fixed point when it contains a self-edge or longer cycle. Diagnostics identify the concrete type, cycle, defining variable or resolver dependency, provider path, production path, and use path.

Focused registry tests cover direct and transitive provider/use overlap, cross-variable cycles, accepted linear and independent orders, argument-distinct branch collapse, nested input-object/list uses, and fixture-lowered node prerequisites. The arbitrary generator now constructs variable edges in the same rank order as ordinary resolver edges instead of generating unrestricted variable programs and relying on rejection. Former Resolver04 widening-only and symbolic-convergence examples are retained as negative registry-construction tests. No new resolver consumes the invariant yet: Resolver04 remains the compiling variable interpreter, and the variable-aware depth-first Resolver03 extension is the intended fast follow.

Validation passed with `./gradlew check`.

Resolver03 and Resolver04 no longer compute transitive input demand with runtime `closeResolverDemand` functions. Each activated resolver contributes its exact registry-computed `predecessorDemand` in one pass; Resolver04 then resolves variables whose registered defining fields belong to the current concrete object type, instantiates those variables across the applicable selections and input closure, and preserves the remaining variables symbolically. The separate `subselections.successorDemand()` call remains the output-side operation that retains client demand and the prerequisites of successor resolver occurrences.

For an exact resolver occurrence `R(arguments)`, its `predecessorDemand(arguments)` is the guarded, path-rooted transitive closure of `R`'s `objectFragment(arguments)` under resolver-dependency expansion. It supplies the prerequisites needed to construct `R`'s input. Separately, `SelectionForest.successorDemand()` walks dynamic output demand and lifts each encountered successor resolver's exact predecessor demand to that occurrence's containing-object path.

The former `extendedFragment` API is now named `predecessorDemand`, and the former private `withExtendedResolverDemand()` operation is now the shared [`SelectionForest.successorDemand()`](./model/src/main/kotlin/model/registry/SuccessorDemand.kt). This is a vocabulary-only semantic change: predecessor demand remains registry-computed, while successor demand remains projected over dynamic incoming output demand at resolution time. No TLA+ artifact or proof changed.

The runtime transitive-demand fault-injection mode and its Resolver02-04 branches have been removed. Resolver support for transitive demand is now a domain choice made by static fixtures and the arbitrary generator's `ResolverFragmentsEnabled` configuration rather than a runtime semantic mode. The fixed generated fault-injection corpora that toggled this mode were removed; independent resolver-program mutation tests remain.

Validation passed with `./gradlew check` and the full 10,000-case Resolver04 stress property at seed `20260804`. The stress run resolved and verified all 10,000 cases, including 8,227 cases with at least one variable binding and 10,000 cases with an activated dependency-bearing resolver.

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

A `Fragment` contains a nominal composite type and a flattened `SelectionForest`. A field resolver's `objectFragment(arguments)` describes the object-valued input that must be resolved before invoking its selection-independent function for that exact argument tuple. Its `predecessorDemand(arguments)` is the guarded, path-rooted transitive closure of that exact object fragment under resolver-dependency expansion. Argument-dependent exact fragments are recursively rebuilt from one representative template, preserving nominal type, field-coordinate occurrences, type guards, nesting, and occurrence multiplicity while retargeting only key arguments. Ordinary resolvers return one fixed object fragment, while fixture-generated loaders use a fixed bridge-shaped template whose exact form retargets `foo(args)` to the matching `foo$id(args)`.

### Resolver Interpretation

The executor registry fixes field resolvers and field-relative variable providers for the canonical reasoning world. A field resolver stores representative `objectFragment` and `predecessorDemand` values, provides their exact fixed-shape argument-dependent forms, and privately stores a function from the resolved object-fragment value and coerced arguments to a nullable, selection-independent `Value.Output`. Every variable provider path is structurally contained by its defining resolver's representative and exact fragments with compatible guards and exact argument values. Semantic resolver algorithms can apply only the public `tenantResolve(..., selections)` operation, which applies the private function and projects its result with `snipToDemand`.

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

[`resolver03`](./semantics/src/main/kotlin/semantics/resolver03/Resolver.kt) filters applicable selections and adds each activated resolver field's exact `predecessorDemand(arguments)` as its input closure before dependency ordering, materialization, and recursive resolution. Separately, before applying a field resolver, it walks the output demand and roots each encountered successor resolver's exact predecessor demand at that occurrence. This guarded output expansion makes passive transitive requirements visible to the producing resolver before `snipToDemand` projects its output. Generated node loaders use the same operations, including exact synthetic bridge argument tuples, and do not introduce a distinct semantic rule.

Resolver03 aggregates transitive guarded demand before each selective resolver application. Registry assembly processes resolver coordinates in dependency-first `mayDemandFrom` order and attaches a `predecessorDemand` to each field resolver. The closure begins with `objectFragment`, walks every finite object path in that fragment, explores each possible concrete type of polymorphic outputs separately, and roots each encountered resolver field's already-computed predecessor demand at the encounter path. The exact-arguments accessor performs the same construction from `objectFragment(arguments)`, preserving argument tuples on fixture-lowered `foo$id(args)` bridges.

Before Resolver03 applies a resolver, it walks the supplied output demand and adds the exact predecessor demand of each resolver occurrence at its containing-object path. This leaves `snipToDemand` as a projection of exactly its supplied demand, leaves Resolver02 unchanged, and does not inspect a producer result while deriving demand. The deterministic flat, nested, and recursive counterexamples now pass, as do the arbitrary properties covering interfaces, unions, lists, arguments, ordinary field resolvers, and fixture-lowered node loaders.

Within its current feature set, Resolver03 satisfies the scoped [`resolver03-one-shot-construction`](./claims.md) claim: it groups all applicable demand for each concrete `Value.Key` at one OER object occurrence, computes the transitive guarded requirements before resolver projection, orders required sibling cells first, and applies the resolver once while constructing that cell. Distinct recursive objects and list elements are distinct OER occurrences and therefore receive their own applications. This Kotlin design result is supported by deterministic and arbitrary tests and the argument in [`arguments/resolver03-one-shot-construction.md`](./arguments/resolver03-one-shot-construction.md); the related TLA+ theorem proves the finite extensional construction calculus, not a machine-checked refinement from the Kotlin function or a claim about the deferred features below.

### Variables Break Naive Depth-First Resolution

Resolver03 demand follows the selection-tree structure. Each resolver's predecessor demand supplies the guarded, path-rooted closure of selections required at that resolver's parent OER and below it. Every argument tuple is already concrete, so a depth-first traversal of the selection set being resolved can collect and resolve all demand below a selection before resolving the selection itself. Returning from a subtree means that subtree is complete.

Resolver04 extends that construction with `fromObjectField`-style execution variables. Each globally named variable has one `VariableCoordinate` pairing it with the concrete object field whose resolver defines it, and maps to one provider path relative to that field's containing OER. After selecting the registry-computed symbolic input closure and before exact-key grouping or materialization, Resolver04 recursively resolves provider dependencies, reads each provider path from the current OER, stores the resulting binding in that OER's `variableValues`, and substitutes every binding whose defining field belongs to the current concrete object type throughout the applicable selections and input closure, including nested keys. Other registered variables remain symbolic until resolution reaches their defining object type. Intermediate null and error values propagate, terminal lists become input lists, and provider paths may neither traverse lists nor terminate at objects.

Variable providers and field resolvers share one acyclic demand graph over `Schema.ResolverSite`. Providers remain ordering sites, but their contained paths introduce no independent structural demand: ordinary traversal of the defining fragment extends resolver requirements encountered along those paths. Resolver04 evaluates a provider by requesting its contained path against the already-known symbolic envelope rather than reinjecting a separately extended provider forest. Deterministic tests cover direct, nested selective, recursive, list, null, equal-valued convergence, and overlapping provider/operation demand cases. Resolver04's arbitrary generator can produce globally unique variables, type-compatible contained provider paths, argument-bearing fragments, aliased query selections, and deep transitive dependencies; the regular property requires at least one generated result to contain a resolved variable binding, while the gated stress property requires bindings in at least ten percent of 10,000 or more cases at a minimum query depth of four.

Variables add flows of values that don't follow the tree structure.  A provider reads a value from one path in the OER tree, while uses of that variable insert the value into resolver arguments at other positions in the tree.  Provider and use positions need not have an ancestor-descendant relationship, and in particular resolving the provider may itself require resolving one field of an arbitrarily-deep OER where another field of that same OER contains a use of that variable.

[Widening for Variables](./examples.md#widening-for-variables) gives the complete commented schema and execution trace for a provider whose binding makes new exact demand appear inside an already-visited child OER. Its [Why One Shot Is Impossible](./examples.md#why-one-shot-is-impossible) appendix distinguishes harmless scalar-key convergence from late-equal occurrences that contribute additional output demand. Canonical registry construction now rejects these worlds, and [`ResolverWideningTest`](./semantics/src/test/kotlin/semantics/resolver04/ResolverWideningTest.kt) retains them as negative invariant tests.

[`resolver04`](./semantics/src/main/kotlin/semantics/resolver04/Resolver.kt) makes resolution resumable. It obtains input closure from the activated resolvers' exact predecessor demands, identifies variables whose defining fields belong to the current concrete object type, and resolves their registered provider paths against that OER while recursively resolving provider variables and field demand first. The partial result used to read providers becomes `resolvedVariables`; after bindings are stored and substituted throughout the applicable selections and predecessor demands, Resolver04 computes the now-concrete selection keys.

Resolver04 separates concrete work from symbolic coverage. Registry-computed predecessor demands supply input closure; current-occurrence bindings make the applicable demand concrete for exact-key grouping. One symbolic `envelope` supplies optional coverage for a key before variable values are known. The `widened` pass intersects concrete demanded keys with keys already present after provider resolution; for each intersection, `resolveExistingKey` re-enters the existing cell and resolves newly demanded descendants without applying its producer again. `ResolutionSources` retains the producer's selection-independent output so those descendants can be projected from the original source. `sources.union` merges the enlarged cell back into the result and preserves that source association. The subsequent dependency-ordered fold excludes keys already present in `widened`, preventing a second producer application.

Resolver04 therefore does not restore a pure depth-first ordering. It augments the selection tree with variable data-flow dependencies and permits a previously visited subtree to resume after a binding makes new exact demand available, without applying the producer of an already-present cell again.

That widening capability now lies outside the canonical registry domain: accepted worlds have one acyclic structural branch order, while the existing Resolver04 implementation has not yet been replaced with the depth-first variable-aware construction that exploits it.

### Correctness Theorems

[`semantics/theorems.md`](./semantics/theorems.md) records informal mathematical claims and supporting arguments; it is not a machine-checked proof artifact. Its current theorem is specific to Resolver02 and claims that, in its stated domain, Resolver02 produces a result satisfying `isClosedUnderResolverDemand`.

That informal theorem does not concern Resolver03 or Resolver04, does not establish full `correctResolution`, and does not prove selective one-shot execution. Resolver04's finite Kotlin tests are consistency and counterexample-finding evidence; the separate TLA+ result remains a construction-calculus theorem rather than a refinement proof for the Kotlin function.

### Resolver Demand

The externally supplied canonical executor registry contains an acyclic resolver-demand graph over `Schema.ResolverSite` elements. A site is either a registered concrete `Schema.ObjectField` resolver coordinate or a registered `VariableCoordinate` pairing a globally unique variable name with its defining field. Object types and bare `Value.Variable` values are never resolver sites.

For a registered field `f`, `registry.mayDemandFrom(f)` contains the registered output fields directly implicated by selections reachable from its representative object fragment, the variable coordinates referenced anywhere in those selections' keys, and lowering-supplied bridge edges omitted from that representative. For a variable coordinate, the relation contains the corresponding sites implicated by its provider path. A selection directly implicates the registered output-field coordinate, when present, at each concrete possible type combined with `selection.key.field.fieldName`.

`registry.mayBeDemandedBy(site)` is the exact transpose of `mayDemandFrom`. Pre-reasoning registry assembly rejects a self-cycle or longer field-variable demand cycle with `IllegalArgumentException`.

Registry assembly uses the acyclic relation to process dependencies before their consumers. For each resolver, it computes representative and exact predecessor demand by walking the object fragment's selection occurrences, tracking the root-to-current object path, and adding every encountered resolver's already-computed predecessor demand rooted through that path. Provider paths need no separate root insertion because they are already occurrences in the defining fragment; variables remain graph sites so their values are ordered before exact argument keys are formed. Polymorphic outputs are traversed once per possible concrete object so their local guards and concrete resolver coordinates remain distinct.

The intended demand interpretation starts with the registered fields directly implicated by an external selection forest and takes the least superset closed under `mayDemandFrom`. The relation is intentionally a conservative coordinate-level possibility relation derived from representative fragment shapes rather than an exact-occurrence graph. It retains edges whose type conditions may not apply to the runtime concrete object and edges whose exact occurrence may contain `Value.Error` arguments and therefore never apply its resolver; this conservatism is necessary because argument-dependent exact fragments may replace representative argument values while preserving shape.

This is a resolver-demand graph, not a graph of every invocation input, value provenance fact, or scheduling prerequisite. Fixture lowering makes node loading explicit as an edge from generated `foo` to registered bridge producer `foo$id` when that bridge has its own resolver. A passive bridge remains an exact object-fragment requirement without becoming a registry vertex.

The graph API is defined in [`ExecutorRegistry.kt`](./model/src/main/kotlin/model/registry/ExecutorRegistry.kt), while assembly and invariant checks are pre-reasoning fixture infrastructure. [`ResolverDemandTest.kt`](./model/src/test/kotlin/model/registry/ResolverDemandTest.kt) exercises nested reachability, polymorphic lowered fields, bridge edges, transposition, and cycle rejection.

## Current Scope

Inputs are post-validation and all named fragment spreads are assumed to have been inlined. The `TestWorld` parsing fixture accepts one named fragment definition as a parsing envelope, ignores its name, rejects nested named spreads, and supplies its nominal type and flattened `SelectionForest` to semantic reasoning.

`@skip` and `@include` belong to the eventual field-resolution model but are deferred. Applied directives are currently rejected. Query fragments, `fromQueryField`, `@parent`, lazy executor values, checkers, and raw-versus-checked dependency distinctions are also not yet modeled. Execution variables are intentionally limited to `fromObjectField` paths rooted at the defining resolver's current OER occurrence. `fromArgument` and `VariablesProvider` are excluded from the modeling roadmap because they do not require OER fields to be resolved in order to produce their values and therefore add no interesting demand dependency for the present problem. The roadmap next addresses `@parent` and then checkers. `EngineResult.Cell.check` remains in the carrier algebra for that future work, but the initial `correctResolution` judgment is explicitly check-insensitive.

The current `correctResolution` judgment requires its supplied operation fragment to contain no unresolved variables; operation-variable substitution is pre-reasoning parsing or composition work. Registered execution variables may occur in resolver fragments and provider paths, and the judgment instantiates those fragments from each OER's stored bindings before comparing demand or resolver output. An argument tuple containing `Value.Error` requires the error cell mandated by the OER carrier invariant and does not invoke the registered field resolver.

Every argument-bearing output field is currently assumed to have an explicit field resolver. Production namespace exceptions are outside the model.

The registry currently rejects every cycle in the conservative coordinate-level resolver-demand graph. This intentionally includes false-positive cycles whose exact active occurrences would be acyclic, such as a syntactic cycle broken by an error-valued resolver argument. `evergreen.md` records legal production RSS cycles as an eventual hard case, so conservative cycle rejection is a present scope constraint rather than a general claim about Viaduct.

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
