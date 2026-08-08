package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

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
