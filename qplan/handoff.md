# Correct OER Specification Handoff

## Purpose

This is a historical handoff for the shift toward defining predicates and invariants for a correct `ObjectEngineResult` (OER). The current architecture and continuation point are recorded in [Query-Planning Model Architecture and Selection-Flattening Handoff](https://slate.airbnb.tools/RJDGeEFw2Q/DRAFT+Query-Planning+Model+Architecture+and+Selection-Flattening+Handoff). The current repository state is summarized below so that the historical instructions are not mistaken for next steps.

Use this document for prior context:

- [Query Plan Research](./evergreen.md) is the evergreen source for the problem statement, vocabulary, established findings, hard cases, correctness constraints, and validation strategy. This handoff assumes that material rather than restating it.

## Current Repository State

This repository currently contains two Gradle projects, `model` and `semantics`, with conventional source layouts:

- model declarations and GraphQL Java-backed construction live under [`model/src/main/kotlin/model/`](./model/src/main/kotlin/model/);
- model examples and checks live under [`model/src/test/kotlin/model/`](./model/src/test/kotlin/model/);
- semantic operations live under [`semantics/src/main/kotlin/semantics/`](./semantics/src/main/kotlin/semantics/); and
- semantic examples and checks live under [`semantics/src/test/kotlin/semantics/`](./semantics/src/test/kotlin/semantics/).

There are no executable JVM-global `schema` or `variableValues` declarations and no `establishAssumptions` initializer. Each reasoning exercise fixes one `Schema` and `Assumptions`; their concrete implementations are annotated `@Singleton`. `Assumptions` supplies the schema and variable bindings for that world and parses validated named fragments into `SpecSelection` values; `Assumptions.of` constructs the GraphQL Java-backed implementation. Every `Schema.Value` is constructed through instance factories on that schema, which validate canonical schema references at the construction boundary. Main sources use `jakarta.inject` annotations so constructors are injection-ready without selecting a DI framework. Guice is a test-only dependency of `semantics`.

Input-object types and output-field argument definitions implement `Schema.InputObjectLike`; their fields implement `Schema.InputLikeField`. An output field carries one `Schema.FieldArguments` definition for its complete argument tuple, and every field with no arguments shares the identity singleton `Schema.NoArguments`.

Input-object values and argument-tuple values implement `Schema.InputLikeValue`, covariantly narrowing their `type` properties to `InputObjectType` and `FieldArguments`, respectively. `ObjectEngineResult.Key.arguments` is an `ArgumentsValue`; empty argument values are ordinary structurally equal values rather than a distinguished singleton.

The `model` project defines both GraphQL-shaped `SpecSelection` values and flattened field-resolution `Selection` values. Semantic equality for `Selection` remains undefined. `SpecSelectionFlattener` is implemented in [`semantics/src/main/kotlin/semantics/spec/SpecSelectionFlattener.kt`](./semantics/src/main/kotlin/semantics/spec/SpecSelectionFlattener.kt). Its public operation is `flatten(typeInScope, selectionSet)`. Its tests are finite evidence for the modeled behavior, not proofs.

## Current Continuation

The immediate next step is to expand the fixed reasoning world beyond its schema and request variable bindings by adding a resolver table. Each table entry associates a resolvable field coordinate with its required `objectFragment` and resolver function, fixing ordinary field-resolver lookup and interpretation without introducing query-plan structure or scheduling policy.

Each resolver function takes fully coerced arguments, resolved object-fragment values, and the requested flattened selections, and returns `Schema.OutputValue?`. The selection input is semantically relevant: a selective resolver may return different projections or coverage for different requested selections. Holding every other resolver input fixed, each requested selection determines one result, and results for any two requested selections must agree at every OER coordinate selected by both.

Resolver return values must carry the information needed for result reasoning, including null, list, error, and concrete object-type information. Checkers, node resolution, and variable providers are explicitly out of scope for this phase.

Once that world is modeled, the next semantic layer should judge resolution results directly:

```text
world |- minimalResolution(oer, nominalType, selections)
world |- correctResolution(oer, nominalType, selections)
world |- maximalResolution(oer, nominalType, selections)
```

These predicates concern field resolution, not response completion, and should not mention planner nodes, readiness, or execution order. Their exact relationship remains to be defined. In particular, `maximalResolution` requires an explicit finite universe of eligible cells; an unbounded schema and argument domain need not admit a finite maximal OER. Whether resolver coherence across requested selections is sufficient to make minimal resolution unique remains to be established. Query plans and their execution should be introduced only after these predicates provide a plan-independent correctness target.

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

The proposed direction was instead to define correctness directly over the result. That result-oriented direction remains current, while the earlier focus on one minimal **perfect OER** has been generalized to the `minimalResolution`, `correctResolution`, and `maximalResolution` predicates above.

The intended shape of the definition is:

```text
PerfectOer(inputs, operation, oer)
```

The exact partitioning of `inputs` was not important to the sketch. It included a schema, the actual `objectFragments`, the available resolvers, and a world fixing any ambient facts needed to interpret them.

The current resolver contract is specified once in Current Continuation. In particular, requested flattened selections are resolver inputs and may affect returned projection or coverage, while node resolution is outside the present scope.

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

At the time of this handoff, the carrier model lived directly in [`model/`](./model/). It has since moved to the `model` Gradle project's conventional source tree. Read [`model/AGENTS.md`](./model/AGENTS.md) before extending it; that file records the current modeling discipline and scope boundaries.

The decisions recorded for the proposed next phase were:

- `Schema` now models the canonical query root, named input and output types, fields and arguments, type expressions, defaults, `__typename`, and the declarative relations needed to reason about polymorphic selection sets.
- The schema was treated as a stipulated input to each reasoning exercise. It is now the singleton `Assumptions.schema`, not an executable JVM-global declaration. Schema definitions use canonical identity; `Schema.Value` instances are constructed through that schema and carry its canonical definitions, while coordinate-oriented modeling domains may use type names and field coordinates.
- `EngineResult` is a finite value tree containing object, list, and simple results.
- An OER field contains one `Cell` with a nullable value and a check result.
- Missing fields are distinct from present fields whose values are null.
- Keys present in an OER contain a schema field name and fully coerced arguments. They contain neither aliases nor unresolved variables; `ObjectEngineResult.Key` values used outside an OER may contain unresolved variables.
- Executor output values and OER values are separate representations.
- Errors are collapsed to `Schema.ErrorValue`.
- Correctness, demand, and checked-versus-raw semantics intentionally remain outside the `model` package.

The schema model was judged sufficient for the immediate polymorphism work. Other semantic inputs were not yet modeled, including the supported operation and selection structures, `objectFragments`, executor functions, and the execution world that fixes their denotations. The carrier model also excluded custom scalars, precise error metadata, and lazy values or references returned by executors.

## Historical Modeling Scope

The proposed next round was to isolate the polymorphism problem and model how type-conditioned demand aggregates bottom-up while concrete object types resolve top-down. This scope is retained as historical context, not as current instructions.

That round proposed:

- including `objectFragments`, including `objectFragments` on `Query` fields;
- excluding `queryFragments`;
- excluding variables and variable providers, so arguments would be literal and fully coerced;
- excluding `@skip` and `@include`; and
- deferring other directive-controlled applicability.

These exclusions were intended to be temporary. They removed independent sources of conditional demand to isolate what polymorphism alone required from the result predicate and demand closure.

## Historical Next Definitions

The proposed sequence below predates the current `model` and `semantics` Gradle projects and the implemented selection flattener. Its first two steps, which called for remaining semantic inputs and executor denotations, have been superseded by Current Continuation and are not repeated here. The still-useful historical progression after defining the resolver world was:

1. **Type applicability.** Given an object occurrence and its concrete type, determine which type-conditioned selections apply and construct their alias-free, fully coerced OER keys.
2. **Polymorphic required-cell closure.** Seed demand from the external operation, add demand induced by `objectFragments`, and define the least closure while preserving unresolved type conditions as guards.
3. **Nested result correctness.** Define how the guarded requirements specialize over concrete object and list results.
4. **Exact result.** Require exactly the cells in the specialized least closure, with the values produced by the applicable resolver calls.

The sequence also left open whether to define a second predicate permitting additional semantically correct cells. Such a predicate would separate perfect minimality from the correctness of a production executor that conservatively over-materializes.

The historical desired result was an existence-and-uniqueness statement:

```text
For a fixed supported execution world and operation,
there exists exactly one perfect OER.
```

This is not a current claim. Its status depends in part on whether the resolver coherence requirement above is sufficient to make minimal resolution unique.

## Historical First Examples

The proposed examples were:

- a type-conditioned passive field;
- the guarded `B/helper` RSS case above;
- sibling `objectFragment` consumers whose guarded demand converges on one producer;
- two concrete implementations that induce different demand at the same result position;
- two concrete implementations that induce the same continuation and may share its representation; and
- a list containing more than one concrete object type.

The intent was for these examples to become assertions over later semantic definitions, not simulations of an executor. Such assertions would be finite evidence rather than formal proofs.

## Historical Deferred Questions

The deferred questions were `queryFragments`, variables and variable providers, `@skip`/`@include`, other directives, `@parent`, lazy executor values, legal RSS cycles, and whether conservative extra cells should be accepted by a separate coverage predicate.

The historical recommendation was to defer production scheduler design until the output semantics were stable, then ask what static structures and runtime transitions were sufficient to establish them.
