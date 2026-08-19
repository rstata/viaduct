package model

import graphql.schema.GraphQLObjectType

/**
 * The canonical opaque GraphQL-Java definition attached to this qplan object type.
 *
 * This mirrors ViaductSchema's GraphQL-Java-backed `gjDef` accessors so a future schema migration
 * can replace the backing model without changing Engine API call sites.
 */
val Schema.ObjectType.gjDef: GraphQLObjectType
    get() = graphQLJavaDefinition
