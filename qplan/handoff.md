# Correct OER Specification Handoff

## Purpose

This is a historical handoff for the shift toward defining predicates and invariants for a correct
`ObjectEngineResult` (OER). The current architecture and continuation point are recorded in
[Query-Planning Model Architecture and Selection-Flattening Handoff](https://slate.airbnb.tools/RJDGeEFw2Q/DRAFT+Query-Planning+Model+Architecture+and+Selection-Flattening+Handoff).
The current repository state is summarized below so that the historical instructions are not
mistaken for next steps.

Use this document for prior context:

- [Query Plan Research](./evergreen.md) is the evergreen source for the problem statement, vocabulary, established findings, hard cases, correctness constraints, and validation strategy. This handoff assumes that material rather than restating it.

## Current Repository State

This repository currently contains one Gradle project, `model`, with a conventional source layout:

- model declarations and GraphQL Java-backed construction live under
  [`model/src/main/kotlin/model/`](./model/src/main/kotlin/model/); and
- executable examples of model construction and variable behavior live under
  [`model/src/test/kotlin/model/`](./model/src/test/kotlin/model/).

There are no executable JVM-global `schema` or `variableValues` declarations and no
`establishAssumptions` initializer. `GlobalAssumptions` supplies the `schema` and `variableValues`
for one reasoning world and parses validated named fragments into `SpecSelection` values.
`GJAssumptions` is the GraphQL Java-backed implementation. Main sources use `jakarta.inject`
annotations so constructors are injection-ready, but the project does not select or depend on a DI
framework.

The `model` project defines both GraphQL-shaped `SpecSelection` values and flattened
field-resolution `Selection` values. Semantic equality for `Selection` remains undefined. A
separate semantics project and the operation that flattens `SpecSelection` values into `Selection`
values are intentionally absent from this model-only snapshot; they can be reconstructed in later
history.

## Historical Change in Direction

We initially tried to move directly from the research findings to an executable dependency graph. During this session, type conditions exposed a more fundamental problem that would organize the proposed next phase:

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

The session chose to answer the first question before returning to the second.

## Historical Correctness Direction

We briefly considered defining a deterministic, deliberately naive reference executor and proving the production executor equivalent to it. That would still require relating two operational state machines, especially their intermediate treatment of type conditions and demand.

The proposed direction was instead to define correctness directly over the result. Under the assumption that executors return the same semantic values and concrete types for the same inputs, independent of the selection set supplied to them, a fixed execution world should determine one minimal **perfect OER**.

The intended shape of the definition is:

```text
PerfectOer(inputs, operation, oer)
```

The exact partitioning of `inputs` is not important yet. It includes a schema, the actual `objectFragments`, the available executors, and a world that fixes any ambient facts needed to interpret them. Executors may instead be considered part of that world.

What matters is the executor contract. For this model, an ordinary executor that takes an `objectFragment` is a deterministic function of only the values supplied for that `objectFragment`. A node resolver is a deterministic function of only its node ID. Any other ambient state is held fixed by the world. The supplied output selection set is not a semantic input: changing it may change projection or cost, but not the values or concrete types denoted for the same executor inputs.

`PerfectOer` says which cells exist and what values and checks they contain. It does not mention planner nodes, dependency counts, readiness, or execution order.

This also gives us a cleaner target for a production proof. An execution algorithm is correct when its output satisfies the result predicate, regardless of how it reached that output.

## Historical Result-Shaped Requirements

We also considered identifying result positions with root-to-leaf syntactic locators. Those locators no longer look like the right foundation. Requirements should instead resemble the OER tree they constrain. A preliminary shape is:

```kotlin
data class RequirementNode(
    val fields: Map<
        GraphQLObjectType,
        Map<ObjectEngineResult.Key, RequirementNode>,
    >,
)
```

For an object result of concrete type `T`, the `T` branch states which field keys are required. The real type would need to account for lists, scalar leaves, checks, ownership boundaries, and RSS targets, so this was an intuition rather than an API proposal.

Semantically, requirements should unfold as a tree. An implementation may share identical continuation nodes as a DAG, but only when the shared subtree has no remaining dependence on ancestry, target scope, ownership, or an ancestor type condition. Sharing is a representation choice, not part of correctness.

Another useful interpretation is:

```text
Demand : RuntimeTypeAssignment -> RequirementTree
```

Each complete runtime type assignment produces an ordinary monomorphic requirement tree. A guarded representation compactly describes that family. The correctness definition can evaluate the guards against the actual concrete types supplied by `world`, even though an executor learns those types incrementally.

## Historical Model Snapshot

At the time of this handoff, the carrier model lived directly in [`model/`](./model/). It has since
moved to the `model` Gradle project's conventional source tree. Read
[`model/AGENTS.md`](./model/AGENTS.md) before extending it; that file records the current modeling
discipline and scope boundaries.

The decisions recorded for the proposed next phase were:

- `Schema` now models the canonical query root, named input and output types, fields and arguments, type expressions, defaults, `__typename`, and the declarative relations needed to reason about polymorphic selection sets.
- The schema was treated as a stipulated input to each reasoning exercise. It is now supplied by
  `GlobalAssumptions.schema`, not an executable JVM-global declaration. Schema definitions use
  canonical identity, while references from other modeling domains use type names and field
  coordinates.
- `EngineResult` is a finite value tree containing object, list, and simple results.
- An OER field contains one `Cell` with a nullable value and a check result.
- Missing fields are distinct from present fields whose values are null.
- Keys present in an OER contain a schema field name and fully coerced arguments. They contain
  neither aliases nor unresolved variables; `ObjectEngineResult.Key` values used outside an OER
  may contain unresolved variables.
- Executor output values and OER values are separate representations.
- Errors are collapsed to `GraphQLErrorValue`.
- Correctness, demand, and checked-versus-raw semantics intentionally remain outside the `model` package.

The schema model was judged sufficient for the immediate polymorphism work. Other semantic inputs
were not yet modeled, including the supported operation and selection structures, `objectFragments`,
executor functions, and the execution world that fixes their denotations. The carrier model also
excluded custom scalars, precise error metadata, and lazy values or references returned by
executors.

## Historical Modeling Scope

The proposed next round was to isolate the polymorphism problem and model how type-conditioned
demand aggregates bottom-up while concrete object types resolve top-down. This scope is retained as
historical context, not as current instructions.

That round proposed:

- including `objectFragments`, including `objectFragments` on `Query` fields;
- excluding `queryFragments`;
- excluding variables and variable providers, so arguments would be literal and fully coerced;
- excluding `@skip` and `@include`; and
- deferring other directive-controlled applicability.

These exclusions were intended to be temporary. They removed independent sources of conditional
demand to isolate what polymorphism alone required from the result predicate and demand closure.

## Historical Next Definitions

The proposed sequence below predates the current `model` Gradle project and its selection models.
It is not the current work queue:

1. **Remaining semantic inputs.** Define the supported operation, `objectFragments`, executor
   functions, and world assumptions against which an OER is judged. Schema and variable bindings
   belong to `GlobalAssumptions`.
2. **Executor denotations.** Define ordinary executors as deterministic functions of their object-fragment values and node resolvers as deterministic functions of node IDs.
3. **Type applicability.** Given an object occurrence and its concrete type, determine which type-conditioned selections apply and construct their alias-free, fully coerced OER keys.
4. **Polymorphic required-cell closure.** Seed demand from the external operation, add demand induced by `objectFragments`, and define the least closure while preserving unresolved type conditions as guards.
5. **Nested result correctness.** Define how the guarded requirements specialize over concrete object and list results.
6. **Perfect OER.** Require exactly the cells in the specialized least closure, with the correct values and checks.

The sequence also left open whether to define a second predicate permitting additional semantically
correct cells. Such a predicate would separate perfect minimality from the correctness of a
production executor that conservatively over-materializes.

The desired result was an existence-and-uniqueness statement:

```text
For a fixed supported execution world and operation,
there exists exactly one perfect OER.
```

## Historical First Examples

The proposed examples were:

- a type-conditioned passive field;
- the guarded `B/helper` RSS case above;
- sibling `objectFragment` consumers whose guarded demand converges on one producer;
- two concrete implementations that induce different demand at the same result position;
- two concrete implementations that induce the same continuation and may share its representation; and
- a list containing more than one concrete object type.

The intent was for these examples to become assertions over later semantic definitions, not
simulations of an executor. Such assertions would be finite evidence rather than formal proofs.

## Historical Deferred Questions

The deferred questions were `queryFragments`, variables and variable providers, `@skip`/`@include`,
other directives, `@parent`, lazy executor values, legal RSS cycles, and whether conservative extra
cells should be accepted by a separate coverage predicate.

The historical recommendation was to defer production scheduler design until the output semantics
were stable, then ask what static structures and runtime transitions were sufficient to establish
them.
