package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MaterializedFieldKeyTest {
    private val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
    private val lookup = schema.objectField("Query", "lookup")

    @Test
    fun `argumentless fields use their canonical field name`() {
        val key =
            ObjectEngineResult.GroundKey.of(
                schema.objectField("Query", "plain"),
                emptyMap(),
            )

        assertEquals("plain", key.materializedFieldKey())
    }

    @Test
    fun `argument and nested input-object iteration order cannot change the address`() {
        val first =
            key(
                linkedMapOf(
                    "filter" to linkedMapOf("right" to 2, "left" to "one"),
                    "optional" to 3,
                ),
            )
        val second =
            key(
                linkedMapOf(
                    "optional" to 3,
                    "filter" to linkedMapOf("left" to "one", "right" to 2),
                ),
            )

        assertEquals(first, second)
        assertEquals(first.materializedFieldKey(), second.materializedFieldKey())
    }

    @Test
    fun `omitted arguments remain distinct from explicit null`() {
        val omitted = key(emptyMap())
        val explicitNull = key(mapOf("nullable" to null))

        assertNotEquals(omitted, explicitNull)
        assertNotEquals(omitted.materializedFieldKey(), explicitNull.materializedFieldKey())
    }

    @Test
    fun `lists retain order and strings are encoded without delimiter ambiguity`() {
        val first =
            key(
                mapOf(
                    "values" to listOf("a,b", "c):["),
                    "text" to "x=y,z",
                ),
            )
        val equal =
            key(
                linkedMapOf(
                    "text" to "x=y,z",
                    "values" to listOf("a,b", "c):["),
                ),
            )
        val reordered =
            key(
                mapOf(
                    "values" to listOf("c):[", "a,b"),
                    "text" to "x=y,z",
                ),
            )

        assertEquals(first.materializedFieldKey(), equal.materializedFieldKey())
        assertNotEquals(first.materializedFieldKey(), reordered.materializedFieldKey())
    }

    @Test
    fun `scalar kinds and values participate in the address`() {
        val first =
            key(
                mapOf(
                    "count" to 1,
                    "ratio" to -0.0,
                    "enabled" to true,
                    "id" to "1",
                    "mode" to "FAST",
                ),
            )
        val equal =
            key(
                linkedMapOf(
                    "mode" to "FAST",
                    "id" to EngineIDData("1"),
                    "enabled" to true,
                    "ratio" to -0.0,
                    "count" to 1,
                ),
            )
        val positiveZero =
            key(
                mapOf(
                    "count" to 1,
                    "ratio" to 0.0,
                    "enabled" to true,
                    "id" to "1",
                    "mode" to "FAST",
                ),
            )

        assertEquals(first.materializedFieldKey(), equal.materializedFieldKey())
        assertNotEquals(first.materializedFieldKey(), positiveZero.materializedFieldKey())
    }

    @Test
    fun `occurrence stamps are erased while field and arguments remain visible`() {
        val ordinary = key(mapOf("optional" to 7))
        val occurrence =
            ObjectEngineResult.GroundKey.of(
                stamp =
                    Stamp.Occurrence.of(
                        resolverPath = listOf(ListEngineResult.Index.of(0)),
                    ),
                field = ordinary.field,
                arguments = ordinary.arguments,
            )
        val otherField =
            ObjectEngineResult.GroundKey.of(
                schema.objectField("Query", "other"),
                mapOf("optional" to 7),
            )

        assertNotEquals(ordinary, occurrence)
        assertEquals(ordinary.materializedFieldKey(), occurrence.materializedFieldKey())
        assertNotEquals(ordinary.materializedFieldKey(), otherField.materializedFieldKey())
    }

    @Test
    fun `argument errors have one reserved address`() {
        val first =
            ObjectEngineResult.GroundKey.of(
                lookup,
                OpenArguments.Ground.Error,
            )
        val second =
            ObjectEngineResult.GroundKey.of(
                stamp =
                    Stamp.Occurrence.of(
                        resolverPath = listOf(ListEngineResult.Index.of(1)),
                    ),
                field = lookup,
                arguments = OpenArguments.Ground.Error,
            )

        assertEquals(first.materializedFieldKey(), second.materializedFieldKey())
        assertNotEquals(first.materializedFieldKey(), key(emptyMap()).materializedFieldKey())
    }

    private fun key(arguments: Map<String, Any?>): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(lookup, arguments)

    private companion object {
        val SCHEMA_SDL =
            """
            enum Mode {
              FAST
              SLOW
            }

            input Filter {
              left: String
              right: Int
            }

            type Query {
              plain: String
              lookup(
                optional: Int
                nullable: Int
                filter: Filter
                values: [String]
                text: String
                count: Int
                ratio: Float
                enabled: Boolean
                id: ID
                mode: Mode
              ): String
              other(optional: Int): String
            }
            """.trimIndent()
    }
}
