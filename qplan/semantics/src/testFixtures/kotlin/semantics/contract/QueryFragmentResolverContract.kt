package semantics.contract

import java.util.concurrent.atomic.AtomicInteger
import model.Arguments
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.isContextuallyGrounded
import model.requireObjectField
import model.groundedArguments
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Contract for independently resolved Query-rooted field-resolver fragments. */
interface QueryFragmentResolverContract : ResolverContract {
    @Test
    fun `query fragments preserve aliases bind arguments and do not share OERs`() {
        val sourceApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      source(value: Int!): Int!
                      consumer(value: Int!): Int!
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, _ ->
                    if (field.name == "source") {
                        sourceApplications.incrementAndGet()
                    }
                },
                fieldResolvers = { schema ->
                    val source = schema.requireObjectField("Query", "source")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    val queryFragment =
                        schema.fragmentFrom(
                            """
                            fragment ConsumerQuery on Query {
                              aliased: source(value: ${'$'}argumentValue)
                            }
                            """.trimIndent(),
                        )
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = queryFragment,
                            ) { _, queryValue, _ ->
                                assertEquals(setOf("aliased"), queryValue.selectionValues().keys)
                                queryValue.selectionValues().getValue("aliased")
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "argumentValue") to
                            schema.fromArgument(consumer, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val firstKey =
            world.schema.contractKey("Query", "consumer", mapOf("value" to 2))
        val secondKey =
            world.schema.contractKey("Query", "consumer", mapOf("value" to 3))

        val result =
            resolveAndValidate(
                world,
                """
                query {
                  first: consumer(value: 2)
                  second: consumer(value: 3)
                }
                """.trimIndent(),
            )

        assertEquals(2, result.getCell(firstKey).get())
        assertEquals(3, result.getCell(secondKey).get())
        assertEquals(setOf(firstKey, secondKey), result.keys)
        assertEquals(2, sourceApplications.get())

        val firstQueryResult = world.queryValues.getValue(listOf(firstKey))
        val secondQueryResult = world.queryValues.getValue(listOf(secondKey))
        assertNotSame(firstQueryResult, secondQueryResult)
        listOf(firstQueryResult to 2, secondQueryResult to 3).forEach {
                (queryResult, expectedValue) ->
            val expectedKey =
                world.schema.contractKey(
                    "Query",
                    "source",
                    mapOf("value" to expectedValue),
                )
            val actualKey = queryResult.keys.single()
            if (actualKey is ObjectEngineResult.GroundKey) {
                assertEquals(expectedKey, actualKey)
            } else {
                    assertFalse(actualKey is ObjectEngineResult.GroundKey)
                    assertEquals(expectedKey.field, actualKey.field)
                    assertTrue(context(world) { actualKey.isContextuallyGrounded() })
                    assertEquals(
                        expectedKey.arguments,
                        context(world) { actualKey.groundedArguments() },
                    )
            }
        }
    }

    @Test
    fun `query fragment resolution is transitive`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      base: Int!
                      middle: Int!
                      result: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val base = schema.requireObjectField("Query", "base")
                    val middle = schema.requireObjectField("Query", "middle")
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        base to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 4 },
                        middle to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment MiddleQuery on Query { value: base }",
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("value")
                            },
                        result to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ResultQuery on Query { value: middle }",
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("value")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")
        val middleKey = world.schema.contractKey("Query", "middle")
        val baseKey = world.schema.contractKey("Query", "base")

        val resolved = resolveAndValidate(world, "query { result }")

        assertEquals(4, resolved.getCell(resultKey).get())
        assertEquals(setOf(middleKey), world.queryValues.getValue(listOf(resultKey)).keys)
        assertEquals(setOf(baseKey), world.queryValues.getValue(listOf(middleKey)).keys)
    }
}
