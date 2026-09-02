package detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.rules.KotlinCoreEnvironmentTest
import io.gitlab.arturbosch.detekt.test.lintWithContext
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@KotlinCoreEnvironmentTest
class ArgumentExpressionAliasRuleTest(private val env: KotlinCoreEnvironment) {
    private val rule = ArgumentExpressionAliasRule(Config.empty)

    @Test
    fun `bare Any in expression decoder carriers is flagged`() {
        val findings = rule.lintWithContext(
            env,
            """
            package model.testing

            internal fun decodeInputValue(): Any? = null

            private fun decodeInputObjectFields(
                decodeSupplied: () -> Any?,
            ): Any {
                val fields: Map<String, Any?> = emptyMap()
                return fields
            }
            """.trimIndent(),
        )

        assertEquals(4, findings.size)
        assertTrue(findings.all { it.message.contains("ArgumentExpression") })
    }

    @Test
    fun `expression alias is accepted and raw unrelated Any is ignored`() {
        val findings = rule.lintWithContext(
            env,
            """
            package model.testing

            typealias ArgumentExpression = Any

            internal fun decodeInputValue(): ArgumentExpression? = null

            private fun decodeInputObjectFields(
                decodeSupplied: () -> ArgumentExpression?,
            ): ArgumentExpression {
                val fields: Map<String, ArgumentExpression?> = emptyMap()
                return fields
            }

            fun unrelatedBoundary(): Any? = null
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty(), findings.joinToString())
    }
}
