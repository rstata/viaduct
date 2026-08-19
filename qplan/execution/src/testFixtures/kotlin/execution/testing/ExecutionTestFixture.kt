package execution.testing

import execution.QPlanExecutionStrategy
import execution.QPlanWiringFactory
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import model.ObjectEngineResult
import model.Schema
import model.testing.TestWorld

/**
 * End-to-end GraphQL-Java harness backed by one qplan reasoning world.
 */
class ExecutionTestFixture private constructor(
    private val graphQL: GraphQL,
    private val root: ObjectEngineResult? = null,
) {
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult {
        val input =
            ExecutionInput
                .newExecutionInput()
                .query(query)
                .variables(variables)
        root?.let(input::root)
        return graphQL.execute(input.build())
    }

    companion object {
        fun fromSDL(schemaSDL: String): ExecutionTestFixture =
            fromWorld(
                schemaSDL = schemaSDL,
                world = TestWorld.fromSDL(schemaSDL),
            )

        fun fromResolverDSL(
            schemaSDL: String,
            resolverSchemaSDL: String,
        ): ExecutionTestFixture =
            fromWorld(
                schemaSDL = schemaSDL,
                world = TestWorld.fromDSL(resolverSchemaSDL),
            )

        private fun fromWorld(
            schemaSDL: String,
            world: TestWorld,
        ): ExecutionTestFixture {
            val runtimeWiring =
                RuntimeWiring
                    .newRuntimeWiring()
                    .wiringFactory(QPlanWiringFactory(world.schema))
                    .build()
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

        /**
         * Builds a vanilla GraphQL-Java executor that completes fields from [root].
         */
        fun fromResolvedRoot(
            schemaSDL: String,
            schema: Schema,
            root: ObjectEngineResult,
        ): ExecutionTestFixture {
            val runtimeWiring =
                RuntimeWiring
                    .newRuntimeWiring()
                    .wiringFactory(QPlanWiringFactory(schema))
                    .build()
            val graphQLSchema =
                SchemaGenerator().makeExecutableSchema(
                    SchemaParser().parse(schemaSDL),
                    runtimeWiring,
                )
            return ExecutionTestFixture(
                graphQL = GraphQL.newGraphQL(graphQLSchema).build(),
                root = root,
            )
        }
    }
}
