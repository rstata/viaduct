package execution

import graphql.TypeResolutionEnvironment
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.schema.TypeResolver
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.InterfaceWiringEnvironment
import graphql.schema.idl.UnionWiringEnvironment
import graphql.schema.idl.WiringFactory
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.SourceSchemaAdapter

/**
 * GraphQL-Java completion wiring backed by an already-resolved qplan result tree.
 *
 * The execution strategy supplies an [ObjectEngineResult] as the root source. Nested object values
 * remain OERs so the same data fetcher can traverse the complete tree.
 */
class QPlanWiringFactory(
    schema: Schema,
) : WiringFactory {
    private val dataFetcher = ObjectEngineResultDataFetcher(SourceSchemaAdapter(schema))

    override fun getDefaultDataFetcher(
        environment: FieldWiringEnvironment,
    ): DataFetcher<*> = dataFetcher

    override fun providesTypeResolver(environment: InterfaceWiringEnvironment): Boolean = true

    override fun getTypeResolver(environment: InterfaceWiringEnvironment): TypeResolver =
        TypeResolver(::resolveType)

    override fun providesTypeResolver(environment: UnionWiringEnvironment): Boolean = true

    override fun getTypeResolver(environment: UnionWiringEnvironment): TypeResolver =
        TypeResolver(::resolveType)
}

private class ObjectEngineResultDataFetcher(
    private val sourceSchema: SourceSchemaAdapter,
) : DataFetcher<Any?> {
    override fun get(environment: DataFetchingEnvironment): Any? {
        val source =
            environment.getSource<Any>() as? ObjectEngineResult
                ?: throw IllegalStateException(
                    "QPlan completion requires an ObjectEngineResult source for " +
                        environment.fieldDefinition.name,
                )
        val sourceFieldName = environment.fieldDefinition.name
        val field = sourceSchema.field(source.type.typeName, sourceFieldName)
        require(field is Schema.ObjectField) {
            "QPlan completion requires a concrete field for " +
                "${source.type.typeName}/$sourceFieldName"
        }
        val key =
            ObjectEngineResult.GroundKey.of(
                field = field,
                arguments = environment.arguments,
            )
        val value =
            source
                .getCell(key)
                .getValue()
                .get()
        return if (field.fieldName == sourceFieldName) {
            value.toGraphQLJavaValue()
        } else {
            value.toGraphQLJavaNodeValue()
        }
    }
}

private fun resolveType(environment: TypeResolutionEnvironment): GraphQLObjectType {
    val source =
        environment.getObject<Any>() as? ObjectEngineResult
            ?: throw IllegalStateException(
                "QPlan abstract type completion requires an ObjectEngineResult source",
            )
    return environment.schema.getObjectType(source.type.typeName)
        ?: throw IllegalStateException(
            "GraphQL schema has no object type named ${source.type.typeName}",
        )
}

private fun EngineResult?.toGraphQLJavaValue(): Any? =
    when (this) {
        null -> null
        ErrorEngineResult ->
            throw IllegalStateException(
                "QPlan cannot complete ErrorEngineResult without GraphQL error metadata",
            )
        is ObjectEngineResult -> this
        is ListEngineResult ->
            map { cell ->
                cell
                    .getValue()
                    .get()
                    .toGraphQLJavaValue()
            }
        is Schema.ID -> value
        is Schema.EnumValue -> name
        is Int,
        is Double,
        is Boolean,
        is String,
        -> this
        else -> throw IllegalStateException("Unexpected qplan engine result: $this")
    }

private fun EngineResult?.toGraphQLJavaNodeValue(): Any? =
    when (this) {
        null -> null
        is ListEngineResult ->
            map { cell ->
                cell
                    .getValue()
                    .get()
                    .toGraphQLJavaNodeValue()
            }
        is ObjectEngineResult -> {
            val payloadField =
                type.fields["node"]
                    ?: throw IllegalStateException(
                        "QPlan node bridge ${type.typeName} has no payload field",
                    )
            val payloadKey = ObjectEngineResult.GroundKey.of(payloadField, emptyMap())
            getCell(payloadKey)
                .getValue()
                .get()
                .toGraphQLJavaValue()
        }
        else -> toGraphQLJavaValue()
    }
