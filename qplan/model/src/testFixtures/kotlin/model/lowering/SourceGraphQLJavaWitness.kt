package model.lowering

import graphql.schema.GraphQLObjectType
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.gjDef

/**
 * The exact source-schema object represented by this lowered semantic object.
 *
 * Synthetic lowered objects have no source witness. The GraphQL-Java schema reconstructed during
 * validation is deliberately not retained here.
 */
internal val ViaductSchema.Object.sourceGraphQLJavaDefinitionOrNull: GraphQLObjectType?
    get() = runCatching { gjDef }.getOrNull()
