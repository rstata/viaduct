package execution.testing

import graphql.ExecutionResult
import graphql.GraphQLContext
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.SchemaPrinter
import java.util.IdentityHashMap
import java.util.Locale
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineErrorData
import model.EngineOutputData
import model.Fragment
import model.SelectionForest
import model.SourceSchemaAdapter
import model.emptyFragmentOf
import model.fragmentFrom
import model.fragmentFromDocument
import model.requireQueryTypeDef
import model.requireType
import model.testing.FieldResolverDefinition
import model.testing.NodeResolverFunction
import model.testing.TestWorld
import model.testing.VariableDeclaration
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import model.testing.selectiveFieldResolverOf
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.ViaductSchema as EngineSchema
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.graphql.schema.ViaductSchema as QPlanSchema
import viaduct.graphql.test.assertJson as realAssertJson

/**
 * GraphQL feature-test surface backed directly by qplan and an [EngineTestModule]'s executors.
 *
 * This is intentionally a pre-dispatcher integration: it does not construct a DispatcherRegistry
 * or data loaders. The current integration accepts synchronous, unbatched field executors,
 * including selective ones.
 */
class QPlanFeatureTest internal constructor(
    private val fixture: ExecutionTestFixture,
) {
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult = fixture.runQuery(query, variables)

    fun runQueryWithTimeout(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 1_000,
    ): ExecutionResult {
        require(timeoutMillis > 0) { "Timeout must be positive" }
        return runQuery(query, variables)
    }

    fun ExecutionResult.assertJson(expectedJson: String): Unit = realAssertJson(expectedJson)
}

/**
 * Runs qplan against the executor registry represented by this in-memory engine module.
 *
 * Source executor values are adapted before qplan's existing fixture lowering. Consequently,
 * `__typename` remains GraphQL-Java completion over qplan's generated typename resolvers, while
 * Node references and Node resolver outputs use the canonical qplan node-bridge lowering.
 */
fun EngineTestModule.runQPlanFeatureTest(
    withoutDefaultQueryNodeResolvers: Boolean = false,
    schema: EngineSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: QPlanFeatureTest.() -> Unit,
) {
    if (withoutDefaultQueryNodeResolvers) {
        TODO("Qplan feature tests do not support disabling default Query node resolvers yet")
    }
    if (schema != null) {
        TODO("Qplan feature tests do not support a distinct executable schema yet")
    }
    if (engineConfig != null) {
        TODO("Qplan feature tests do not support custom engine configuration yet")
    }
    val schemaSDL = qplanSchemaSDL(fullSchema)
    val context = ContextMocks(myFullSchema = fullSchema).engineExecutionContext
    val registryInputs = IdentityHashMap<QPlanSchema, QPlanRegistryInputs>()
    validateSupportedExecutors()

    val world =
        TestWorld.fromSDL(
            schemaSDL = schemaSDL,
            fieldResolvers = { schema ->
                registryInputs
                    .getOrPut(schema) { qplanRegistryInputs(schema, context) }
                    .fieldResolvers
            },
            nodeResolvers = { schema ->
                qplanNodeResolvers(schema, context)
            },
            variableProviders = { schema ->
                registryInputs
                    .getOrPut(schema) { qplanRegistryInputs(schema, context) }
                    .variableProviders
            },
        )
    QPlanFeatureTest(ExecutionTestFixture.fromWorld(schemaSDL, world)).block()
}

fun MockTenantModuleBootstrapper.runQPlanFeatureTest(
    withoutDefaultQueryNodeResolvers: Boolean = false,
    schema: EngineSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: QPlanFeatureTest.() -> Unit,
) {
    EngineTestModule(
        fullSchema = fullSchema,
        fieldResolverExecutors = fieldResolverExecutors,
        nodeResolverExecutors = nodeResolverExecutors,
        checkerExecutors = checkerExecutors,
        typeCheckerExecutors = typeCheckerExecutors,
    ).runQPlanFeatureTest(
        withoutDefaultQueryNodeResolvers = withoutDefaultQueryNodeResolvers,
        schema = schema,
        engineConfig = engineConfig,
        block = block,
    )
}


