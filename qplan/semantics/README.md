# Semantics

The semantics project defines transformations, resolver algorithms, and correctness judgments over qplan's model carriers. It constructs model values but does not redefine or defensively re-check carrier invariants established by `model`.

## Principal Judgment

```kotlin
context(operation: OperationContext)
fun ObjectEngineResult.correctResolution(selections: ObjectSelectionForest): Boolean
```

`correctResolution` judges the value slots of a completed primary Query OER extensionally. When a field resolver declares a nonempty Query-rooted fragment, the judgment also requires the independently resolved Query OER stored for that exact resolver occurrence to be correct and uses its materialized value when re-evaluating the resolver relation. Reapplication supplies the finite canonical demand reconstructed from the completed output occurrence; this is sufficient under the selective-function agreement law and is not a claim that the judgment recovered the algorithm's original supplied demand. Access-result slots are deliberately outside this judgment: access checks are future qplan work, and maintained resolver versions do not publish or agree on those slots. It also does not establish resolver application count, supplied demand, execution order, provider binding, lifecycle ownership, or concurrency. Those properties require separate witnesses and tests. [`testing-contracts.md`](./testing-contracts.md#resolver-fixture-and-oracle-boundary) explains why the judgment reapplies resolver relations and how `FieldResolver.of` and `FieldResolver.ofSelective` define different test-oracle boundaries.

## Vocabulary

A type with the suffix **Context** is a structurally immutable, scope-specific bundle of stable references that are commonly passed together. A context may refer to mutable state, but it does not expose mutable storage or define state transitions itself. `OperationContext` is the shared semantics boundary for one resolution operation; Resolver26 extends it with request-scope references and Resolver26-specific state.

A type with the suffix **State** owns one mutable protocol. Its storage is private, and its methods expose the protocol's legal reads and transitions. `VariableBindingsState`, `CycleCheckState`, `BindingDeclarationsState`, and `QueryValuesState` keep mutation out of configuration and context types.

A type with the suffix **Observer** is semantically passive instrumentation. Replacing a normally returning observer that does not mutate resolver inputs or state with its NOP implementation preserves resolution's semantic input/output behavior. A synchronous observer can still affect failure or latency by throwing or blocking, so the convention is not a claim that arbitrary observer implementations are operationally invisible.

Resolver classes use `init` blocks only for constructor preconditions and object invariants. Property initializers and explicit lifecycle methods perform initialization and operational transitions; an `init` block must not enqueue work, publish state, or otherwise start the resolution lifecycle.

An **OER** is an `ObjectEngineResult`, always associated with one concrete GraphQL object type. An **LER** is a `ListEngineResult`, whose element cells preserve exact list positions. The ordinary object-field and list-element containment edges form a well-founded structural tree. A demanded `@parent` field adds the sole distinguished backedge: its `ParentKey` cell references the actual immediate ancestor OER. Structural traversals and occurrence paths exclude parent backedges; finite selection-driven materialization may follow them.

When discussing relationships among OER occurrences, a list is treated as a one-to-many path edge. The object containing a list field is therefore the parent of each object element for resolver-ancestry purposes, while each `ListEngineResult.Index` remains part of the element's exact identity.

A schema-derived map validates each argument-free, singular composite `@parent` field and associates it with its unique compatible argument-free producer field. Producer output may contain the child directly or through any finite list nesting. This relation supports demand lifting from a child occurrence to the producer's containing ancestor.

An **active field** has a standard registered field resolver. At a particular output occurrence, an argumentless active field is dynamically passive when the resolver that owns an ancestor output region supplies it; otherwise its standard resolver owns it. Fields with arguments are always active and may never be supplied passively. A resolver's **fringe** is the set of produced object occurrences whose selected fields require further active resolution.

A **resolver template** is the static registry definition for one concrete object field. A **resolver instance** is the dynamic application associated with one exact field key on one OER occurrence. Unqualified "resolver" usually means the instance when discussing execution and the template when discussing registry structure; use the full term where that distinction matters.

**Content** is the materialized object fragment, Query fragment, and arguments a resolver consumes, or the output value it produces. A resolver is a **reader** or consumer of resolver-produced fields selected by either input fragment; the inverse producer is sometimes called the **author**. At the template level this is a guarded may-read relationship, while actual execution relates exact resolver instances.

A producer is a **predecessor** of a consumer when the consumer must materialize content produced by that resolver instance. The inverse relation is **successor**. Predecessor edges arise both from object-fragment reads and from the resolver instance that creates a descendant OER; runtime variables may add value-flow dependencies. These are occurrence relationships, not merely relationships between schema coordinates.

`SlotOrchestrator` and `SlotResolver` are names specific to the Resolver06-08 `DepthFirstReactor`: the orchestrator coordinates active fields for one OER, while slot resolvers produce exact values and expose descendant fringe work. Depth-first traversal is an implementation property of the recursive and reactor progressions, not a universal semantic assumption; the coroutine resolvers express readiness through promises and structured ownership instead.

## Shared Semantic Boundaries

