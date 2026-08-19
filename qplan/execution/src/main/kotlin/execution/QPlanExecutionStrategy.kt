package execution

import graphql.ExecutionResult
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.SimpleDataFetcherExceptionHandler
import java.util.concurrent.CompletableFuture
import model.Assumptions
import model.selectionsFrom
import semantics.resolver26.resolve

/**
 * Query execution boundary for the qplan GraphQL-Java harness.
 *
 * Each request decodes its validated operation into qplan selections, resolves those selections
 * with Resolver26, and delegates GraphQL completion with the resolved OER as the root source.
 */
class QPlanExecutionStrategy(
    private val world: Assumptions,
    dataFetcherExceptionHandler: DataFetcherExceptionHandler =
        SimpleDataFetcherExceptionHandler(),
) : AsyncExecutionStrategy(dataFetcherExceptionHandler) {
    override fun execute(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters,
    ): CompletableFuture<ExecutionResult> {

        // Query Planniing: Convert operation to be executed into a [SelectionForest]
        val selections =
            world.selectionsFrom(
                operation = executionContext.operationDefinition,
                variables = executionContext.coercedVariables,
                graphQLContext = executionContext.graphQLContext,
                locale = executionContext.locale,
            )

        // Field Resolution: Use resolver26 to compute an OER tree
        val root =
            context(world) {
                resolve(selections)
            }

        // Field Completion: Let graphql-java handle it
        return super.execute(
            executionContext,
            parameters.transform { builder -> builder.source(root) },
        )
    }
}
