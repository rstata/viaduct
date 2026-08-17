package semantics.resolver25

import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PhasePlanRegressionTest {
    @Test
    fun `accepts two path-variable owners with an acyclic canonical branch order`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      outer: Int!
                        @resolver(
                          of: "source middle(value: ${'$'}a)"
                          pathVars: [{name: "a", path: ["source"]}]
                          result: "sum(middle)"
                        )
                      source: Int! @resolver(result: 7)
                      middle(value: Int!): Int!
                        @resolver(
                          of: "source sink(value: ${'$'}b)"
                          pathVars: [{name: "b", path: ["source"]}]
                          result: "sum(sink)"
                        )
                      sink(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val outerKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "outer"),
                emptyMap(),
            )

        val observation =
            observeWithLifecycleValidation(
                world = world,
                root = world.objectOf("Query"),
                selections =
                    world.fragmentFrom(
                        "fragment ignored on Query { outer }",
                    ).subselections,
            )
        val resolved = observation.result

        assertContains(
            observation.lifecycleEvents.resolver25StructuralSignatures(),
            Resolver25StructuralSignature.MULTIPLE_OBJECT_PATH_OWNERS,
        )
        assertEquals(Value.Int.of(7), resolved.getCell(outerKey).getValue().get())
    }
}
