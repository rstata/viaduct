package semantics.contract

import model.requireObjectField
import model.ObjectEngineResult
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
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
        val resultField = world.schema.requireObjectField("Query", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())
        val resolver = world.resolverRegistry.resolver(resultField)
        val resolved = resolveAndValidate(world, "query { result }")
        val boundVariable =
            context(world) {
                resolver
                    .boundObjectPathDefinitions(listOf(resultKey))
                    .single()
                    .variable
            }

        assertEquals(14, resolved.getCell(resultKey).get())
        assertEquals(
            VariableBinding.of(7),
            world.getBinding(boundVariable),
        )
    }

    @Test
    fun `binds null and error from nullable provider paths`() {
        listOf(false, true).forEach { isError ->
            val provided = null
            val dslValue = if (isError) "\"ERROR\"" else "null"
            var observedResultInput = false
            var consumedKey: String? = null
            var consumedValue: EngineOutputData? = null
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
                            field.containingDef.name == "Query" &&
                            field.name == "result"
                        ) {
                            val consumed =
                                input.selectionValues().entries
                                    .single { (key, _) -> key == "consume" }
                            consumedKey = consumed.key
                            consumedValue = consumed.value
                            observedResultInput = true
                        }
                    },
                )
            val world = testWorld.assumptions
            val resultField = world.schema.requireObjectField("Query", "result")
            val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())
            val resolver = world.resolverRegistry.resolver(resultField)
            val resolved = resolveAndValidate(world, "query { result }")
            val boundVariable =
                context(world) {
                    resolver
                        .boundObjectPathDefinitions(listOf(resultKey))
                        .single()
                        .variable
                }

            assertEquals<EngineResult?>(
                if (isError) ErrorEngineResult else null,
                resolved.getCell(resultKey).get(),
            )
            assertEquals(
                if (isError) {
                    VariableBinding.Error
                } else {
                    VariableBinding.of(provided)
                },
                world.getBinding(boundVariable),
            )
            assertEquals("consume", consumedKey)
            assertEquals(true, observedResultInput)
            assertEquals(if (isError) EngineErrorData else null, consumedValue)
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
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(world, "query { result }")

        assertEquals(9, resolved.getCell(resultKey).get())
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
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(world, "query { result }")

        assertEquals(10, resolved.getCell(resultKey).get())
        testWorld.applicationArguments.assertArguments(
            world.schema.requireObjectField("Query", "consume"),
            mapOf("values" to listOf(2, 3, 5)),
        )
    }
}
