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
    fun `node reference contains the node id`() {
        val world = TestWorld.fromSDL(NODE_SCHEMA_SDL).assumptions
        val schema = world.schema
        val user = schema.type("User") as Schema.ObjectType
        val idField = schema.field("User", "id")
        val id = Value.ID.of("1")

        val result =
            context(world) {
                EngineResult.Object.nodeRef(idField, id)
            }

        val idKey = Value.Key.of(idField, emptyMap())
        assertEquals(user, result.type)
        assertEquals(setOf(idKey), result.keys)
        assertEquals(id, result.fetch(idKey).value)
    }

    @Test
    fun `node reference rejects a non-id field`() {
        val world = TestWorld.fromSDL(NODE_SCHEMA_SDL).assumptions

        assertFailsWith<IllegalArgumentException> {
            context(world) {
                EngineResult.Object.nodeRef(
                    world.schema.field("User", "name"),
                    Value.ID.of("1"),
                )
            }
        }
    }

    @Test
    fun `node reference rejects a non-Node type`() {
        val world = TestWorld.fromSDL(NODE_SCHEMA_SDL).assumptions

        assertFailsWith<IllegalArgumentException> {
            context(world) {
                EngineResult.Object.nodeRef(
                    world.schema.field("Other", "id"),
                    Value.ID.of("1"),
                )
            }
        }
    }

    @Test
    fun `list engine result retains its elements`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.field("Query", "value").typeExpr
        val first =
            EngineResult.Cell.of(
                Value.String.of("one"),
                Value.Boolean.of(true),
            )
        val second = EngineResult.Cell.of(null, Value.Boolean.of(true))
        val result =
            EngineResult.List.of(
                typeExpr = elementType,
                cells = listOf(first, second),
            )

        assertEquals<List<EngineResult.Cell>>(
            listOf(first, second),
            result,
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
            Value.Key.of(
                schema.field("Query", "required"),
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
        val check = Value.Boolean.of(true)
        val left =
            EngineResult.List.of(
                elementType,
                listOf(
                    EngineResult.Cell.of(Value.String.of("same"), check),
                    EngineResult.Cell.of(null, check),
                ),
            )
        val right =
            EngineResult.List.of(
                elementType,
                listOf(
                    EngineResult.Cell.of(Value.String.of("same"), check),
                    EngineResult.Cell.of(null, check),
                ),
            )

        assertEquals(left, left.union(right))
    }

    @Test
    fun `list engine results with incompatible shapes have no union`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val stringType = schema.field("Query", "value").typeExpr
        val intType = schema.field("Query", "integer").typeExpr
        val check = Value.Boolean.of(true)
        val stringCell = EngineResult.Cell.of(Value.String.of("value"), check)

        assertFailsWith<IllegalArgumentException> {
            EngineResult.List
                .of(stringType, emptyList())
                .union(EngineResult.List.of(intType, emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            EngineResult.List
                .of(stringType, emptyList())
                .union(EngineResult.List.of(stringType, listOf(stringCell)))
        }
        assertFailsWith<IllegalArgumentException> {
            EngineResult.List
                .of(stringType, listOf(EngineResult.Cell.of(null, check)))
                .union(EngineResult.List.of(stringType, listOf(stringCell)))
        }
        assertFailsWith<IllegalArgumentException> {
            EngineResult.List
                .of(
                    stringType,
                    listOf(EngineResult.Cell.of(Value.String.of("left"), check)),
                ).union(
                    EngineResult.List.of(
                        stringType,
                        listOf(EngineResult.Cell.of(Value.String.of("right"), check)),
                    ),
                )
        }
    }

    @Test
    fun `object engine results union disjoint and recursively shared cells`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val check = Value.Boolean.of(true)
        val leftUser =
            schema.objectEngineResult(
                "User",
                "first" to EngineResult.Cell.of(Value.String.of("first"), check),
            )
        val rightUser =
            schema.objectEngineResult(
                "User",
                "second" to EngineResult.Cell.of(Value.String.of("second"), check),
            )
        val left =
            schema.objectEngineResult(
                "Query",
                "first" to EngineResult.Cell.of(Value.String.of("first"), check),
                "user" to EngineResult.Cell.of(leftUser, check),
            )
        val right =
            schema.objectEngineResult(
                "Query",
                "second" to EngineResult.Cell.of(Value.String.of("second"), check),
                "user" to EngineResult.Cell.of(rightUser, check),
            )

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
                .objectEngineResult(
                    "Query",
                    "first" to EngineResult.Cell.of(Value.String.of("left"), trueCheck),
                ).union(
                    schema.objectEngineResult(
                        "Query",
                        "first" to EngineResult.Cell.of(Value.String.of("right"), trueCheck),
                    ),
                )
        }
        assertFailsWith<IllegalArgumentException> {
            schema
                .objectEngineResult(
                    "Query",
                    "first" to EngineResult.Cell.of(Value.String.of("same"), trueCheck),
                ).union(
                    schema.objectEngineResult(
                        "Query",
                        "first" to EngineResult.Cell.of(Value.String.of("same"), falseCheck),
                    ),
                )
        }
        assertFailsWith<IllegalArgumentException> {
            schema.objectEngineResult("Query").union(schema.objectEngineResult("User"))
        }
    }

    private fun Schema.objectEngineResult(
        typeName: String,
        vararg fields: Pair<String, EngineResult.Cell>,
    ): EngineResult.Object {
        val type = type(typeName) as Schema.ObjectType
        val cells =
            fields.associate { (fieldName, cell) ->
                key(typeName, fieldName) to cell
            }
        return EngineResult.Object.of(type, cells)
    }

    private fun Schema.key(
        typeName: String,
        fieldName: String,
    ): Value.Key = Value.Key.of(field(typeName, fieldName), emptyMap())

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
            }
            """

        const val NODE_SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
            }

            type Other {
              id: ID!
            }

            type Query {
              user: User
            }
            """
    }
}
