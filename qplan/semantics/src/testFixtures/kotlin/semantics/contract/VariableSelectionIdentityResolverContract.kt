package semantics.contract

import model.requireQueryTypeDef
import model.requireObjectField
import model.EngineResult
import model.ObjectEngineResult
import viaduct.graphql.schema.ViaductSchema
import model.Stamp
import model.instantiateBindings
import model.merge
import model.objectOf
import model.operationSelectionsFrom
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

enum class VariableSelectionIdentityPolicy {
    MERGE_EQUAL_GROUNDED_KEYS,
    PRESERVE_RESPONSE_GROUP_OCCURRENCES,
}

/**
 * Policy contract for variable-bearing selections that ground to the same arguments.
 */
interface VariableSelectionIdentityResolverContract : ResolverContract {
    val variableSelectionIdentityPolicy: VariableSelectionIdentityPolicy

    @Test
    fun `equal pre-grounded selections merge in fragments and external queries`() {
        val suppliedDemandFields = ConcurrentLinkedQueue<Set<String>>()
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
                        field.containingDef.name == "Query" &&
                        field.name == "payload" &&
                        demand != null
                    ) {
                        suppliedDemandFields +=
                            demand
                                .merge(field.type.baseTypeDef as ViaductSchema.Object)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { key ->
                                    key.field.name
                                }
                    }
                },
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")
        val resultSelections = world.operationSelectionsFrom("query { result }")

        val resolvedResult =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                resultSelections,
            )

        assertEquals(8, resolvedResult.getCell(resultKey).get())
        assertEquals(1, suppliedDemandFields.size)
        assertEquals(listOf(setOf("one", "two")), suppliedDemandFields.toList())

        val payloadKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "payload"),
                mapOf("arg" to 1),
            )
        val oneKey = world.schema.contractKey("Payload", "one")
        val twoKey = world.schema.contractKey("Payload", "two")
        val externalSelections =
            world.operationSelectionsFrom(
                """
                query {
                  payload(arg: 1) { one }
                  payload(arg: 1) { two }
                }
                """.trimIndent(),
            )

        val resolvedExternal =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                externalSelections,
            )
        val payload =
            assertIs<ObjectEngineResult>(
                resolvedExternal.getCell(payloadKey).get(),
            )

        assertEquals(3, payload.getCell(oneKey).get())
        assertEquals(5, payload.getCell(twoKey).get())
        assertEquals(2, suppliedDemandFields.size)
        assertEquals(
            listOf(setOf("one", "two"), setOf("one", "two")),
            suppliedDemandFields.toList(),
        )
        assertEquals(Stamp.VariableFreeOccurrence, payloadKey.stamp)
    }

    @Test
    fun `applies the configured identity policy after variable selections ground equally`() {
        val suppliedDemandFields = ConcurrentLinkedQueue<Set<String>>()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!, other: Int!): Int!
                        @resolver(
                          of: "ground: payload(arg: 1) { one } seedValue: payload(arg: ${'$'}seed) { two } seedValue: payload(arg: ${'$'}seed) { two } otherValue: payload(arg: ${'$'}other) { one }"
                          result: "sum(ground.one, seedValue.two)"
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
                        field.containingDef.name == "Query" &&
                        field.name == "payload" &&
                        demand != null
                    ) {
                        suppliedDemandFields +=
                            demand
                                .merge(field.type.baseTypeDef as ViaductSchema.Object)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { key ->
                                    key.field.name
                                }
                    }
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf(
                    "seed" to 1,
                    "other" to 1,
                ),
            )
        val selections =
            world.operationSelectionsFrom(
                """query { result(seed: 1, other: 1) }""",
            )

        val resolved =
            resolveAndValidate(world, selections)

        assertEquals(8, resolved.getCell(resultKey).get())
        when (variableSelectionIdentityPolicy) {
            VariableSelectionIdentityPolicy.MERGE_EQUAL_GROUNDED_KEYS -> {
                assertEquals(1, suppliedDemandFields.size)
                assertEquals(listOf(setOf("one", "two")), suppliedDemandFields.toList())
            }
            VariableSelectionIdentityPolicy.PRESERVE_RESPONSE_GROUP_OCCURRENCES -> {
                assertEquals(3, suppliedDemandFields.size)
                assertEquals(
                    mapOf(
                        setOf("one") to 2,
                        setOf("two") to 1,
                    ),
                    suppliedDemandFields.groupingBy { fields -> fields }.eachCount(),
                )
            }
        }
        assertTrue(
            context(world) {
                resolved.correctResolution(
                    selections
                        .merge(world.schema.requireQueryTypeDef())
                        .instantiateBindings(),
                )
            },
        )
    }
}
