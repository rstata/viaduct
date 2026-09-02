package semantics.resolver26

import model.Assumptions
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineResultOf
import model.fragmentFrom
import model.registry.InstantiatedFieldPathDefinition
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromQueryField
import semantics.contract.validateFromFieldBindings
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ResolverFromFieldBindingOracleTest {
    @Test
    fun `partial from-field binding state is rejected`() {
        val fixture = bindingFixture()
        fixture.bind(fixture.definitions.first())

        assertFailsWith<AssertionError> {
            context(fixture.world) {
                fixture.result.validateFromFieldBindings(setOf(fixture.occurrenceId))
            }
        }
    }

    @Test
    fun `an applied occurrence with no object-path bindings is rejected`() {
        val fixture = bindingFixture()

        assertFailsWith<AssertionError> {
            context(fixture.world) {
                fixture.result.validateFromFieldBindings(setOf(fixture.occurrenceId))
            }
        }
    }

    @Test
    fun `a passive occurrence requires no object-path bindings`() {
        val fixture = bindingFixture()

        context(fixture.world) {
            fixture.result.validateFromFieldBindings(emptySet())
        }
    }

    @Test
    fun `a passive occurrence with an object-path binding is rejected`() {
        val fixture = bindingFixture()
        fixture.bind(fixture.definitions.first())

        assertFailsWith<AssertionError> {
            context(fixture.world) {
                fixture.result.validateFromFieldBindings(emptySet())
            }
        }
    }

    @Test
    fun `object-path bindings in a query-fragment root are validated`() {
        val testWorld = bindingWorld()
        val world = testWorld.assumptions
        val primaryResult = world.engineResultOf("Query")
        val queryResult = completedBindingResult(world)
        val queryFixture = bindingFixture(world, queryResult)
        queryFixture.definitions.forEach { definition ->
            world.bindVariable(
                requireNotNull(definition.variable.instanceId),
                -1,
            )
        }
        world.queryValues[ResolverOccurrenceId.at(primaryResult, emptyList())] = queryResult

        assertFailsWith<AssertionError> {
            context(world) {
                primaryResult.validateFromFieldBindings(setOf(queryFixture.occurrenceId))
            }
        }
    }

    @Test
    fun `Query-path bindings are validated against their occurrence Query root`() {
        val queryFragment =
            "fragment Input on Query { provided: provider use(value: ${'$'}value) }"
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      provider: Int!
                      use(value: Int!): Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        schema.requireObjectField("Query", "provider") to
                            fieldResolverOf(empty) { _, _ -> 7 },
                        schema.requireObjectField("Query", "use") to
                            fieldResolverOf(empty) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment = empty,
                                queryFragment = schema.fragmentFrom(queryFragment),
                            ) { _, _, _ -> 7 },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "value") to
                            schema.fromQueryField(queryFragment, listOf("provided")),
                    )
                },
            )
        val world = testWorld.assumptions
        val result =
            world.engineResultOf("Query") {
                "consumer" resolvesTo 7
            }
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val consumerKey = ObjectEngineResult.GroundKey.of(consumer, emptyMap())
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(consumerKey))
        val queryResult =
            world.engineResultOf("Query") {
                "provider" resolvesTo 7
                field("use", "value" to 7) resolvesTo 7
            }
        world.queryValues[occurrenceId] = queryResult
        val definition =
            world.resolverRegistry
                .resolver(consumer)
                .instantiateQueryFragment(occurrenceId)
                .pathVariableDefinitions
                .single()
        world.bindVariable(requireNotNull(definition.variable.instanceId), -1)

        assertFailsWith<AssertionError> {
            context(world) {
                result.validateFromFieldBindings(setOf(occurrenceId))
            }
        }
    }
}

private data class BindingFixture(
    val world: Assumptions,
    val result: ObjectEngineResult,
    val occurrenceId: ResolverOccurrenceId,
    val definitions: List<InstantiatedFieldPathDefinition>,
) {
    fun bind(definition: InstantiatedFieldPathDefinition) {
        val providerField = definition.path.single().field.name
        val value =
            when (providerField) {
                "first" -> 11
                "second" -> 13
                else -> error("Unexpected provider field $providerField")
            }
        world.bindVariable(requireNotNull(definition.variable.instanceId), value)
    }
}

private fun bindingFixture(): BindingFixture {
    val world = bindingWorld().assumptions
    return bindingFixture(world, completedBindingResult(world))
}

private fun bindingFixture(
    world: Assumptions,
    result: ObjectEngineResult,
): BindingFixture {
    val resultField = world.schema.requireObjectField("Query", "result")
    val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())
    val occurrenceId = ResolverOccurrenceId.at(result, listOf(resultKey))
    val resolver = world.resolverRegistry.resolver(resultField)
    val definitions =
        resolver.instantiateObjectFragmentAt(result, listOf(resultKey)).pathVariableDefinitions
    return BindingFixture(
        world = world,
        result = result,
        occurrenceId = occurrenceId,
        definitions = definitions,
    )
}

private fun completedBindingResult(world: Assumptions): ObjectEngineResult =
    world.engineResultOf("Query") {
        "first" resolvesTo 11
        "second" resolvesTo 13
        "result" resolvesTo 24
    }

private fun bindingWorld(): TestWorld =
    TestWorld.fromDSL(
        selectiveResolvers = true,
        schemaSDL =
            """
            extend type Query {
              result: Int!
                @resolver(
                  of: "first second consume(first: ${'$'}firstValue, second: ${'$'}secondValue)"
                  pathVars: [
                    {name: "firstValue", path: ["first"]}
                    {name: "secondValue", path: ["second"]}
                  ]
                  result: "sum(consume)"
                )
              first: Int! @resolver(result: 11)
              second: Int! @resolver(result: 13)
              consume(first: Int!, second: Int!): Int!
                @resolver(result: "sum(${'$'}first, ${'$'}second)")
            }
            """.trimIndent(),
    )
