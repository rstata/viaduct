package semantics.contract

import model.EngineResult
import model.IntEngineResult
import model.ObjectEngineResult
import model.Schema
import model.Stamp
import model.Value
import model.fragmentFrom
import model.merge
import model.objectOf
import model.testing.TestWorld
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
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "payload(arg: 1) { one } payload(arg: 1) { two }"
                          result: "sum(payload.one, payload.two)"
                        )
                      payload(arg: Int!): Payload!
                        @resolver(result: {one: 3, two: 5})
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, demand ->
                    if (
                        field.containingType.typeName == "Query" &&
                        field.fieldName == "payload" &&
                        demand != null
                    ) {
                        payloadApplications += 1
                        suppliedDemandFields +=
                            demand
                                .merge(field.typeExpr.baseType as Schema.ObjectType)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { key ->
                                    key.field.fieldName
                                }
                    }
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

        assertEquals(IntEngineResult.of(8), resolvedResult.getCell(resultKey).get())
        assertEquals(1, payloadApplications)
        assertEquals(listOf(setOf("one", "two")), suppliedDemandFields)

        val payloadKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "payload"),
                mapOf("arg" to 1),
            )
        val oneKey = world.schema.contractKey("Payload", "one")
        val twoKey = world.schema.contractKey("Payload", "two")
        val externalQuery =
            world.fragmentFrom(
                """
                fragment Query on Query {
                  payload(arg: 1) { one }
                  payload(arg: 1) { two }
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
            assertIs<ObjectEngineResult>(
                resolvedExternal.getCell(payloadKey).get(),
            )

        assertEquals(IntEngineResult.of(3), payload.getCell(oneKey).get())
        assertEquals(IntEngineResult.of(5), payload.getCell(twoKey).get())
        assertEquals(2, payloadApplications)
        assertEquals(
            listOf(setOf("one", "two"), setOf("one", "two")),
            suppliedDemandFields,
        )
        assertEquals(Stamp.VariableFreeOccurrence, payloadKey.stamp)
    }

    @Test
    fun `applies the configured identity policy after variable selections ground equally`() {
        var payloadApplications = 0
        val suppliedDemandFields = mutableListOf<Set<String>>()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!, other: Int!): Int!
                        @resolver(
                          of: "payload(arg: 1) { one } payload(arg: ${'$'}seed) { two } payload(arg: ${'$'}seed) { two } payload(arg: ${'$'}other) { one }"
                          result: "sum(payload.one, payload.two)"
                        )
                      payload(arg: Int!): Payload!
                        @resolver(result: {one: 3, two: 5})
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, demand ->
                    if (
                        field.containingType.typeName == "Query" &&
                        field.fieldName == "payload" &&
                        demand != null
                    ) {
                        payloadApplications += 1
                        suppliedDemandFields +=
                            demand
                                .merge(field.typeExpr.baseType as Schema.ObjectType)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { key ->
                                    key.field.fieldName
                                }
                    }
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "result"),
                mapOf(
                    "seed" to 1,
                    "other" to 1,
                ),
            )
        val fragment =
            world.fragmentFrom(
                """fragment Query on Query { result(seed: 1, other: 1) }""",
            )

        val resolved =
            resolveAndValidate(world, fragment)

        assertEquals(IntEngineResult.of(8), resolved.getCell(resultKey).get())
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
