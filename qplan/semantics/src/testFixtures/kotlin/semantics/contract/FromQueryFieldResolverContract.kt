package semantics.contract

import model.Arguments
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromQueryField
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Contract for variables produced by exact paths in the defining Query fragment. */
interface FromQueryFieldResolverContract : ResolverContract {
    @Test
    fun `Query field binding can be consumed within the Query fragment`() {
        assertFromQueryFieldConsumers(
            consumeInObjectFragment = false,
            consumeInQueryFragment = true,
        )
    }

    @Test
    fun `Query field binding can be consumed within the object fragment`() {
        assertFromQueryFieldConsumers(
            consumeInObjectFragment = true,
            consumeInQueryFragment = false,
        )
    }

    @Test
    fun `Query field binding can be consumed within both fragments`() {
        assertFromQueryFieldConsumers(
            consumeInObjectFragment = true,
            consumeInQueryFragment = true,
        )
    }

    private fun assertFromQueryFieldConsumers(
        consumeInObjectFragment: Boolean,
        consumeInQueryFragment: Boolean,
    ) {
        val objectFragmentSource =
            if (consumeInObjectFragment) {
                "fragment ConsumerObject on Query { objectSide: consume(value: ${'$'}provided) }"
            } else {
                ""
            }
        val queryFragmentSource =
            buildString {
                append("fragment ConsumerQuery on Query { providedSource: provider")
                if (consumeInQueryFragment) {
                    append(" querySide: consume(value: ${'$'}provided)")
                }
                append(" }")
            }
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      provider: Int!
                      consume(value: Int!): Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    val provider = schema.requireObjectField("Query", "provider")
                    val consume = schema.requireObjectField("Query", "consume")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        provider to fieldResolverOf(empty) { _, _ -> 7 },
                        consume to
                            fieldResolverOf(empty) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment =
                                    if (consumeInObjectFragment) {
                                        schema.fragmentFrom(objectFragmentSource)
                                    } else {
                                        empty
                                    },
                                queryFragment = schema.fragmentFrom(queryFragmentSource),
                            ) { input, queryValue, _ ->
                                listOfNotNull(
                                    input.selectionValues()["objectSide"] as? Int,
                                    queryValue.selectionValues()["querySide"] as? Int,
                                ).sum()
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "provided") to
                            schema.fromQueryField(
                                queryFragmentSource,
                                listOf("providedSource"),
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val consumerKey = world.schema.contractKey("Query", "consumer")

        val resolved = resolveAndValidate(world, "query { consumer }")

        assertEquals(
            if (consumeInObjectFragment && consumeInQueryFragment) 14 else 7,
            resolved.getCell(consumerKey).get(),
        )
    }
}