private fun EngineTestModule.validateSupportedExecutors() {
    if (checkerExecutors.isNotEmpty() || typeCheckerExecutors.isNotEmpty()) {
        TODO("Qplan feature tests do not support checker executors yet")
    }
    fieldResolverExecutors.forEach { (coordinate, executor) ->
        if (executor.isBatching) {
            TODO("Qplan feature tests do not support batching field executor ${coordinate.render()}")
        }
    }
    nodeResolverExecutors.forEach { (typeName, executor) ->
        if (executor.isBatching) {
            TODO("Qplan feature tests do not support batching node executor $typeName")
        }
        if (executor.isSelective) {
            TODO("Qplan feature tests do not support selective node executor $typeName")
        }
    }
}

private data class QPlanRegistryInputs(
    val fieldResolvers: Map<QPlanSchema.Field, FieldResolverDefinition>,
    val variableProviders: Map<Arguments.Variable, VariableDeclaration>,
)

private fun EngineTestModule.qplanRegistryInputs(
    schema: QPlanSchema,
    context: EngineExecutionContext,
): QPlanRegistryInputs {
    val sourceSchema = SourceSchemaAdapter(schema)
    val variableRecovery = RequiredSelectionSetVariableRecovery(schema)
    val variableProviders = linkedMapOf<Arguments.Variable, VariableDeclaration>()
    val supplied =
        fieldResolverExecutors.associate { (coordinate, executor) ->
            val field =
                sourceSchema.field(coordinate.first, coordinate.second)
                    as? QPlanSchema.ObjectField
                    ?: throw IllegalArgumentException(
                        "Field executor ${coordinate.render()} does not map to a concrete object field",
                    )
            val sourceField =
                requireNotNull(fullSchema.schema.getObjectType(coordinate.first))
                    .getFieldDefinition(coordinate.second)
            val objectFragment = executor.objectFragment(schema, field)
            val queryFragment = executor.queryFragment(schema, field)
            variableRecovery
                .recover(
                    field = field,
                    objectFragment = objectFragment,
                    objectRequiredSelectionSet = executor.objectSelectionSet,
                    queryFragment = queryFragment,
                    queryRequiredSelectionSet = executor.querySelectionSet,
                )
                .forEach { (variable, declaration) ->
                    require(variableProviders.put(variable, declaration) == null) {
                        "Duplicate variable provider \$${variable.variableName} for ${coordinate.render()}"
                    }
                }
            val invokeExecutor =
                fun(
                    input: EngineObjectData.Sync,
                    queryValue: EngineObjectData.Sync,
                    arguments: Arguments.Resolved,
                    selections: EngineSelectionSet?,
                ): EngineOutputData? {
                    val selector =
                        FieldResolverExecutor.Selector(
                            arguments = arguments.fieldValues,
                            selections = selections,
                            syncObjectValueGetter = { input },
                            syncQueryValueGetter = { queryValue },
                        )
                    val output =
                        runBlocking {
                            executor.batchResolve(listOf(selector), context)[selector]
                        } ?: Result.failure(
                            IllegalStateException(
                                "Field executor ${coordinate.render()} omitted its selector",
                            ),
                        )
                    return output.fold(
                        onSuccess = { normalizeSourceOutput(sourceField.type, it) },
                        onFailure = { EngineErrorData.of(it) },
                    )
                }
            val resolver =
                if (executor.isSelective) {
                    selectiveFieldResolverOf(
                        objectFragment = objectFragment,
                        queryFragment = queryFragment,
                        function = { input, queryValue, arguments, selections ->
                            val selectionSet =
                                (field.type.baseTypeDef as? QPlanSchema.CompositeTypeDef)?.let {
                                    selections.toEngineSelectionSet(it, fullSchema)
                                }
                            invokeExecutor(input, queryValue, arguments, selectionSet)
                        },
                    )
                } else {
                    fieldResolverOf(
                        objectFragment = objectFragment,
                        queryFragment = queryFragment,
                        function = { input, queryValue, arguments ->
                            invokeExecutor(input, queryValue, arguments, null)
                        },
                    )
                }
            field to resolver
        }

    val duplicateCount = fieldResolverExecutors.count() - supplied.size
    require(duplicateCount == 0) {
        "Qplan feature tests require unique field executor coordinates"
    }
    return QPlanRegistryInputs(
        fieldResolvers = supplied + builtInNodeFieldResolvers(schema, context, supplied.keys),
        variableProviders = variableProviders,
    )
}

