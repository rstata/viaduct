# Resolution Algorithms By Example

These examples use the [resolver-test DSL](./resolver-test-dsl.md) to describe complete deterministic
resolver worlds. They illustrate Resolver03's two distinct demand operations. Local closure
determines everything needed to construct resolver inputs. Output projection determines everything
a producer must retain so client demand and downstream resolver inputs can be satisfied. Resolver
worlds are presented top-down: root fields first, followed by the types reached from them.

## Demand Closure

Consider this world:

```graphql
extend type Query {
  user: User!
    @resolver(result: {first: 2, last: 3})
}

type User {
  first: Int!
  last: Int!

  display: Int!
    @resolver(
      of: "first last"
      result: "sum(first, last)"
    )

  greeting: Int!
    @resolver(
      of: "display"
      result: "sumplus1(display)"
    )
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

`User.greeting` directly requires `display`, but `display` is another resolver field. Its resolver
requires the passive fields `first` and `last`, so closing the local demand is transitive:

```graphql
fragment on User {
  greeting
  display
  first
  last
}
```

The algorithm orders the exact field keys by their input dependencies:

```text
first, last -> display -> greeting
```

The `Query.user` resolver supplies `first = 2` and `last = 3`. The `display` resolver receives those
values and returns `5`; the `greeting` resolver then returns `6`.

At one concrete object-result occurrence, the resolution algorithm repeatedly selects each exact
resolver occurrence not yet expanded, binds its variables, stamps its fixed fragment at that
occurrence path, and combines the grounded requirements with local demand. The finite closure
reaches a fixed point when no activated resolver key remains unexpanded.

One question remains: `first` and `last` originated in the raw output of `Query.user`, but the
client asked only for `greeting`. The next section explains why they survive projection of the
producer's output.

## Output Projection

This section applies to selective producers. Resolver01 and Resolver02 instead consume each
resolver's complete finite returned value.

For a producer `P`:

- Local demand closure expands `P.of`, constructing `P`'s input from predecessor resolvers.
- Output projection needs the recursively closed input requirements of demanded successor
  resolvers inside `P`'s output.

Extend the world with:

```graphql
extend type Query {
  project: Project!
    @resolver(
      result: {
        owner: {
          first: 2
          last: 3
        }
      }
    )
}

type Project {
  owner: User!
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

Closing the input demand for `Query.project` adds nothing because its `of` fragment is empty.
`User.greeting` is nested output demand, not a key on the current Query object.

Without successor-demand extension, `Query.project` would receive only:

```graphql
fragment on Project {
  owner {
    greeting
  }
}
```

Projection retains the passive `owner` object but stops at the resolver-owned `greeting` boundary.
The returned value would become `Project { owner: User {} }`. Resolution could later discover that
`greeting` requires `display`, `first`, and `last`, but the producer projection would already have
discarded the passive inputs.

`successorDemand()` prevents that loss. It walks through `owner`, finds the demanded
`User.greeting` occurrence, closes that successor's input requirements, and roots them at the
containing `User` occurrence:

```graphql
fragment on Project {
  owner {
    greeting
    display
    first
    last
  }
}
```

Projection still stops at the resolver-owned `greeting` and `display` fields, but retains
`first` and `last`:

```text
Project {
  owner: User {
    first: 2
    last: 3
  }
}
```

When resolution enters that `User`, local closure can construct `display` and then `greeting`.
Local closure constructs a producer's input from predecessors on the current object occurrence;
successor demand preserves producer-owned passive data needed by resolvers on descendant
occurrences.

## Type Conditions

Conditions on an occurrence path must remain attached when demand is lifted. An abstract selection
may denote different concrete resolver coordinates whose inputs require different passive fields:

```graphql
extend type Query {
  subject: Subject!
    @resolver(
      result: {
        __typename: "Person"
        first: 7
      }
    )
}

interface Subject {
  summary: Int!
}

type Person implements Subject {
  first: Int!

  summary: Int!
    @resolver(
      of: "first"
      result: "sum(first)"
    )
}

type Organization implements Subject {
  legal: Int!

  summary: Int!
    @resolver(
      of: "legal"
      result: "sum(legal)"
    )
}
```

The client asks for:

```graphql
query {
  subject {
    summary
  }
}
```

The demand collector treats the resolver function as opaque and learns the concrete `Subject` type
only from its result. It therefore lifts both possible successor requirements while preserving
their conditions:

```graphql
fragment on Subject {
  summary

  ... on Person {
    first
  }

  ... on Organization {
    legal
  }
}
```

Projection examines the returned `Person`. The `Person` condition applies, so `first` is retained;
the `Organization` condition does not apply. Lifting preserves possible successor demand, while
the retained type conditions prevent that demand from becoming unconditional.
