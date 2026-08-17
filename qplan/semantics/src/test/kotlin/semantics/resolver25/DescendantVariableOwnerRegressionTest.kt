package semantics.resolver25

import model.EngineResult
import model.IntEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DescendantVariableOwnerRegressionTest {
    @Test
    fun `binds a path variable owned by a resolver in a list element`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      items: [Item!]!
                        @resolver(
                          result: [
                            {source: 3, fixed: 10}
                            {source: 5, fixed: 20}
                          ]
                        )
                    }

                    type Item {
                      source: Int!
                      fixed: Int!
                      consume(value: Int!): Int!
                        @resolver(
                          of: "fixed"
                          result: "sum(${'$'}value, fixed)"
                        )
                      result: Int!
                        @resolver(
                          of: "source consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(consume)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val observation =
            observeWithLifecycleValidation(
                world = world,
                root = world.objectOf("Query"),
                selections =
                    world.fragmentFrom(
                        "fragment ignored on Query { items { result } }",
                    ).subselections,
            )
        val resolved = observation.result
        val signatures = observation.lifecycleEvents.resolver25StructuralSignatures()
        assertContains(
            signatures,
            Resolver25StructuralSignature.DESCENDANT_VARIABLE_OWNER,
        )
        assertContains(
            signatures,
            Resolver25StructuralSignature.LIST_ELEMENT_VARIABLE_OWNER,
        )
        val items =
            resolved
                .getCell(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "items"),
                        emptyMap(),
                    ),
                ).getValue().get() as ListEngineResult
        assertEquals(
            listOf(IntEngineResult.of(13), IntEngineResult.of(25)),
            items.map { cell ->
                val item = cell.getValue().get() as ObjectEngineResult
                item
                    .getCell(
                        Value.GroundKey.of(
                            world.schema.objectField("Item", "result"),
                            emptyMap(),
                        ),
                    ).getValue().get()
            },
        )
    }
}
