package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Provides
import com.google.inject.ProvisionException
import jakarta.inject.Singleton
import model.Assumptions
import model.EngineErrorData
import model.ObjectEngineResult
import model.SelectionForest
import model.emptyFragmentOf
import model.lowering.LOWERED_TYPENAME_FIELD
import model.requireQueryTypeDef
import model.registry.ResolverRegistry
import model.selectionsFrom
import java.util.IdentityHashMap

private val testRoots = IdentityHashMap<ViaductSchema, ObjectEngineResult>()
private val fieldTestRoots =
    IdentityHashMap<ViaductSchema.Object, ObjectEngineResult>()

/** Returns one stable synthetic Query root for occurrence-identity unit tests on this schema. */
fun ViaductSchema.testRoot(): ObjectEngineResult =
    synchronized(testRoots) {
        testRoots.getOrPut(this) {
            ObjectEngineResult.of(
                type = requireQueryTypeDef(),
                values = emptyMap(),
            )
        }
    }

/** Returns one stable synthetic occurrence root for isolated tests involving this field. */
fun ViaductSchema.ObjectField.testRoot(): ObjectEngineResult =
    synchronized(fieldTestRoots) {
        fieldTestRoots.getOrPut(containingDef) {
            ObjectEngineResult.of(
                type = containingDef,
                values = emptyMap(),
            )
        }
    }

/**
 * One Guice-assembled reasoning world for model and semantics tests.
 */
class TestWorld private constructor(
    private val injector: Injector,
    private val recordedApplicationArguments: ResolverApplicationArguments?,
) {
    private val gjSchema: GJSchema = injector.getInstance(GJSchema::class.java)
    val schema: ViaductSchema = gjSchema
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

    fun selectionsFrom(fragment: String): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> =
        assumptions.selectionsFrom(fragment)

    companion object {
        /**
         * Composes ordinary GraphQL and raw resolver inputs into one canonical reasoning world.
         *
         * GraphQL SDL and fragments remain external source text. Raw [nodeResolvers] and node-valued
         * source fields are lowered through synthetic bridge objects and generated `node` field
         * resolvers before [Assumptions] is constructed, so semantic code observes only lowered
         * field-resolver coordinates. Missing Query field resolvers are filled with
         * nullability-aware fallback producers before supplied field resolvers are overlaid.
         */
        fun fromSDL(
            schemaSDL: String,
            nodeResolvers:
                (ViaductSchema) -> Map<ViaductSchema.Object, NodeResolverFunction> = { emptyMap() },
            fieldResolvers:
                ((ViaductSchema) -> Map<ViaductSchema.Field, FieldResolverDefinition>)? = null,
            variableProviders:
                (ViaductSchema) -> Map<Arguments.Variable, VariableDeclaration> = { emptyMap() },
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
            nodeResolvers: (ViaductSchema) -> Map<ViaductSchema.Object, NodeResolverFunction>,
            fieldResolvers: ((ViaductSchema) -> Map<ViaductSchema.Field, FieldResolverDefinition>)?,
            variableProviders: (ViaductSchema) -> Map<Arguments.Variable, VariableDeclaration>,
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
    private val nodeResolvers: (ViaductSchema) -> Map<ViaductSchema.Object, NodeResolverFunction>,
    private val fieldResolvers: ((ViaductSchema) -> Map<ViaductSchema.Field, FieldResolverDefinition>)?,
    private val variableProviders: (ViaductSchema) -> Map<Arguments.Variable, VariableDeclaration>,
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
    fun nodeResolvers(schema: GJSchema): Map<ViaductSchema.Object, NodeResolverFunction> =
        nodeResolvers.invoke(schema)

    @Provides
    @FieldResolvers
    fun fieldResolvers(schema: GJSchema): Map<ViaductSchema.Field, FieldResolverDefinition> =
        fallbackQueryResolvers(schema) + fieldResolvers?.invoke(schema).orEmpty()

    @Provides
    @VariableProviders
    fun variableProviders(schema: GJSchema): Map<Arguments.Variable, VariableDeclaration> =
        variableProviders.invoke(schema)

    @Provides
    @Singleton
    fun resolverRegistry(
        schema: GJSchema,
        @NodeResolvers nodeResolvers: Map<ViaductSchema.Object, NodeResolverFunction>,
        @FieldResolvers fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>,
        @VariableProviders
        variableProviders: Map<Arguments.Variable, VariableDeclaration>,
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

    private fun fallbackQueryResolvers(
        schema: GJSchema,
    ): Map<ViaductSchema.Field, FieldResolverDefinition> {
        val queryFragment = schema.emptyFragmentOf("Query")
        return schema.requireQueryTypeDef().fields
            .filter {
                it.name != LOWERED_TYPENAME_FIELD
            }
            .associateWith { field ->
                fieldResolverOf(
                    objectFragment = queryFragment,
                    function = { _, _ ->
                        if (field.type.isNullable) null else EngineErrorData.of()
                    },
                )
            }
    }
}
