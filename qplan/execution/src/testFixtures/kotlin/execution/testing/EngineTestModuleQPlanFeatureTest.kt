package execution.testing

import graphql.ExecutionResult
import graphql.language.AstPrinter
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLOutputType
import graphql.schema.idl.SchemaPrinter
import kotlinx.coroutines.runBlocking
import model.EngineErrorData
import model.SourceSchemaAdapter
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireQueryTypeDef
import model.requireType
import model.testing.FieldResolverDefinition
import model.testing.NodeResolverFunction
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.ViaductSchema as EngineSchema
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.graphql.schema.ViaductSchema as QPlanSchema
import viaduct.graphql.test.assertJson as realAssertJson

/**
 * GraphQL feature-test surface backed directly by qplan and an [EngineTestModule]'s executors.
 *
 * This is intentionally a pre-dispatcher integration: it does not construct a DispatcherRegistry
 * or data loaders. The first integration slice accepts only synchronous, unbatched,
 * non-selective executors.
 */
class QPlanFeatureTest internal constructor(
    private val fixture: ExecutionTestFixture,
) {
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult = fixture.runQuery(query, variables)

    fun ExecutionResult.assertJson(expectedJson: String): Unit = realAssertJson(expectedJson)
}

/**
 * Runs qplan against the executor registry represented by this in-memory engine module.
 *
 * Source executor values are adapted before qplan's existing fixture lowering. Consequently,
 * `__typename` remains GraphQL-Java completion over qplan's generated typename resolvers, while
 * Node references and Node resolver outputs use the canonical qplan node-bridge lowering.
 */
fun EngineTestModule.runQPlanFeatureTest(block: QPlanFeatureTest.() -> Unit) {
    val schemaSDL = qplanSchemaSDL(fullSchema)
    val context = ContextMocks(myFullSchema = fullSchema).engineExecutionContext
    validateSupportedExecutors()

    val world =
        TestWorld.fromSDL(
            schemaSDL = schemaSDL,
            fieldResolvers = { schema ->
                qplanFieldResolvers(schema, context)
            },
            nodeResolvers = { schema ->
                qplanNodeResolvers(schema, context)
            },
        )
    QPlanFeatureTest(ExecutionTestFixture.fromWorld(schemaSDL, world)).block()
}

private fun EngineTestModule.validateSupportedExecutors() {
    require(checkerExecutors.isEmpty() && typeCheckerExecutors.isEmpty()) {
        "Qplan feature tests do not support checker executors yet"
    }
    fieldResolverExecutors.forEach { (coordinate, executor) ->
        require(!executor.isBatching) {
            "Qplan feature tests do not support batching field executor ${coordinate.render()}"
        }
        require(!executor.isSelective) {
            "Qplan feature tests do not support selective field executor ${coordinate.render()}"
        }
        require(executor.querySelectionSet == null) {
            "Qplan feature tests do not support query required selections for ${coordinate.render()}"
        }
        require(executor.objectSelectionSet?.variablesResolvers.orEmpty().isEmpty()) {
            "Qplan feature tests do not support variables in object required selections for ${coordinate.render()}"
        }
    }
    nodeResolverExecutors.forEach { (typeName, executor) ->
        require(!executor.isBatching) {
            "Qplan feature tests do not support batching node executor $typeName"
        }
        require(!executor.isSelective) {
            "Qplan feature tests do not support selective node executor $typeName"
        }
    }
}

private fun EngineTestModule.qplanFieldResolvers(
    schema: QPlanSchema,
    context: EngineExecutionContext,
): Map<QPlanSchema.Field, FieldResolverDefinition> {
    val sourceSchema = SourceSchemaAdapter(schema)
    val queryData =
        ResolvedEngineObjectData(
            fullSchema.schema.queryType,
            emptyMap(),
        )
    val supplied =
        fieldResolverExecutors.associate { (coordinate, executor) ->
            val field = sourceSchema.field(coordinate.first, coordinate.second)
            val sourceField =
                requireNotNull(fullSchema.schema.getObjectType(coordinate.first))
                    .getFieldDefinition(coordinate.second)
            field to
                fieldResolverOf(
                    objectFragment = executor.objectFragment(schema, coordinate.first),
                    function = { input, arguments ->
                        val selector =
                            FieldResolverExecutor.Selector(
                                arguments = arguments.fieldValues,
                                selections = null,
                                syncObjectValueGetter = { input },
                                syncQueryValueGetter = { queryData },
                            )
                        val output =
                            runBlocking {
                                executor.batchResolve(listOf(selector), context)[selector]
                            } ?: Result.failure(
                                IllegalStateException(
                                    "Field executor ${coordinate.render()} omitted its selector",
                                ),
                            )
                        output.fold(
                            onSuccess = { normalizeSourceOutput(sourceField.type, it) },
                            onFailure = { EngineErrorData },
                        )
                    },
                )
        }

    val duplicateCount = fieldResolverExecutors.count() - supplied.size
    require(duplicateCount == 0) {
        "Qplan feature tests require unique field executor coordinates"
    }
    return supplied + builtInNodeFieldResolvers(schema, context, supplied.keys)
}

private fun FieldResolverExecutor.objectFragment(
    schema: QPlanSchema,
    typeName: String,
) =
    objectSelectionSet?.let { required ->
        schema.fragmentFrom(
            "fragment _ on ${required.selections.typeName} " +
                AstPrinter.printAst(required.selections.selections),
        )
    } ?: schema.emptyFragmentOf(typeName)

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
                            EngineErrorData
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
    if (globalId !is String) return EngineErrorData
    return try {
        val (typeName) = context.globalIDCodec.deserialize(globalId)
        val type = context.fullSchema.schema.getObjectType(typeName) ?: return EngineErrorData
        if (type.interfaces.none { it.name == "Node" }) return EngineErrorData
        normalizeNodeReference(context.createNodeReference(globalId, type))
    } catch (_: IllegalArgumentException) {
        EngineErrorData
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
                        onFailure = { EngineErrorData },
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
        is GraphQLCompositeType ->
            when (value) {
                is NodeReference -> normalizeNodeReference(value)
                is EngineObjectData.Sync -> normalizeSourceObject(value)
                else -> value
            }
        else -> value
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
    val type = value.type
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
            .includeDirectiveDefinitions(false)
            .includeDirectives(false)
            .includeSchemaDefinition(false)
    return SchemaPrinter(options).print(schema.schema)
}

private fun Pair<String, String>.render(): String = "$first.$second"
