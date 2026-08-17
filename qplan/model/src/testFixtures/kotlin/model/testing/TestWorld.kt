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
import model.emptyFragmentOf
import model.registry.ResolverRegistry
import model.selectionsFrom

/**
 * One Guice-assembled reasoning world for model and semantics tests.
 */
class TestWorld private constructor(
    private val injector: Injector,
    private val recordedApplicationArguments: ResolverApplicationArguments?,
) {
    private val gjSchema: GJSchema = injector.getInstance(GJSchema::class.java)
    val schema: Schema = gjSchema
    val resolverRegistry: ResolverRegistry =
        injector.getInstance(ResolverRegistry::class.java)
    val assumptions: Assumptions =
        injector.getInstance(Assumptions::class.java)

    /**
     * Resolver arguments recorded for this schema-embedded deterministic world.
     *
     * Argument recording is intentionally limited to worlds created by [fromDSL].
     */
    val applicationArguments: ResolverApplicationArguments
        get() =
            checkNotNull(recordedApplicationArguments) {
                "Resolver application arguments are recorded only for TestWorld.fromDSL"
            }

    /** Creates independent request-local binding state over this world's schema and registry. */
    fun newAssumptions(
        selectiveResolvers: Boolean = assumptions.selectiveResolvers,
    ): Assumptions =
        Assumptions.of(
            schema = schema,
            resolverRegistry = resolverRegistry,
            selectiveResolvers = selectiveResolvers,
        )

    fun <T : Any> instance(type: Class<T>): T = injector.getInstance(type)

    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, SelectionForest> =
        assumptions.selectionsFrom(fragment)

    companion object {
        /**
         * Composes ordinary GraphQL and raw resolver inputs into one canonical reasoning world.
         *
         * GraphQL SDL and fragments remain external source text. Raw [nodeResolvers] and node-valued
         * source fields are lowered through synthetic bridge objects and generated `node` field
         * resolvers before [Assumptions] is constructed, so semantic code observes only lowered
         * field-resolver coordinates.
         */
        fun fromSDL(
            schemaSDL: String,
            nodeResolvers:
                (Schema) -> Map<Schema.ObjectType, NodeResolverFunction> = { emptyMap() },
            fieldResolvers:
                ((Schema) -> Map<Schema.OutputField, FieldResolverDefinition>)? = null,
            variableProviders:
                (Schema) -> Map<Value.Variable.Template, VariableDeclaration> = { emptyMap() },
            selectiveResolvers: Boolean = true,
            applicationObserver: CanonicalFieldResolverApplicationObserver? = null,
        ): TestWorld =
            create(
                schemaSDL = schemaSDL,
                nodeResolvers = nodeResolvers,
                fieldResolvers = fieldResolvers,
                variableProviders = variableProviders,
                selectiveResolvers = selectiveResolvers,
                applicationObserver = applicationObserver,
                applicationArguments = null,
            )

        private fun create(
            schemaSDL: String,
            nodeResolvers: (Schema) -> Map<Schema.ObjectType, NodeResolverFunction>,
            fieldResolvers: ((Schema) -> Map<Schema.OutputField, FieldResolverDefinition>)?,
            variableProviders: (Schema) -> Map<Value.Variable.Template, VariableDeclaration>,
            selectiveResolvers: Boolean,
            applicationObserver: CanonicalFieldResolverApplicationObserver?,
            applicationArguments: ResolverApplicationArguments?,
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
                TestWorld(injector, applicationArguments)
            } catch (exception: ProvisionException) {
                val cause = exception.cause
                if (cause is RuntimeException) throw cause
                throw exception
            }
        }

        /**
         * Composes one schema-embedded deterministic resolver world.
         *
         * Resolver-test directives are compiled into the ordinary fixture registry and stripped
         * before the retained source schema is decoded. For readability, present [schemaSDL]
         * top-down: start with `extend type Query`, then define the types reached from those root
         * fields, followed by the types they reach.
         */
        fun fromDSL(
            schemaSDL: String,
            selectiveResolvers: Boolean = true,
            applicationObserver: CanonicalFieldResolverApplicationObserver? = null,
        ): TestWorld {
            val dsl = ResolverTestDsl.parse(schemaSDL)
            val applicationArguments = ResolverApplicationArguments()
            return create(
                schemaSDL = dsl.schemaSDL,
                nodeResolvers = dsl::nodeResolvers,
                fieldResolvers = dsl::fieldResolvers,
                variableProviders = dsl::variableProviders,
                selectiveResolvers = selectiveResolvers,
                applicationObserver = applicationArguments.observer(applicationObserver),
                applicationArguments = applicationArguments,
            )
        }
    }
}

@JvmSuppressWildcards
private class TestWorldModule(
    private val schemaSDL: String,
    private val nodeResolvers: (Schema) -> Map<Schema.ObjectType, NodeResolverFunction>,
    private val fieldResolvers: ((Schema) -> Map<Schema.OutputField, FieldResolverDefinition>)?,
    private val variableProviders: (Schema) -> Map<Value.Variable.Template, VariableDeclaration>,
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
    fun fieldResolvers(schema: GJSchema): Map<Schema.OutputField, FieldResolverDefinition> =
        fieldResolvers?.invoke(schema) ?: defaultQueryResolvers(schema)

    @Provides
    @VariableProviders
    fun variableProviders(schema: GJSchema): Map<Value.Variable.Template, VariableDeclaration> =
        variableProviders.invoke(schema)

    @Provides
    @Singleton
    fun resolverRegistry(
        schema: GJSchema,
        @NodeResolvers nodeResolvers: Map<Schema.ObjectType, NodeResolverFunction>,
        @FieldResolvers fieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>,
        @VariableProviders
        variableProviders: Map<Value.Variable.Template, VariableDeclaration>,
    ): ResolverRegistry =
        resolverRegistryOf(
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
        resolverRegistry: ResolverRegistry,
    ): Assumptions =
        Assumptions.of(
            schema = schema,
            resolverRegistry = resolverRegistry,
            selectiveResolvers = selectiveResolvers,
        )

    private fun defaultQueryResolvers(
        schema: GJSchema,
    ): Map<Schema.OutputField, FieldResolverDefinition> {
        val queryFragment = schema.emptyFragmentOf("Query")
        return schema.query.fields.values
            .filter {
                it.fieldName != "__typename"
            }
            .associateWith {
                fieldResolverOf(
                    objectFragment = queryFragment,
                    function = { _, _ -> Value.Error },
                )
            }
    }
}
