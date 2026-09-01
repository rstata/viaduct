# Qplan Design Principles

## Purpose

This document records durable principles for the qplan model and its resolver algorithms. The evidence, known hard cases, and source provenance behind them live in [`research-evidence.md`](./research-evidence.md). Current implementation state and scope boundaries belong in [`handoff.md`](./handoff.md), practical workflows belong in [`maintainer-guide.md`](./maintainer-guide.md), and completed chronology belongs in Git history.

## A Compiling Mathematical Model

Compiling Kotlin is qplan's primary specification language. Model declarations denote sets, values, functions, relations, and partial operations; they do not imply JVM timing, allocation, caching, or effects unless the model explicitly represents those concepts.

Each reasoning exercise fixes one canonical `Assumptions`, lowered `ViaductSchema`, and resolver registry. Source GraphQL parsing, schema lowering, registry assembly, node adaptation, and provider-path compilation are pre-reasoning composition. Semantic code trusts the carrier invariants established at that boundary.

A schema definition may retain a canonical opaque foreign attachment needed at an integration boundary. Such an attachment is not a model value: semantic logic does not inspect it or use it in equality, hashing, conformance, or schema relations. Qplan source-backed objects retain the exact definitions from the unchanged source GraphQL-Java schema for Viaduct Engine API type witnesses; synthetic bridge objects retain generated witnesses for internal values only.

Compilation, examples, generated tests, stress campaigns, and cross-resolver agreement are finite consistency evidence. They are not mathematical proof. The TLA+ baseline proves only its explicitly stated finite calculus and assumptions.

## Keep Semantic Domains Distinct

Selections may contain open `ObjectEngineResult.Key` values. An `ObjectEngineResult.ObjectKey` has a concrete object field and is therefore eligible for exact OER cells and result paths even when its arguments contain instantiated variables. `ObjectEngineResult.GroundKey` is the refinement whose arguments have resolved; operations such as resolver invocation that require input values must cross that checked boundary explicitly. EOD selections are strings and never contain OER keys.

Resolver input demand, client demand, output projection demand, symbolic or potential demand, and supplied demand serve different purposes. Resolver-owned output, internal selection forests, tenant-visible GraphQL fragments, and completed result coverage are likewise related but not interchangeable representations.

Cross each boundary through an explicit checked operation. Do not make exact operations tolerate open values or reuse one demand representation merely because two values happen to coincide in one example.

Semantic domains need not be nominal Kotlin hierarchies. Performance-sensitive domains may be represented by `Any` typealiases whose members are specified by documented conformance relations and checked at construction, publication, and conversion boundaries. Distinct aliases remain distinct mathematical domains even though Kotlin cannot use them as overload discriminators or prevent an arbitrary `Any` from crossing an unchecked programming boundary.

A semantic union is **equality-homogeneous** when every member has the same equality semantics and **equality-heterogeneous** when different members have different equality semantics. Whole-union equality is useful only for a homogeneous value-equality union; recursive structural equality of lists, maps, and other immutable composites counts as value equality for this classification. Equality on a heterogeneous union has no semantic meaning until an operation narrows its operands to a homogeneous subset with a documented equality relation. `EngineInputData` is homogeneous value-equality. `EngineOutputData` is heterogeneous: its simple-data subset is homogeneous value-equality, while object and error members need not be. `EngineResult` is likewise heterogeneous because scalar values, result occurrences, lists of cell occurrences, and errors have different equality semantics.

A **pre-domain type** supplies an unambiguous runtime representation that one or more semantic domains may admit. Ordinary Kotlin `Int`, finite `Double`, `Boolean`, and `String` values are pre-domain representations. Qplan's result domain additionally uses `EngineIDResult` and canonical `ViaductSchema.EnumValue` values so GraphQL strings, IDs, and enum members remain distinguishable without carrying a nominal result wrapper around every scalar.

Production Viaduct currently overloads Kotlin `String` for GraphQL String, ID, and enum values in engine input and output data. Qplan's carrier model preserves that representation in `EngineInputData` and `EngineOutputData` for current compatibility, while `EngineResult` uses `String`, `EngineIDResult`, and `ViaductSchema.EnumValue` respectively. Crossings between output data and engine results therefore require schema-directed conversion. This deliberate conversion boundary may disappear after production input and output carriers adopt the stronger pre-domain representations.

Errors belong to domains rather than to every pre-domain type. Engine results, engine output data, and argument resolution use distinct error variants; `EngineInputData` has no error member. No error variant impersonates scalar, list, or object interfaces merely to obtain an artificial Kotlin union. `EngineErrorData` owns all metadata associated with an output error, including causal and attributional metadata. When one erroneous field causes another resolver error, the new error chains the source error while adding its own metadata, preserving the complete causal and attributional history in the error value rather than decomposing it into sidecars. Crossing between output data and engine results must preserve that chain without loss.

## Result Occurrence Is Identity

The semantic identity of work is an occurrence in the result tree. Equal node IDs, schema coordinates, arguments, or values do not merge separate object or list occurrences. List indices and concrete containing paths remain part of occurrence identity. Caching, batching, and request deduplication are separate execution layers.

Cells are allocated by their containing OER or LER. Cell reference identity is the cell occurrence identity; a parallel numeric cell identifier would duplicate and risk disagreeing with the carrier.

Symbolic object keys preserve variable-instance identity structurally in their arguments. Equal symbolic keys may occur in different containing OERs without collision because the OER occurrence already supplies their concrete result-tree location.

## One-Shot Correctness Is Producer-Specific

For every resolver-bearing occurrence in scope, all producer-owned values later consumed from that occurrence must be covered by the demand supplied to its one selective resolver application.

