# Resolution Algorithms By Example

These examples describe two current Resolver03 jobs and one historical experiment. Demand closure determines everything needed to construct resolver inputs. Output projection determines everything a producer must retain in its output so that client demand and downstream resolver inputs can be satisfied. The final example records how the removed Resolver04 attempted to widen demand after an execution variable was bound and why that strategy was abandoned.

An `objectFragment` is the resolver's declared input requirement at its containing object. A `predecessorDemand` is the same requirement closed transitively under the requirements of every resolver occurrence reachable through it, with occurrence paths and concrete-type guards preserved.

## Demand Closure

Consider this schema:

```graphql
type Query {
  user: User!
      # has resolver with objectFragment-selections of "{}"
      # raw resolver output is:
      #   User { firstName: "Ada" }
}

type User {
  firstName: String!
      # passive field: no resolver
      # supplied in the raw output of Query.user

  displayName: String!
      # has resolver with objectFragment-selections of "{ firstName }"
      # returns its input firstName

  greeting: String!
      # has resolver with objectFragment-selections of "{ displayName }"
      # returns "Hello, " + its input displayName
}
```

The client asks for:

```graphql
query {
  user {
    greeting
  }
}
```

The direct input requirement of `User.greeting` is not enough by itself. Its `objectFragment` asks for `displayName`, but `displayName` is another resolver field whose own input must be constructed before that resolver can yield a value.

Registry assembly computes these closures in dependency-first order. Because `firstName` is passive, the closure for `displayName` is just its direct fragment:

```graphql
# User.displayName.predecessorDemand
fragment on User {
  firstName
}
```

The direct fragment for `greeting` contains the resolver occurrence `displayName`. Registry assembly roots `displayName`'s already-computed closure at that occurrence, giving:

```graphql
# User.greeting.predecessorDemand
fragment on User {
  displayName
  firstName
}
```

The closure is transitive. If `displayName` had depended on another resolver, that resolver's requirements would already be present inside `displayName.predecessorDemand` and would therefore also become part of `greeting.predecessorDemand`.

When resolution reaches the concrete `User` object, the initial local demand is:

```graphql
fragment on User {
  greeting
}
```

Activating the exact `greeting` resolver occurrence adds its exact predecessor demand. The complete local demand becomes:

```graphql
fragment on User {
  greeting
  displayName
  firstName
}
```

The algorithm then orders the exact field keys by their input dependencies:

```text
firstName -> displayName -> greeting
```

It first reads the passive `firstName` supplied by `Query.user`. It then materializes `{ firstName: "Ada" }` as the input to `displayName`, whose resolver yields `"Ada"`. Finally it materializes `{ displayName: "Ada" }` as the input to `greeting`, whose resolver yields `"Hello, Ada"`.

This combines what might otherwise look like two closure mechanisms. Registry assembly computes each resolver's reusable, guarded transitive closure. At a concrete OER occurrence, the resolution algorithm selects the predecessor demands of the exact resolver occurrences activated there and combines them with the local demand. No runtime fixed-point search is needed because each selected predecessor demand is already transitively closed.

One question remains: `firstName` originated in the raw output of `Query.user`, but the client asked only for `greeting`. The next section explains why `firstName` survives projection of `Query.user`'s output.

## Output Projection

This section applies to Resolver03's selective producers. Resolver01 and Resolver02 instead consume each resolver's complete finite returned value, so their passive OER construction is bounded by that value rather than by `successorDemand()`.

For a producer `P`:

- `P.predecessorDemand` closes `P.objectFragment`, so it constructs `P`'s input, including the requirements of each **predecessor** resolver needed to provide that input.
- Output projection instead needs the `predecessorDemand` of each demanded **successor** resolver `S` inside `P`'s output.

`successorDemand()` computes this output-projection demand. It collects the predecessor demands of a producer's demanded successor resolver occurrences and roots them in the producer's output demand, making those successors' prerequisites visible to the producer before its output is projected.

Extend the running schema with:

```graphql
extend type Query {
  project: Project!
      # has resolver with objectFragment-selections of "{}"
      # raw resolver output is:
      #   Project {
      #     owner: User { firstName: "Ada" }
      #   }
}

type Project {
  owner: User!
      # passive field: no resolver
      # supplied in the raw output of Query.project
}
```

The client asks for:

```graphql
query {
  project {
    owner {
      greeting
    }
  }
}
```

The initial call to `Value.Object.resolve` operates on the concrete `Query` OER. Its local demand contains the exact key `Query.project`; `User.greeting` is nested output demand, not a key on that current OER. Closing the input demand for `Query.project` adds nothing:

```text
Query.project.predecessorDemand = {}
```

The descendant `User` OER does not exist until `Query.project` yields its result. Before resolution can enter that `User` and close its local demand, the `Query.project` result must be projected.

Without output-demand extension, `Query.project` would receive only the client-derived demand:

```graphql
fragment on Project {
  owner {
    greeting
  }
}
```

Projection retains the passive `owner` object but stops at the behavioral `greeting` boundary inside it. Neither `displayName` nor `firstName` was demanded, so the projected result is:

```text
Project {
  owner: User {}
}
```

