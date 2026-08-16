package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ObjectConstructionTest {
    @Test
    fun `constructs fields using names and schema-directed output values`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema

        val friend =
            schema.objectOf("User") {
                "id" setTo "friend"
                "name" setTo "Grace"
            }
        val user =
            world.objectOf("User") {
                "id" setTo "user"
                "name" setTo "Ada"
                "age" setTo 36
                "score" setTo 9.5
                "active" setTo true
                "status" setTo "ACTIVE"
                "aliases" setTo listOf("A", "Countess")
                "friend" setTo friend
            }

        assertEquals(
            "user",
            assertIs<Value.ID>(user.fieldValues[key(schema, "id")]).idValue,
        )
        assertEquals(
            "Ada",
            assertIs<Value.String>(user.fieldValues[key(schema, "name")]).stringValue,
        )
        assertEquals(
            36,
            assertIs<Value.Int>(user.fieldValues[key(schema, "age")]).intValue,
        )
        assertEquals(
            9.5,
            assertIs<Value.Float>(user.fieldValues[key(schema, "score")]).floatValue,
        )
        assertEquals(
            true,
            assertIs<Value.Boolean>(user.fieldValues[key(schema, "active")]).booleanValue,
        )
        assertEquals(
            "ACTIVE",
            assertIs<Value.Enum>(user.fieldValues[key(schema, "status")]).enumValue,
        )
        val aliases =
            assertIs<Value.OutputList>(user.fieldValues[key(schema, "aliases")])
        assertEquals(
            listOf("A", "Countess"),
            aliases.values.map { assertIs<Value.String>(it).stringValue },
        )
        assertEquals(friend, user.fieldValues[key(schema, "friend")])
    }

    @Test
    fun `field references preserve distinct named argument tuples`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val friend = schema.objectOf("User")

        val user =
            world.objectOf("User") {
                field("friend", "limit" to 1) setTo friend
                field("friend", "limit" to 2) setTo null
            }

        val first = key(schema, "friend", "limit" to 1)
        val second = key(schema, "friend", "limit" to 2)
        assertEquals(setOf(first, second), user.fieldValues.keys)
        assertEquals(friend, user.fieldValues[first])
        assertEquals(null, user.fieldValues[second])
    }

    @Test
    fun `object values retain only explicitly supplied fields`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val userType = schema.type("User") as Schema.ObjectType
        val typenameKey = key(schema, "__typename")
        val typenameValue = Value.String.of("User")

        val implicit = Value.Object.of(userType)
        val explicit = Value.Object.of(userType, mapOf(typenameKey to typenameValue))

        assertEquals(emptyMap<Value.GroundKey, Value.Output?>(), implicit.fieldValues)
        assertEquals(
            mapOf<Value.GroundKey, Value.Output?>(typenameKey to typenameValue),
            explicit.fieldValues,
        )
    }

    @Test
    fun `nested object construction retains the shared schema context`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema

        val user =
            schema.objectOf("User") {
                "friend" setTo
                    objectOf("User") {
                        "name" setTo "Grace"
                    }
            }

        val friend =
            assertIs<Value.Object>(user.fieldValues[key(schema, "friend")])
        assertEquals(
            "Grace",
            assertIs<Value.String>(friend.fieldValues[key(schema, "name")]).stringValue,
        )
    }

    @Test
    fun `rejects ambiguous or invalid object assignments`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema

        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("User") {
                "name" setTo "Ada"
                "name" setTo "Grace"
            }
        }
        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("User") {
                field("friend", "limit" to 1, "limit" to 2) setTo null
            }
        }
        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("User") {
                "name" setTo 1
            }
        }
        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("Status")
        }
    }

    @Test
    fun `object fields reject keys owned by another concrete object type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val userType = schema.type("User") as Schema.ObjectType
        val user = schema.objectOf("User")
        val viewerKey = Value.GroundKey.of(schema.objectField("Query", "viewer"), emptyMap())

        assertFailsWith<IllegalArgumentException> {
            Value.Object.of(
                type = userType,
                fields = mapOf(viewerKey to user),
            )
        }
    }

    @Test
    fun `completed object scopes cannot mutate constructed values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        lateinit var scope: ObjectValueScope
        val user =
            schema.objectOf("User") {
                scope = this
                "name" setTo "Ada"
            }

        assertFailsWith<IllegalArgumentException> {
            with(scope) {
                "age" setTo 36
            }
        }
        assertEquals(
            setOf(key(schema, "name")),
            user.fieldValues.keys,
        )
    }

    private fun key(
        schema: Schema,
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): Value.GroundKey =
        Value.GroundKey.of(
            field = schema.objectField("User", fieldName),
            arguments = arguments.toMap(),
        )

    private companion object {
        val SCHEMA_SDL =
            """
            enum Status {
              ACTIVE
              INACTIVE
            }

            type User {
              id: ID!
              name: String!
              age: Int
              score: Float
              active: Boolean!
              status: Status!
              aliases: [String!]!
              friend(limit: Int): User
            }

            type Query {
              viewer: User
            }
            """.trimIndent()
    }
}
