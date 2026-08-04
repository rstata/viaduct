# Variable Provider Separation Handoff

## Purpose

This handoff records a possible restriction on field-relative variable providers. We previously set the restriction aside because Resolver04 can resume an already visited subtree, and the proposed Resolver05 worklist can populate a write-once child OER incrementally. We are reconsidering it because variable binding can make a symbolic occurrence converge with a field cell that had to execute in order to produce the variable itself. If those occurrences contribute different output demand, one-shot execution requires conservative demand that may be difficult to derive, explain, and implement.

This is an investigation, not yet an accepted invariant. It should be evaluated separately from the provider-containment simplifications in [`invariants-handoff.md`](./invariants-handoff.md) and the demand-availability scheduler in [`execution-handoff.md`](./execution-handoff.md).

## The Late-Convergence Problem

Consider the established example:

```graphql
child {
  field2(arg: $value)
}
common
```

The provider reads `$value` from `common`, while resolving `common` requires:

```graphql
child {
  field2(arg: "literal")
}
```

Suppose `common` returns `"literal"`. The symbolic occurrence then instantiates to the same exact key that was required to produce the variable:

```text
field2(arg: $value)
    becomes
field2(arg: "literal")
```

One terminology distinction matters. A registered field resolver is a deterministic function of its materialized input and exact arguments. If both occurrences become the same `Value.Key`, `resolver.objectFragment(arguments)` is the same exact fragment for both. What may differ is the demanded output contributed by the two selection occurrences. The provider-side occurrence may force the resolver to run before the variable-side occurrence can be grouped with it.

If the first application did not receive the second occurrence's demanded output, the engine cannot apply that exact resolver-bearing OER cell again without violating one-shot execution. Resolver04's ambient and speculative machinery attempts to cover this case conservatively by supplying variable-free output demand from a symbolic occurrence to a concrete occurrence that may later match it. The proposed restriction would instead make this kind of convergence invalid at registry construction.

## Proposed Invariant

The proposed rule is:

> For a variable `v`, no field-cell path required to produce `v` may possibly be a prefix of a path to a selection whose arguments use `v`.

This rule is paired with a second registry invariant:

> Every argument-dependent object fragment is produced by retargeting one fixed selection shape.

The compiling Kotlin model now enforces this restriction by construction. `Resolver.Field.ofArgumentRetargeting` recursively preserves the representative fragment's nominal type, field-coordinate occurrences, type guards, nesting, and occurrence multiplicity. Its callback may substitute or forward values into argument positions already present in that shape, but cannot add, remove, or choose different structural occurrences.

This is stronger than the existing cycle check. The cycle check rejects a variable used directly or transitively as an input to its own production. The new rule also rejects a use that is outside the production closure but lies in an OER subtree entered by that production.

The defining resolver's containing OER is not itself a field-cell path. Provider and use branches may share that root. They must diverge before production enters a field cell that contains, or may become, the variable use.

## Structural Definition

For each registered variable `v`, define:

```text
productionPaths(v)
    Every field-cell occurrence path visited while evaluating provider(v),
    including every path introduced through transitive field-resolver
    objectFragments and variable-provider dependencies.

usePaths(v)
    Every occurrence path to a field selection in the defining resolver's
    objectFragment whose argument values contain v.
```

`productionPaths(v)` includes every traversed prefix. If producing `v` requires `child { nested { value } }`, the set includes paths for `child`, `child.nested`, and `child.nested.value`, with their guards and arguments.

The registry accepts `v` only when:

```text
for every p in productionPaths(v), u in usePaths(v):
    p does not possibly identify a prefix of u
```

Path comparison must preserve the identity distinctions used by the OER:

- Canonical concrete field coordinates.
- Fully coerced argument tuples.
- Symbolic argument values that may later equal concrete values.
- Concrete-type guards and possible-type overlap.
- Every object and list occurrence segment represented by the structural path.
- Alias-free identity, because response aliases do not distinguish OER cells.

The check is conservative. If a symbolic argument could become equal to a concrete argument, the corresponding segments may match. Runtime values, nulls, errors, and concrete-type choices must not be used to justify a registry that is unsafe for another valid execution.

## How The Example Is Rejected

For the running example, the production closure contains:

```text
common
child
child.field2(arg: "literal")
```

The use path is:

