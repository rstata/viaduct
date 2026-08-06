# An Idealized Viaduct Query Execution Model

## Purpose and scope

Viaduct is an implementation of [GraphQL](https://spec.graphql.org/September2025/). It executes GraphQL operations according to a schema and returns the tree-shaped result prescribed by GraphQL. This document considers only [query operations](https://spec.graphql.org/September2025/#sec-Query) and only the field-resolution part of execution. Mutation ordering, subscriptions, incremental delivery, directives, field completion, error propagation, and response serialization are outside its scope.

The production system has APIs for batching, selective resolution, subqueries, scopes, and other concerns. We deliberately omit those details here. The aim is a small source-world description that explains which resolver is responsible for each value, what information that resolver receives, and how resolver responsibilities compose into a result. The qplanning canonical model subsequently lowers this two-kind source vocabulary to field resolvers only.

The central idea is that a query induces a tree of demanded values, but responsibility for producing that tree moves between resolvers at explicit boundaries. A resolver owns a maximal region of the tree, called its output selection set. An explicit field resolver starts a new region at its field, while a node-valued field starts a new region after the containing resolver has produced the node's identifier.

## A running schema

The examples use this schema:

```graphql
interface Node {
  id: ID!
}

type Query {
  search(q: String): [Node!]! @resolver
}

extend type Query {
  node(id: ID!): Node
}

type User implements Node @resolver {
  id: ID!
  firstName: String
  lastName: String
  displayName: String @resolver
}

type Listing implements Node @resolver {
  id: ID!
  title: String
  host: User
}
```

The `@resolver` notation is illustrative. A type-level annotation registers a node resolver for that concrete node type; a field-level annotation registers a field resolver at that concrete field coordinate.

Viaduct automatically adds `Query.node` when a schema uses the `Node` interface. This field has a built-in field resolver. In the source-world description, `F_Query.node` has an empty `objectFragment` and turns its ID argument into a node reference; the concrete type embedded in that reference then selects the node resolver. Fixture composition later lowers this boundary to synthetic field coordinates.

## Queries and results

A GraphQL query contains a selection set describing the fields the client wants. After validation and variable coercion, we can treat each field occurrence as demand for a value. GraphQL field collection may combine compatible occurrences into one response position, and nested selections demand descendants of that position.

For example:

```graphql
query {
  listing: node(id: "Listing:7") {
    ... on Listing {
      title
      host {
        displayName
      }
    }
  }
}
```

has a result shaped like:

```json
{
  "listing": {
    "title": "Analytical Engine",
    "host": {
      "displayName": "Ada Lovelace"
    }
  }
}
```

The model distinguishes this result tree from the graph of entities used to produce it. A result position is identified by its path through the query, including list positions. It is not identified by the node ID stored there.

## Nodes and global IDs

`Node` is a distinguished GraphQL interface:

```graphql
interface Node {
  id: ID!
}
```

A concrete object type implementing `Node` represents entities that can be fetched from an identifier alone. In the idealized model, a node identifier is a pair:

```text
GlobalID = (concrete type, internal identifier)
```

Thus `"User:42"` stands for something like `(User, 42)`. The serialized form is opaque to clients, but the engine can recover the concrete type needed for dispatch. This reflects Viaduct's [Global ID semantics](https://viaduct.airbnb.tech/docs/developers/globalids/): the type component selects a node resolver, while the internal identifier selects an entity within that type.

The embedded type matters when a schema field has an abstract result type such as `Node`. The schema tells us only that the result is some node; the identifier tells us at runtime whether this occurrence is a `User`, a `Listing`, or another concrete implementation.

## Source Resolver Inputs

Let `T` be a concrete object type and `f` a field declared on `T`.

### Node resolvers

A node resolver is registered at a concrete node type `T`. In the external source vocabulary it is a deterministic partial function:

```text
N_T : ID_T ⇀ Object(T)
```

Its only ordinary input is an identifier whose embedded type is `T`. Its output repeats that identifier at the canonical argumentless `id` field and contains the fields owned by the node resolver for that object. Repeating the identifier normalizes the raw object value; it does not make the node resolver the owner of the root `id` OER cell. The function is partial because the identifier may not denote an existing node.

A node resolver is not attached to one parent field. It can provide a `T` object wherever a node reference to `T` occurs in the query. This is why the source input is associated with a concrete type even though the canonical registry has only field coordinates.

### Field resolvers

A field resolver is registered at a concrete field coordinate `<T, f>`. It produces the value of that particular field:

```text
F_T.f : Object(Fragment_T.f) × Arguments(T.f) ⇀ Value(TypeOf(T.f))
```

`Fragment_T.f` is the resolver's `objectFragment`: a GraphQL fragment on `T` declaring the parent-object data needed to resolve `f`. Before applying the field resolver, the engine resolves that fragment and supplies the resulting object value along with the field's coerced arguments.

For `User.displayName`, the declaration might be:

```graphql
fragment DisplayNameInput on User {
  firstName
  lastName
}
```

and the resolver function can be idealized as:

```text
F_User.displayName({ firstName, lastName }, {}) =
  joinNonNull(firstName, lastName)
```

The production documentation calls this fragment a [required selection set](https://viaduct.airbnb.tech/docs/developers/resolvers/field_resolvers/). In this model, `objectFragment` names the fragment itself, while the resolved value of that fragment is the field resolver's first input.

## Canonical Field-Only Lowering

The qplanning test fixture treats ordinary GraphQL SDL and fragments plus the two source resolver inputs above as a language to compile into one canonical semantic model. For every source node-valued field `foo(args)`, schema decoding adds a hidden bridge whose named output is `ID` and whose list and nullability structure matches `foo`: singular fields use `foo$id(args)`, while fields with any list layer use `foo$ids(args)`.

If `foo` has a source field resolver, its canonical producer is relocated to its `foo$id` or `foo$ids` bridge and adapted to extract a typed global ID from each node reference. A generated canonical field resolver remains at `foo`; its exact argument-dependent `objectFragment` requires that bridge, and its function decodes each typed ID and applies the appropriate raw node lookup. Both coordinates retain the source arguments, so `foo(a)` and `foo(b)` remain distinct `Value.ObjectKey` values.

Typed fixture IDs carry the concrete object type as well as the internal ID. This lets one generated loader dispatch each element of an abstract `Node` list independently. The current lowering accepts only fields declared as `Node` or a subtype whose every possible concrete type has a raw node resolver; it rejects possible-type sets that mix node-resolved and inline object values.

After this composition step, `Resolver.Node`, object-type resolver coordinates, node references, and node-specific ownership rules do not exist in the canonical algebra. The registry, resolver-demand graph, correctness predicates, and resolver constructors see only ordinary and generated field resolvers. Synthetic `$id` and `$ids` fields are explicit internal dependencies and never appear in GraphQL input text.

## Output selection sets and ownership

In the source-world description, every resolver is responsible for its entire output selection set, or OSS. Despite the name, an OSS is not literally one GraphQL selection set. It is the maximal portion of the demanded result reachable from a resolver before responsibility crosses to another resolver.

Starting from a field resolver's field, follow demanded fields recursively:

1. The field resolver owns the value of its own field.
2. It continues through scalar, enum, list, and non-node object values.
3. It stops before a nested field having its own field resolver; that field resolver owns the nested field.
4. It stops at a nested node-valued field after materializing the node's `id`; the node resolver selected by that ID owns the remaining fields of the node occurrence.

A node resolver follows the same recursive rule from inside its node object, with one important difference: although its raw object repeats the root node's `id`, it does not own that OER field. That ID was the input used to dispatch the node resolver. The [Viaduct node resolver documentation](https://viaduct.airbnb.tech/docs/developers/resolvers/node_resolvers/) makes the same ownership distinction.

We can summarize the ownership transfer at a node boundary as:

```text
containing resolver ──produces──> GlobalID(T, k)
                                      │
                                      └──dispatches──> N_T(k)
```

The containing resolver therefore cannot omit the ID merely because the client did not select `id`. The ID is an engine bridge: it is internally required to continue resolution. Viaduct exposes this production mechanism as a [node reference](https://viaduct.airbnb.tech/docs/developers/resolvers/node_references/). For the built-in `Query.node` field, `F_Query.node` materializes that node reference directly from its ID argument.

The OSS rule separates ownership from demand. A client selection or an `objectFragment` says which values are needed. Resolver boundaries say which resolver must provide each needed value.

Canonical lowering preserves this transfer as the ordinary field dependency from `foo(args)` to its `foo$id(args)` or `foo$ids(args)` bridge. The generated loader owns `foo`, the containing producer owns or resolves the bridge, and all further ownership uses the same field-resolver boundary rule.

## Resolution as closure of obligations

The source-world description does not prescribe a traversal order or a concurrency strategy. Instead, a correct resolution is the least result-shaped collection of resolver obligations closed under the following rules:

1. Each selected root field creates an obligation for its field resolver on `Query`, including the built-in resolver `F_Query.node`.
2. A field-resolver obligation creates obligations for every field demanded by its `objectFragment`.
3. Traversing a resolver's OSS creates a field-resolver obligation whenever it reaches a field-resolver boundary.
4. Traversing a resolver's OSS to a node-valued field requires the containing resolver to produce an ID and creates a node-resolver obligation selected by the ID's concrete type.
5. A node-resolver obligation traverses the demanded fields of that node occurrence, excluding the root `id` bridge, and creates further obligations at the same boundaries.
6. Resolution is complete when every obligation has a value and every demanded result-tree position has the value supplied by its owner.

This is a closure because resolver inputs can introduce demand that was not written in the client query. In particular, a field resolver's `objectFragment` may require fields owned by other resolvers. Those requirements must themselves be resolved before the field resolver has its complete input.

The rules describe dependencies, not execution events. An implementation may schedule independent obligations concurrently, batch compatible node lookups, or reuse a materialization, provided the resulting values satisfy the same ownership and input requirements.

Under canonical lowering, rules 4 and 5 are represented without a distinct obligation kind: the generated field resolver for `foo` requires its `foo$id` or `foo$ids` bridge, and resolving `foo` recursively activates ordinary field resolvers on the loaded object.

## Example 1: a field resolver and its object fragment

Consider:

```graphql
query {
  user: node(id: "User:42") {
    ... on User {
      displayName
    }
  }
}
```

The client did not select the `Node.id`, `firstName`, or `lastName` fields, but all three values participate in resolution:

1. The built-in field resolver `F_Query.node` receives the coerced ID argument `(User, 42)` and produces the corresponding node reference.
2. The embedded `User` type dispatches the reference to `N_User`.
3. `User.displayName` is an explicit field-resolver boundary, so `N_User` does not produce `displayName`.
4. `F_User.displayName` requires `firstName` and `lastName` through its `objectFragment`.
5. Those fields belong to `N_User`, so the engine gathers `{ firstName: "Ada", lastName: "Lovelace" }` from the node resolver's result.
6. `F_User.displayName` maps that object value to `"Ada Lovelace"`.

The externally visible result remains:

```json
{
  "user": {
    "displayName": "Ada Lovelace"
  }
}
```

The fragment fields are resolver inputs, not implicit client selections. They need not appear in the GraphQL response.

## Example 2: polymorphic node dispatch

Now consider a field whose declared type is the abstract `Node` interface:

```graphql
query {
  search(q: "Babbage") {
    id
    ... on User {
      displayName
    }
    ... on Listing {
      title
    }
  }
}
```

Suppose `F_Query.search` returns these node references:

```text
[(User, 42), (Listing, 7)]
```

The declared field type does not determine one node resolver. Dispatch occurs independently for each list position:

```text
(User, 42)    ↦ N_User(42)
(Listing, 7)  ↦ N_Listing(7)
```

The runtime concrete type also determines which inline-fragment selections apply. The first occurrence demands `User.displayName`, which in turn demands `User.firstName` and `User.lastName`. The second occurrence demands `Listing.title`. No `Listing` field is demanded from the `User` resolver, and no `User` field is demanded from the `Listing` resolver.

One possible result is:

```json
{
  "search": [
    {
      "id": "User:42",
      "displayName": "Ada Lovelace"
    },
    {
      "id": "Listing:7",
      "title": "Analytical Engine"
    }
  ]
}
```

This example is the reason node dispatch is type-based and runtime-dependent even when the operation and schema are fixed before execution.

## Example 3: repeated IDs still produce a tree

Finally, suppose two different query paths reach the same user:

```graphql
query {
  user: node(id: "User:42") {
    ... on User {
      displayName
    }
  }
  listing: node(id: "Listing:7") {
    ... on Listing {
      host {
        displayName
      }
    }
  }
}
```

Assume `Listing:7` has host `User:42`. The result contains two distinct object occurrences:

```json
{
  "user": {
    "displayName": "Ada Lovelace"
  },
  "listing": {
    "host": {
      "displayName": "Ada Lovelace"
    }
  }
}
```

The two `User` objects are equal here, but neither is a pointer to the other. GraphQL [executes selection sets](https://spec.graphql.org/September2025/#sec-Executing-Selection-Sets) and assembles values at response positions, so the semantic result is a tree rather than an entity graph.

An implementation may recognize that both occurrences refer to the same node ID and share, cache, or batch the underlying lookup. That is an execution optimization. It does not introduce sharing into the result value and does not erase the two result positions, which may have different selections, ancestry, list indices, or error paths.

## Compact formal summary

For a validated query `Q`, a schema `S`, a source node-resolver registry `N`, and a source field-resolver registry `F`, resolution constructs a result tree `R` satisfying these conditions:

- Every field collected from `Q` has a corresponding position in `R`, subject to ordinary GraphQL applicability, field merging, and nullability rules deferred by this model.
- Every value at a field-resolver coordinate `<T, f>`, whether tenant-defined or built-in, is supplied by `F_T.f` from its coerced arguments and a resolved value of `Fragment_T.f`.
- Every demanded concrete node occurrence with identifier `(T, k)` is supplied by `N_T(k)`.
- The resolver owning a node-valued field supplies `(T, k)`; `N_T` supplies the demanded remainder of that node occurrence.
- Each resolver supplies every value in the demanded portion of its OSS, and demand crossing an OSS boundary is supplied by the resolver on the other side.
- Equal node IDs at distinct result positions do not identify those positions. `R` remains a tree.

These conditions characterize acceptable resolution without choosing a query-plan representation or execution order. A planner and executor are correct relative to this model when their completed result satisfies the conditions and every resolver application receives the inputs and demand assigned to it.

The canonical qplanning formulation states the same obligations over the lowered schema and one field-resolver registry: each source node-valued `foo(args)` is represented by a singular `foo$id(args)` or list-shaped `foo$ids(args)` bridge plus the generated loader at `foo(args)`, and typed IDs preserve the concrete dispatch formerly expressed by `N`.

## References

- [GraphQL specification, September 2025](https://spec.graphql.org/September2025/)
- [Viaduct developer documentation](https://viaduct.airbnb.tech/docs/developers/)
- [Viaduct resolvers and output selection sets](https://viaduct.airbnb.tech/docs/developers/resolvers/)
- [Viaduct node resolvers](https://viaduct.airbnb.tech/docs/developers/resolvers/node_resolvers/)
- [Viaduct field resolvers](https://viaduct.airbnb.tech/docs/developers/resolvers/field_resolvers/)
- [Viaduct node references](https://viaduct.airbnb.tech/docs/developers/resolvers/node_references/)
- [Viaduct Global IDs](https://viaduct.airbnb.tech/docs/developers/globalids/)
