package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class EngineValueDataTest {
    @Test
    fun `simple casts preserve GraphQL string ID and enum distinctions`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val role = schema.type("Role") as Schema.EnumType

        assertEquals(
            "same",
            toEngineSimpleData(TypeExpr.Named.of(Schema.StringType), "same"),
        )
        assertEquals(
            EngineIDData("same"),
            toEngineSimpleData(TypeExpr.Named.of(Schema.IDType), "same"),
        )
        assertEquals(
            EngineEnumValueData("ADMIN", role),
            toEngineSimpleData(TypeExpr.Named.of(role), "ADMIN"),
        )
    }

    @Test
    fun `casts reject non-finite floats and enums from another type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val role = schema.type("Role") as Schema.EnumType
        val otherRole = schema.type("OtherRole") as Schema.EnumType

        assertFailsWith<ClassCastException> {
            toEngineSimpleData(TypeExpr.Named.of(Schema.FloatType), Double.NaN)
        }
        assertFailsWith<ClassCastException> {
            toEngineSimpleData(
                TypeExpr.Named.of(role),
                EngineEnumValueData("ADMIN", otherRole),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EngineEnumValueData("MISSING", role)
        }
    }

    @Test
    fun `input casts recursively copy and schema-check lists and objects`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filter = schema.type("Filter") as Schema.InputObjectType
        val role = schema.type("Role") as Schema.EnumType
        val ids = mutableListOf<Any?>("first")
        val source = mutableMapOf<String, Any?>("ids" to ids, "role" to "ADMIN")

        val converted = toEngineInputObjectData(filter, source)
        source["role"] = "MEMBER"
        ids += "second"

        val convertedIds = assertIs<EngineInputListData>(converted.getValue("ids"))
        assertNotSame(ids, convertedIds)
        assertEquals(listOf(EngineIDData("first")), convertedIds)
        assertEquals(EngineEnumValueData("ADMIN", role), converted.getValue("role"))
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
