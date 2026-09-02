package detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.rules.KotlinCoreEnvironmentTest
import io.gitlab.arturbosch.detekt.test.lintWithContext
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val engineObjectDataSource = """
    package viaduct.engine.api

    interface EngineObjectData {
        suspend fun fetch(selection: String): Any?
        suspend fun fetchOrNull(selection: String): Any?
        suspend fun fetchSelections(): Iterable<String>

        interface Sync : EngineObjectData {
            fun get(selection: String): Any?
            fun getOrNull(selection: String): Any?
            fun isPresent(selection: String): Boolean
            fun getSelections(): Iterable<String>
        }
    }
""".trimIndent()

private val engineOutputDataSource = """
    package model

    typealias EngineOutputData = Any
""".trimIndent()

@KotlinCoreEnvironmentTest
class EngineOutputDataOverrideRuleTest(private val env: KotlinCoreEnvironment) {
    private val rule = EngineOutputDataOverrideRule(Config.empty)

    // --- violations ---

    @Test
    fun `get override with bare Any is flagged`() {
        val findings = rule.lintWithContext(
            env,
            """
            package other.pkg

            import viaduct.engine.api.EngineObjectData

            class SomeEod : EngineObjectData.Sync {
                override suspend fun fetch(selection: String): Any? = get(selection)
                override suspend fun fetchOrNull(selection: String): Any? = getOrNull(selection)
                override suspend fun fetchSelections(): Iterable<String> = getSelections()
                override fun get(selection: String): Any? = null
                override fun getOrNull(selection: String): Any? = null
                override fun isPresent(selection: String): Boolean = false
                override fun getSelections(): Iterable<String> = emptyList()
            }
            """.trimIndent(),
            engineObjectDataSource,
            engineOutputDataSource
        )
        assertEquals(4, findings.size)
        assertTrue(findings.all { it.message.contains("EngineOutputData") })
    }

    @Test
    fun `get override using an arbitrary unrelated alias of Any is flagged`() {
        val findings = rule.lintWithContext(
            env,
            """
            package other.pkg

            import viaduct.engine.api.EngineObjectData

            typealias SomeOtherAlias = Any

            class SomeEod : EngineObjectData.Sync {
                override suspend fun fetch(selection: String): Any? = null
                override suspend fun fetchOrNull(selection: String): Any? = null
                override suspend fun fetchSelections(): Iterable<String> = emptyList()
                override fun get(selection: String): SomeOtherAlias? = null
                override fun getOrNull(selection: String): Any? = null
                override fun isPresent(selection: String): Boolean = false
                override fun getSelections(): Iterable<String> = emptyList()
            }
            """.trimIndent(),
            engineObjectDataSource,
            engineOutputDataSource
        )
        assertEquals(4, findings.size)
    }

    @Test
    fun `get override with an inferred type is flagged`() {
        val findings = rule.lintWithContext(
            env,
            """
            package other.pkg

            import viaduct.engine.api.EngineObjectData

            class SomeEod : EngineObjectData.Sync {
                override suspend fun fetch(selection: String) = null
                override suspend fun fetchOrNull(selection: String): Any? = null
                override suspend fun fetchSelections(): Iterable<String> = emptyList()
                override fun get(selection: String): Any? = null
                override fun getOrNull(selection: String): Any? = null
                override fun isPresent(selection: String): Boolean = false
                override fun getSelections(): Iterable<String> = emptyList()
            }
            """.trimIndent(),
            engineObjectDataSource,
            engineOutputDataSource
        )
        assertEquals(4, findings.size)
        assertTrue(findings.any { it.message.contains("inferred") })
    }

    // --- no violations ---

    @Test
    fun `get override using EngineOutputData typealias is not flagged`() {
        val findings = rule.lintWithContext(
            env,
            """
            package other.pkg

            import model.EngineOutputData
            import viaduct.engine.api.EngineObjectData

            class SomeEod : EngineObjectData.Sync {
                override suspend fun fetch(selection: String): EngineOutputData? = null
                override suspend fun fetchOrNull(selection: String): EngineOutputData? = null
                override suspend fun fetchSelections(): Iterable<String> = emptyList()
                override fun get(selection: String): EngineOutputData? = null
                override fun getOrNull(selection: String): EngineOutputData? = null
                override fun isPresent(selection: String): Boolean = false
                override fun getSelections(): Iterable<String> = emptyList()
            }
            """.trimIndent(),
            engineObjectDataSource,
            engineOutputDataSource
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `unrelated get function on an unrelated class is not flagged`() {
        val findings = rule.lintWithContext(
            env,
            """
            class NotAnEod {
                fun get(selection: String): Any? = null
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `non-override get function is not flagged`() {
        val findings = rule.lintWithContext(
            env,
            """
            package other.pkg

            class NotAnEod {
                fun get(selection: String): Any? = null
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty())
    }
}
