package model.testing

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Provides
import com.google.inject.ProvisionException
import jakarta.inject.Singleton
import model.Assumptions
import model.Schema
import model.SelectionForest
import model.Value
import model.VariableCoordinate
import model.emptyFragmentOf
import model.registry.ExecutorRegistry
import model.registry.Resolver
import model.selectionsFrom

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

    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, SelectionForest> =
        assumptions.selectionsFrom(fragment)

    companion object {
        /**
         * Composes ordinary GraphQL and raw resolver inputs into one canonical reasoning world.
         *
         * GraphQL SDL and fragments remain external source text. Raw [nodeResolvers] are lowered
         * with node-valued source field resolvers into synthetic `$id`/`$ids` bridge fields and
         * generated field resolvers before [Assumptions] is constructed, so semantic code observes
         * only field resolver coordinates.
         */
        fun fromSDL(
            schemaSDL: String,
            nodeResolvers:
                (Schema) -> Map<Schema.ObjectType, NodeResolverFunction> = { emptyMap() },
            fieldResolvers:
                ((Schema) -> Map<Schema.OutputField, Resolver.Field>)? = null,
            variableProviders:
                (Schema) -> Map<VariableCoordinate, FromObjectField> = { emptyMap() },
            selectiveResolvers: Boolean = true,
            applicationObserver: CanonicalFieldResolverApplicationObserver? = null,
        ): TestWorld {
            val injector =
                Guice.createInjector(
                    TestWorldModule(
                        schemaSDL = schemaSDL,
                        nodeResolvers = nodeResolvers,
                        fieldResolvers = fieldResolvers,
                        variableProviders = variableProviders,
                        selectiveResolvers = selectiveResolvers,
                        applicationObserver = applicationObserver,
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
    private val nodeResolvers: (Schema) -> Map<Schema.ObjectType, NodeResolverFunction>,
    private val fieldResolvers: ((Schema) -> Map<Schema.OutputField, Resolver.Field>)?,
    private val variableProviders: (Schema) -> Map<VariableCoordinate, FromObjectField>,
    private val selectiveResolvers: Boolean,
    private val applicationObserver: CanonicalFieldResolverApplicationObserver?,
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
    @NodeResolvers
    fun nodeResolvers(schema: GJSchema): Map<Schema.ObjectType, NodeResolverFunction> =
        nodeResolvers.invoke(schema)

    @Provides
    @FieldResolvers
    fun fieldResolvers(schema: GJSchema): Map<Schema.OutputField, Resolver.Field> =
        fieldResolvers?.invoke(schema) ?: defaultQueryResolvers(schema)

    @Provides
    @VariableProviders
    fun variableProviders(schema: GJSchema): Map<VariableCoordinate, FromObjectField> =
        variableProviders.invoke(schema)

    @Provides
    @Singleton
    fun executorRegistry(
        schema: GJSchema,
        @NodeResolvers nodeResolvers: Map<Schema.ObjectType, NodeResolverFunction>,
        @FieldResolvers fieldResolvers: Map<Schema.OutputField, Resolver.Field>,
        @VariableProviders variableProviders: Map<VariableCoordinate, FromObjectField>,
    ): ExecutorRegistry =
        executorRegistryOf(
            schema = schema,
            nodeResolvers = nodeResolvers,
            fieldResolvers = fieldResolvers,
            variableProviders = variableProviders,
            applicationObserver = applicationObserver,
        )

    @Provides
    @Singleton
    fun assumptions(
        schema: GJSchema,
        executorRegistry: ExecutorRegistry,
    ): Assumptions =
        Assumptions.of(
            schema = schema,
            executorRegistry = executorRegistry,
            selectiveResolvers = selectiveResolvers,
        )

    private fun defaultQueryResolvers(
        schema: GJSchema,
    ): Map<Schema.OutputField, Resolver.Field> {
        val queryFragment = schema.emptyFragmentOf("Query")
        return schema.query.fields.values
            .filter {
                it.fieldName != "__typename" &&
                    !isNodeIdBridgeName(it.fieldName)
            }
            .associateWith {
                fieldResolverOf(
                    objectFragment = queryFragment,
                    function = { _, _ -> Value.Error },
                )
            }
    }
}
