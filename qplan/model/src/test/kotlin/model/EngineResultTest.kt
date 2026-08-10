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
                result.getValue(schema.key("User", "lookup", "limit" to 1)).get(),
            ).stringValue,
        )
        val aliases =
            assertIs<EngineResult.List>(
                result.getValue(schema.key("User", "aliases")).get(),
            )
        assertEquals(
            listOf("A", null),
            aliases.map { value -> (value as? Value.String)?.stringValue },
        )
        val friend =
            assertIs<EngineResult.Object>(
                result.getValue(schema.key("User", "friend")).get(),
            )
        assertEquals(
            "Grace",
            assertIs<Value.String>(
                friend.getValue(schema.key("User", "first")).get(),
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
            result.map { it },
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
    fun `mutable object publishes each value once`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val firstValue = Value.String.of("first")
        val result = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)

        assertFalse(result.isValueSet(key))
        assertFailsWith<MissingFieldException> {
            result.getValue(key)
        }

        result.setValue(key, firstValue)

        assertTrue(result.isValueSet(key))
        assertSame(firstValue, result.getValue(key).get())
        assertEquals(setOf(key), result.keys)

        assertFailsWith<IllegalStateException> {
            result.setValue(key, Value.String.of("second"))
        }
        assertSame(firstValue, result.getValue(key).get())
    }

    @Test
    fun `value field check and type check are independently monotonic`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result =
            EngineResult.Object.of(
                type = schema.query,
                typeCheck = null,
                mutable = true,
            )

        assertFalse(result.isValueSet(key))
        assertFailsWith<MissingFieldException> { result.getFieldCheck(key) }
        assertFailsWith<IllegalStateException> { result.getTypeCheck() }

        val value = result.createValuePromise(key)
        val fieldCheck = result.createFieldCheckPromise(key)
        val typeCheck = result.createTypeCheckPromise()

        assertFailsWith<UncompletedPromiseException> { value.get() }
        assertFailsWith<UncompletedPromiseException> { fieldCheck.get() }
        assertFailsWith<UncompletedPromiseException> { typeCheck.get() }

        value.complete(Value.String.of("ready"))
        fieldCheck.complete(Value.Boolean.of(false))
        typeCheck.complete(Value.Boolean.of(true))

        assertEquals(Value.String.of("ready"), result.getValue(key).get())
        assertEquals(Value.Boolean.of(false), result.getFieldCheck(key).get())
        assertEquals(Value.Boolean.of(true), result.getTypeCheck().get())
        assertFailsWith<IllegalStateException> {
            result.setTypeCheck(Value.Boolean.of(false))
        }
    }

    @Test
    fun `immutable object and union results reject writes`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val requiredKey = schema.key("Query", "required")
        val immutable = EngineResult.Object.of(schema.query, emptyMap())

        assertFailsWith<IllegalStateException> {
            immutable.setValue(firstKey, Value.String.of("first"))
        }
        val fixture = schema.engineResultOf("Query")
        assertFailsWith<IllegalStateException> {
            fixture.setValue(firstKey, Value.String.of("first"))
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

        assertFailsWith<IllegalStateException> {
            union.setValue(requiredKey, Value.String.of("required"))
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
            result.setValue(foreignKey, Value.String.of("wrong owner"))
        }
        assertFalse(result.isValueSet(foreignKey))

        assertFailsWith<IllegalArgumentException> {
            result.setValue(requiredKey, null)
        }
        assertFalse(result.isValueSet(requiredKey))

        assertFailsWith<IllegalArgumentException> {
            user.setValue(lookupWithError, Value.String.of("not an error"))
        }
        assertFalse(user.isValueSet(lookupWithError))

        user.setValue(lookupWithError, Value.Error)
        assertSame(Value.Error, user.getValue(lookupWithError).get())
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
                        result.setValue(key, Value.String.of(value))
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
        val value = assertIs<Value.String>(result.getValue(key).get())
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
                    result.setValue(key, Value.String.of(value))
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        writes.forEach { (key, value) ->
            assertEquals(value, assertIs<Value.String>(result.getValue(key).get()).stringValue)
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

        parent.setValue(userKey, child)
        child.setValue(firstKey, Value.String.of("later"))

        val retainedChild = assertIs<EngineResult.Object>(parent.getValue(userKey).get())
        assertEquals(
            Value.String.of("later"),
            retainedChild.getValue(firstKey).get(),
        )
    }

    @Test
    fun `object equality is identity based and hashing is stable through mutation`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val firstValue = Value.String.of("first")
        val mutable = EngineResult.Object.of(schema.query, emptyMap(), mutable = true)
        val equivalent = EngineResult.Object.of(schema.query, mapOf(firstKey to firstValue))

        mutable.setValue(firstKey, firstValue)
        mutable.setFieldCheck(firstKey, Value.Boolean.of(true))

        assertNotEquals(equivalent, mutable)
        assertSame(mutable, mutable)
        val hashCode = mutable.hashCode()
        val keyed = hashMapOf(mutable to "retained")

        mutable.setValue(secondKey, Value.String.of("second"))
        mutable.setFieldCheck(secondKey, Value.Boolean.of(true))

        assertEquals(hashCode, mutable.hashCode())
        assertEquals("retained", keyed[mutable])
    }

    @Test
    fun `list equality includes type expression while object elements retain identity`() {
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

        assertEquals(list, EngineResult.List.of(elementType, listOf(shared)))
        assertNotEquals(list, EngineResult.List.of(elementType, listOf(equivalent)))
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
            EngineResult.Object.of(
                type = schema.query,
                typeCheck = null,
                mutable = true,
            )
        incomplete.createValuePromise(schema.key("Query", "first"))

        assertFailsWith<UncompletedPromiseException> {
            incomplete.sameCompletedResultAs(incomplete)
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
            union.getValue(schema.key("Query", "first")).get(),
        )
        assertEquals(
            Value.String.of("second"),
            union.getValue(schema.key("Query", "second")).get(),
        )
        val user = assertIs<EngineResult.Object>(union.getValue(schema.key("Query", "user")).get())
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
