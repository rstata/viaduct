package semantics.shared

import model.requireQueryTypeDef
import model.requireObjectField
import model.EngineResultCell
import model.ObjectEngineResult
import model.PathComponent
import model.testing.TestWorld
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CycleCheckStateTest {
    @Test
    fun `acyclic reads are retained without failure`() {
        val fixture = Fixture()

        fixture.register("second")
        fixture.register("third")
        fixture.checker.cycleCheck(
            reader = fixture.path("first"),
            cell = fixture.cell("second"),
        )
        fixture.checker.cycleCheck(
            reader = fixture.path("second"),
            cell = fixture.cell("third"),
        )
    }

    @Test
    fun `direct self-cycle reports its complete path`() {
        val fixture = Fixture()
        fixture.register("first")

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(
                    reader = fixture.path("first"),
                    cell = fixture.cell("first"),
                )
            }

        assertEquals(
            listOf(fixture.path("first"), fixture.path("first")),
            failure.cycle,
        )
    }

    @Test
    fun `read recorded before writer registration still detects a cycle`() {
        val fixture = Fixture()
        fixture.checker.cycleCheck(
            reader = fixture.path("first"),
            cell = fixture.cell("first"),
        )

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.register("first")
            }

        assertEquals(
            listOf(fixture.path("first"), fixture.path("first")),
            failure.cycle,
        )
    }

    @Test
    fun `multi-hop cycle reports dependency order`() {
        val fixture = Fixture()
        fixture.register("first")
        fixture.register("second")
        fixture.register("third")
        fixture.checker.cycleCheck(
            reader = fixture.path("first"),
            cell = fixture.cell("second"),
        )
        fixture.checker.cycleCheck(
            reader = fixture.path("second"),
            cell = fixture.cell("third"),
        )

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(
                    reader = fixture.path("third"),
                    cell = fixture.cell("first"),
                )
            }

        assertEquals(
            listOf(
                fixture.path("third"),
                fixture.path("first"),
                fixture.path("second"),
                fixture.path("third"),
            ),
            failure.cycle,
        )
    }

    @Test
    fun `completed deferred slot still contributes a read edge`() {
        val fixture = Fixture()
        val key = fixture.key("first")
        fixture.target
            .reserveCell(key)
            .createValuePromise()
            .complete("complete")
        fixture.register("first")

        assertFailsWith<ResolverReadCycleException> {
            fixture.checker.cycleCheck(
                reader = fixture.path("first"),
                cell = fixture.target.getCell(key),
            )
        }
    }

    @Test
    fun `recording the same acyclic edge repeatedly is harmless`() {
        val fixture = Fixture()
        fixture.register("second")

        repeat(2) {
            fixture.checker.cycleCheck(
                reader = fixture.path("first"),
                cell = fixture.cell("second"),
            )
        }
    }

    @Test
    fun `writer registration is write-once per exact slot`() {
        val fixture = Fixture()
        fixture.register("first")

        assertFailsWith<IllegalStateException> {
            fixture.register("first")
        }
    }

    @Test
    fun `concurrent edge insertion detects a newly closed cycle`() {
        val fixture = Fixture()
        fixture.register("first")
        fixture.register("second")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val checks =
            listOf(
                fixture.path("first") to fixture.cell("second"),
                fixture.path("second") to fixture.cell("first"),
            )
        val workers =
            checks.map { (reader, cell) ->
                thread {
                    ready.countDown()
                    start.await()
                    try {
                        fixture.checker.cycleCheck(reader, cell)
                    } catch (throwable: Throwable) {
                        failures += throwable
                    }
                }
            }

        ready.await()
        start.countDown()
        workers.forEach(Thread::join)

        assertTrue(failures.isNotEmpty())
        failures.forEach { failure ->
            assertIs<ResolverReadCycleException>(failure)
            assertEquals(failure.cycle.first(), failure.cycle.last())
            assertEquals(
                setOf(fixture.path("first"), fixture.path("second")),
                failure.cycle.toSet(),
            )
        }
    }

    @Test
    fun `NOP cycle checker ignores writer registration and cycle checks`() {
        val fixture = Fixture()
        val checker = CycleCheckState.createNOP()

        checker.registerWriter(
            cell = fixture.cell("first"),
            writer = fixture.path("first"),
        )
        checker.cycleCheck(
            reader = fixture.path("first"),
            cell = fixture.cell("first"),
        )
    }

    private class Fixture {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Query {
                      first: String!
                      second: String!
                      third: String!
                    }
                    """.trimIndent(),
                ).assumptions
        val target = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        val checker = CycleCheckState.create()

        fun key(name: String): ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", name),
                emptyMap(),
            )

        fun path(name: String): List<PathComponent> = listOf(key(name))

        fun cell(name: String): EngineResultCell = target.reserveCell(key(name))

        fun register(name: String) {
            checker.registerWriter(
                cell = cell(name),
                writer = path(name),
            )
        }
    }
}
