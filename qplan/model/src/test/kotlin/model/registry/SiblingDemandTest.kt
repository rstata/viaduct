package model.registry

import model.Fragment
import model.Schema
import model.Selection
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.selectionForestOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiblingDemandTest {
    @Test
    fun `field demands an applicable top-level sibling selected by its object fragment`() {
        val world = testWorld().assumptions
        val schema = world.schema

        assertTrue(
            context(world) {
                schema.key(schema.query, "consumer").demandsFromSibling(
                    schema.key(schema.query, "sibling", mapOf("input" to 1)),
                )
            },
        )
        assertFalse(
            context(world) {
                schema.key(schema.query, "consumer").demandsFromSibling(
                    schema.key(schema.query, "other"),
                )
            },
        )
        assertFalse(
            context(world) {
                schema.key(schema.query, "consumer").demandsFromSibling(
                    schema.key(schema.query, "sibling", mapOf("input" to 2)),
                )
            },
        )
    }

    @Test
    fun `field does not demand a sibling hidden by an inapplicable type condition`() {
        val world = testWorld(includeInapplicableSelection = true).assumptions
        val schema = world.schema

        assertFalse(
            context(world) {
                schema.key(schema.query, "consumer").demandsFromSibling(
                    schema.key(schema.query, "other"),
                )
            },
        )
    }

    @Test
    fun `sibling demand is undefined across object types`() {
        val world = testWorld().assumptions
        val schema = world.schema

        assertFailsWith<IllegalArgumentException> {
            context(world) {
                schema.key(schema.query, "consumer").demandsFromSibling(
                    schema.key(
                        schema.type("Payload") as Schema.ObjectType,
                        "nested",
                    ),
                )
            }
        }
    }

    private fun testWorld(
        includeInapplicableSelection: Boolean = false,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val consumerFragment =
                    if (includeInapplicableSelection) {
                        val query = schema.query
                        Fragment.of(
                            nominalType = query,
                            subselections =
                                selectionForestOf(
                                    Selection.of(
                                        key = schema.key(query, "other"),
                                        nominalType = query,
                                        possibleTypes = emptySet(),
                                        subselections = selectionForestOf(),
                                    ),
                                ),
                        )
                    } else {
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              sibling(input: 1) {
                                nested
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                val emptyFragment = schema.emptyFragmentOf("Query")
                mapOf(
                    schema.field("Query", "consumer") to
                        model.testing.fieldResolverOf(
                            objectFragment = consumerFragment,
                            function = { _, _ -> Value.String.of("consumer") },
                        ),
                    schema.field("Query", "sibling") to
                        model.testing.fieldResolverOf(
                            objectFragment = emptyFragment,
                            function = { _, _ -> schema.objectOf("Payload") },
                        ),
                    schema.field("Query", "other") to
                        model.testing.fieldResolverOf(
                            objectFragment = emptyFragment,
                            function = { _, _ -> Value.String.of("other") },
                        ),
                )
            },
        )

    private fun Schema.key(
        type: Schema.ObjectType,
        fieldName: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): Value.Key =
        Value.Key.of(
            field = field(type.typeName, fieldName),
            arguments = arguments,
        )

    private companion object {
        val SCHEMA_SDL =
            """
            type Payload {
              nested: String
            }

            type Query {
              consumer: String
              sibling(input: Int): Payload
              other: String
            }
            """.trimIndent()
    }
}
