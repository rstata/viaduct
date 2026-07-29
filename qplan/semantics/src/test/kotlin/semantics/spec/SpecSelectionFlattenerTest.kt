package semantics.spec

import model.Schema
import model.SelectionForest
import model.Value
import model.selectionsFrom
import model.spec.SpecSelection
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpecSelectionFlattenerTest {
    @Test
    fun `fields use object keys instead of response aliases`() {
        val fixture = SchemaFixture()
        val (typeInScope, selectionSet) =
            fixture.assumptions.selectionsFrom(
                """
                fragment ignored on Query {
                  release: version
                }
                """.trimIndent(),
            )
        val result =
            fixture.flatten(
                typeInScope = typeInScope,
                selectionSet = selectionSet,
            )

        val version = result.single()
        assertEquals(fixture.schema.field("Query", "version"), version.key.field)
        assertEquals(Schema.NoArguments, version.key.arguments.type)
        assertEquals(emptyMap(), version.key.arguments.fieldValues)
        assertEquals(fixture.query, version.nominalType)
        assertEquals(setOf(fixture.query), version.possibleTypes)
        assertTrue(version.isLeaf)
        assertTrue(version.subselections.isEmpty())
    }

    @Test
    fun `flattening preserves duplicate field occurrences without source order`() {
        val fixture = SchemaFixture()
        val (typeInScope, selectionSet) =
            fixture.assumptions.selectionsFrom(
                """
                fragment ignored on Query {
                  version
                  version
                }
                """.trimIndent(),
            )

        val result = fixture.flatten(typeInScope, selectionSet)
        val versionField = fixture.schema.field("Query", "version")

        assertEquals(2, result.size)
        assertTrue(result.all { it.key.field == versionField })
    }

    @Test
    fun `field arguments become values of the canonical argument definition`() {
        val fixture = SchemaFixture()
        val (typeInScope, selectionSet) =
            fixture.assumptions.selectionsFrom(
                """
                fragment ignored on Query {
                  release(channel: "beta")
                }
                """.trimIndent(),
            )

        val release =
            fixture
                .flatten(typeInScope, selectionSet)
                .single()
        val field = fixture.assumptions.schema.field("Query", "release")

        assertEquals(field, release.key.field)
        assertEquals(field.arguments, release.key.arguments.type)
        assertEquals(
            field.arguments,
            release.key.arguments.fieldValues.containingType,
        )
        assertEquals(
            "beta",
            assertIs<Value.String>(
                release.key.arguments.fieldValues["channel"],
            ).stringValue,
        )
    }

    @Test
    fun `selection keys may retain abstract nominal fields and cumulative possible types`() {
        val fixture = SchemaFixture()
        val (typeInScope, selectionSet) =
            fixture.assumptions.selectionsFrom(
                """
                fragment ignored on Query {
                  pet {
                    ... on Dog {
                      ... on Pet {
                        name
                      }
                      ... {
                        barkVolume
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
        val result =
            fixture.flatten(
                typeInScope = typeInScope,
                selectionSet = selectionSet,
            )

        val pet = result.single()
        val name = pet.subselections.single { it.key.field.fieldName == "name" }
        val barkVolume =
            pet.subselections.single { it.key.field.fieldName == "barkVolume" }

        assertEquals(fixture.pet, name.key.field.containingType)
        assertEquals(fixture.pet, name.nominalType)
        assertEquals(setOf(fixture.dog), name.possibleTypes)
        assertEquals(fixture.dog, barkVolume.key.field.containingType)
        assertEquals(fixture.dog, barkVolume.nominalType)
        assertEquals(setOf(fixture.dog), barkVolume.possibleTypes)
    }

    @Test
    fun `descending through a field resets the child type context`() {
        val fixture = SchemaFixture()
        val (typeInScope, selectionSet) =
            fixture.assumptions.selectionsFrom(
                """
                fragment ignored on Pet {
                  ... on Dog {
                    friend {
                      name
                    }
                  }
                }
                """.trimIndent(),
            )
        val result =
            fixture.flatten(
                typeInScope = typeInScope,
                selectionSet = selectionSet,
            )

        val friend = result.single()
        val name = friend.subselections.single()

        assertEquals(fixture.dog, friend.nominalType)
        assertEquals(setOf(fixture.dog), friend.possibleTypes)
        assertEquals(fixture.pet, name.nominalType)
        assertEquals(setOf(fixture.dog, fixture.cat), name.possibleTypes)
    }

    @Test
    fun `pairwise-valid nested fragments may have no cumulative possible type`() {
        val fixture = SchemaFixture()
        val (typeInScope, selectionSet) =
            fixture.assumptions.selectionsFrom(
                """
                fragment ignored on I1 {
                  ... on I2 {
                    ... on I3 {
                      x
                    }
                  }
                }
                """.trimIndent(),
            )
        val result =
            fixture.flatten(
                typeInScope = typeInScope,
                selectionSet = selectionSet,
            )

        val x = result.single()
        assertEquals(fixture.i3, x.nominalType)
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
        val i3 = schema.type("I3") as Schema.InterfaceType

        fun flatten(
            typeInScope: Schema.CompositeType,
            selectionSet: List<SpecSelection>,
        ): SelectionForest =
            context(assumptions) {
                semantics.spec.flatten(typeInScope, selectionSet)
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
