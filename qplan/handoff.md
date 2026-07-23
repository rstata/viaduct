# Correct OER Specification Handoff

## Purpose

The next phase of query-planning work is to define predicates and invariants for a correct `ObjectEngineResult` (OER). This handoff records only the change in direction and the work completed since the existing research was written.

Use this document for prior context:

- [Query Plan Research](./evergreen.md) is the evergreen source for the problem statement, vocabulary, established findings, hard cases, correctness constraints, and validation strategy. This handoff assumes that material rather than restating it.

## Change in Direction

We initially tried to move directly from the research findings to an executable dependency graph. During this session, type conditions exposed a more fundamental problem that should organize the next phase:

> **Demand aggregates bottom-up, while concrete type information resolves top-down.**

An RSS on a descendant executor can add demand to an ancestor producer, so discovering the complete demand starts with consumers and moves toward their producers. Whether that demand applies can depend on the concrete type produced by an ancestor, which is learned in the opposite direction. Neither flow can simply run to completion before the other.

We explored scheduler states such as may-run, will-not-run, and eligible, along with a second count for unresolved demand contributors. That made the demand supplied to a producer depend on future scheduler decisions about which consumers would run. The useful result of that exploration was not the state machine. It was the realization that bottom-up demand can retain top-down type conditions as guards.

Consider:

```graphql
p {
  ... on B {
    expensive
  }
}
```

If the resolver for `expensive` requires the passive field `helper` from `p`, then `p` can receive:

```graphql
p {
  ... on B {
    helper
  }
}
```

We do not need to know whether `p` will produce an `A` or a `B` before starting it. The demand itself says what is needed in the `B` case. Runtime type resolution later determines whether the guarded requirement applies and whether the `expensive` resolver runs.

This separates two questions:

1. What values must a correct execution contain under each concrete type?
2. How does an implementation schedule the work that produces those values?

We are now answering the first question before returning to the second.

## Correctness as an Output Predicate

We briefly considered defining a deterministic, deliberately naive reference executor and proving the production executor equivalent to it. That would still require relating two operational state machines, especially their intermediate treatment of type conditions and demand.

Instead, we will define correctness directly over the result. Under the assumption that executors return the same semantic values and concrete types for the same inputs, independent of the selection set supplied to them, a fixed execution world should determine one minimal **perfect OER**.

The intended shape of the definition is:

```text
PerfectOer(inputs, operation, oer)
```

The exact partitioning of `inputs` is not important yet. It includes a schema, the actual `objectFragments`, the available executors, and a world that fixes any ambient facts needed to interpret them. Executors may instead be considered part of that world.

What matters is the executor contract. For this model, an ordinary executor that takes an `objectFragment` is a deterministic function of only the values supplied for that `objectFragment`. A node resolver is a deterministic function of only its node ID. Any other ambient state is held fixed by the world. The supplied output selection set is not a semantic input: changing it may change projection or cost, but not the values or concrete types denoted for the same executor inputs.

`PerfectOer` says which cells exist and what values and checks they contain. It does not mention planner nodes, dependency counts, readiness, or execution order.

This also gives us a cleaner target for a production proof. An execution algorithm is correct when its output satisfies the result predicate, regardless of how it reached that output.

## Result-Shaped Requirements

We also considered identifying result positions with root-to-leaf syntactic locators. Those locators no longer look like the right foundation. Requirements should instead resemble the OER tree they constrain. A preliminary shape is:

```kotlin
data class RequirementNode(
    val fields: Map<
        GraphQLObjectType,
        Map<ObjectEngineResult.Key, RequirementNode>,
    >,
)
```

For an object result of concrete type `T`, the `T` branch states which field keys are required. The real type will need to account for lists, scalar leaves, checks, ownership boundaries, and RSS targets, so this is an intuition rather than an API proposal.

Semantically, requirements should unfold as a tree. An implementation may share identical continuation nodes as a DAG, but only when the shared subtree has no remaining dependence on ancestry, target scope, ownership, or an ancestor type condition. Sharing is a representation choice, not part of correctness.

Another useful interpretation is:

```text
Demand : RuntimeTypeAssignment -> RequirementTree
```

