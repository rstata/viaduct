package model.registry

import model.Fragment
import model.Schema
import model.Selection
import model.Value
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
                schema.field("Query", "consumer").demandsFromSibling(
                    schema.key(schema.query, "sibling", mapOf("input" to 1)),
                )
            },
        )
        assertFalse(
            context(world) {
                schema.field("Query", "consumer").demandsFromSibling(
                    schema.key(schema.query, "other"),
                )
            },
        )
        assertFalse(
            context(world) {
                schema.field("Query", "consumer").demandsFromSibling(
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
                schema.field("Query", "consumer").demandsFromSibling(
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
                schema.field("Query", "consumer").demandsFromSibling(
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
                val query = schema.query
                val payload = schema.type("Payload") as Schema.ObjectType
                val siblingSelection =
                    Selection.of(
                        key = schema.key(query, "sibling", mapOf("input" to 1)),
                        nominalType = query,
                        possibleTypes = setOf(query),
                        subselections =
                            selectionForestOf(
                                Selection.of(
                                    key = schema.key(payload, "nested"),
                                    nominalType = payload,
                                    possibleTypes = setOf(payload),
                                    subselections = selectionForestOf(),
                                ),
                            ),
                    )
                val inapplicableSelection =
                    Selection.of(
                        key = schema.key(query, "other"),
                        nominalType = query,
                        possibleTypes = emptySet(),
                        subselections = selectionForestOf(),
                    )
                val consumerFragment =
                    Fragment.of(
                        nominalType = query,
                        subselections =
                            if (includeInapplicableSelection) {
                                selectionForestOf(inapplicableSelection)
                            } else {
                                selectionForestOf(siblingSelection)
                            },
                    )
                val emptyFragment = Fragment.of(query, selectionForestOf())
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
