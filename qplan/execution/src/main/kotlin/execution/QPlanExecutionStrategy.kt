package execution

import graphql.ExecutionResult
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLSchema
import java.util.concurrent.CompletableFuture
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import model.Assumptions
import model.ObjectEngineResult
import model.Schema
import model.SourceSchemaAdapter
import model.TypeExpr
import model.engineResultOf
import model.selectionsFrom
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.ImplicitNullValueWeight
import viaduct.arbitrary.graphql.ListValueSize
import viaduct.arbitrary.graphql.TypenameValueWeight
import viaduct.arbitrary.graphql.ir
import viaduct.engine.api.ViaductSchema
import viaduct.mapping.graphql.IR

/**
 * Query execution boundary for the qplan GraphQL-Java harness.
 *
 * The schema-taking constructor creates one random, resolved Query OER. Each request decodes its
 * operation into qplan selections and delegates GraphQL completion with that OER as the root source.
 * The assumptions-only constructor retains the milestone-one placeholder behavior.
 */
class QPlanExecutionStrategy internal constructor(
    private val assumptions: Assumptions,
    internal val resolvedRoot: ObjectEngineResult?,
    dataFetcherExceptionHandler: DataFetcherExceptionHandler =
        SimpleDataFetcherExceptionHandler(),
) : AsyncExecutionStrategy(dataFetcherExceptionHandler) {
    constructor(
        assumptions: Assumptions,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler =
            SimpleDataFetcherExceptionHandler(),
    ) : this(
        assumptions = assumptions,
        resolvedRoot = null,
        dataFetcherExceptionHandler = dataFetcherExceptionHandler,
    )

    constructor(
        assumptions: Assumptions,
        graphQLSchema: GraphQLSchema,
        randomSeed: Long = DEFAULT_RANDOM_SEED,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler =
            SimpleDataFetcherExceptionHandler(),
    ) : this(
        assumptions = assumptions,
        resolvedRoot =
            randomQueryResult(
                graphQLSchema = graphQLSchema,
                modelSchema = assumptions.schema,
                randomSeed = randomSeed,
            ),
        dataFetcherExceptionHandler = dataFetcherExceptionHandler,
    )

    override fun execute(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters,
    ): CompletableFuture<ExecutionResult> {
        assumptions.selectionsFrom(
            operation = executionContext.operationDefinition,
            variables = executionContext.coercedVariables,
            graphQLContext = executionContext.graphQLContext,
            locale = executionContext.locale,
        )

        val root = resolvedRoot
            ?: return CompletableFuture.completedFuture(
                ExecutionResult
                    .newExecutionResult()
                    .data(emptyMap<String, Any?>())
                    .build(),
            )
        return super.execute(
            executionContext,
            parameters.transform { builder -> builder.source(root) },
        )
    }

    private companion object {
        const val DEFAULT_RANDOM_SEED = 1L

        fun randomQueryResult(
            graphQLSchema: GraphQLSchema,
            modelSchema: Schema,
            randomSeed: Long,
        ): ObjectEngineResult {
            requireManualSchema(graphQLSchema)
            val generatorConfig =
                Config.default +
                    (ImplicitNullValueWeight to 0.0) +
                    (ListValueSize to 2..2) +
                    (TypenameValueWeight to 0.0)
            val queryValue =
                Arb
                    .ir(
                        schema = ViaductSchema(graphQLSchema),
                        type = GraphQLNonNull.nonNull(graphQLSchema.queryType),
                        cfg = generatorConfig,
                    ).next(RandomSource.seeded(randomSeed))
            require(queryValue is IR.Value.Object && queryValue.name == "Query") {
                "Expected the arbitrary value generator to produce a Query object"
            }
            return queryValue.toObjectEngineResult(modelSchema)
        }

        fun requireManualSchema(graphQLSchema: GraphQLSchema) {
            graphQLSchema.allTypesAsList
                .filterIsInstance<GraphQLFieldsContainer>()
                .filterNot { type -> type.name.startsWith("__") }
                .forEach { type ->
                    type.fieldDefinitions.forEach { field ->
                        require(field.arguments.isEmpty()) {
                            "Manual OER execution does not support field arguments: " +
                                "${type.name}/${field.name}"
                        }
                    }
                }
        }

        fun IR.Value.Object.toObjectEngineResult(schema: Schema): ObjectEngineResult {
            val sourceSchema = SourceSchemaAdapter(schema)
            return schema.engineResultOf(name) {
                fields.forEach { (fieldName, value) ->
                    require(fieldName != "__typename") {
                        "Manual OER generation does not retain __typename cells"
                    }
                    val canonicalField = sourceSchema.field(name, fieldName)
                    require(canonicalField is Schema.ObjectField) {
                        "Manual OER generation requires a concrete field for $name/$fieldName"
                    }
                    field(canonicalField.fieldName).resolvesTo(
                        value.toEngineResultValue(
                            schema = schema,
                            sourceTypeExpr = sourceSchema.typeExpr(canonicalField),
                            resultTypeExpr = canonicalField.typeExpr,
                            lowerNode = canonicalField.fieldName != fieldName,
                        ),
                    )
                }
            }
        }

        fun IR.Value.toEngineResultValue(
            schema: Schema,
            sourceTypeExpr: TypeExpr<Schema.OutputType>,
            resultTypeExpr: TypeExpr<Schema.OutputType>,
            lowerNode: Boolean,
        ): Any? {
            if (this == IR.Value.Null) return null

            return when {
                sourceTypeExpr is TypeExpr.List && resultTypeExpr is TypeExpr.List -> {
                    require(this is IR.Value.List) {
                        "Expected a generated list value for $sourceTypeExpr"
                    }
                    value.map { element ->
                        element.toEngineResultValue(
                            schema = schema,
                            sourceTypeExpr = sourceTypeExpr.elementType,
                            resultTypeExpr = resultTypeExpr.elementType,
                            lowerNode = lowerNode,
                        )
                    }
                }

                sourceTypeExpr is TypeExpr.Named && resultTypeExpr is TypeExpr.Named ->
                    if (lowerNode) {
                        require(this is IR.Value.Object) {
                            "Expected a generated Node object for $sourceTypeExpr"
                        }
                        toNodeBridgeResult(schema, resultTypeExpr)
                    } else {
                        when (this) {
                            is IR.Value.Boolean -> value
                            is IR.Value.Number -> value
                            is IR.Value.String -> value
                            is IR.Value.Object -> toObjectEngineResult(schema)
                            is IR.Value.Time ->
                                error(
                                    "Manual OER execution does not support custom temporal scalars",
                                )
                            is IR.Value.List,
                            IR.Value.Null,
                            -> error("Generated value does not match $sourceTypeExpr")
                        }
                    }

                else ->
                    error(
                        "Source type $sourceTypeExpr and result type $resultTypeExpr have " +
                            "different list shapes",
                    )
            }
        }

        fun IR.Value.Object.toNodeBridgeResult(
            schema: Schema,
            bridgeTypeExpr: TypeExpr.Named<Schema.OutputType>,
        ): ObjectEngineResult {
            val node = toObjectEngineResult(schema)
            val bridgeType = bridgeTypeExpr.baseType as Schema.ObjectType
            val id =
                fields["id"]
                    ?: error("Generated Node object $name has no id")
            return schema.engineResultOf(bridgeType.typeName) {
                "id" resolvesTo id.toSimpleResultValue()
                "node" resolvesTo node
            }
        }

        fun IR.Value.toSimpleResultValue(): Any =
            when (this) {
                is IR.Value.Boolean -> value
                is IR.Value.Number -> value
                is IR.Value.String -> value
                else -> error("Expected a generated simple value, found $this")
            }
    }
}