Each complete runtime type assignment produces an ordinary monomorphic requirement tree. A guarded representation compactly describes that family. The correctness definition can evaluate the guards against the actual concrete types supplied by `world`, even though an executor learns those types incrementally.

## Model Now Available

The carrier model built during this session lives in [`model/`](./model/). Read [`model/AGENTS.md`](./model/AGENTS.md) before extending it; that file records the modeling discipline and scope boundaries.

The decisions most relevant to the next phase are:

- `Schema` now models the canonical query root, named input and output types, fields and arguments, type expressions, defaults, `__typename`, and the declarative relations needed to reason about polymorphic selection sets.
- The global `schema` is a stipulated input to each reasoning exercise. Schema definitions use canonical identity, while references from other modeling domains use type names and field coordinates.
- `EngineResult` is a finite value tree containing object, list, and simple results.
- An OER field contains one `Cell` with a nullable value and a check result.
- Missing fields are distinct from present fields whose values are null.
- OER keys contain a schema field name and fully coerced arguments. They do not contain aliases or unresolved variables.
- Executor output values and OER values are separate representations.
- Errors are collapsed to `GraphQLErrorValue`.
- Correctness, demand, and checked-versus-raw semantics intentionally remain outside the `model` package.

The schema model is sufficient for the immediate polymorphism work. Other semantic inputs still
need models, including the supported operation and selection structures, `objectFragments`,
executor functions, and the execution world that fixes their denotations. The carrier model still
excludes custom scalars, precise error metadata, and lazy values or references returned by
executors.

## Immediate Modeling Scope

The next round should isolate the polymorphism problem. We will model how type-conditioned demand aggregates bottom-up while concrete object types resolve top-down.

For this round:

- include `objectFragments`, including `objectFragments` on `Query` fields;
- exclude `queryFragments`;
- exclude variables and variable providers, so arguments are literal and fully coerced;
- exclude `@skip` and `@include`; and
- defer other directive-controlled applicability.

These exclusions are temporary. They remove independent sources of conditional demand so we can understand what polymorphism alone requires from the result predicate and demand closure.

## Next Definitions

Add the correctness work in a new package alongside `model`. The first pass should define, in this order:

1. **Remaining semantic inputs.** Use the existing global `schema`, and define the supported operation, `objectFragments`, executor functions, and world assumptions against which an OER is judged.
2. **Executor denotations.** Define ordinary executors as deterministic functions of their object-fragment values and node resolvers as deterministic functions of node IDs.
3. **Type applicability.** Given an object occurrence and its concrete type, determine which type-conditioned selections apply and construct their alias-free, fully coerced OER keys.
4. **Polymorphic required-cell closure.** Seed demand from the external operation, add demand induced by `objectFragments`, and define the least closure while preserving unresolved type conditions as guards.
5. **Nested result correctness.** Define how the guarded requirements specialize over concrete object and list results.
6. **Perfect OER.** Require exactly the cells in the specialized least closure, with the correct values and checks.

It may also be useful to define a second predicate that permits additional semantically correct cells. That would separate perfect minimality from the correctness of a production executor that conservatively over-materializes. We should decide whether that distinction is needed after the exact perfect-OER predicate is written.

The desired result is an existence-and-uniqueness statement:

```text
For a fixed supported execution world and operation,
there exists exactly one perfect OER.
```

## First Examples

Use small examples to force the definitions into concrete form:

- a type-conditioned passive field;
- the guarded `B/helper` RSS case above;
- sibling `objectFragment` consumers whose guarded demand converges on one producer;
- two concrete implementations that induce different demand at the same result position;
- two concrete implementations that induce the same continuation and may share its representation; and
- a list containing more than one concrete object type.

These examples should become assertions over the definitions, not simulations of an executor.

## Deferred Questions

After the polymorphic closure is understood, later rounds must add `queryFragments`, variables and variable providers, `@skip`/`@include`, other directives, `@parent`, lazy executor values, and legal RSS cycles. We must also decide whether conservative extra cells are accepted by a separate coverage predicate.

Do not resume the production scheduler design while these output semantics are still unsettled. Once the predicates are stable, we can return to planning and ask what static structures and runtime transitions are sufficient to establish them.
