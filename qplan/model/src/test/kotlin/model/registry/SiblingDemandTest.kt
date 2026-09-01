package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.requireQueryTypeDef
import model.requireObjectField
import model.requireField
import model.requireType
import model.ObjectEngineResult
import model.Fragment
import model.Selection
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.testRoot
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
                schema.key(schema.requireQueryTypeDef(), "consumer").demandsFromSibling(
                    schema.key(schema.requireQueryTypeDef(), "sibling", mapOf("input" to 1)),
                    schema.testRoot(),
                )
            },
        )
        assertFalse(
            context(world) {
                schema.key(schema.requireQueryTypeDef(), "consumer").demandsFromSibling(
                    schema.key(schema.requireQueryTypeDef(), "other"),
                    schema.testRoot(),
                )
            },
        )
        assertFalse(
            context(world) {
                schema.key(schema.requireQueryTypeDef(), "consumer").demandsFromSibling(
                    schema.key(schema.requireQueryTypeDef(), "sibling", mapOf("input" to 2)),
                    schema.testRoot(),
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
                schema.key(schema.requireQueryTypeDef(), "consumer").demandsFromSibling(
                    schema.key(schema.requireQueryTypeDef(), "other"),
                    schema.testRoot(),
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
                schema.key(schema.requireQueryTypeDef(), "consumer").demandsFromSibling(
                    schema.key(
                        schema.requireType("Payload") as ViaductSchema.Object,
                        "nested",
                    ),
                    schema.testRoot(),
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
                        val query = schema.requireQueryTypeDef()
                        Fragment.of(
                            nominalType = query,
                            subselections =
                                selectionForestOf(
                                    Selection.of(
                                        key = schema.key(query, "other"),
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
                    schema.requireField("Query", "consumer") to
                        fieldResolverOf(
                            objectFragment = consumerFragment,
                            function = { _, _ -> "consumer" },
                        ),
                    schema.requireField("Query", "sibling") to
                        fieldResolverOf(
                            objectFragment = emptyFragment,
                            function = { _, _ -> schema.objectOf("Payload") },
                        ),
                    schema.requireField("Query", "other") to
                        fieldResolverOf(
                            objectFragment = emptyFragment,
                            function = { _, _ -> "other" },
                        ),
                )
            },
        )

    private fun ViaductSchema.key(
        type: ViaductSchema.Object,
        fieldName: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(
            field = requireObjectField(type.name, fieldName),
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