```text
child.field2(arg: $value)
```

`child` is a production path and a prefix of the use path, so the registry rejects the variable definition. The terminal `field2` segments may also converge when `$value == "literal"`, but the earlier `child` overlap is already sufficient.

This rejection is independent of the value that `common` returns. Even when `$value` becomes `"bound"` and the two `field2` keys remain distinct, producing the variable still enters the `child` subtree that contains the use. The invariant chooses a simple structural guarantee rather than accepting some runtime values and rejecting others.

## A Valid Shape

This shape can remain valid:

```graphql
common
other {
  field(arg: $value)
}
```

It is valid only if the complete transitive production closure of `common` never enters `other`. Execution can finish the provider branch, bind `$value`, and then enter the disjoint `other` branch with a concrete argument. Variable dependency edges order sibling subtrees without carrying new exact demand into a subtree already used for production.

## Registry Validation

This should be a world-construction invariant, not a runtime check. The executor registry already computes a dependency-first transitive extension of resolver and variable sites. Validation needs a richer product of that traversal which retains rooted occurrence paths instead of reducing everything to `Schema.ResolverSite` coordinates.

For each variable coordinate, registry construction would:

1. Start with the registered provider selection rooted at the defining field's containing object.
2. Follow every field resolver encountered on that path and root its exact or structural-envelope object fragment at the encounter path.
3. Follow variables used by those fragments in dependency order and include the paths required to produce them.
4. Record every traversed field-cell prefix, with arguments and possible-type guards.
5. Traverse every exact defining object fragment and record paths to selections whose nested argument values contain the variable.
6. Reject when a production path may be a prefix of a use path.

The validation should produce a diagnostic naming the variable, its defining resolver, the provider path, the overlapping production prefix, and the use path. A generic demand-cycle message would hide the distinct reason for rejection.

## Fixed-Shape Argument-Dependent Fragments

The representative fragment is the structural envelope for every exact fragment, while the constructor callback is limited to substituting or forwarding argument values within that envelope.

Registry construction should enforce or externally establish:

```text
shape(objectFragment(arguments)) == shape(representativeObjectFragment)
```

for every valid resolver argument tuple. Here `shape` preserves selection coordinates, guards, and tree structure while abstracting argument values. The canonical registry API now represents this operation as a fixed fragment template plus per-key argument retargeting, so Resolver05 cannot discover a different fragment shape after execution begins.

## Consequences

If adopted, this restriction would:

- Eliminate provider/use re-entry and provider-induced late exact-key convergence.
- Restore a depth-first order between each provider branch and every branch using its value.
- Remove the need to conservatively apply symbolic use demand to a concrete cell encountered during production of that same variable.
- Make the representative object fragment a complete static structural envelope for every argument tuple.
- Simplify Resolver04 to one symbolic envelope and Resolver05's obligation-merging rules.
- Reject the current `common` and `child.field2($value)` regression as outside the supported variable domain.

It would not eliminate all variable scheduling. Providers may depend acyclically on other variables, and uses still cannot become exact obligations until their bindings exist. It would make those dependencies order disjoint branches rather than reopen or converge with the provider's production subtree.

The restriction is deliberately conservative. It may reject executions that Resolver04 can handle correctly through speculative demand and widening. The decision is therefore a product-model decision as well as an algorithm simplification: we must establish whether production variable providers permit the rejected overlap before treating the rule as canonical.

## Follow-Up

The separate investigation should:

1. Confirm the intended production semantics of field-relative variable providers and whether provider/use overlap is legal.
2. Inventory current Resolver04 tests and generated worlds rejected by the proposed prefix rule.
3. Build minimal examples for direct exact-key convergence, shared passive prefixes, nested resolver prefixes, polymorphic guards, and argument tuples that are provably disjoint.
4. Evaluate the fixed-template argument-retargeting API against production lowering needs.
5. Prototype occurrence-path extraction in registry assembly without changing Resolver04.
6. Compare the complexity removed from Resolver04 and Resolver05 with the valid production shapes lost.
7. If the invariants are adopted, update [`evergreen.md`](./evergreen.md), all relevant KDoc and guidance, generators, claims, arguments, and handoff documents to describe the restricted domain consistently.

Until that follow-up reaches a decision, Resolver04's existing speculative coverage and widening tests remain evidence for the broader domain and should not be deleted.
