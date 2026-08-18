package semantics.contract

import model.ObjectEngineResult
import model.OpenArguments

import model.EngineResult
import model.ErrorEngineResult
import model.IntEngineResult
import model.Value
import model.VariableBinding
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract for resolver variables read from exact paths in the defining object fragment.
 */
interface ObjectFragmentFromObjectPathResolverContract :
    ResolverContract,
    DeferredNestedObjectPathDemandResolverContract,
    PassiveObjectPathProviderResolverContract,
    NestedObjectPathUseResolverContract,
    ObjectPathNodeInteractionResolverContract,
    AcyclicVariableDependencyResolverContract {
    @Test
    fun `binds a variable from a direct active scalar provider`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: Int! @resolver(result: 7)
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.objectField("Query", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())
        val resolver = world.resolverRegistry.resolver(resultField)
        val resolved = resolveAndValidate(world, "fragment ignored on Query { result }")
        val boundVariable =
            context(world) {
                resolver
                    .boundObjectPathDefinitions(listOf(resultKey))
                    .single()
                    .variable
            }

        assertEquals(IntEngineResult.of(14), resolved.getCell(resultKey).get())
        assertEquals(
            VariableBinding.of(Value.Int.of(7)),
            world.getBinding(boundVariable),
        )
    }

    @Test
    fun `binds null and error from nullable provider paths`() {
        listOf<Value.Input?>(null, Value.Error).forEach { provided ->
            val dslValue = if (provided == Value.Error) "\"ERROR\"" else "null"
            var observedResultInput = false
            var consumedArguments: OpenArguments.Ground? = null
            var consumedValue: Value.Output? = null
            val testWorld =
                TestWorld.fromDSL(
                    selectiveResolvers = selectiveResolvers,
                    schemaSDL =
                        """
                        extend type Query {
                          result: Int
                            @resolver(
                              of: "source consume(value: ${'$'}value)"
                              pathVars: [{name: "value", path: ["source"]}]
                              result: $dslValue
                            )
                          source: Int @resolver(result: $dslValue)
                          consume(value: Int): Int @resolver(result: $dslValue)
                        }
                        """.trimIndent(),
                    applicationObserver = { field, input, _, _ ->
                        if (
                            field.containingType.typeName == "Query" &&
                            field.fieldName == "result"
                        ) {
                            val consumed =
                                input.fieldValues.entries
                                    .single { (key, _) -> key.field.fieldName == "consume" }
                            consumedArguments = consumed.key.arguments
                            consumedValue = consumed.value
                            observedResultInput = true
                        }
                    },
                )
            val world = testWorld.assumptions
            val resultField = world.schema.objectField("Query", "result")
            val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())
            val resolver = world.resolverRegistry.resolver(resultField)
            val resolved = resolveAndValidate(world, "fragment ignored on Query { result }")
            val boundVariable =
                context(world) {
                    resolver
                        .boundObjectPathDefinitions(listOf(resultKey))
                        .single()
                        .variable
                }

            assertEquals<EngineResult?>(
                if (provided == Value.Error) ErrorEngineResult else null,
                resolved.getCell(resultKey).get(),
            )
            assertEquals(
                if (provided == Value.Error) {
                    VariableBinding.Error
                } else {
                    VariableBinding.of(provided)
                },
                world.getBinding(boundVariable),
            )
            assertEquals(
                if (provided == Value.Error) {
                    OpenArguments.Ground.Error
                } else {
                    Value.Arguments.of(
                        world.schema.objectField("Query", "consume"),
                        mapOf("value" to provided),
                    )
                },
                consumedArguments,
            )
            assertEquals(true, observedResultInput)
            assertEquals(provided as Value.Output?, consumedValue)
        }
    }

    @Test
    fun `reads a nested provider after its active ancestor publishes passive content`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "box { value } consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["box", "value"]}]
                          result: "sum(consume)"
                        )
                      box: Box! @resolver(result: {value: 9})
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Box {
                      value: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(world, "fragment ignored on Query { result }")

        assertEquals(IntEngineResult.of(9), resolved.getCell(resultKey).get())
    }

    @Test
    fun `converts a terminal scalar list to a ground input list`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source consume(values: ${'$'}values)"
                          pathVars: [{name: "values", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: [Int!]! @resolver(result: [2, 3, 5])
                      consume(values: [Int!]!): Int! @resolver(result: 10)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(world, "fragment ignored on Query { result }")

        assertEquals(IntEngineResult.of(10), resolved.getCell(resultKey).get())
        testWorld.applicationArguments.assertArguments(
            world.schema.objectField("Query", "consume"),
            mapOf("values" to listOf(2, 3, 5)),
        )
    }
}
