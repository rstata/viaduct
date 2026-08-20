package execution.viaductfeaturetests

// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/execution/RequiredSelectionsTest.kt
// Implemented 12 out of 60 tests as of 2026-08-20

import execution.testing.runQPlanFeatureTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Disabled
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs

class RequiredSelectionsTest {
    @Test
    fun `resolve field with required sibling field`() =
        EngineTestModule("extend type Query { foo: String, bar: String }") {
            fieldWithValue("Query" to "bar", "BAR")
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> (obj.fetch("bar") as String).reversed() }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": "RAB"}}""")
        }

    @Test
    fun `resolve field with transitive required selections`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int, baz: Int }") {
            fieldWithValue("Query" to "baz", 2)
            field("Query" to "bar") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 3 }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections use aliases`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("aliasedBar: bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("aliasedBar") * 2 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    @Disabled("Fails under qplan; investigate production parity")
    fun `required selections use deep aliases`() =
        EngineTestModule("extend type Query { string1: String, bar: Bar } type Bar { value: String }") {
            field("Query" to "bar") {
                resolver {
                    fn { _, _, _, _, _ -> mapOf("value" to "B") }
                }
            }
            field("Query" to "string1") {
                resolver {
                    objectSelections("aliasedBar: bar { aliasedValue: value }")
                    fn { _, obj, _, _, _ ->
                        val bar = obj.fetchAs<EngineObjectData>("aliasedBar")
                        val value = bar.fetch("aliasedValue")
                        "A:$value"
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{string1}")
                .assertJson("""{"data": {"string1": "A:B"}}""")
        }

    @Test
    fun `required selections use arguments`() =
        EngineTestModule("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections use aliases and arguments`() =
        EngineTestModule("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("aliasedBar:bar(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("aliasedBar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections select an argumented field multiple times`() =
        EngineTestModule("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("b1:bar(x:3), b2:bar(x:5)")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("b1") * obj.fetchAs<Int>("b2")
                    }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 2 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 60}}""")
        }

    @Test
    fun `required selections use untyped inline fragments`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("... { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use typed inline fragments`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("... on Query { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `resolve fields with shared requirement`() {
        val bazCount = AtomicInteger()
        EngineTestModule("extend type Query { foo: Int, bar: Int, baz: Int }") {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, _ -> bazCount.incrementAndGet().let { 5 } }
                }
            }
            field("Query" to "bar") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 3 }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 2 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 10, "bar": 15}}""")
                .also { assertEquals(1, bazCount.get()) }
        }
    }

    @Test
    fun `resolve field with multiple requirements`() =
        EngineTestModule("extend type Query { foo: Int, bar: Int, baz: Int }") {
            fieldWithValue("Query" to "baz", 5)
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar baz")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("bar") * obj.fetchAs<Int>("baz")
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 15}}""")
        }

    @Test
    @Disabled("Fails under qplan; investigate production parity")
    fun `resolve fields multiple mergeable requirements`() {
        val barCount = AtomicInteger()
        EngineTestModule("extend type Query { foo: Int, bar: Int }") {
            field("Query" to "bar") {
                resolver {
                    fn { _, _, _, _, _ -> 3.also { barCount.incrementAndGet() } }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections(
                        """
                        fragment F on Query { bar }
                        fragment Main on Query {
                          bar
                          aliasedBar: bar
                          ... {
                            bar
                            ... {
                              bar
                              ... F
                            }
                          }
                          ... on Query {
                            bar
                            ... on Query {
                              bar
                              ... F
                            }
                          }
                          ... F
                        }
                        """.trimIndent(),
                    )
                    fn { _, obj, _, _, _ ->
                        // make sure we wait for aliasedBar
                        obj.fetchAs<Int>("aliasedBar")

                        // but ultimately just return 2 * "bar"
                        obj.fetchAs<Int>("bar") * 2
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 6, "bar": 3}}""")
                .also { assertEquals(2, barCount.get()) }
        }
    }
}
