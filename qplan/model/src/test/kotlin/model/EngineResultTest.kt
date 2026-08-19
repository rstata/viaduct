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
            result
                .getCell(schema.key("User", "lookup", "limit" to 1))
                .getValue()
                .get(),
        )
        val aliases =
            assertIs<ListEngineResult>(
                result.getCell(schema.key("User", "aliases")).getValue().get(),
            )
        assertEquals(
            listOf("A", null),
            aliases.map { cell -> cell.getValue().get() },
        )
        val friend =
            assertIs<ObjectEngineResult>(
                result.getCell(schema.key("User", "friend")).getValue().get(),
            )
        assertEquals(
            "Grace",
            friend.getCell(schema.key("User", "first")).getValue().get(),
        )
    }

    @Test
    fun `list engine result retains its elements`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val elementType = schema.field("Query", "value").typeExpr
        val result = world.listResultOf(elementType, "one", null)

        assertEquals(
            listOf("one", null),
            result.map { cell -> cell.getValue().get() },
        )
        assertEquals(elementType, result.typeExpr)
    }

    @Test
    fun `typed empty list retains its intended element type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr

        val result = ListEngineResult.of(elementType, emptyList())

        assertEquals(elementType, result.typeExpr)
        assertEquals(0, result.size)
    }

    @Test
    fun `object result factory rejects values that violate field typing`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key =
            ObjectEngineResult.GroundKey.of(
                schema.objectField("Query", "required"),
                emptyMap(),
            )
        assertFailsWith<IllegalArgumentException> {
            ObjectEngineResult.of(schema.query, mapOf(key to null))
        }
    }

    @Test
    fun `strict read does not reserve a missing mutable value`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.query, mutable = true)

        assertFailsWith<MissingFieldException> { result.getCell(key) }
        assertFalse(result.isCellSet(key))
    }

    @Test
    fun `mutable object publishes each value once`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val firstValue = "first"
        val result = ObjectEngineResult.of(schema.query, emptyMap(), mutable = true)

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
            cell.setValue("second")
        }
        assertSame(firstValue, result.getCell(key).getValue().get())
    }

    @Test
    fun `freeze fails unclaimed reader placeholders and rejects new values`() =
        runBlocking {
            val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
            val firstKey = schema.key("Query", "first")
            val secondKey = schema.key("Query", "second")
            val result = ObjectEngineResult.of(schema.query, mutable = true)
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
                firstCell.setValue("late")
            }
            assertFailsWith<IllegalStateException> { result.freeze() }
        }

    @Test
    fun `claimed value promise may complete after freeze`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.query, mutable = true)
        val cell = result.reserveCell(key)
        val readerPlaceholder = cell.reserveValue()
        val writerPromise = cell.createValuePromise()

        result.freeze()
        writerPromise.complete("ready")

        assertSame(readerPlaceholder, writerPromise)
        assertEquals("ready", cell.getValue().get())
    }

    @Test
    fun `concurrent reader and writer share one value promise`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.query, mutable = true)
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
            ObjectEngineResult.of(type = schema.query, mutable = true)

        assertFalse(result.isCellSet(key))
        val cell = result.reserveCell(key)
        assertFailsWith<IllegalStateException> { cell.getValue() }
        assertFailsWith<IllegalStateException> { cell.getAccessResult() }

        val value = cell.createValuePromise()
        val accessResult = cell.createAccessResultPromise()

        assertFailsWith<UncompletedPromiseException> { value.get() }
        assertFailsWith<UncompletedPromiseException> { accessResult.get() }

        value.complete("ready")
        accessResult.complete(false)

        assertEquals("ready", cell.getValue().get())
        assertEquals(false, cell.getAccessResult().get())
        assertFailsWith<IllegalStateException> {
            cell.setAccessResult(true)
        }
    }

    @Test
    fun `access results accept only booleans or the error sentinel`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val elementType = schema.field("Query", "value").typeExpr
        val completed =
            ObjectEngineResult.of(
                type = schema.query,
                values = mapOf(firstKey to "ready"),
                accessResults = mapOf(firstKey to ErrorEngineResult),
            )

        assertSame(ErrorEngineResult, completed.getCell(firstKey).getAccessResult().get())
        assertFailsWith<IllegalArgumentException> {
            ObjectEngineResult.of(
                type = schema.query,
                accessResults = mapOf(firstKey to "not an access result"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(
                typeExpr = elementType,
                values = listOf("ready"),
                accessResults = listOf("not an access result"),
            )
        }

        val mutable = ObjectEngineResult.of(schema.query, mutable = true)
        val direct = mutable.reserveCell(firstKey)
        assertFailsWith<IllegalArgumentException> {
            direct.setAccessResult("not an access result")
        }
        direct.setAccessResult(ErrorEngineResult)
        assertSame(ErrorEngineResult, direct.getAccessResult().get())

        val deferred = mutable.reserveCell(secondKey).createAccessResultPromise()
        assertFailsWith<IllegalArgumentException> {
            deferred.complete("not an access result")
        }
        assertFalse(deferred.isCompleted)
        deferred.complete(false)
        assertEquals(false, deferred.get())
    }

    @Test
    fun `list allocates stable cells whose access promises complete independently`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr
        val result =
            ListEngineResult.of(
                typeExpr = elementType,
                values =
                    listOf(
                        "first",
                        "second",
                    ),
                accessResults = listOf(null, null),
                mutableCells = true,
            )

        val first = result[0]
        val second = result[1]
        val firstAccess = first.createAccessResultPromise()
        val secondAccess = second.createAccessResultPromise()

        firstAccess.complete(false)
        assertEquals(false, first.getAccessResult().get())
        assertFailsWith<UncompletedPromiseException> { second.getAccessResult().get() }

        secondAccess.complete(true)
        assertEquals(true, second.getAccessResult().get())
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
            ObjectEngineResult.of(
                schema.query,
                mapOf(firstKey to "existing"),
            )

        assertFailsWith<IllegalStateException> {
            immutable.getCell(firstKey).setValue("first")
        }
        val fixture =
            schema.engineResultOf("Query") {
                "first" resolvesTo "existing"
            }
        assertFailsWith<IllegalStateException> {
            fixture.getCell(firstKey).setValue("first")
        }

        val left =
            ObjectEngineResult.of(
                schema.query,
                mapOf(firstKey to "first"),
                mutable = true,
            )
        val right =
            ObjectEngineResult.of(
                schema.query,
                mapOf(secondKey to "second"),
            )
        val union = left.union(right)

        assertFailsWith<MissingFieldException> {
            union.reserveCell(requiredKey)
        }
    }

    @Test
    fun `mutable object rejects invalid values before publication`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val result = ObjectEngineResult.of(schema.query, emptyMap(), mutable = true)
        val foreignKey = schema.key("User", "first")
        val requiredKey = schema.key("Query", "required")
        val lookupWithError =
            ObjectEngineResult.GroundKey.of(
                schema.objectField("User", "lookup"),
                mapOf("limit" to Value.Error),
            )
        val user =
            ObjectEngineResult.of(
                schema.objectField("User", "first").containingType,
                emptyMap(),
                mutable = true,
            )

        assertFailsWith<IllegalArgumentException> {
            result.reserveCell(foreignKey).setValue("wrong owner")
        }
        assertFalse(result.isCellSet(foreignKey))

        assertFailsWith<IllegalArgumentException> {
            result.reserveCell(requiredKey).setValue(null)
        }
        assertTrue(result.isCellSet(requiredKey))

        assertFailsWith<IllegalArgumentException> {
            user.reserveCell(lookupWithError).setValue("not an error")
        }
        assertTrue(user.isCellSet(lookupWithError))

        user.getCell(lookupWithError).setValue(ErrorEngineResult)
        assertSame(ErrorEngineResult, user.getCell(lookupWithError).getValue().get())
    }

    @Test
    fun `concurrent object writers produce one winner and one exception`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.query, emptyMap(), mutable = true)
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
                        result.reserveCell(key).setValue(value)
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
        val value = assertIs<String>(result.getCell(key).getValue().get())
        assertTrue(value in setOf("first", "second"))
    }

    @Test
    fun `concurrent object writes to distinct keys are retained`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val result = ObjectEngineResult.of(schema.query, emptyMap(), mutable = true)
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
                    result.reserveCell(key).setValue(value)
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        writes.forEach { (key, value) ->
            assertEquals(
                value,
                result.getCell(key).getValue().get(),
            )
        }
    }

    @Test
    fun `written parent value observes later writes to mutable child`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val userKey = schema.key("Query", "user")
        val firstKey = schema.key("User", "first")
        val child =
            ObjectEngineResult.of(
                schema.objectField("User", "first").containingType,
                emptyMap(),
                mutable = true,
            )
        val parent = ObjectEngineResult.of(schema.query, emptyMap(), mutable = true)

        parent.reserveCell(userKey).setValue(child)
        child.reserveCell(firstKey).setValue("later")

        val retainedChild =
            assertIs<ObjectEngineResult>(parent.getCell(userKey).getValue().get())
        assertEquals(
            "later",
            retainedChild.getCell(firstKey).getValue().get(),
        )
    }

    @Test
    fun `object equality is identity based and hashing is stable through mutation`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val firstValue = "first"
        val mutable = ObjectEngineResult.of(schema.query, mutable = true)
        val equivalent =
            ObjectEngineResult.of(
                schema.query,
                mapOf(firstKey to firstValue),
            )

        mutable.reserveCell(firstKey).also { cell ->
            cell.setValue(firstValue)
            cell.setAccessResult(true)
        }

        assertNotEquals(equivalent, mutable)
        assertSame(mutable, mutable)
        val hashCode = mutable.hashCode()
        val keyed = hashMapOf(mutable to "retained")

        mutable.reserveCell(secondKey).also { cell ->
            cell.setValue("second")
            cell.setAccessResult(true)
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
        val list = ListEngineResult.of(elementType, listOf(shared))

        assertEquals(list, list)
        assertSame(list[0], list[0])
        assertNotEquals(list, ListEngineResult.of(elementType, listOf(shared)))
        assertNotEquals(
            list,
            ListEngineResult.of(elementType, listOf(equivalent)),
        )
        assertNotEquals(
            ListEngineResult.of(schema.field("Query", "value").typeExpr, emptyList()),
            ListEngineResult.of(schema.field("Query", "integer").typeExpr, emptyList()),
        )
    }

    @Test
    fun `completed result comparison is extensional over nested values and checks`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val accepted = true
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
                        "first".resolvesTo("same", false)
                    }
            }

        assertTrue(left.sameCompletedResultAs(right))
        assertFalse(left.sameCompletedResultAs(differentCheck))
    }

    @Test
    fun `completed result comparison rejects an uncompleted promise`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val incomplete =
            ObjectEngineResult.of(type = schema.query, mutable = true)
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
        val incomplete = ObjectEngineResult.of(schema.query, mutable = true)
        incomplete
            .reserveCell(schema.key("Query", "first"))
            .createValuePromise()

        assertFailsWith<UncompletedPromiseException> {
            "different variant".sameCompletedResultAs(incomplete)
        }
    }

    @Test
    fun `list result factory rejects incompatible element values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr

        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(elementType, listOf(1))
        }
    }

    @Test
    fun `simple resolver values convert to pre-domain engine results and back`() {
        val schema =
            TestWorld
                .fromSDL(
                    """
                    type Query {
                      status: Status
                    }

                    enum Status {
                      READY
                    }
                    """.trimIndent(),
                ).schema
        val status = schema.type("Status") as Schema.EnumType
        val cases =
            listOf(
                Triple<Value.Simple, EngineResult, Schema.SimpleType>(
                    Value.Int.of(1),
                    1,
                    Schema.IntType,
                ),
                Triple(Value.Float.of(2.5), 2.5, Schema.FloatType),
                Triple(Value.String.of("three"), "three", Schema.StringType),
                Triple(Value.Boolean.of(true), true, Schema.BooleanType),
                Triple(Value.ID.of("four"), Schema.ID.of("four"), Schema.IDType),
                Triple(
                    Value.Enum.of(status, "READY"),
                    status.values.getValue("READY"),
                    status,
                ),
            )

        cases.forEach { (value, expectedResult, type) ->
            val result = value.toEngineResult()
            assertEquals(expectedResult, result)
            assertEquals(value, result.toValue(type))
        }
        assertEquals(Schema.ID.of("four"), Schema.ID.of("four"))
        assertSame(status.values.getValue("READY"), cases.last().second)
        assertSame(ErrorEngineResult, Value.Error.toEngineResult())
    }

    @Test
    fun `result factories enforce GraphQL scalar domains`() {
        val schema =
            TestWorld
                .fromSDL(
                    """
                    type Query {
                      status: Status
                    }

                    enum Status {
                      READY
                    }

                    enum OtherStatus {
                      READY
                    }
                    """.trimIndent(),
                ).schema
        val status = schema.type("Status") as Schema.EnumType
        val otherStatus = schema.type("OtherStatus") as Schema.EnumType
        val floatType = TypeExpr.Named.of(Schema.FloatType)
        val statusType = TypeExpr.Named.of(status)

        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(floatType, listOf(Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(floatType, listOf(Double.POSITIVE_INFINITY))
        }
        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(
                statusType,
                listOf(otherStatus.values.getValue("READY")),
            )
        }
    }

    @Test
    fun `equal simple engine results have a union`() {
        val left = "same"
        val right = "same"

        assertSame(left, left.union(right))
        assertSame(left, (left as EngineResult).union(right))
    }

    @Test
    fun `unequal simple engine results have no union`() {
        assertFailsWith<IllegalArgumentException> {
            "left".union("right")
        }
    }

    @Test
    fun `different engine result variants have no union`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val simple: EngineResult = "value"
        val list: EngineResult =
            ListEngineResult.of(
                schema.field("Query", "value").typeExpr,
                emptyList(),
            )

        assertFailsWith<IllegalArgumentException> { simple.union(list) }
        assertFailsWith<IllegalArgumentException> { list.union(simple) }
    }

    @Test
    fun `nullable engine results union only matching nulls`() {
        val absent: EngineResult? = null
        val present: EngineResult = "value"

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
            ListEngineResult
                .of(stringType, emptyList())
                .union(ListEngineResult.of(intType, emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            ListEngineResult
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
        val accept = true
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
            "first",
            union.getCell(schema.key("Query", "first")).getValue().get(),
        )
        assertEquals(
            "second",
            union.getCell(schema.key("Query", "second")).getValue().get(),
        )
        val user =
            assertIs<ObjectEngineResult>(
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
        val trueCheck = true
        val falseCheck = false

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
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(objectField(typeName, fieldName), arguments.toMap())

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
