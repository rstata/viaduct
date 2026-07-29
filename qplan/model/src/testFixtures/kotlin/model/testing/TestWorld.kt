package model.testing

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Provides
import com.google.inject.ProvisionException
import jakarta.inject.Singleton
import model.Assumptions
import model.Fragment
import model.Schema
import model.registry.ExecutorRegistry
import model.registry.FieldResolver
import model.registry.NodeResolver
import model.spec.SpecSelection

/**
 * One Guice-assembled reasoning world for model and semantics tests.
 */
class TestWorld private constructor(
    private val injector: Injector,
) {
    private val gjSchema: GJSchema = injector.getInstance(GJSchema::class.java)
    val schema: Schema = gjSchema
    val executorRegistry: ExecutorRegistry =
        injector.getInstance(ExecutorRegistry::class.java)
    val assumptions: Assumptions =
        injector.getInstance(Assumptions::class.java)

    fun <T : Any> instance(type: Class<T>): T = injector.getInstance(type)

    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, List<SpecSelection>> =
        GJSpecSelectionParser(gjSchema, assumptions.variableValues).selectionsFrom(fragment)

    companion object {
        fun fromSDL(
            schemaSDL: String,
            variableValues: (Schema) -> Map<String, Schema.Value?> = { emptyMap() },
            nodeResolvers:
                (Schema) -> Map<Schema.ObjectType, NodeResolver> = { emptyMap() },
            fieldResolvers:
                ((Schema) -> Map<Schema.OutputField, FieldResolver>)? = null,
        ): TestWorld {
            val injector =
                Guice.createInjector(
                    TestWorldModule(
                        schemaSDL = schemaSDL,
                        variableValues = variableValues,
                        nodeResolvers = nodeResolvers,
                        fieldResolvers = fieldResolvers,
                    ),
                )
            return try {
                TestWorld(injector)
            } catch (exception: ProvisionException) {
                val cause = exception.cause
                if (cause is RuntimeException) throw cause
                throw exception
            }
        }
    }
}

@JvmSuppressWildcards
private class TestWorldModule(
    private val schemaSDL: String,
    private val variableValues: (Schema) -> Map<String, Schema.Value?>,
    private val nodeResolvers: (Schema) -> Map<Schema.ObjectType, NodeResolver>,
    private val fieldResolvers: ((Schema) -> Map<Schema.OutputField, FieldResolver>)?,
) : AbstractModule() {
    override fun configure() {
        bind(String::class.java)
            .annotatedWith(SchemaSDL::class.java)
            .toInstance(schemaSDL)
    }

    @Provides
    @Singleton
    fun schema(
        @SchemaSDL schemaSDL: String,
    ): GJSchema = GJSchema.fromSDL(schemaSDL)

    @Provides
    @VariableValues
    fun variableValues(schema: GJSchema): Map<String, Schema.Value?> =
        variableValues.invoke(schema)

    @Provides
    @NodeResolvers
    fun nodeResolvers(schema: GJSchema): Map<Schema.ObjectType, NodeResolver> =
        nodeResolvers.invoke(schema)

    @Provides
    @FieldResolvers
    fun fieldResolvers(schema: GJSchema): Map<Schema.OutputField, FieldResolver> =
        fieldResolvers?.invoke(schema) ?: defaultQueryResolvers(schema)

    @Provides
    @Singleton
    fun executorRegistry(
        schema: GJSchema,
        @NodeResolvers nodeResolvers: Map<Schema.ObjectType, NodeResolver>,
        @FieldResolvers fieldResolvers: Map<Schema.OutputField, FieldResolver>,
    ): ExecutorRegistry =
        executorRegistryOf(
            schema = schema,
            nodeResolvers = nodeResolvers,
            fieldResolvers = fieldResolvers,
        )

    @Provides
    @Singleton
    fun assumptions(
        schema: GJSchema,
        @VariableValues variableValues: Map<String, Schema.Value?>,
        executorRegistry: ExecutorRegistry,
    ): Assumptions =
        Assumptions.of(
            schema = schema,
            bindings = variableValues,
            executorRegistry = executorRegistry,
        )

    private fun defaultQueryResolvers(
        schema: GJSchema,
    ): Map<Schema.OutputField, FieldResolver> {
        val queryFragment = Fragment.of(schema.query, model.selectionForestOf())
        return schema.query.fields.values
            .filter { it.fieldName != "__typename" }
            .associateWith {
                fieldResolverOf(
                    objectFragment = queryFragment,
                    function = { _, _ -> Schema.ErrorValue },
                )
            }
    }
}
