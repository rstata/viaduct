package execution.viaductfeaturetests

// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/execution/FromFieldVariablesFeatureTest.kt
// Implemented 11 out of 43 tests as of 2026-08-20

import execution.testing.runQPlanFeatureTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.FieldUnbatchedResolverFn
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleDSL
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.select.SelectionsParser

class FromFieldVariablesFeatureTest {
    @Test
    fun `from object field -- simple`() =
        EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runQPlanFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from object field -- variables used by field on non-root object`() =
        EngineTestModule(
            """
            type Obj { x:Int, y(b:Int):Int, z:Int }
            extend type Query { obj:Obj }
            """.trimIndent(),
        ) {
            fieldWithFromFieldVariables(
                coord = "Obj" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Obj" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Obj" to "z", 2)
            field("Query" to "obj") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            requireNotNull(schema.schema.getObjectType("Obj")),
                            emptyMap(),
                        )
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ obj { x } }").assertJson("{data: {obj: {x: 30}}}")
        }

    @Test
    fun `from object field -- selection is aliased`() =
        EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), myz:z",
                variables = listOf(FromObjectFieldVariable("b", "myz")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runQPlanFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from object field -- selection is list-valued`() =
        EngineTestModule("extend type Query { x:Int, y(b:[Int]):Int, z:[Int] }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<List<Int>>("b").fold(1) { acc, i -> acc * i }
                    }
                }
            }
            fieldWithValue("Query" to "z", listOf(2, 3, 5))
        }.runQPlanFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `from object field -- multiple variables on required selection`() =
        EngineTestModule("extend type Query { x:Int, y(b:Int, c:Int):Int, z:Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b, c:\$c), z, w",
                variables =
                    listOf(
                        FromObjectFieldVariable("b", "z"),
                        FromObjectFieldVariable("c", "w"),
                    ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("b") * args.getAs<Int>("c") * 5
                    }
                }
            }
            fieldWithValue("Query" to "z", 3)
            fieldWithValue("Query" to "w", 2)
        }.runQPlanFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `from object field -- selection traverses through object`() =
        EngineTestModule(
            """
            extend type Query { x:Int, y(b:Int):Int, z:Obj }
            type Obj { w:Int }
            """.trimIndent(),
        ) {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z { w }",
                variables = listOf(FromObjectFieldVariable("b", "z.w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            requireNotNull(schema.schema.getObjectType("Obj")),
                            mapOf("w" to 2),
                        )
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from object field -- selection traverses through null object`() =
        EngineTestModule(
            """
            extend type Query { x:Int, y(b:Int):Int!, z:Obj }
            type Obj { w:Int }
            """.trimIndent(),
        ) {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z { w }",
                variables = listOf(FromObjectFieldVariable("b", "z.w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> ((args["b"] as? Int) ?: -1) * 3 }
                }
            }
            fieldWithValue("Query" to "z", null)
        }.runQPlanFeatureTest {
            runQuery("{ x }").assertJson("{data: {x: -15}}")
        }

    @Test
    fun `from arg -- simple`() =
        EngineTestModule("extend type Query { foo(y: Int!): Int!, bar(x:Int!): Int! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$y)",
                variables = listOf(FromArgumentVariable("y", "y")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo(y:2)}").assertJson("{data: {foo: 30}}")
        }

    @Test
    fun `from arg -- binds argument to variable with a different name`() =
        EngineTestModule("extend type Query { foo(y: Int!): Int!, bar(x:Int!): Int! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$vary)",
                variables = listOf(FromArgumentVariable("vary", "y")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo(y:2)}").assertJson("{data: {foo: 30}}")
        }

    @Test
    fun `from arg -- arg from operation variable`() =
        EngineTestModule("extend type Query { foo(y:Int!):Int!, bar(x:Int!): Int! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$y)",
                variables = listOf(FromArgumentVariable("y", "y")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }.runQPlanFeatureTest {
            runQuery(
                "query Q(\$vary:Int!) {foo(y:\$vary)}",
                mapOf("vary" to 2),
            ).assertJson("{data: {foo: 30}}")
        }

    @Test
    fun `from arg -- path traverses nested input`() {
        val module =
            EngineTestModule(
                """
                input Inp { x:Int! }
                extend type Query { foo(inp:Inp!): Int!, bar(x:Int!):Int! }
                """.trimIndent(),
            ) {
                fieldWithFromFieldVariables(
                    coord = "Query" to "foo",
                    objectSelectionsText = "bar(x:\$x)",
                    variables = listOf(FromArgumentVariable("x", "inp.x")),
                ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                field("Query" to "bar") {
                    resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
                }
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                module.runQPlanFeatureTest {}
            }

        assertTrue(error.message.orEmpty().contains("nested FromArgument path inp.x"))
    }
}

private fun MockTenantModuleDSL<Unit>.fieldWithFromFieldVariables(
    coord: Pair<String, String>,
    objectSelectionsText: String? = null,
    querySelectionsText: String? = null,
    variables: List<SelectionSetVariable> = emptyList(),
    resolveFn: FieldUnbatchedResolverFn,
) {
    val objectSelections = objectSelectionsText?.let { SelectionsParser.parse(coord.first, it) }
    val querySelections = querySelectionsText?.let { SelectionsParser.parse(queryType.name, it) }
    val variablesResolvers =
        VariablesResolver.fromSelectionSetVariables(
            objectSelections = objectSelections,
            querySelections = querySelections,
            variables = variables,
            forChecker = false,
        )

    field(coord) {
        resolverExecutor {
            MockFieldUnbatchedResolverExecutor(
                objectSelectionSet =
                    objectSelections?.let {
                        RequiredSelectionSet(it, variablesResolvers, forChecker = false)
                    },
                querySelectionSet =
                    querySelections?.let {
                        RequiredSelectionSet(it, variablesResolvers, forChecker = false)
                    },
                resolverId = resolverId,
                unbatchedResolveFn = resolveFn,
            )
        }
    }
}
