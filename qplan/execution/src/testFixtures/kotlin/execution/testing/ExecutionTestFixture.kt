package execution.testing

import execution.QPlanExecutionStrategy
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import model.testing.TestWorld

/**
 * End-to-end GraphQL-Java harness backed by one qplan reasoning world.
 */
class ExecutionTestFixture private constructor(
    private val graphQL: GraphQL,
) {
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult =
        graphQL.execute(
            ExecutionInput
                .newExecutionInput()
                .query(query)
                .variables(variables)
                .build(),
        )

    companion object {
        fun fromSDL(
            schemaSDL: String,
            runtimeWiring: RuntimeWiring = RuntimeWiring.newRuntimeWiring().build(),
        ): ExecutionTestFixture {
            val world = TestWorld.fromSDL(schemaSDL)
            val graphQLSchema =
                SchemaGenerator().makeExecutableSchema(
                    SchemaParser().parse(schemaSDL),
                    runtimeWiring,
                )
            val graphQL =
                GraphQL
                    .newGraphQL(graphQLSchema)
                    .queryExecutionStrategy(QPlanExecutionStrategy(world.assumptions))
                    .build()
            return ExecutionTestFixture(graphQL)
        }
    }
}
