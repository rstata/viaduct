package detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Finding
import io.gitlab.arturbosch.detekt.rules.KotlinCoreEnvironmentTest
import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.lintWithContext
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val modelSource = """
    package model

    typealias EngineSimpleData = Any
    typealias EngineInputData = Any
    typealias EngineOutputData = Any
    typealias ArgumentExpression = Any

    sealed interface Arguments {
        sealed interface Variable
    }

    data object ArgumentResolutionError
    data object VariableImpl : Arguments.Variable

    sealed interface EngineErrorData {
        companion object {
            fun of(): EngineErrorData = Impl
        }
    }

    private data object Impl : EngineErrorData
""".trimIndent()

private val engineObjectDataSource = """
    package viaduct.engine.api

    sealed interface EngineObjectData {
        data object Sync : EngineObjectData
    }
""".trimIndent()

private val shadowedAliasSource = """
    package other

    typealias EngineSimpleData = Any
""".trimIndent()

@KotlinCoreEnvironmentTest
class EngineUnionMembershipRuleTest(private val env: KotlinCoreEnvironment) {
    private val rule = EngineUnionMembershipRule(Config.empty)

    private fun lint(
        source: String,
        rule: EngineUnionMembershipRule = this.rule,
        additionalSources: List<String> = emptyList(),
    ) = rule.lintWithContext(
        env,
        source.trimIndent(),
        *(listOf(modelSource) + additionalSources).toTypedArray(),
    )

    private fun assertForbidden(findings: List<Finding>, expectedCount: Int) {
        assertEquals(expectedCount, findings.size, findings.joinToString { it.message })
        assertTrue(
            findings.all { it.message.contains("does not include") },
            findings.joinToString { it.message },
        )
    }

    private fun assertUnknown(findings: List<Finding>, expectedCount: Int) {
        assertEquals(expectedCount, findings.size, findings.joinToString { it.message })
        assertTrue(
            findings.all { it.message.contains("Cannot prove") },
            findings.joinToString { it.message },
        )
    }

    @Test
    fun `known forbidden members are reported`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineOutputData
            import model.EngineSimpleData

            val listAsSimple: EngineSimpleData = listOf(1)
            val errorAsInput: EngineInputData = EngineErrorData.of()
            val mapAsOutput: EngineOutputData = mapOf("count" to 1)
            val invalidMapKey: EngineInputData = mapOf(1 to "value")
            val nestedErrorAsInput: EngineInputData =
                listOf(mapOf("error" to EngineErrorData.of()))
            """,
        )

        assertEquals(5, findings.size, findings.joinToString { it.message })
        assertTrue(findings.all { it.message.contains("does not include") })
    }

    @Test
    fun `valid scalar and recursive collection members are accepted`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineOutputData
            import model.EngineSimpleData
            import viaduct.engine.api.EngineObjectData

            val intValue: EngineSimpleData = 1
            val doubleValue: EngineSimpleData = 1.5
            val booleanValue: EngineSimpleData = true
            val stringValue: EngineSimpleData = "value"
            val inputList: EngineInputData = listOf(1, 2, 3)
            val inputMap: EngineInputData = mapOf("count" to 1)
            val nestedInput: EngineInputData = listOf(mapOf("items" to listOf(false)))
            val nestedOutput: EngineOutputData = listOf(listOf(1))
            val outputError: EngineOutputData = EngineErrorData.of()
            val outputSync: EngineOutputData = EngineObjectData.Sync
            """,
            additionalSources = listOf(engineObjectDataSource),
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `argument expressions accept input members variables errors and recursive collections`() {
        val findings = lint(
            """
            package sample

            import model.ArgumentExpression
            import model.ArgumentResolutionError
            import model.Arguments
            import model.VariableImpl

            fun variable(): Arguments.Variable = VariableImpl

            val scalar: ArgumentExpression = 1
            val inputList: ArgumentExpression = listOf(mapOf("count" to 1))
            val inputMap: ArgumentExpression = mapOf("items" to listOf(false))
            val variableExpression: ArgumentExpression = variable()
            val errorExpression: ArgumentExpression = ArgumentResolutionError
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `argument expressions reject output-only members and invalid map keys`() {
        val findings = lint(
            """
            package sample

            import model.ArgumentExpression
            import model.EngineErrorData
            import viaduct.engine.api.EngineObjectData

            val error: ArgumentExpression = EngineErrorData.of()
            val objectData: ArgumentExpression = EngineObjectData.Sync
            val invalidMap: ArgumentExpression = mapOf(1 to "value")
            """,
            additionalSources = listOf(engineObjectDataSource),
        )

        assertForbidden(findings, expectedCount = 3)
    }

    @Test
    fun `opaque Any is left to the runtime boundary by default`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            fun externalValue(): Any = "runtime"
            fun acceptSimple(value: EngineSimpleData) {}

            val value: EngineSimpleData = externalValue()

            fun useValue() {
                acceptSimple(externalValue())
            }
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `known forbidden function arguments are reported`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineSimpleData

            fun acceptInput(value: EngineInputData) {}
            fun acceptSimple(value: EngineSimpleData) {}

            val error = EngineErrorData.of()
            var x = listOf(1)

            fun useValues() {
                acceptInput(value = error)
                acceptInput(x)
                acceptSimple(x)
            }
            """,
        )

        assertEquals(2, findings.size, findings.joinToString { it.message })
        assertTrue(findings.all { it.message.contains("does not include") })
    }

