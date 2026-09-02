package semantics.resolver26

import model.Arguments
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.operationSelectionsFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import semantics.contract.get
import semantics.contract.selectionValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Resolver26QueryFragmentVariableIsolationTest {
    // Original: execution/src/test/kotlin/execution/viaductfeaturetests/FromFieldVariablesFeatureTest.kt:1024
    @Test
    fun `non-root resolver uses FromObjectField in query fragment`() {
        val objectFragmentSource = "fragment ConsumerObject on Obj { y }"
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      x(a: Int): Int
                      obj: Obj
                    }

                    type Obj {
                      x: Int
                      y: Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val queryX = schema.requireObjectField("Query", "x")
                    val queryObj = schema.requireObjectField("Query", "obj")
                    val objectX = schema.requireObjectField("Obj", "x")
                    val objectY = schema.requireObjectField("Obj", "y")
                    mapOf(
                        queryX to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                (arguments.fieldValues.getValue("a") as Int) * 5
                            },
                        queryObj to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Obj") {}
                            },
                        objectY to
                            fieldResolverOf(schema.emptyFragmentOf("Obj")) { _, _ -> 2 },
                        objectX to
                            fieldResolverOf(
                                objectFragment = schema.fragmentFrom(objectFragmentSource),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerQuery on Query { x(a: ${'$'}a) }",
                                    ),
                            ) { _, queryValue, _ ->
                                (queryValue.selectionValues().getValue("x") as Int) * 3
                            },
                    )
                },
                variableProviders = { schema ->
                    val objectX = schema.requireObjectField("Obj", "x")
                    mapOf(
                        Arguments.Variable.of(objectX, "a") to
                            schema.fromObjectField(
                                objectFragmentSource = objectFragmentSource,
                                responsePath = listOf("y"),
                                variableField = objectX,
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val result =
            context(world) {
                resolve(world.operationSelectionsFrom("query { obj { x } }"))
            }

        val objectResult =
            assertIs<ObjectEngineResult>(
                result
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Query", "obj"),
                            emptyMap(),
                        ),
                    ).get(),
            )
        assertEquals(
            30,
            objectResult
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Obj", "x"),
                        emptyMap(),
                    ),
                ).get(),
        )
    }
}
