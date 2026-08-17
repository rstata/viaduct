package model.spec

import model.Schema
import model.SelectionForest
import model.Value
import model.fieldExpressions
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpecSelectionFlattenerTest {
    @Test
    fun `fields use object keys instead of response aliases`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.query,
                listOf(fixture.field("Query", "version", alias = "release")),
            )

        val version = result.single()
        assertEquals(fixture.schema.field("Query", "version"), version.key.field)
        assertEquals(emptyMap(), version.key.arguments.fieldExpressions())
        assertEquals(setOf(fixture.query), version.possibleTypes)
        assertTrue(version.isLeaf)
        assertTrue(version.subselections.isEmpty())
    }

    @Test
    fun `flattening preserves duplicate field occurrences without source order`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field("Query", "version"),
                    fixture.field("Query", "version"),
                ),
            )
        val versionField = fixture.schema.field("Query", "version")

        assertEquals(2, result.size)
        assertTrue(result.all { it.key.field == versionField })
    }

    @Test
    fun `field arguments become values of the canonical argument definition`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        containingType = "Query",
                        fieldName = "release",
                        arguments = mapOf("channel" to Value.String.of("beta")),
                    ),
                ),
            )

        val release = result.single()
        val field = fixture.schema.field("Query", "release")

        assertEquals(field, release.key.field)
        assertEquals(
            "beta",
            assertIs<Value.String>(
                release.key.arguments.fieldExpressions()["channel"],
            ).stringValue,
        )
    }

    @Test
    fun `selection keys may retain abstract nominal fields and cumulative possible types`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        containingType = "Query",
                        fieldName = "pet",
                        subselections =
                            listOf(
                                fixture.inlineFragment(
                                    typeCondition = fixture.dog,
                                    selections =
                                        listOf(
                                            fixture.inlineFragment(
                                                typeCondition = fixture.pet,
                                                selections =
                                                    listOf(
                                                        fixture.field("Pet", "name"),
                                                    ),
                                            ),
                                            fixture.inlineFragment(
                                                typeCondition = null,
                                                selections =
                                                    listOf(
                                                        fixture.field("Dog", "barkVolume"),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val pet = result.single()
        val name =
            pet.subselections.filter { it.key.field.fieldName == "name" }.single()
        val barkVolume =
            pet.subselections.filter { it.key.field.fieldName == "barkVolume" }.single()

        assertEquals(fixture.pet, name.key.field.containingType)
        assertEquals(setOf(fixture.dog), name.possibleTypes)
        assertEquals(fixture.dog, barkVolume.key.field.containingType)
        assertEquals(setOf(fixture.dog), barkVolume.possibleTypes)
    }

    @Test
    fun `descending through a field resets the child type context`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.pet,
                listOf(
                    fixture.inlineFragment(
                        typeCondition = fixture.dog,
                        selections =
                            listOf(
                                fixture.field(
                                    containingType = "Dog",
                                    fieldName = "friend",
                                    subselections =
                                        listOf(
                                            fixture.field("Pet", "name"),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val friend = result.single()
        val name = friend.subselections.single()

        assertEquals(fixture.dog, friend.key.field.containingType)
        assertEquals(setOf(fixture.dog), friend.possibleTypes)
        assertEquals(fixture.pet, name.key.field.containingType)
        assertEquals(setOf(fixture.dog, fixture.cat), name.possibleTypes)
    }

    @Test
    fun `pairwise-valid nested fragments may have no cumulative possible type`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.i1,
                listOf(
                    fixture.inlineFragment(
                        typeCondition = fixture.i2,
                        selections =
                            listOf(
                                fixture.inlineFragment(
                                    typeCondition = fixture.i3,
                                    selections =
                                        listOf(
                                            fixture.field("I3", "x"),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val x = result.single()
        assertEquals(fixture.i3, x.key.field.containingType)
        assertTrue(x.possibleTypes.isEmpty())
    }

    private class SchemaFixture {
        private val world = TestWorld.fromSDL(SCHEMA_SDL)
        val assumptions = world.assumptions
        val schema = world.schema

        val query = schema.query
        val dog = schema.type("Dog") as Schema.ObjectType
        val cat = schema.type("Cat") as Schema.ObjectType
        val pet = schema.type("Pet") as Schema.InterfaceType
        val i1 = schema.type("I1") as Schema.InterfaceType
        val i2 = schema.type("I2") as Schema.InterfaceType
        val i3 = schema.type("I3") as Schema.InterfaceType

        fun field(
            containingType: String,
            fieldName: String,
            alias: String? = null,
            arguments: Map<String, Value.Input?> = emptyMap(),
            subselections: List<SpecSelection>? = null,
        ): SpecSelection.Field =
            SpecSelection.Field.of(
                alias = alias,
                field = schema.field(containingType, fieldName),
                arguments = arguments,
                subselections = subselections,
            )

        fun inlineFragment(
            typeCondition: Schema.CompositeType?,
            selections: List<SpecSelection>,
        ): SpecSelection.InlineFragment =
            SpecSelection.InlineFragment.of(typeCondition, selections)

        fun flatten(
            typeInScope: Schema.CompositeType,
            selectionSet: List<SpecSelection>,
        ): SelectionForest =
            context(assumptions) {
                model.spec.flatten(typeInScope, selectionSet)
            }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Pet {
              name: String!
            }

            type Dog implements Pet {
              name: String!
              barkVolume: Int
              friend: Pet
            }

            type Cat implements Pet {
              name: String!
            }

            interface I1 {
              i1: String
            }

            interface I2 {
              i2: String
            }

            interface I3 {
              x: String
            }

            type A implements I1 {
              i1: String
            }

            type B implements I1 & I2 {
              i1: String
              i2: String
            }

            type C implements I2 & I3 {
              i2: String
              x: String
            }

            type D implements I3 {
              x: String
            }

            type Query {
              version: String
              release(channel: String): String
              pet: Pet
              pairwise: I1
            }
            """.trimIndent()
    }
}
