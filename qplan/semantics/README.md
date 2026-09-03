# Semantics

The semantics project defines transformations, resolver algorithms, and correctness judgments over qplan's model carriers. It constructs model values but does not redefine or defensively re-check carrier invariants established by `model`.

## Principal Judgment

```kotlin
context(world: Assumptions)
fun ObjectEngineResult.correctResolution(selections: ObjectSelectionForest): Boolean
```

`correctResolution` judges the value slots of a completed primary Query OER extensionally. When a field resolver declares a nonempty Query-rooted fragment, the judgment also requires the independently resolved Query OER stored for that exact resolver occurrence to be correct and uses its materialized value when re-evaluating the resolver relation. Reapplication supplies the finite canonical demand reconstructed from the completed output occurrence; this is sufficient under the selective-function agreement law and is not a claim that the judgment recovered the algorithm's original supplied demand. Access-result slots are deliberately outside this judgment: access checks are future qplan work, and maintained resolver versions are not currently required to publish or agree on those slots even though some maintained resolvers populate them with `true`. It also does not establish resolver application count, supplied demand, execution order, provider binding, lifecycle ownership, or concurrency. Those properties require separate witnesses and tests. [`testing-contracts.md`](./testing-contracts.md#resolver-fixture-and-oracle-boundary) explains why the judgment reapplies resolver relations and how `FieldResolver.of` and `FieldResolver.ofSelective` define different test-oracle boundaries.

## Vocabulary

An **OER** is an `ObjectEngineResult`, always associated with one concrete GraphQL object type. An **LER** is a `ListEngineResult`, whose element cells preserve exact list positions. "OER tree" is convenient shorthand for the complete engine-result tree because active resolver work occurs at object occurrences; list containers and pre-domain scalar values remain explicit parts of the physical result.

When discussing relationships among OER occurrences, a list is treated as a one-to-many path edge. The object containing a list field is therefore the parent of each object element for resolver-ancestry purposes, while each `ListEngineResult.Index` remains part of the element's exact identity.

An **active field** has a standard registered field resolver. At a particular output occurrence, an argumentless active field is dynamically passive when the resolver that owns an ancestor output region supplies it; otherwise its standard resolver owns it. Fields with arguments are always active and may never be supplied passively. A resolver's **fringe** is the set of produced object occurrences whose selected fields require further active resolution.

A **resolver template** is the static registry definition for one concrete object field. A **resolver instance** is the dynamic application associated with one exact field key on one OER occurrence. Unqualified "resolver" usually means the instance when discussing execution and the template when discussing registry structure; use the full term where that distinction matters.

**Content** is the materialized object fragment, Query fragment, and arguments a resolver consumes, or the output value it produces. A resolver is a **reader** or consumer of resolver-produced fields selected by either input fragment; the inverse producer is sometimes called the **author**. At the template level this is a guarded may-read relationship, while actual execution relates exact resolver instances.

A producer is a **predecessor** of a consumer when the consumer must materialize content produced by that resolver instance. The inverse relation is **successor**. Predecessor edges arise both from object-fragment reads and from the resolver instance that creates a descendant OER; runtime variables may add value-flow dependencies. These are occurrence relationships, not merely relationships between schema coordinates.

`SlotOrchestrator` and `SlotResolver` are names specific to the Resolver06-08 `DepthFirstReactor`: the orchestrator coordinates active fields for one OER, while slot resolvers produce exact values and expose descendant fringe work. Depth-first traversal is an implementation property of the recursive and reactor progressions, not a universal semantic assumption; the coroutine resolvers express readiness through promises and structured ownership instead.

## Shared Semantic Boundaries

Open selections are specialized to a concrete object type with `merge(type)`. The resulting `ObjectKey` values may identify OER cells directly. Bindings are instantiated before operations cross through `groundKeys()`, `byGroundKey()`, or `ObjectSelection.groundKey()` when those operations require resolved argument values.

Semantics accepts resolver selection documents that retain named fragment definitions. The current semantic selection carrier cannot represent named fragment spreads, so `fragmentFromDocument` owns lowering those spreads to inline fragments. Keeping that conversion at the semantics boundary allows a future carrier to preserve or optimize named fragments without requiring execution adapters to pre-process them. Resolver query fragments are a different concept: they are Query-rooted resolver inputs that are resolved into an independent OER for each owning resolver occurrence.

`Resolve.kt` contains the recursive monotonic constructor used by Resolver01-03. `ResolvePassiveValues.kt` builds passive result structure, retains child OERs that require active work, and populates those children without replacing their published parents.

`DepthFirstReactor` expresses the Resolver06-08 progression as explicit orchestrator and slot-resolver work. `CoroutineResolve.kt` expresses Resolver21-23 through structured suspension and exact promises. [`resolver-versions.md`](../resolver-versions.md) explains the capability grid and how to use it.

Resolver26 is the self-contained advanced resolver with runtime from-field bindings. It supports both `FromObjectField` and `FromQueryField`; its current protocol is documented in [`resolver26/design.md`](./src/main/kotlin/semantics/resolver26/design.md).

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

## Testing And Benchmarks

- [`testing-contracts.md`](./testing-contracts.md) defines capability contracts, policy mixins, generated profiles, and exact replay.
- [`resolver26/testing-resolver26.md`](./src/main/kotlin/semantics/resolver26/testing-resolver26.md) defines Resolver26 concurrency and stress operation.
- [`resolver-benchmarks.md`](./resolver-benchmarks.md) defines JMH workloads and reporting requirements.

Start generated-failure investigation with coordinate replay rather than rerunning a whole class or campaign.