    @Test
    fun `valid function arguments are accepted`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineOutputData

            fun acceptInput(value: EngineInputData) {}
            fun acceptOutput(value: EngineOutputData) {}

            fun useValues() {
                acceptInput(listOf(1))
                acceptInput(mapOf("count" to 1))
                acceptOutput(EngineErrorData.of())
            }
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `expression and block returns are checked`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineOutputData
            import model.EngineSimpleData

            fun validExpressionReturn(): EngineInputData = listOf(1)

            fun validBlockReturn(): EngineOutputData {
                if (System.currentTimeMillis() > 0) return EngineErrorData.of()
                return listOf(1)
            }

            fun invalidExpressionReturn(): EngineSimpleData = listOf(1)

            fun invalidBlockReturn(): EngineInputData {
                return EngineErrorData.of()
            }
            """,
        )

        assertEquals(2, findings.size, findings.joinToString { it.message })
        assertTrue(findings.all { it.message.contains("does not include") })
    }

    @Test
    fun `nullable null values are accepted`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineSimpleData

            fun acceptInput(value: EngineInputData?) {}

            val simple: EngineSimpleData? = null

            fun useNull() {
                acceptInput(null)
            }
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `opaque Any can be reported when explicitly enabled`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            fun externalValue(): Any = "runtime"
            fun acceptSimple(value: EngineSimpleData) {}

            val value: EngineSimpleData = externalValue()

            fun useValue() {
                acceptSimple(externalValue())
            }
            """,
            EngineUnionMembershipRule(TestConfig("reportUnknown" to true)),
        )

        assertEquals(2, findings.size, findings.joinToString { it.message })
        assertTrue(findings.all { it.message.contains("Cannot prove") })
    }

    @Test
    fun `noncanonical aliases are not treated as engine contracts`() {
        val findings = lint(
            """
            package sample

            typealias LocalAlias = Any

            val value: LocalAlias = listOf(1)
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }
    @Test
    fun `true negative every scalar type is accepted by every domain`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineOutputData
            import model.EngineSimpleData

            val simpleInt: EngineSimpleData = 1
            val simpleDouble: EngineSimpleData = 1.25
            val simpleBoolean: EngineSimpleData = true
            val simpleString: EngineSimpleData = "simple"

            val inputInt: EngineInputData = 1
            val inputDouble: EngineInputData = 1.25
            val inputBoolean: EngineInputData = false
            val inputString: EngineInputData = "input"

            val outputInt: EngineOutputData = 1
            val outputDouble: EngineOutputData = 1.25
            val outputBoolean: EngineOutputData = true
            val outputString: EngineOutputData = "output"
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive simple rejects list and map shapes`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            val listValue: EngineSimpleData = listOf(1)
            val mapValue: EngineSimpleData = mapOf("value" to 1)
            """,
        )

        assertForbidden(findings, expectedCount = 2)
    }

    @Test
    fun `true negative input accepts deeply nested list and map shapes`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData

            val listValue: EngineInputData = listOf(1, 2, 3)
            val mapValue: EngineInputData = mapOf("value" to 1)
            val listOfMaps: EngineInputData = listOf(mapOf("value" to false))
            val mapOfLists: EngineInputData = mapOf("values" to listOf("a", "b"))
            val threeLevels: EngineInputData =
                listOf(mapOf("nested" to listOf(mapOf("deep" to 1.0))))
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive input rejects output errors directly and recursively`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData

            val directError: EngineInputData = EngineErrorData.of()
            val errorInList: EngineInputData = listOf(EngineErrorData.of())
            val errorInMap: EngineInputData = mapOf("error" to EngineErrorData.of())
            val deeplyNestedError: EngineInputData =
                listOf(mapOf("nested" to listOf(EngineErrorData.of())))
            """,
        )

        assertForbidden(findings, expectedCount = 4)
    }

    @Test
    fun `true negative output accepts named variants and recursive lists`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineOutputData
            import viaduct.engine.api.EngineObjectData

            val directError: EngineOutputData = EngineErrorData.of()
            val errorList: EngineOutputData = listOf(EngineErrorData.of())
            val nestedErrorList: EngineOutputData = listOf(listOf(EngineErrorData.of()))
            val directSync: EngineOutputData = EngineObjectData.Sync
            val syncList: EngineOutputData = listOf(EngineObjectData.Sync)
            """,
            additionalSources = listOf(engineObjectDataSource),
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive output rejects maps even with valid scalar values`() {
        val findings = lint(
            """
            package sample

            import model.EngineOutputData

            val directMap: EngineOutputData = mapOf("value" to 1)
            val nestedMap: EngineOutputData = listOf(mapOf("value" to true))
            """,
        )

        assertForbidden(findings, expectedCount = 2)
    }

    @Test
    fun `true positive input rejects every non-string map key`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData

            val intKey: EngineInputData = mapOf(1 to "value")
            val doubleKey: EngineInputData = mapOf(1.0 to "value")
            val booleanKey: EngineInputData = mapOf(true to "value")
            """,
        )

        assertForbidden(findings, expectedCount = 3)
    }

    @Test
    fun `true negative string keys preserve recursive value membership`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData

            val scalarValue: EngineInputData = mapOf("value" to 1)
            val listValue: EngineInputData = mapOf("value" to listOf(false))
            val mapValue: EngineInputData = mapOf("value" to mapOf("nested" to "ok"))
            val deeplyNestedValue: EngineInputData =
                mapOf("value" to listOf(mapOf("nested" to listOf(1.0))))
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive nested map values report output-only errors`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData

            val directMap: EngineInputData = mapOf("bad" to EngineErrorData.of())
            val listMap: EngineInputData = listOf(
                mapOf("bad" to EngineErrorData.of()),
            )
            val deepMap: EngineInputData = mapOf(
                "outer" to listOf(mapOf("bad" to EngineErrorData.of())),
            )
            """,
        )

        assertForbidden(findings, expectedCount = 3)
    }

    @Test
    fun `true negative nullable boundaries and null list elements are not rejected`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineSimpleData

            val nullableSimple: EngineSimpleData? = null
            val nullableInput: EngineInputData? = null
            val inputListWithNull: EngineInputData = listOf(null, 1, null)

            fun acceptInput(value: EngineInputData?) {}

            fun useNull() {
                acceptInput(null)
            }
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive listOfNotNull remains forbidden for simple data`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            val value: EngineSimpleData = listOfNotNull(1, null, 2)
            """,
        )

        assertForbidden(findings, expectedCount = 1)
    }

    @Test
    fun `true negative listOfNotNull is valid input data`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData

            val value: EngineInputData = listOfNotNull(1, null, 2)
            val nested: EngineInputData = listOfNotNull(listOf(1), null)
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive inferred concrete references are checked at properties`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineSimpleData

            val inferredList = listOf(1)
            val inferredError = EngineErrorData.of()

            val validInput: EngineInputData = inferredList
            val invalidSimple: EngineSimpleData = inferredList
            val invalidInput: EngineInputData = inferredError
            """,
        )

        assertForbidden(findings, expectedCount = 2)
    }

    @Test
    fun `true negative concrete generic returns can enter compatible domains`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineOutputData

            fun intList(): List<Int> = listOf(1, 2)
            fun stringMap(): Map<String, String> = mapOf("key" to "value")

            fun validInput(): EngineInputData = intList()
            fun validOutput(): EngineOutputData = listOf(intList())
            fun validNestedInput(): EngineInputData = mapOf("items" to intList())
            fun validStringMapInput(): EngineInputData = stringMap()
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive concrete generic returns are rejected by incompatible domains`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineOutputData
            import model.EngineSimpleData

            fun intList(): List<Int> = listOf(1, 2)
            fun inputMap(): Map<String, Int> = mapOf("key" to 1)

            fun invalidSimple(): EngineSimpleData = intList()
            fun invalidOutput(): EngineOutputData = inputMap()
            fun invalidSimpleMap(): EngineSimpleData = inputMap()
            """,
        )

        assertForbidden(findings, expectedCount = 3)
    }

    @Test
    fun `true positive fully qualified canonical aliases are recognized`() {
        val findings = lint(
            """
            package sample

            val invalidSimple: model.EngineSimpleData = listOf(1)
            val invalidInput: model.EngineInputData = model.EngineErrorData.of()
            """,
        )

        assertForbidden(findings, expectedCount = 2)
    }

    @Test
    fun `false positive guard ignores a shadowed alias with the same short name`() {
        val findings = lint(
            """
            package sample

            import other.EngineSimpleData

            val unrelatedValue: EngineSimpleData = listOf(1)
            """,
            additionalSources = listOf(shadowedAliasSource),
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `false positive guard ignores unrelated local aliases`() {
        val findings = lint(
            """
            package sample

            typealias LocalAlias = Any
            typealias LocalListAlias = List<Int>

            val unrelatedValue: LocalAlias = listOf(1)
            val unrelatedList: LocalListAlias = listOf(1)
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive constructor parameters are checked at construction calls`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineSimpleData

            class InputHolder(val value: EngineInputData)
            class SimpleHolder(val value: EngineSimpleData)

            fun build() {
                InputHolder(EngineErrorData.of())
                SimpleHolder(listOf(1))
            }
            """,
        )

        assertForbidden(findings, expectedCount = 2)
    }

    @Test
    fun `true positive default parameter values are checked`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineSimpleData

            fun invalidInput(value: EngineInputData = EngineErrorData.of()) {}
            fun invalidSimple(value: EngineSimpleData = listOf(1)) {}
            fun validInput(value: EngineInputData = listOf(1)) {}
            """,
        )

        assertForbidden(findings, expectedCount = 2)
    }

    @Test
    fun `false positive guard accepts scalar vararg arguments and array spreads`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            fun acceptSimple(vararg values: EngineSimpleData) {}

            fun useValues() {
                acceptSimple(1, "value", true)
                acceptSimple(*arrayOf(1, 2, 3))
            }
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive vararg spread reports invalid element types`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            fun acceptSimple(vararg values: EngineSimpleData) {}

            fun useValues() {
                acceptSimple(*arrayOf<List<Int>>(listOf(1)))
            }
            """,
        )

        assertForbidden(findings, expectedCount = 1)
    }

    @Test
    fun `false negative opaque values are intentionally ignored by default`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineSimpleData

            fun external(): Any = listOf(1)
            fun acceptInput(value: EngineInputData) {}

            val hidden: EngineSimpleData = external()

            fun useHidden() {
                acceptInput(external())
            }
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `unknown opaque values become warnings when explicitly enabled`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineSimpleData

            fun external(): Any = listOf(1)
            fun acceptInput(value: EngineInputData) {}

            val hidden: EngineSimpleData = external()

            fun returnHidden(): EngineSimpleData = external()

            fun useHidden() {
                acceptInput(external())
            }
            """,
            EngineUnionMembershipRule(TestConfig("reportUnknown" to true)),
        )

        assertUnknown(findings, expectedCount = 3)
    }

    @Test
    fun `false negative unchecked casts hide a known invalid value by default`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            val hidden: EngineSimpleData = listOf(1) as Any
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `unknown unchecked casts can be reported explicitly`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            val hidden: EngineSimpleData = listOf(1) as Any
            """,
            EngineUnionMembershipRule(TestConfig("reportUnknown" to true)),
        )

        assertUnknown(findings, expectedCount = 1)
    }

    @Test
    fun `false negative heterogeneous branches are unknown rather than forbidden`() {
        val findings = lint(
            """
            package sample

            import model.EngineSimpleData

            fun chooseList(flag: Boolean): Any =
                if (flag) 1 else listOf(1)

            val hidden: EngineSimpleData = chooseList(true)
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive assignments after declaration are checked`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineSimpleData

            fun mutate() {
                var simple: EngineSimpleData = 1
                var input: EngineInputData = 1

                simple = listOf(1)
                input = mapOf(1 to "invalid key")
            }
            """,
        )

        assertForbidden(findings, expectedCount = 2)
    }

    @Test
    fun `true negative assignments after declaration accept compatible values`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineOutputData

            fun mutate() {
                var input: EngineInputData = 1
                var output: EngineOutputData = 1

                input = listOf(mapOf("ok" to false))
                output = listOf(2.0)
            }
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive custom property getters are checked`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineSimpleData

            val invalidSimple: EngineSimpleData
                get() = listOf(1)

            val invalidInput: EngineInputData
                get() = mapOf(1 to "invalid key")
            """,
        )

        assertForbidden(findings, expectedCount = 2)
    }

    @Test
    fun `true negative custom property getters accept compatible values`() {
        val findings = lint(
            """
            package sample

            import model.EngineInputData
            import model.EngineOutputData

            val validInput: EngineInputData
                get() = listOf(mapOf("ok" to 1))

            val validOutput: EngineOutputData
                get() = listOf(1, 2.0)
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `false positive guard ignores ordinary Any values without domain boundaries`() {
        val findings = lint(
            """
            package sample

            val ordinary: Any = listOf(1)
            val anotherOrdinary: Any = mapOf(1 to "not an engine value")

            fun consume(value: Any) {}

            fun useOrdinaryValues() {
                consume(ordinary)
                consume(anotherOrdinary)
            }
            """,
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }

    @Test
    fun `true positive unsupported concrete types are rejected in every boundary`() {
        val findings = lint(
            """
            package sample

            import java.time.Instant
            import model.EngineInputData
            import model.EngineOutputData
            import model.EngineSimpleData

            val simple: EngineSimpleData = Instant.EPOCH
            val input: EngineInputData = Instant.EPOCH
            val output: EngineOutputData = Instant.EPOCH
            """,
        )

        assertForbidden(findings, expectedCount = 3)
    }

    @Test
    fun `all independent violations are reported rather than stopping early`() {
        val findings = lint(
            """
            package sample

            import model.EngineErrorData
            import model.EngineInputData
            import model.EngineOutputData
            import model.EngineSimpleData

            val first: EngineSimpleData = listOf(1)
            val second: EngineInputData = EngineErrorData.of()
            val third: EngineOutputData = mapOf("value" to 1)

            fun invalidReturns(): EngineSimpleData = listOf(2)

            fun invalidArguments(value: EngineInputData) {}

            fun useArguments() {
                invalidArguments(EngineErrorData.of())
            }
            """,
        )

        assertForbidden(findings, expectedCount = 5)
    }
}
