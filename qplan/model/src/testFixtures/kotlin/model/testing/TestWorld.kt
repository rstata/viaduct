package model.testing

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Provides
import com.google.inject.ProvisionException
import jakarta.inject.Singleton
import model.Assumptions
import model.FieldResolvers
import model.GJSchema
import model.NodeResolvers
import model.Schema
import model.SchemaSDL
import model.VariableValues
import model.registry.ExecutorRegistry
import model.registry.FieldResolver
import model.registry.NodeResolver

/**
 * One Guice-assembled reasoning world for model and semantics tests.
 */
class TestWorld private constructor(
    private val injector: Injector,
) {
    val schema: GJSchema = injector.getInstance(GJSchema::class.java)
    val executorRegistry: ExecutorRegistry =
        injector.getInstance(ExecutorRegistry::class.java)
    val assumptions: Assumptions =
        injector.getInstance(Assumptions::class.java)

    fun <T : Any> instance(type: Class<T>): T = injector.getInstance(type)

    companion object {
        fun fromSDL(
            schemaSDL: String,
            variableValues: (GJSchema) -> Map<String, Schema.Value?> = { emptyMap() },
            nodeResolvers:
                (GJSchema) -> Map<Schema.ObjectType, NodeResolver> = { emptyMap() },
            fieldResolvers:
                (GJSchema) -> Map<Schema.OutputField, FieldResolver> = { emptyMap() },
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
    private val variableValues: (GJSchema) -> Map<String, Schema.Value?>,
    private val nodeResolvers: (GJSchema) -> Map<Schema.ObjectType, NodeResolver>,
    private val fieldResolvers: (GJSchema) -> Map<Schema.OutputField, FieldResolver>,
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
        fieldResolvers.invoke(schema)

    @Provides
    @Singleton
    fun executorRegistry(
        schema: GJSchema,
        @NodeResolvers nodeResolvers: Map<Schema.ObjectType, NodeResolver>,
        @FieldResolvers fieldResolvers: Map<Schema.OutputField, FieldResolver>,
    ): ExecutorRegistry =
        ExecutorRegistry.of(
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
}
