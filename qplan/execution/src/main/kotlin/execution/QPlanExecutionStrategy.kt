package execution

import graphql.ExecutionResult
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategy
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.SimpleDataFetcherExceptionHandler
import java.util.concurrent.CompletableFuture
import model.Assumptions
import model.selectionsFrom

/**
 * Query execution boundary for the qplan GraphQL-Java harness.
 *
 * This skeleton decodes GraphQL-Java's validated operation into qplan selections but deliberately
 * stops before resolution or completion.
 */
class QPlanExecutionStrategy(
    private val assumptions: Assumptions,
    dataFetcherExceptionHandler: DataFetcherExceptionHandler = SimpleDataFetcherExceptionHandler(),
) : ExecutionStrategy(dataFetcherExceptionHandler) {
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

        return CompletableFuture.completedFuture(
            ExecutionResult
                .newExecutionResult()
                .data(emptyMap<String, Any?>())
                .build(),
        )
    }
}
