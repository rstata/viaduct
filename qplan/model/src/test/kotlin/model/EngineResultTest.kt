package model

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
                result.fetch(schema.key("User", "lookup", "limit" to 1)).value,
            ).stringValue,
        )
        val aliases =
            assertIs<EngineResult.List>(
                result.fetch(schema.key("User", "aliases")).value,
            )
        assertEquals(
            listOf("A", null),
            aliases.map { cell -> (cell.value as? Value.String)?.stringValue },
        )
        val friend =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key("User", "friend")).value,
            )
        assertEquals(
            "Grace",
            assertIs<Value.String>(
                friend.fetch(schema.key("User", "first")).value,
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
            result.map(EngineResult.Cell::value),
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
        val cell = EngineResult.Cell.of(null, Value.Boolean.of(true))

        assertFailsWith<IllegalArgumentException> {
            EngineResult.Object.of(schema.query, mapOf(key to cell))
        }
    }

    @Test
    fun `mutable object publishes each cell once and returns stable snapshots`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val firstCell = EngineResult.Cell.of(Value.String.of("first"))
        val result = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)
        val emptyCells = result.cells
        val emptyKeys = result.keys

        assertFalse(result.isSet(key))
        assertFailsWith<MissingFieldException> {
            result.fetch(key)
        }

        result.write(key, firstCell)

        assertTrue(result.isSet(key))
        assertSame(firstCell, result.fetch(key))
        assertEquals(mapOf(key to firstCell), result.cells)
        assertEquals(setOf(key), result.keys)
        assertTrue(emptyCells.isEmpty())
        assertTrue(emptyKeys.isEmpty())

        assertFailsWith<IllegalStateException> {
            result.write(key, EngineResult.Cell.of(Value.String.of("second")))
        }
        assertSame(firstCell, result.fetch(key))
    }

    @Test
    fun `immutable object and union results reject writes`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val requiredKey = schema.key("Query", "required")
        val immutable = EngineResult.Object.of(schema.query, emptyMap())

        assertFailsWith<IllegalStateException> {
            immutable.write(firstKey, EngineResult.Cell.of(Value.String.of("first")))
        }
        val fixture = schema.engineResultOf("Query")
        assertFailsWith<IllegalStateException> {
            fixture.write(firstKey, EngineResult.Cell.of(Value.String.of("first")))
        }

        val left =
            EngineResult.Object.of(
                schema.query,
                mapOf(firstKey to EngineResult.Cell.of(Value.String.of("first"))),
                mutable = true,
            )
        val right =
            EngineResult.Object.of(
                schema.query,
                mapOf(secondKey to EngineResult.Cell.of(Value.String.of("second"))),
            )
        val union = left.union(right)

        assertFailsWith<IllegalStateException> {
            union.write(requiredKey, EngineResult.Cell.of(Value.String.of("required")))
        }
    }

    @Test
    fun `mutable object rejects invalid cells before publication`() {
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
            result.write(foreignKey, EngineResult.Cell.of(Value.String.of("wrong owner")))
        }
        assertFalse(result.isSet(foreignKey))

        assertFailsWith<IllegalArgumentException> {
            result.write(requiredKey, EngineResult.Cell.of(null))
        }
        assertFalse(result.isSet(requiredKey))

        assertFailsWith<IllegalArgumentException> {
            user.write(lookupWithError, EngineResult.Cell.of(Value.String.of("not an error")))
        }
        assertFalse(user.isSet(lookupWithError))

        user.write(lookupWithError, EngineResult.Cell.Error)
        assertSame(EngineResult.Cell.Error, user.fetch(lookupWithError))
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
                        result.write(key, EngineResult.Cell.of(Value.String.of(value)))
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
        val value = assertIs<Value.String>(result.fetch(key).value)
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
                    result.write(key, EngineResult.Cell.of(Value.String.of(value)))
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        writes.forEach { (key, value) ->
            assertEquals(value, assertIs<Value.String>(result.fetch(key).value).stringValue)
        }
    }

    @Test
    fun `written parent cell observes later writes to mutable child`() {
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
        val parentCell = EngineResult.Cell.of(child)

        parent.write(userKey, parentCell)
        child.write(firstKey, EngineResult.Cell.of(Value.String.of("later")))

        assertSame(parentCell, parent.fetch(userKey))
        val retainedChild = assertIs<EngineResult.Object>(parent.fetch(userKey).value)
        assertEquals(
            Value.String.of("later"),
            retainedChild.fetch(firstKey).value,
        )
    }

    @Test
    fun `completed mutable object has structural equality`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val firstCell = EngineResult.Cell.of(Value.String.of("first"))
        val mutable = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)
        val immutable = EngineResult.Object.of(schema.query, mapOf(firstKey to firstCell))

        mutable.write(firstKey, firstCell)

        assertEquals(immutable, mutable)
        assertEquals(immutable.hashCode(), mutable.hashCode())

        mutable.write(secondKey, EngineResult.Cell.of(Value.String.of("second")))

        assertNotEquals(immutable, mutable)
    }

    @Test
    fun `list result factory rejects incompatible element values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr
        val cell =
            EngineResult.Cell.of(
                Value.Int.of(1),
                Value.Boolean.of(true),
            )

        assertFailsWith<IllegalArgumentException> {
            EngineResult.List.of(elementType, listOf(cell))
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

        assertEquals(left, left.union(right))
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
    fun `object engine results union disjoint and recursively shared cells`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val check = Value.Boolean.of(true)
        val leftUser =
            schema.engineResultOf("User") {
                "first".resolvesTo("first", check)
            }
        val rightUser =
            schema.engineResultOf("User") {
                "second".resolvesTo("second", check)
            }
        val left =
            schema.engineResultOf("Query") {
                "first".resolvesTo("first", check)
                "user".resolvesTo(leftUser, check)
            }
        val right =
            schema.engineResultOf("Query") {
                "second".resolvesTo("second", check)
                "user".resolvesTo(rightUser, check)
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
            union.fetch(schema.key("Query", "first")).value,
        )
        assertEquals(
            Value.String.of("second"),
            union.fetch(schema.key("Query", "second")).value,
        )
        val user = assertIs<EngineResult.Object>(union.fetch(schema.key("Query", "user")).value)
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
