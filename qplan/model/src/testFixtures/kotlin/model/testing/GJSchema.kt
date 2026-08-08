package model.testing

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import model.Schema
import model.Value

/**
 * A fixture pair of the GraphQL-visible source schema and the canonical decoded [Schema].
 *
 * Construct the reasoning world's one schema before its values and assumptions so every non-error
 * value is created through this exact canonical graph. The canonical graph may contain synthetic
 * node bridge types and fields absent from the retained GraphQL Java schema. [Value.Error] is
 * schema-independent. The retained source schema parses and validates GraphQL selections, ensuring
 * those inputs cannot name synthetic definitions.
 */
internal class GJSchema private constructor(
    internal val graphQLSchema: GraphQLSchema,
    private val decodedSchema: Schema,
) : Schema by decodedSchema {
    /** The canonical concrete object types available to fixture registry lowering. */
    internal val objectTypes: List<Schema.ObjectType>
        get() =
            graphQLSchema.allTypesAsList
                .filterIsInstance<graphql.schema.GraphQLObjectType>()
                .filterNot { it.name.startsWith("__") }
                .map { type(it.name) as Schema.ObjectType }

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