private fun FieldResolverExecutor.objectFragment(
    schema: QPlanSchema,
    field: QPlanSchema.ObjectField,
): Fragment =
    objectSelectionSet?.let { required ->
        schema.fragmentFromDocument(
            document = required.selections.toDocument(),
            variableField = field,
        )
    } ?: schema.emptyFragmentOf(field.containingDef.name)

private fun FieldResolverExecutor.queryFragment(
    schema: QPlanSchema,
    field: QPlanSchema.ObjectField,
): Fragment =
    querySelectionSet?.let { required ->
        schema.fragmentFromDocument(
            document = required.selections.toDocument(),
            variableField = field,
        )
    } ?: schema.emptyFragmentOf(schema.requireQueryTypeDef().name)

private fun EngineTestModule.builtInNodeFieldResolvers(
    schema: QPlanSchema,
    context: EngineExecutionContext,
    suppliedFields: Set<QPlanSchema.Field>,
): Map<QPlanSchema.Field, FieldResolverDefinition> {
    val sourceSchema = SourceSchemaAdapter(schema)
    val query = schema.emptyFragmentOf(schema.requireQueryTypeDef().name)
    return buildMap {
        fullSchema.schema.queryType.getFieldDefinition("node")?.let { sourceField ->
            val field = sourceSchema.field(fullSchema.schema.queryType.name, sourceField.name)
            if (field !in suppliedFields) {
                put(
                    field,
                    fieldResolverOf(query) { _, arguments ->
                        nodeReference(arguments.fieldValues["id"], context)
                    },
                )
            }
        }
        fullSchema.schema.queryType.getFieldDefinition("nodes")?.let { sourceField ->
            val field = sourceSchema.field(fullSchema.schema.queryType.name, sourceField.name)
            if (field !in suppliedFields) {
                put(
                    field,
                    fieldResolverOf(query) { _, arguments ->
                        val ids = arguments.fieldValues["ids"]
                        if (ids !is List<*>) {
                            EngineErrorData.of()
                        } else {
                            ids.map { nodeReference(it, context) }
                        }
                    },
                )
            }
        }
    }
}

private fun nodeReference(
    globalId: Any?,
    context: EngineExecutionContext,
): Any {
    if (globalId !is String) return EngineErrorData.of()
    return try {
        val (typeName) = context.globalIDCodec.deserialize(globalId)
        val type =
            context.fullSchema.schema.getObjectType(typeName)
                ?: return EngineErrorData.of()
        if (type.interfaces.none { it.name == "Node" }) return EngineErrorData.of()
        normalizeNodeReference(context.createNodeReference(globalId, type))
    } catch (_: IllegalArgumentException) {
        EngineErrorData.of()
    }
}

private fun EngineTestModule.qplanNodeResolvers(
    schema: QPlanSchema,
    context: EngineExecutionContext,
): Map<QPlanSchema.Object, NodeResolverFunction> {
    val byType =
        nodeResolverExecutors.associate { (typeName, executor) ->
            val type = schema.requireType(typeName) as QPlanSchema.Object
            type to
                nodeResolverOf { id ->
                    val selections =
                        context.engineSelectionSetFactory.engineSelectionSet(
                            typeName,
                            "id",
                            emptyMap(),
                        )
                    val selector = NodeResolverExecutor.Selector(id, selections)
                    val output =
                        runBlocking {
                            executor.resolve(listOf(selector), context)[selector]
                        } ?: Result.failure(
                            IllegalStateException(
                                "Node executor $typeName omitted its selector",
                            ),
                        )
                    output.fold(
                        onSuccess = { normalizeSourceObject(it) },
                        onFailure = { EngineErrorData.of(it) },
                    )
                }
        }
    require(nodeResolverExecutors.count() == byType.size) {
        "Qplan feature tests require unique node executor types"
    }
    return byType
}