Open selections are specialized to a concrete object type with `merge(type)`. The resulting `ObjectKey` values may identify OER cells directly. Bindings are instantiated before operations cross through `groundKeys()`, `byGroundKey()`, or `ObjectSelection.groundKey()` when those operations require resolved argument values.

Model fixture preparation accepts resolver selection documents that retain named fragment definitions. The current selection carrier cannot represent named fragment spreads, so `fragmentFromDocument` owns lowering those spreads to inline fragments before semantic reasoning. Keeping that conversion beside the model carrier's other parsing helpers allows a future carrier to preserve or optimize named fragments without requiring execution adapters to pre-process them. Resolver query fragments are a different concept: they are Query-rooted resolver inputs that are resolved into an independent OER for each owning resolver occurrence.

`resolvers/resolver01/DepthFirstResolve.kt` contains the recursive monotonic constructor used by Resolver01-03. `resolvers/ResolvePassiveValues.kt` builds passive result structure shared by Resolver01-23, retains child OERs that require active work, and populates those children without replacing their published parents. The `resolvers` package contains the remaining operations shared by those maintained resolver families.

`resolvers/resolver06/DepthFirstReactor.kt` expresses the Resolver06-08 progression as explicit orchestrator and slot-resolver work. `resolvers/resolver21/CoroutineResolve.kt` expresses Resolver21-23 through structured suspension and exact promises. The `shared` package contains the operation context, variable-binding and cycle-check state, observer boundaries, grounding, materialization, and other operations used across resolver and correctness boundaries. [`resolver-versions.md`](../resolver-versions.md) explains the capability grid and how to use it.

Resolver26 is the self-contained advanced resolver with runtime from-field bindings. It supports both `FromObjectField` and `FromQueryField`; its current protocol is documented in [`resolver26/design.md`](./src/main/kotlin/semantics/resolver26/design.md).

Resolver22/23 and Resolver26 resolve `@parent` selections. Each installs the child's parent cell with the containing ancestor OER itself, including for list and nested-list child occurrences. Resolver input fragments may not use variables beneath a parent selection. Demand on `parent { ... }` is lifted one ancestor at a time, so repeated static closure handles grandparents. Resolver26 transposes parent demand into selective producer output through successor demand and independently adds parent-induced ancestor work through input-demand closure.

Resolver01-03 and Resolver06-08 reject `@parent` demand. Their depth-first progression orders sibling resolver keys within one OER, but parent-induced work can require leaving a descendant for an ancestor and re-entering that same still-open descendant before either resolver can complete. Supporting that graph re-entry would require occurrence-aware suspension or orchestration beyond their intentionally small execution models. [`examples.md`](../examples.md#why-the-depth-first-resolvers-do-not-support-parent) demonstrates the ordering problem.

## Variable Production And Consumption

A variable recipe determines where one resolver-occurrence binding is produced. Independently, every occurrence of that variable in the resolver's object fragment or Query fragment is a consumer of the same binding. The fragment that consumes a variable does not determine or change its source, and a binding may be consumed by either fragment or by both.

| Recipe | Binding producer | Legal consumers |
| --- | --- | --- |
| `FromArgument` | A path rooted at an argument of the defining resolver occurrence | Object fragment, Query fragment, or both |
| `FromObjectField` | A provider path in the defining resolver's object fragment | Object fragment, Query fragment, or both |
| `FromQueryField` | A provider path in the defining resolver's Query fragment | Object fragment, Query fragment, or both |

Producer/consumer legality is distinct from current implementation support. `FromArgument` is implemented by every maintained resolver, while Resolver26 implements `FromObjectField` and `FromQueryField`. Resolver26 permits either from-field binding to be consumed by its object fragment, Query fragment, or both.

## Publication

OER construction is monotonic. Active cells have one writer, parent values may publish stable child OERs before those children complete, and each algorithm must install or reserve discoverable child work before a reader can depend on it.

Resolver-visible `EngineObjectData` does not retain OER identity. Materializing a parent selection follows the selected parent backedge and constructs a fresh finite EOD projection; the resulting object is not reference-equal to an EOD previously used to construct or materialize that ancestor. Parent backedges therefore do not enter the EOD carrier.

Sometimes-passive active fields make transitive parent demand conservative: a grandchild field that ultimately remains passive can still lift `parent.parent` demand and resolve or materialize unused ancestor fields. This marginal speculative work is an accepted precision tradeoff for keeping closure monotonic and independent of the later dynamic ownership decision.

## Testing And Benchmarks

- [`testing-contracts.md`](./testing-contracts.md) defines capability contracts, policy mixins, generated profiles, and exact replay.
- [`resolver26/testing-resolver26.md`](./src/main/kotlin/semantics/resolver26/testing-resolver26.md) defines Resolver26 concurrency and stress operation.
- [`resolver-benchmarks.md`](./resolver-benchmarks.md) defines JMH workloads and reporting requirements.

Start generated-failure investigation with coordinate replay rather than rerunning a whole class or campaign.
