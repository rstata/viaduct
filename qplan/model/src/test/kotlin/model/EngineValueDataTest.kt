package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class EngineValueDataTest {
    @Test
    fun `simple casts use production-compatible strings for String ID and enum`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val role = schema.requireType("Role") as Schema.Enum
        val otherRole = schema.requireType("OtherRole") as Schema.Enum

        assertEquals(
            "same",
            toEngineSimpleData(TypeExpr.Named.of(Schema.StringType), "same"),
        )
        assertEquals(
            "same",
            toEngineSimpleData(TypeExpr.Named.of(Schema.IDType), "same"),
        )
        assertEquals(
            "ADMIN",
            toEngineSimpleData(TypeExpr.Named.of(role), "ADMIN"),
        )
        assertEquals(
            "ADMIN",
            toEngineSimpleData(TypeExpr.Named.of(otherRole), "ADMIN"),
        )
    }

    @Test
    fun `casts reject non-finite floats and unknown enum names`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val role = schema.requireType("Role") as Schema.Enum

        assertFailsWith<ClassCastException> {
            toEngineSimpleData(TypeExpr.Named.of(Schema.FloatType), Double.NaN)
        }
        assertFailsWith<ClassCastException> {
            toEngineSimpleData(TypeExpr.Named.of(Schema.IDType), 1)
        }
        assertFailsWith<ClassCastException> {
            toEngineSimpleData(
                TypeExpr.Named.of(role),
                "MISSING",
            )
        }
    }

    @Test
    fun `input casts recursively copy and schema-check lists and objects`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filter = schema.requireType("Filter") as Schema.Input
        val ids = mutableListOf<Any?>("first")
        val source = mutableMapOf<String, Any?>("ids" to ids, "role" to "ADMIN")

        val converted = toEngineInputObjectData(filter, source)
        source["role"] = "MEMBER"
        ids += "second"

        val convertedIds = assertIs<EngineInputListData>(converted.getValue("ids"))
        assertNotSame(ids, convertedIds)
        assertEquals(listOf("first"), convertedIds)
        assertEquals("ADMIN", converted.getValue("role"))
    }

    @Test
    fun `input casts require an actual list`() {
        val listType =
            TypeExpr.List.of(
                elementType = TypeExpr.Named.of(Schema.IntType, isNullable = false),
            )

        assertFailsWith<ClassCastException> {
            toEngineInputData(listType, 1)
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            enum Role {
              MEMBER
              ADMIN
            }

            enum OtherRole {
              ADMIN
            }

            input Filter {
              ids: [ID!]!
              role: Role!
            }

            type Query {
              value(filter: Filter): Int
            }
            """.trimIndent()
    }
}
