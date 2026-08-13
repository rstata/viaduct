package semantics.contract

import model.EngineResult
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Output-boundary test policy for resolution strategies that consume complete resolver outputs.
 */
interface CompleteResolverOutputPolicyContract : ResolverContract {
    override val selectiveResolvers: Boolean
        get() = false

    @Test
    fun `retains unselected passive fields from complete resolver outputs`() {
        val resolved = resolvePassiveOutputFixture()

        assertEquals(setOf("requested", "extra"), resolved.fieldNames)
    }

    @Test
    fun `rejects a selective resolver world`() {
        assertRejectsWorldMode(selectiveResolvers = true)
    }
}

/**
 * Output-boundary test policy for resolution strategies that project resolver outputs to demand.
 */
interface SelectiveResolverOutputPolicyContract : ResolverContract {
    override val selectiveResolvers: Boolean
        get() = true

    @Test
    fun `omits unselected passive fields from selective resolver outputs`() {
        val resolved = resolvePassiveOutputFixture()

        assertEquals(setOf("requested"), resolved.fieldNames)
    }

    @Test
    fun `rejects a non-selective resolver world`() {
        assertRejectsWorldMode(selectiveResolvers = false)
    }
}

/**
 * Object-fragment output policy for resolution strategies that retain complete passive subtrees.
 */
interface CompleteObjectFragmentOutputPolicyContract : ResolverContract {
    @Test
    fun `retains complete passive recursive output`() {
        val resolved = resolveRecursiveOutputFixture()

        assertEquals(setOf("label", "next"), resolved.fieldNames)
        assertTrue(resolved.hasNext)
        assertEquals(null, resolved.nextValue)
    }
}

/**
 * Object-fragment output policy for strategies that project passive subtrees to required demand.
 */
interface SelectiveObjectFragmentOutputPolicyContract : ResolverContract {
    @Test
    fun `projects passive recursive output to required demand`() {
        val resolved = resolveRecursiveOutputFixture()

        assertEquals(setOf("label"), resolved.fieldNames)
        assertTrue(!resolved.hasNext)
    }
}

private data class PassiveOutputFixtureResult(
    val fieldNames: Set<String>,
)

private fun ResolverContract.assertRejectsWorldMode(selectiveResolvers: Boolean) {
    val testWorld =
        TestWorld.fromSDL(
            schemaSDL = "type Query { value: Int }",
            selectiveResolvers = selectiveResolvers,
        )
    val world = testWorld.assumptions
    val selections = world.fragmentFrom("fragment ignored on Query { __typename }").subselections

    assertFailsWith<IllegalArgumentException> {
        resolve(world, world.objectOf("Query"), selections)
    }
}

private fun ResolverContract.resolvePassiveOutputFixture(): PassiveOutputFixtureResult {
    val testWorld =
        TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL =
                """
                type User {
                  requested: String!
                  extra: String!
                }

                type Query {
                  user: User!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                mapOf(
                    schema.field("Query", "user") to
                        model.testing.fieldResolverOf(
                            schema.emptyFragmentOf("Query"),
                        ) { _, _ ->
                            schema.objectOf("User") {
                                "requested" setTo "requested"
                                "extra" setTo "extra"
                            }
                        },
                )
            },
        )
    val world = testWorld.assumptions
    val fragment = world.fragmentFrom("fragment ignored on Query { user { requested } }")
    val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
    val user =
        assertIs<EngineResult.Object>(
            result.getValue(world.schema.contractKey("Query", "user")).get(),
        )
    return PassiveOutputFixtureResult(
        fieldNames = user.keys.map { it.field.fieldName }.toSet(),
    )
}

private data class RecursiveOutputFixtureResult(
    val fieldNames: Set<String>,
    val hasNext: Boolean,
    val nextValue: EngineResult?,
)

private fun ResolverContract.resolveRecursiveOutputFixture(): RecursiveOutputFixtureResult {
    val testWorld =
        TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL =
                """
                type Chain { label: String!, next: Chain, computed: String! }
                type Query { chain: Chain! }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val nextKey = schema.contractKey("Chain", "next")
                val labelKey = schema.contractKey("Chain", "label")
                mapOf(
                    schema.field("Query", "chain") to
                        model.testing.fieldResolverOf(
                            schema.emptyFragmentOf("Query"),
                        ) { input, _ ->
                            require(input.hasExactlyFields())
                            schema.objectOf("Chain") {
                                "label" setTo "first"
                                "next" setTo
                                    objectOf("Chain") {
                                        "label" setTo "second"
                                        "next" setTo null
                                    }
                            }
                        },
                    schema.field("Chain", "computed") to
                        model.testing.fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ignored on Chain { next { label } }",
                            ),
                        ) { input, _ ->
                            val next =
                                input.fieldValues.getValue(nextKey) as Value.Object
                            next.fieldValues.getValue(labelKey) as Value.String
                        },
                )
            },
        )
    val world = testWorld.assumptions
    val fragment =
        world.fragmentFrom("fragment ignored on Query { chain { computed } }")
    val result = resolveAndValidate(world, world.objectOf("Query"), fragment)
    val chain =
        assertIs<EngineResult.Object>(
            result.getValue(world.schema.contractKey("Query", "chain")).get(),
        )
    val next =
        assertIs<EngineResult.Object>(
            chain.getValue(world.schema.contractKey("Chain", "next")).get(),
        )
    val nextKey = world.schema.contractKey("Chain", "next")
    return RecursiveOutputFixtureResult(
        fieldNames = next.keys.map { it.field.fieldName }.toSet(),
        hasNext = nextKey in next.keys,
        nextValue = next.keys.takeIf { nextKey in it }?.let { next.getValue(nextKey).get() },
    )
}
