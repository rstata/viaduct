# Resolution Algorithms By Example

These examples describe Resolver03's two distinct demand operations. Local closure determines everything needed to construct resolver inputs. Output projection determines everything a producer must retain so client demand and downstream resolver inputs can be satisfied. Durable lessons from the removed Resolver04 experiment live in [Query Plan Research](./evergreen.md#resolver04-and-resolver05-retrospective).

An `objectFragment` is the resolver's fixed open input requirement at its containing object. Local demand closure repeatedly adds the stamped and grounded object fragments of activated resolver occurrences until every direct and transitive input requirement is present.

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

Because `firstName` is passive, activating `displayName` adds just its direct fragment:

```graphql
# demand added for User.displayName
fragment on User {
  firstName
}
```

The direct fragment for `greeting` first adds `displayName`. That newly activated resolver then adds its own fragment on the next closure step, giving:

```graphql
# demand after closing User.greeting
fragment on User {
  displayName
  firstName
}
```

The closure is transitive. If `displayName` had depended on another resolver, that newly activated resolver would contribute its object fragment on a subsequent step.

When resolution reaches the concrete `User` object, the initial local demand is:

```graphql
fragment on User {
  greeting
}
```

Activating the exact `greeting` resolver occurrence and closing the resulting demand produces:

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

At a concrete OER occurrence, the resolution algorithm repeatedly selects each exact resolver occurrence not yet expanded, binds its argument-defined variables, stamps its fixed fragment at that occurrence path, and combines the grounded requirements with local demand. The finite closure reaches a fixed point when no activated resolver key remains unexpanded.

One question remains: `firstName` originated in the raw output of `Query.user`, but the client asked only for `greeting`. The next section explains why `firstName` survives projection of `Query.user`'s output.

## Output Projection

This section applies to Resolver03's selective producers. Resolver01 and Resolver02 instead consume each resolver's complete finite returned value, so their passive OER construction is bounded by that value rather than by `successorDemand()`. Resolver02 uses the smaller `successorBoundaryDemand()` only to expose nested behavioral continuation paths; it does not need full passive successor demand.

For a producer `P`:

- Local demand closure expands `P.objectFragment`, so it constructs `P`'s input, including the requirements of each **predecessor** resolver needed to provide that input.
- Output projection instead needs the recursively closed input requirements of each demanded **successor** resolver `S` inside `P`'s output.

`successorDemand()` computes this output-projection demand. It recursively expands a producer's demanded successor resolver occurrences and roots their input requirements in the producer's output demand, making those prerequisites visible before the producer is projected.

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
closed input demand added for Query.project = {}
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

`successorDemand()` prevents that loss. It walks through the passive `owner` selection, finds the demanded `User.greeting` successor occurrence, and recursively adds `greeting`'s input requirements at that occurrence's containing `User`. From the Demand Closure section:

```graphql
# demand after closing User.greeting
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

When resolution subsequently enters that `User`, local input closure can construct `displayName` and then `greeting`. The two closures act at different times and in different directions: local closure constructs the producer's input from predecessors on the current `Query` OER, while `successorDemand()` preserves data needed by successors on descendant OERs inside the producer's output.

More generally, a resolver must supply the demanded portion of its output selection set. Some fields within that output are boundaries owned by successor resolvers. The producer does not supply the successor field itself, but it must supply any passive values in its own output that those successor resolvers will require as input. Client selections alone do not necessarily name those prerequisites.

`successorDemand()` recursively walks the requested output and adds every encountered successor resolver occurrence's closed input requirements at its containing-object path. `snipToDemand` then clips the resulting demand at behavioral boundaries, retaining only the demanded passive portion owned by the producer.

In a production implementation, the resulting selection forest describes what the selective resolver must generate. The model represents selective resolution as a selection-independent function followed by `snipToDemand`, so the same forest instead describes what must survive projection of the raw resolver output. These are two interpretations of the same requirement: the producer's output must contain everything expected from its output selection set.

The operation can be described as lifting because requirements originating at successor resolver boundaries are moved upward, along their occurrence paths, into the demand supplied to their producer:

```text
successor resolver occurrence
    -> successor input closure
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
