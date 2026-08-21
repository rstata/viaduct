package model.lowering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoweredSourceCoordinatesTest {
    private val lowered = lowerSchema(graphQLSchema(SCHEMA))

    @Test
    fun `ordinary source field resolves to the same lowered coordinate`() {
        val field = lowered.loweredFieldFromSourceCoordinate("Query", "count")

        assertSame(lowered.requireField("Query", "count"), field)
        assertFalse(field.isLoweredNodeBridgeField())
        assertSame(field.type, lowered.sourceTypeExpr(field))
    }

    @Test
    fun `Node source field resolves to its lowered bridge producer`() {
        val field = lowered.loweredFieldFromSourceCoordinate("Query", "users")

        assertSame(lowered.requireField("Query", nodeBridgeFieldName("users")), field)
        assertSame(field, lowered.loweredNodeBridgeField("Query", "users"))
        assertTrue(field.isLoweredNodeBridgeField())
    }

    @Test
    fun `typename resolves through its lowered owner`() {
        assertSame(
            lowered.requireField("User", LOWERED_TYPENAME_FIELD),
            lowered.loweredTypenameField("User"),
        )
        assertSame(
            lowered.requireField("Named", LOWERED_TYPENAME_FIELD),
            lowered.loweredFieldFromSourceCoordinate("Named", "__typename"),
        )
        assertSame(
            lowered.requireField(ALL_SOURCE_OBJECTS_TYPE, LOWERED_TYPENAME_FIELD),
            lowered.loweredFieldFromSourceCoordinate("SearchResult", "__typename"),
        )
    }

    @Test
    fun `synthetic coordinates are not accepted as source coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            lowered.loweredFieldFromSourceCoordinate(ALL_SOURCE_OBJECTS_TYPE, "__typename")
        }
        assertFailsWith<IllegalArgumentException> {
            lowered.loweredFieldFromSourceCoordinate("Query", LOWERED_TYPENAME_FIELD)
        }
    }

    @Test
    fun `Node bridge source type preserves source type and every wrapper`() {
        val bridgeField = lowered.loweredNodeBridgeField("Query", "users")
        val sourceType = lowered.sourceTypeExpr(bridgeField)

        assertSame(lowered.requireType("User"), sourceType.baseTypeDef)
        assertEquals(bridgeField.type.nullabilityShape(), sourceType.nullabilityShape())
        assertEquals(2, sourceType.listDepth)
    }

    private companion object {
        val SCHEMA =
            """
            interface Node {
              id: ID!
            }

            interface Named {
              name: String!
            }

            union SearchResult = User | Photo

            type User implements Node & Named {
              id: ID!
              name: String!
            }

            type Photo implements Named {
              name: String!
            }

            type Query {
              count: Int!
              users: [[User!]!]
              search: SearchResult
            }
            """
    }
}