Only after this projection does resolution enter the returned `User` OER. Its local input closure correctly discovers that `greeting` requires `displayName` and transitively requires `firstName`, but the producer projection has already discarded `firstName`.

`successorDemand()` prevents that loss. It walks through the passive `owner` selection, finds the demanded `User.greeting` successor occurrence, and roots `greeting.predecessorDemand` at that occurrence's containing `User`. From the Demand Closure section:

```graphql
# User.greeting.predecessorDemand
fragment on User {
  displayName
  firstName
}
```

The output demand supplied to `Query.project` therefore becomes:

```graphql
fragment on Project {
  owner {
    greeting
    displayName
    firstName
  }
}
```

Projection still stops at the behavioral `greeting` and `displayName` boundaries, but it now retains the passive `firstName`:

```text
Project {
  owner: User {
    firstName: "Ada"
  }
}
```

When resolution subsequently enters that `User`, local input closure can construct `displayName` and then `greeting`. The two closures act at different times and in different directions: `Query.project.predecessorDemand` constructs the producer's input from predecessors on the current `Query` OER, while `successorDemand()` preserves data needed by successors on descendant OERs inside the producer's output.

More generally, a resolver must supply the demanded portion of its output selection set. Some fields within that output are boundaries owned by successor resolvers. The producer does not supply the successor field itself, but it must supply any passive values in its own output that those successor resolvers will require as input. Client selections alone do not necessarily name those prerequisites.

`successorDemand()` recursively walks the requested output and adds every encountered successor resolver occurrence's exact `predecessorDemand` at its containing-object path. `snipToDemand` then clips the resulting demand at behavioral boundaries, retaining only the demanded passive portion owned by the producer.

In a production implementation, the resulting selection forest describes what the selective resolver must generate. The model represents selective resolution as a selection-independent function followed by `snipToDemand`, so the same forest instead describes what must survive projection of the raw resolver output. These are two interpretations of the same requirement: the producer's output must contain everything expected from its output selection set.

The operation can be described as lifting because requirements originating at successor resolver boundaries are moved upward, along their occurrence paths, into the demand supplied to their producer:

```text
successor resolver occurrence
    -> successor predecessor demand
    -> root at the successor's containing-object path
    -> producer output demand
    -> producer-owned passive projection
```

### Type Conditions and Output Projections

Every condition on an occurrence path must remain attached when demand is lifted. Concrete-type conditions are especially important because one abstract selection may denote different successor resolver coordinates whose inputs require different passive fields.

Extend the running schema again:

```graphql
extend type Query {
  subject(user: Boolean!): Subject!
      # has resolver with objectFragment-selections of "{}"
      # when user is true, its raw resolver output is:
      #   User { firstName: "Ada" }
      # when user is false, its raw resolver output is:
      #   Organization { legalName: "Analytical Engines Ltd." }
}

interface Subject {
  summary: String!
}

extend type User implements Subject {
  summary: String!
      # has resolver with objectFragment-selections of "{ firstName }"
      # returns its input firstName
}

type Organization implements Subject {
  legalName: String!
      # passive field: no resolver
      # supplied when Query.subject returns an Organization

  summary: String!
      # has resolver with objectFragment-selections of "{ legalName }"
      # returns its input legalName
}
```

The client asks for:

```graphql
query {
  subject(user: true) {
    summary
  }
}
```

The abstract `summary` selection can activate either `User.summary`, whose predecessor demand requires `firstName`, or `Organization.summary`, whose predecessor demand requires `legalName`. Although the `user` argument makes `Query.subject(user:)` deterministic, the demand collector treats the resolver function as opaque and learns the concrete `Subject` type only from its result.

`successorDemand()` therefore lifts both possible successor requirements while preserving the condition under which each applies:

```graphql
# Output demand supplied to Query.subject(user: true) after extension
fragment on Subject {
  summary

  ... on User {
    firstName
  }

  ... on Organization {
    legalName
  }
}
```

For `user: true`, projection examines the returned raw `User` value. The `User` condition applies, so `firstName` is retained; the `Organization` condition does not apply, so `legalName` is ignored. For `user: false`, the opposite condition applies. Lifting preserves possible successor demand, while the retained type conditions prevent that demand from becoming unconditional.

## Historical Resolver04 Widening Example

Execution variables add value flow that does not follow the selection tree. A provider reads a value from one OER path, while uses of the variable insert that value into resolver arguments at another path.

Consider this schema:

```graphql
type Query {
  source: Object1!
      # has resolver with objectFragment-selections of "{}"
      # raw resolver output is:
      #   Object1 {}
}

type Object1 {
  variableConsumer: Int!
      # has resolver with objectFragment-selections of:
      #   "{ child { field2(arg: $value) } common }"
      # defines execution variable $value with provider path "common"
      # returns 1 after its complete input has been constructed

  common: String!
      # has resolver with objectFragment-selections of:
      #   "{ child { field2(arg: \"literal\") } }"
      # returns "bound"

  child: Object2!
      # has resolver with objectFragment-selections of "{}"
      # raw resolver output is:
      #   Object2 {}
}

type Object2 {
  field2(arg: String): Int!
      # has resolver with objectFragment-selections of "{}"
      # returns 2 for each concrete argument tuple
}
```

