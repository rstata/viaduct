package semantics.contract

import model.EngineResult
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

enum class VariableSelectionIdentityPolicy {
    MERGE_EQUAL_GROUNDED_KEYS,
    PRESERVE_SELECTION_OCCURRENCES,
}

/**
 * Policy contract for variable-bearing selections that ground to the same arguments.
 */
interface VariableSelectionIdentityResolverContract : ResolverContract {
    val variableSelectionIdentityPolicy: VariableSelectionIdentityPolicy

    @Test
    fun `equal pre-grounded selections merge in fragments and external queries`() {
        var payloadApplications = 0
        val suppliedDemandFields = mutableListOf<Set<String>>()
        val resultFragment =
            """
            fragment Result on Query {
              payload(arg: "same") { one }
              payload(arg: "same") { two }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Payload {
                      one: Int!
                      two: Int!
                    }

                    type Query {
                      result: Int!
                      payload(arg: String!): Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val payloadType = schema.type("Payload") as Schema.ObjectType
                    val payload = schema.objectField("Query", "payload")
                    val payloadKey = Value.GroundKey.of(payload, mapOf("arg" to "same"))
                    val oneKey = schema.contractKey("Payload", "one")
                    val twoKey = schema.contractKey("Payload", "two")
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val value =
                                    input.fieldValues.getValue(payloadKey) as Value.Object
                                val one = value.fieldValues.getValue(oneKey) as Value.Int
                                val two = value.fieldValues.getValue(twoKey) as Value.Int
                                Value.Int.of(one.intValue + two.intValue)
                            },
                        payload to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            }.observeApplications { _, _, demand ->
                                if (demand != null) {
                                    payloadApplications += 1
                                    suppliedDemandFields +=
                                        demand
                                            .merge(payloadType)
                                            .groundKeys()
                                            .mapTo(linkedSetOf()) { key ->
                                                key.field.fieldName
                                            }
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")
        val resultQuery = world.fragmentFrom("fragment Query on Query { result }")

        val resolvedResult =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                resultQuery,
            )

        assertEquals(Value.Int.of(8), resolvedResult.getCell(resultKey).get())
        assertEquals(1, payloadApplications)
        assertEquals(listOf(setOf("one", "two")), suppliedDemandFields)

        val payloadKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "payload"),
                mapOf("arg" to "same"),
            )
        val oneKey = world.schema.contractKey("Payload", "one")
        val twoKey = world.schema.contractKey("Payload", "two")
        val externalQuery =
            world.fragmentFrom(
                """
                fragment Query on Query {
                  payload(arg: "same") { one }
                  payload(arg: "same") { two }
                }
                """.trimIndent(),
            )

        val resolvedExternal =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                externalQuery,
            )
        val payload =
            assertIs<EngineResult.Object>(
                resolvedExternal.getCell(payloadKey).get(),
            )

        assertEquals(Value.Int.of(3), payload.getCell(oneKey).get())
        assertEquals(Value.Int.of(5), payload.getCell(twoKey).get())
        assertEquals(2, payloadApplications)
        assertEquals(
            listOf(setOf("one", "two"), setOf("one", "two")),
            suppliedDemandFields,
        )
        assertTrue(payloadKey !is Value.GroundKey.Stamped)
    }

    @Test
    fun `applies the configured identity policy after variable selections ground equally`() {
        var payloadApplications = 0
        val suppliedDemandFields = mutableListOf<Set<String>>()
        val resultFragment =
            """
            fragment Result on Query {
              payload(arg: "same") { one }
              payload(arg: ${'$'}seed) { two }
              payload(arg: ${'$'}seed) { two }
              payload(arg: ${'$'}other) { one }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Payload {
                      one: Int!
                      two: Int!
                    }

                    type Query {
                      result(seed: String!, other: String!): Int!
                      payload(arg: String!): Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val payload = schema.objectField("Query", "payload")
                    val payloadKey = Value.GroundKey.of(payload, mapOf("arg" to "same"))
                    val oneKey = schema.contractKey("Payload", "one")
                    val twoKey = schema.contractKey("Payload", "two")
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val value =
                                    input.fieldValues.getValue(payloadKey) as Value.Object
                                val one = value.fieldValues.getValue(oneKey) as Value.Int
                                val two = value.fieldValues.getValue(twoKey) as Value.Int
                                Value.Int.of(one.intValue + two.intValue)
                            },
                        payload to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            }.observeApplications { _, _, demand ->
                                if (demand != null) {
                                    payloadApplications += 1
                                    suppliedDemandFields +=
                                        demand
                                            .merge(
                                                schema.type("Payload") as Schema.ObjectType,
                                            ).groundKeys()
                                            .mapTo(linkedSetOf()) { key ->
                                                key.field.fieldName
                                            }
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                        Value.Variable.of(result, "other") to
                            schema.fromArgument(result, "other"),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "result"),
                mapOf(
                    "seed" to "same",
                    "other" to "same",
                ),
            )
        val fragment =
            world.fragmentFrom(
                """fragment Query on Query { result(seed: "same", other: "same") }""",
            )

        val resolved =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                fragment,
            )

        assertEquals(Value.Int.of(8), resolved.getCell(resultKey).get())
        when (variableSelectionIdentityPolicy) {
            VariableSelectionIdentityPolicy.MERGE_EQUAL_GROUNDED_KEYS -> {
                assertEquals(1, payloadApplications)
                assertEquals(listOf(setOf("one", "two")), suppliedDemandFields)
            }
            VariableSelectionIdentityPolicy.PRESERVE_SELECTION_OCCURRENCES -> {
                assertEquals(4, payloadApplications)
                assertEquals(
                    mapOf(
                        setOf("one") to 2,
                        setOf("two") to 2,
                    ),
                    suppliedDemandFields.groupingBy { fields -> fields }.eachCount(),
                )
            }
        }
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }
}
