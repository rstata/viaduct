package model

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import jakarta.inject.Singleton

/**
 * A [Schema] decoded from GraphQL SDL.
 *
 * Construct the reasoning world's one schema before its values and assumptions so every value is
 * created through this exact canonical graph. The retained GraphQL Java schema is used by
 * [Assumptions] to parse and validate selections without decoding a second model schema.
 */
@Singleton
class GJSchema private constructor(
    internal val graphQLSchema: GraphQLSchema,
    private val decodedSchema: Schema,
) : Schema by decodedSchema {
    companion object {
        private val STANDARD_SCALAR_NAMES = setOf("Int", "Float", "String", "Boolean", "ID")
        private val STANDARD_DIRECTIVE_NAMES =
            setOf("skip", "include", "deprecated", "specifiedBy", "oneOf")

        @JvmStatic
        fun fromSDL(schemaSDL: String): GJSchema {
            val graphQLSchema = parseSchema(schemaSDL)
            return GJSchema(
                graphQLSchema = graphQLSchema,
                decodedSchema = GJSchemaDecoder(graphQLSchema).decode(),
            )
        }

        private fun parseSchema(schemaSDL: String): GraphQLSchema {
            val registry = SchemaParser().parse(schemaSDL)
            val nonStandardScalars =
                (
                    registry.scalars().keys +
                        registry.scalarTypeExtensions().keys
                ) - STANDARD_SCALAR_NAMES
            require(nonStandardScalars.isEmpty()) {
                "Non-standard scalar types are outside the model: " +
                    nonStandardScalars.sorted().joinToString()
            }

            val nonStandardDirectives =
                registry.directiveDefinitions.keys - STANDARD_DIRECTIVE_NAMES
            require(nonStandardDirectives.isEmpty()) {
                "Non-standard directives are outside the model: " +
                    nonStandardDirectives.sorted().joinToString()
            }

            return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        }
    }
}