private fun normalizeSourceOutput(
    expectedType: GraphQLOutputType,
    value: Any?,
): Any? =
    when (expectedType) {
        is GraphQLNonNull ->
            normalizeSourceOutput(expectedType.wrappedType as GraphQLOutputType, value)
        is GraphQLList -> {
            if (value !is List<*>) {
                value
            } else {
                value.map {
                    normalizeSourceOutput(expectedType.wrappedType as GraphQLOutputType, it)
                }
            }
        }
        is GraphQLObjectType ->
            when (value) {
                is NodeReference -> normalizeNodeReference(value)
                is EngineObjectData.Sync -> normalizeSourceObject(expectedType, value)
                is Map<*, *> -> normalizeSourceObjectMap(expectedType, value)
                else -> value
            }
        is GraphQLCompositeType ->
            when (value) {
                is NodeReference -> normalizeNodeReference(value)
                is EngineObjectData.Sync -> normalizeSourceObject(value)
                else -> value
            }
        is GraphQLScalarType ->
            value?.let {
                expectedType.coercing.serialize(
                    it,
                    GraphQLContext.getDefault(),
                    Locale.getDefault(),
                )
            }
        else -> value
    }

private fun normalizeSourceObjectMap(
    expectedType: GraphQLObjectType,
    value: Map<*, *>,
): EngineObjectData.Sync {
    /*
     * EngineTestModule field executors may return a raw GraphQL object source as a map because
     * FieldResolverExecutor's output contract is Any?. Production execution accepts that source,
     * resolves its child fields into an OER, and only then projects required selections as
     * EngineObjectData. This pre-dispatcher adapter bypasses those steps, so materialize the map
     * here before it crosses into qplan's stricter EngineOutputData domain. The declared concrete
     * object type makes this conversion unambiguous; abstract map outputs remain unsupported.
     */
    require(value.keys.all { it is String }) {
        "Qplan feature tests require string keys in map object executor outputs"
    }
    @Suppress("UNCHECKED_CAST")
    return normalizeSourceObject(
        createEngineObjectData(expectedType, value as Map<String, Any?>),
    )
}

private fun normalizeNodeReference(reference: NodeReference): EngineObjectData.Sync =
    ResolvedEngineObjectData(
        reference.type,
        mapOf("id" to reference.id),
    )

private fun normalizeSourceObject(value: EngineObjectData): EngineObjectData.Sync {
    require(value is EngineObjectData.Sync) {
        "Qplan feature tests require synchronous EngineObjectData executor outputs"
    }
    return normalizeSourceObject(value.type, value)
}

private fun normalizeSourceObject(
    type: GraphQLObjectType,
    value: EngineObjectData.Sync,
): EngineObjectData.Sync {
    val fields =
        value.getSelections().associateWith { selection ->
            val field =
                requireNotNull(type.getFieldDefinition(selection)) {
                    "Executor output ${type.name} has no field named $selection"
                }
            normalizeSourceOutput(field.type, value.get(selection))
        }
    return ResolvedEngineObjectData(type, fields)
}

private fun qplanSchemaSDL(schema: EngineSchema): String {
    val options =
        SchemaPrinter.Options
            .defaultOptions()
            .includeIntrospectionTypes(false)
            .includeScalarTypes(false)
            .includeDirectiveDefinition { directiveName -> directiveName == "parent" }
            .includeDirectives { directiveName -> directiveName == "parent" }
            .includeSchemaDefinition(false)
    return SchemaPrinter(options).print(schema.schema)
}

private fun Pair<String, String>.render(): String = "$first.$second"