A correct final union is weaker. A late cache hit, widened result, or second materialization can make the completed OER look adequate even when the producing application discarded required output. Waiting for all contributors currently known to exist is also weaker than proving that no future contributor can target the producer.

One-shot designs must therefore bound all contributors before application, conservatively include bounded alternatives, assign late demand a distinct occurrence, or reject the shape. Post-application widening cannot repair an under-supplied selective producer.

## Attribute Demand To Its Owner

Demand must be projected through the producer that owns the requested output. Traversal through passive fields remains within the current producer; traversal stops at resolver-bearing boundaries and attributes successor work to the successor resolver.

Resolver object and Query fragments determine input requirements. Resolver arguments identify an eventual resolver instance but do not choose the resolver template or its fixed fragments. This distinction lets symbolic closure discover fixed input requirements before variable values are available.

## Progress Is Monotonic And Strict

Mutable semantic state is limited to documented monotonic stores. An OER or LER cell value, a cell's access result, and a request-local variable binding move from absent to one immediate or deferred promise; a deferred promise completes once.

A parent may publish a stable child OER before the child is complete. Later work fills absent child cells without replacing the parent or rebuilding the subtree.

Duplicate claims, duplicate writers, repeated lifecycle transitions, undeclared binding reads, and unclaimed cells at freeze time should fail visibly. Idempotence must not hide duplicate scheduling or ownership errors.

## Runtime Variables Add Value-Flow Dependencies

`FromArgument` values are available from the defining resolver occurrence. `FromObjectField` values require reading a resolved provider path and can reveal an exact consumer key only later.

Provider paths are compiled and validated before semantic reasoning, but provider evaluation occurs at runtime in Resolver25 and Resolver26. Provider containment and branch ordering are domain restrictions; they are not themselves an execution algorithm.

Substitution precedes exact-key grouping in Resolver01 through Resolver25, so Resolver25 merges selections that become the same ground key before launch. Resolver26 retains symbolic keys: selections with the same field and variable instances coalesce, while keys containing different variable instances remain distinct even when those variables bind to equal values.

## Structured Concurrency Owns Request Lifetime

One request-root scope owns all request coroutines. Successful synchronous return means request quiescence, and failure cancels sibling work through structured ownership.

Cross-task readiness travels through named promises or value-bearing deferreds, not through another task's call stack or `Job` completion. Independent object and list occurrences should not require a global barrier.

## Use Earlier Resolvers To Remove Accidental Complexity

Resolver03 is the compact semantic reference for demand closure, selective projection, exact-key publication, and completed-result correctness.

Resolver08 holds that semantic capability roughly constant while replacing recursive continuation with explicit depth-first work. Comparing Resolver26 with Resolver03 and Resolver08 helps distinguish essential demand semantics from scheduling machinery.

Resolver23 adds structured suspension and promise installation without path-variable identity. It is the clean coroutine comparison.

Resolver10 is useful as a negative lesson rather than a maintained implementation. Readiness rescanning, persistent late-demand acceptance, and complete-output retention added substantial machinery and could mask an incomplete producing application. Do not recreate that architecture to solve a local Resolver26 problem.

Resolver25 remains a useful alternate experiment because it merges late-equal grounded keys. Its current activation protocol is not a general planning solution and should not be imported into Resolver26 by default.

## Validate Independent Properties Independently

`correctResolution` judges the completed primary Query OER and every required occurrence-specific query-fragment OER extensionally. It does not establish resolver application count, supplied demand, binding correctness, execution order, lifecycle ownership, or concurrency.

Keep separate evidence for completed-result correctness, exact and occurrence-aware application identities, object-path bindings, lifecycle invariants, mutation tests, structural activation, and scheduling behavior. An expected-application oracle derived from the completed result under test is not fully independent and must be described accordingly.

Generated presence is weaker than runtime activation. Directed profiles should require the target interaction to execute, and broad campaigns should record enough information to replay one exact `S:R:Q` coordinate. Large green campaigns remain finite evidence and do not override a focused counterexample.

## Engine API Boundary

The qplan model owns carriers such as `EngineResult`, typed keys, schema validation, and occurrence-aware cells. Viaduct's `EngineObjectData.Sync` is the synchronous partial-object boundary and distinguishes an absent field from a present null value. Qplan supplies its own validating implementation so it can preserve schema preconditions and instrumentation points.

Qplan uses Viaduct engine API carriers where they express the same semantic fact while preserving qplan-only structure needed for formal reasoning. Do not erase occurrence identity, ground-key validation, or model invariants merely to reduce source-level differences.

Qplan-owned EODs retain canonical lowered `ViaductSchema.Object` identity for semantic reasoning, but source-backed EODs expose the exact retained source `GraphQLObjectType` through the Engine API. Resolver inputs must have source-schema field shape, and source resolver outputs must be normalized into qplan-owned lowered values before semantic reasoning. Synthetic bridge witnesses and bridge payload objects remain internal to qplan.

EOD is a policy-neutral value boundary. Reading a present erroneous selection should expose its `EngineErrorData`; the Tenant API layer decides how tenant code observes that error, including whether a generated accessor throws. Current Engine API implementations that throw while reading an erroneous selection are transitional behavior that qplan must remain compatible with, not the desired ownership boundary. Until that behavior is removed, qplan-owned EODs should provide an explicit engine-only `outputValue` observation that returns the stored output value without applying Tenant API error policy.

Alignment does not require preserving every fragile production representation inside the result tree. In particular, qplan intentionally distinguishes result-domain ID and enum values even though current production engine input and output data represent both as strings. Keep that mismatch isolated in explicit adapters so a future production migration can remove conversions without changing result semantics.

The aligned boundary keeps the future implementation distance small. It does not make production runtime concerns part of every qplan function or imply that `execution2` design is part of qplan work.
