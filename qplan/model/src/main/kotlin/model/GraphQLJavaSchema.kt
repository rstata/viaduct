package model

import graphql.schema.GraphQLObjectType

/**
 * The Engine API GraphQL-Java witness attached to this qplan object type.
 *
 * Source-backed objects expose the exact definition from the retained source schema. Synthetic
 * objects may expose an internal generated definition, but must not cross the tenant boundary.
 */
val Schema.Object.gjDef: GraphQLObjectType
    get() = graphQLJavaDefinition
