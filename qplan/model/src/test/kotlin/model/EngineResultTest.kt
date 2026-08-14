package model

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineResultTest {
    @Test
    fun `fixture DSL constructs nested argument-bearing results`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val result =
            world.engineResultOf("User") {
                field("lookup", "limit" to 1) resolvesTo "one"
                "aliases" resolvesTo listOf("A", null)
                "friend" resolvesTo
                    engineResultOf("User") {
                        "first" resolvesTo "Grace"
                    }
            }

        assertEquals(
            "one",
            assertIs<Value.String>(
                result
                    .getCell(schema.key("User", "lookup", "limit" to 1))
                    .getValue()
                    .get(),
            ).stringValue,
        )
        val aliases =
            assertIs<EngineResult.List>(
                result.getCell(schema.key("User", "aliases")).getValue().get(),
            )
        assertEquals(
            listOf("A", null),
            aliases.map { cell -> (cell.getValue().get() as? Value.String)?.stringValue },
        )
        val friend =
            assertIs<EngineResult.Object>(
                result.getCell(schema.key("User", "friend")).getValue().get(),
            )
        assertEquals(
            "Grace",
            assertIs<Value.String>(
                friend.getCell(schema.key("User", "first")).getValue().get(),
            ).stringValue,
        )
    }

    @Test
    fun `list engine result retains its elements`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val elementType = schema.field("Query", "value").typeExpr
        val result = world.listResultOf(elementType, "one", null)

        assertEquals(
            listOf(Value.String.of("one"), null),
            result.map { cell -> cell.getValue().get() },
        )
        assertEquals(elementType, result.typeExpr)
    }

    @Test
    fun `typed empty list retains its intended element type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr

        val result = EngineResult.List.of(elementType, emptyList())

        assertEquals(elementType, result.typeExpr)
        assertEquals(0, result.size)
    }

    @Test
    fun `object result factory rejects values that violate field typing`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key =
            Value.GroundKey.of(
                schema.objectField("Query", "required"),
                emptyMap(),
            )
        assertFailsWith<IllegalArgumentException> {
            EngineResult.Object.of(schema.query, mapOf(key to null))
        }
    }

    @Test
    fun `strict read does not reserve a missing mutable value`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = EngineResult.Object.of(schema.query, mutable = true)

        assertFailsWith<MissingFieldException> { result.getCell(key) }
        assertFalse(result.isCellSet(key))
    }

    @Test
    fun `mutable object publishes each value once`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val firstValue = Value.String.of("first")
        val result = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)

        assertFalse(result.isCellSet(key))
        val cell = result.reserveCell(key)
        val readerPlaceholder = cell.reserveValue()
        assertFalse(readerPlaceholder.isCompleted)

        val writerPromise = cell.createValuePromise()
        assertSame(readerPlaceholder, writerPromise)
        writerPromise.complete(firstValue)

        assertTrue(result.isCellSet(key))
        assertSame(firstValue, result.getCell(key).getValue().get())
        assertEquals(setOf(key), result.keys)

        assertFailsWith<IllegalStateException> {
            cell.setValue(Value.String.of("second"))
        }
        assertSame(firstValue, result.getCell(key).getValue().get())
    }

    @Test
    fun `freeze fails unclaimed reader placeholders and rejects new values`() =
        runBlocking {
            val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
            val firstKey = schema.key("Query", "first")
            val secondKey = schema.key("Query", "second")
            val result = EngineResult.Object.of(schema.query, mutable = true)
            val firstCell = result.reserveCell(firstKey)
            val missing = firstCell.reserveValue()
            val awaitingMissing = async { missing.await() }

            result.freeze()

            assertFailsWith<MissingFieldException> { awaitingMissing.await() }
            assertFailsWith<MissingFieldException> { result.reserveCell(secondKey) }
            assertFailsWith<IllegalStateException> {
                firstCell.createValuePromise()
            }
            assertFailsWith<IllegalStateException> {
                firstCell.setValue(Value.String.of("late"))
            }
            assertFailsWith<IllegalStateException> { result.freeze() }
        }

    @Test
    fun `claimed value promise may complete after freeze`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = EngineResult.Object.of(schema.query, mutable = true)
        val cell = result.reserveCell(key)
        val readerPlaceholder = cell.reserveValue()
        val writerPromise = cell.createValuePromise()

        result.freeze()
        writerPromise.complete(Value.String.of("ready"))

        assertSame(readerPlaceholder, writerPromise)
        assertEquals(Value.String.of("ready"), cell.getValue().get())
    }

    @Test
    fun `concurrent reader and writer share one value promise`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = EngineResult.Object.of(schema.query, mutable = true)
        val start = CountDownLatch(1)
        val promises = ConcurrentLinkedQueue<Promise<EngineResult?>>()
        val reader =
            thread {
                start.await()
                promises += result.reserveCell(key).reserveValue()
            }
        val writer =
            thread {
                start.await()
                promises += result.reserveCell(key).createValuePromise()
            }

        start.countDown()
        reader.join()
        writer.join()

        assertEquals(2, promises.size)
        assertSame(promises.first(), promises.last())
    }

    @Test
    fun `cell value and access acceptance are independently monotonic`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result =
            EngineResult.Object.of(type = schema.query, mutable = true)

        assertFalse(result.isCellSet(key))
        val cell = result.reserveCell(key)
        assertFailsWith<IllegalStateException> { cell.getValue() }
        assertFailsWith<IllegalStateException> { cell.getAccessAccepted() }

        val value = cell.createValuePromise()
        val accessAccepted = cell.createAccessAcceptedPromise()

        assertFailsWith<UncompletedPromiseException> { value.get() }
        assertFailsWith<UncompletedPromiseException> { accessAccepted.get() }

        value.complete(Value.String.of("ready"))
        accessAccepted.complete(Value.Boolean.of(false))

        assertEquals(Value.String.of("ready"), cell.getValue().get())
        assertEquals(Value.Boolean.of(false), cell.getAccessAccepted().get())
        assertFailsWith<IllegalStateException> {
            cell.setAccessAccepted(Value.Boolean.of(true))
        }
    }

    @Test
    fun `list allocates stable cells whose access promises complete independently`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr
        val result =
            EngineResult.List.of(
                typeExpr = elementType,
                values = listOf(Value.String.of("first"), Value.String.of("second")),
                accessAccepted = listOf(null, null),
                mutableCells = true,
            )

        val first = result[0]
        val second = result[1]
        val firstAccess = first.createAccessAcceptedPromise()
        val secondAccess = second.createAccessAcceptedPromise()

        firstAccess.complete(Value.Boolean.of(false))
        assertEquals(Value.Boolean.of(false), first.getAccessAccepted().get())
        assertFailsWith<UncompletedPromiseException> { second.getAccessAccepted().get() }

        secondAccess.complete(Value.Boolean.of(true))
        assertEquals(Value.Boolean.of(true), second.getAccessAccepted().get())
        assertSame(first, result[0])
        assertSame(second, result[1])
        assertNotEquals(first, second)
    }

    @Test
    fun `immutable object and union results reject writes`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val requiredKey = schema.key("Query", "required")
        val immutable =
            EngineResult.Object.of(
                schema.query,
                mapOf(firstKey to Value.String.of("existing")),
            )

        assertFailsWith<IllegalStateException> {
            immutable.getCell(firstKey).setValue(Value.String.of("first"))
        }
        val fixture =
            schema.engineResultOf("Query") {
                "first" resolvesTo "existing"
            }
        assertFailsWith<IllegalStateException> {
            fixture.getCell(firstKey).setValue(Value.String.of("first"))
        }

        val left =
            EngineResult.Object.of(
                schema.query,
                mapOf(firstKey to Value.String.of("first")),
                mutable = true,
            )
        val right =
            EngineResult.Object.of(
                schema.query,
                mapOf(secondKey to Value.String.of("second")),
            )
        val union = left.union(right)

        assertFailsWith<MissingFieldException> {
            union.reserveCell(requiredKey)
        }
    }

    @Test
    fun `mutable object rejects invalid values before publication`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val result = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)
        val foreignKey = schema.key("User", "first")
        val requiredKey = schema.key("Query", "required")
        val lookupWithError =
            Value.GroundKey.of(
                schema.objectField("User", "lookup"),
                mapOf("limit" to Value.Error),
            )
        val user =
            EngineResult.Object.of(
                schema.objectField("User", "first").containingType,
                emptyMap(),
                mutable = true,
            )

        assertFailsWith<IllegalArgumentException> {
            result.reserveCell(foreignKey).setValue(Value.String.of("wrong owner"))
        }
        assertFalse(result.isCellSet(foreignKey))

        assertFailsWith<IllegalArgumentException> {
            result.reserveCell(requiredKey).setValue(null)
        }
        assertTrue(result.isCellSet(requiredKey))

        assertFailsWith<IllegalArgumentException> {
            user.reserveCell(lookupWithError).setValue(Value.String.of("not an error"))
        }
        assertTrue(user.isCellSet(lookupWithError))

        user.getCell(lookupWithError).setValue(Value.Error)
        assertSame(Value.Error, user.getCell(lookupWithError).getValue().get())
    }

    @Test
    fun `concurrent object writers produce one winner and one exception`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val writers =
            listOf("first", "second").map { value ->
                thread {
                    ready.countDown()
                    start.await()
                    try {
                        result.reserveCell(key).setValue(Value.String.of(value))
                        successes.incrementAndGet()
                    } catch (throwable: Throwable) {
                        failures.add(throwable)
                    }
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        assertEquals(1, successes.get())
        assertIs<IllegalStateException>(failures.single())
        val value = assertIs<Value.String>(result.getCell(key).getValue().get())
        assertTrue(value.stringValue in setOf("first", "second"))
    }

    @Test
    fun `concurrent object writes to distinct keys are retained`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val result = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val writes =
            listOf(
                schema.key("Query", "first") to "first",
                schema.key("Query", "second") to "second",
            )
        val writers =
            writes.map { (key, value) ->
                thread {
                    ready.countDown()
                    start.await()
                    result.reserveCell(key).setValue(Value.String.of(value))
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        writes.forEach { (key, value) ->
            assertEquals(
                value,
                assertIs<Value.String>(result.getCell(key).getValue().get()).stringValue,
            )
        }
    }

    @Test
    fun `written parent value observes later writes to mutable child`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val userKey = schema.key("Query", "user")
        val firstKey = schema.key("User", "first")
        val child =
            EngineResult.Object.of(
                schema.objectField("User", "first").containingType,
                emptyMap(),
                mutable = true,
            )
        val parent = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)

        parent.reserveCell(userKey).setValue(child)
        child.reserveCell(firstKey).setValue(Value.String.of("later"))

        val retainedChild =
            assertIs<EngineResult.Object>(parent.getCell(userKey).getValue().get())
        assertEquals(
            Value.String.of("later"),
            retainedChild.getCell(firstKey).getValue().get(),
        )
    }

    @Test
    fun `object equality is identity based and hashing is stable through mutation`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val firstValue = Value.String.of("first")
        val mutable = EngineResult.Object.of(schema.query, mutable = true)
        val equivalent =
            EngineResult.Object.of(
                schema.query,
                mapOf(firstKey to firstValue),
            )

        mutable.reserveCell(firstKey).also { cell ->
            cell.setValue(firstValue)
            cell.setAccessAccepted(Value.Boolean.of(true))
        }

        assertNotEquals(equivalent, mutable)
        assertSame(mutable, mutable)
        val hashCode = mutable.hashCode()
        val keyed = hashMapOf(mutable to "retained")

        mutable.reserveCell(secondKey).also { cell ->
            cell.setValue(Value.String.of("second"))
            cell.setAccessAccepted(Value.Boolean.of(true))
        }

        assertEquals(hashCode, mutable.hashCode())
        assertEquals("retained", keyed[mutable])
    }

    @Test
    fun `list equality includes type expression and cell occurrence identity`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "user").typeExpr
        val shared =
            schema.engineResultOf("User") {
                "first" resolvesTo "same"
            }
        val equivalent =
            schema.engineResultOf("User") {
                "first" resolvesTo "same"
            }
        val list = EngineResult.List.of(elementType, listOf(shared))

        assertEquals(list, list)
        assertSame(list[0], list[0])
        assertNotEquals(list, EngineResult.List.of(elementType, listOf(shared)))
        assertNotEquals(
            list,
            EngineResult.List.of(elementType, listOf(equivalent)),
        )
        assertNotEquals(
            EngineResult.List.of(schema.field("Query", "value").typeExpr, emptyList()),
            EngineResult.List.of(schema.field("Query", "integer").typeExpr, emptyList()),
        )
    }

    @Test
    fun `completed result comparison is extensional over nested values and checks`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val accepted = Value.Boolean.of(true)
        val left =
            schema.engineResultOf("Query") {
                "user" resolvesTo
                    engineResultOf("User") {
                        "first".resolvesTo("same", accepted)
                    }
            }
        val right =
            schema.engineResultOf("Query") {
                "user" resolvesTo
                    engineResultOf("User") {
                        "first".resolvesTo("same", accepted)
                    }
            }
        val differentCheck =
            schema.engineResultOf("Query") {
                "user" resolvesTo
                    engineResultOf("User") {
                        "first".resolvesTo("same", Value.Boolean.of(false))
                    }
            }

        assertTrue(left.sameCompletedResultAs(right))
        assertFalse(left.sameCompletedResultAs(differentCheck))
    }

    @Test
    fun `completed result comparison rejects an uncompleted promise`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val incomplete =
            EngineResult.Object.of(type = schema.query, mutable = true)
        incomplete
            .reserveCell(schema.key("Query", "first"))
            .createValuePromise()

        assertFailsWith<UncompletedPromiseException> {
            incomplete.sameCompletedResultAs(incomplete)
        }
    }

    @Test
    fun `completed result comparison audits completion after an early inequality`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val incomplete = EngineResult.Object.of(schema.query, mutable = true)
        incomplete
            .reserveCell(schema.key("Query", "first"))
            .createValuePromise()

        assertFailsWith<UncompletedPromiseException> {
            Value.String.of("different variant").sameCompletedResultAs(incomplete)
        }
    }

    @Test
    fun `list result factory rejects incompatible element values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr

        assertFailsWith<IllegalArgumentException> {
            EngineResult.List.of(elementType, listOf(Value.Int.of(1)))
        }
    }

    @Test
    fun `equal simple engine results have a union`() {
        val left = Value.String.of("same")
        val right = Value.String.of("same")

        assertSame(left, left.union(right))
        assertSame(left, (left as EngineResult).union(right))
    }

    @Test
    fun `unequal simple engine results have no union`() {
        assertFailsWith<IllegalArgumentException> {
            Value.String.of("left").union(Value.String.of("right"))
        }
    }

    @Test
    fun `different engine result variants have no union`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val simple: EngineResult = Value.String.of("value")
        val list: EngineResult =
            EngineResult.List.of(
                schema.field("Query", "value").typeExpr,
                emptyList(),
            )

        assertFailsWith<IllegalArgumentException> { simple.union(list) }
        assertFailsWith<IllegalArgumentException> { list.union(simple) }
    }

    @Test
    fun `nullable engine results union only matching nulls`() {
        val absent: EngineResult? = null
        val present: EngineResult = Value.String.of("value")

        assertNull(absent.union(null))
        assertFailsWith<IllegalArgumentException> { absent.union(present) }
        assertFailsWith<IllegalArgumentException> { present.union(absent) }
    }

    @Test
    fun `list engine results union corresponding cells`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr
        val left = schema.listResultOf(elementType, "same", null)
        val right = schema.listResultOf(elementType, "same", null)

        assertTrue(left.sameCompletedResultAs(left.union(right)))
    }

    @Test
    fun `list engine results with incompatible shapes have no union`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val stringType = schema.field("Query", "value").typeExpr
        val intType = schema.field("Query", "integer").typeExpr
        val stringResult = schema.listResultOf(stringType, "value")

        assertFailsWith<IllegalArgumentException> {
            EngineResult.List
                .of(stringType, emptyList())
                .union(EngineResult.List.of(intType, emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            EngineResult.List
                .of(stringType, emptyList())
                .union(stringResult)
        }
        assertFailsWith<IllegalArgumentException> {
            schema.listResultOf(stringType, null).union(stringResult)
        }
        assertFailsWith<IllegalArgumentException> {
            schema
                .listResultOf(stringType, "left")
                .union(schema.listResultOf(stringType, "right"))
        }
    }

    @Test
    fun `object engine results union disjoint and recursively shared values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val accept = Value.Boolean.of(true)
        val leftUser =
            schema.engineResultOf("User") {
                "first".resolvesTo("first", accept)
            }
        val rightUser =
            schema.engineResultOf("User") {
                "second".resolvesTo("second", accept)
            }
        val left =
            schema.engineResultOf("Query") {
                "first".resolvesTo("first", accept)
                "user".resolvesTo(leftUser, accept)
            }
        val right =
            schema.engineResultOf("Query") {
                "second".resolvesTo("second", accept)
                "user".resolvesTo(rightUser, accept)
            }

        val union = left.union(right)

        assertEquals(
            setOf(
                schema.key("Query", "first"),
                schema.key("Query", "second"),
                schema.key("Query", "user"),
            ),
            union.keys,
        )
        assertEquals(
            Value.String.of("first"),
            union.getCell(schema.key("Query", "first")).getValue().get(),
        )
        assertEquals(
            Value.String.of("second"),
            union.getCell(schema.key("Query", "second")).getValue().get(),
        )
        val user =
            assertIs<EngineResult.Object>(
                union.getCell(schema.key("Query", "user")).getValue().get(),
            )
        assertEquals(
            setOf(schema.key("User", "first"), schema.key("User", "second")),
            user.keys,
        )
    }

    @Test
    fun `object engine results with incompatible cells or types have no union`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val trueCheck = Value.Boolean.of(true)
        val falseCheck = Value.Boolean.of(false)

        assertFailsWith<IllegalArgumentException> {
            schema
                .engineResultOf("Query") {
                    "first".resolvesTo("left", trueCheck)
                }.union(
                    schema.engineResultOf("Query") {
                        "first".resolvesTo("right", trueCheck)
                    },
                )
        }
        assertFailsWith<IllegalArgumentException> {
            schema
                .engineResultOf("Query") {
                    "first".resolvesTo("same", trueCheck)
                }.union(
                    schema.engineResultOf("Query") {
                        "first".resolvesTo("same", falseCheck)
                    },
                )
        }
        assertFailsWith<IllegalArgumentException> {
            schema.engineResultOf("Query").union(schema.engineResultOf("User"))
        }
    }

    private fun Schema.key(
        typeName: String,
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): Value.GroundKey =
        Value.GroundKey.of(objectField(typeName, fieldName), arguments.toMap())

    private companion object {
        const val SCHEMA_SDL =
            """
            type Query {
              value: String
              integer: Int
              required: String!
              first: String
              second: String
              user: User
            }

            type User {
              first: String
              second: String
              aliases: [String]
              friend: User
              lookup(limit: Int): String
            }
            """
    }
}
