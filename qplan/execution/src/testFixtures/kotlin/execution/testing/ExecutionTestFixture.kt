package execution.testing

import execution.QPlanExecutionStrategy
import execution.QPlanWiringFactory
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.language.AstPrinter
import graphql.language.Definition
import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.parser.Parser
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import model.ObjectEngineResult
import model.SourceSchemaAdapter
import model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

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

        /**
         * Builds a fixture directly from resolver-test DSL.
         *
         * The executable GraphQL schema is derived by removing fixture-only directives from the
         * DSL document. The qplan world independently compiles the same document into resolvers.
         */
        fun fromResolverDSL(resolverSchemaSDL: String): ExecutionTestFixture =
            fromResolverDSL(
                schemaSDL = executableSchemaSDL(resolverSchemaSDL),
                resolverSchemaSDL = resolverSchemaSDL,
            )

        internal fun fromWorld(
            schemaSDL: String,
            world: TestWorld,
        ): ExecutionTestFixture {
            val runtimeWiring =
                RuntimeWiring
                    .newRuntimeWiring()
                    .wiringFactory(QPlanWiringFactory(SourceSchemaAdapter(world.schema)))
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
            schema: ViaductSchema,
            root: ObjectEngineResult,
        ): ExecutionTestFixture {
            val runtimeWiring =
                RuntimeWiring
                    .newRuntimeWiring()
                    .wiringFactory(QPlanWiringFactory(SourceSchemaAdapter(schema)))
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

private fun executableSchemaSDL(resolverSchemaSDL: String): String {
    val document = Parser.parse(resolverSchemaSDL)
    val stripped =
        document.transform { builder ->
            builder.definitions(document.definitions.map(::stripResolverDirectives))
        }
    return BUILT_IN_SCHEMA + "\n" + AstPrinter.printAst(stripped)
}

private fun stripResolverDirectives(definition: Definition<*>): Definition<*> =
    when (definition) {
        is ObjectTypeExtensionDefinition ->
            definition.transformExtension { builder ->
                builder
                    .directives(definition.directives.withoutResolverDirectives())
                    .fieldDefinitions(
                        definition.fieldDefinitions.map { it.withoutResolverDirectives() },
                    )
            }
        is ObjectTypeDefinition ->
            definition.transform { builder ->
                builder
                    .directives(definition.directives.withoutResolverDirectives())
                    .fieldDefinitions(
                        definition.fieldDefinitions.map { it.withoutResolverDirectives() },
                    )
            }
        else -> definition
    }

private fun FieldDefinition.withoutResolverDirectives(): FieldDefinition =
    transform { builder -> builder.directives(directives.withoutResolverDirectives()) }

private fun List<Directive>.withoutResolverDirectives(): List<Directive> =
    filterNot { directive -> directive.name in RESOLVER_DIRECTIVES }

private val RESOLVER_DIRECTIVES = setOf("resolver", "nodeResolver")

private val BUILT_IN_SCHEMA =
    """
    directive @parent on FIELD_DEFINITION
    interface Node { id: ID! }
    type Query { node(id: ID!): Node }
    """.trimIndent()