The client asks for:

```graphql
query {
  source {
    variableConsumer
  }
}
```

The `variableConsumer` resolver requires:

```graphql
fragment on Object1 {
  child {
    field2(arg: $value)
  }
  common
}
```

Its provider defines `$value` as the value of `common`. The provider path is contained in the resolver's object fragment, but evaluating `common` has its own transitive input requirement:

```graphql
fragment on Object1 {
  child {
    field2(arg: "literal")
  }
}
```

Registry-computed predecessor demands make both structural paths visible before resolution starts, but they cannot turn `field2(arg: $value)` into an exact OER key before `$value` has a value.

To evaluate the provider, resolution first constructs the argumentless `child` cell and resolves `field2(arg: "literal")` in that returned `Object2`. It can then materialize the input to `common`; `common` yields `"bound"`, which is stored as the `$value` binding on the containing `Object1` OER.

Substitution now reveals new exact demand:

```graphql
fragment on Object1 {
  child {
    field2(arg: "bound")
  }
}
```

Both the provider-side demand and the newly concrete variable-side demand pass through the same argumentless `Object1.child` cell. A strict post-order traversal has already returned from that child subtree. Applying the `child` resolver again would recover the subtree but would violate the requirement that each exact resolver-bearing OER cell have one resolver application.

Resolver04 instead widened the existing child result. It retained the selection-independent source associated with the first `child` application, re-entered the already-present `child` cell, and resolved the newly concrete descendant against that source. The child OER grew from:

```graphql
fragment on Object2 {
  field2(arg: "literal")
}
```

to:

```graphql
fragment on Object2 {
  field2(arg: "literal")
  field2(arg: "bound")
}
```

The `child` resolver was still applied once. The two `field2` cells had different exact argument tuples, so each had its own one-shot resolver application. After both descendants and `common` were present, the complete exact input to `variableConsumer` could be materialized and its resolver could be applied.

Widening therefore did not discover a new structural path. The symbolic path was already present in the fixed envelope. It resumed an existing OER subtree after a variable binding turned that symbolic path into a new exact key.

### Why Widening Cannot Recover One-Shot After Late Equality

There is an important precision point in the proposed `"literal"` variation. In the schema above, `field2` returns a scalar. If `common` returned `"literal"` instead of `"bound"`, the two occurrences would converge on the same exact key, but they would also make the same complete scalar demand. Equality alone would not force a second application.

The one-shot problem appears when late-equal occurrences contribute different output demand. The following complete variation makes that distinction visible:

```graphql
type Query {
  source: Object1!
      # has resolver with objectFragment-selections of "{}"
      # raw resolver output is:
      #   Object1 {}
}

type Object1 {
  variableConsumer: Int!
      # has resolver with objectFragment-selections of:
      #   "{ child { field2(arg: $value) { forConsumer } } common }"
      # defines execution variable $value with provider path "common"
      # returns 1 after its complete input has been constructed

  common: String!
      # has resolver with objectFragment-selections of:
      #   "{ child { field2(arg: \"literal\") { forProvider } } }"
      # returns "literal"

  child: Object2!
      # has resolver with objectFragment-selections of "{}"
      # raw resolver output is:
      #   Object2 {}
}

type Object2 {
  field2(arg: String): Payload!
      # has resolver with objectFragment-selections of "{}"
      # raw resolver output is:
      #   Payload { forProvider: 1, forConsumer: 2 }
}

type Payload {
  forProvider: Int!
      # passive field: no resolver
      # supplied in the raw output of Object2.field2

  forConsumer: Int!
      # passive field: no resolver
      # supplied in the raw output of Object2.field2
}
```

Producing `$value` first requires the exact cell `field2(arg: "literal")` with output demand `{ forProvider }`. When `common` returns `"literal"`, substitution turns the pending variable-side occurrence into that same exact cell, but with additional output demand `{ forConsumer }`.

If `field2(arg: "literal")` was already applied and projected only to `{ forProvider }`, widening cannot manufacture `forConsumer` from that projected value. Applying the resolver again would violate one-shot execution. Once an application has occurred with incomplete demand, one-shot execution is impossible to recover for that strategy.

This made one-shot resolution impossible for Resolver04's strategy under its loose restrictions on variable definitions and uses. Before `$value` was bound, the algorithm could not know whether the symbolic occurrence would converge with `field2(arg: "literal")`. Applying the concrete occurrence with only `{ forProvider }` could require a second application after late convergence, while speculatively adding `{ forConsumer }` over-selected whenever the occurrences did not converge. The canonical model now rejects provider/use shapes that permit this late convergence by requiring an acyclic argument-insensitive structural branch order.

The exact lesson is that widening solved late discovery of a distinct descendant key such as `"bound"`, but it could not repair an under-projected resolver cell after late equality. Preserving one-shot under `"literal"` requires the potentially convergent output demand to be accounted for before the first resolver application. The broader history and surviving design constraints are recorded in the [Resolver04 retrospective](./evergreen.md#appendix-resolver04-retrospective).
