package semantics.spec

import com.google.inject.AbstractModule
import com.google.inject.Guice
import model.Assumptions
import model.GJSchema
import model.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpecSelectionFlattenerTest {
    @Test
    fun `fields use OER keys instead of response aliases`() {
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
            flattener(fixture.assumptions).flatten(
                typeInScope = typeInScope,
                selectionSet = selectionSet,
            )

        val version = result.single()
        assertEquals("version", version.key.fieldName)
        assertEquals(emptyMap(), version.key.arguments)
        assertSame(fixture.query, version.nominalType)
        assertEquals(setOf(fixture.query), version.possibleTypes)
        assertNull(version.subselections)
    }

    @Test
    fun `nested fragments retain the immediate nominal type and cumulative possible types`() {
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
            flattener(fixture.assumptions).flatten(
                typeInScope = typeInScope,
                selectionSet = selectionSet,
            )

        val pet = result.single()
        val name = pet.subselections.orEmpty().single { it.key.fieldName == "name" }
        val barkVolume =
            pet.subselections.orEmpty().single { it.key.fieldName == "barkVolume" }

        assertSame(fixture.pet, name.nominalType)
        assertEquals(setOf(fixture.dog), name.possibleTypes)
        assertSame(fixture.dog, barkVolume.nominalType)
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
            flattener(fixture.assumptions).flatten(
                typeInScope = typeInScope,
                selectionSet = selectionSet,
            )

        val friend = result.single()
        val name = friend.subselections.orEmpty().single()

        assertSame(fixture.dog, friend.nominalType)
        assertEquals(setOf(fixture.dog), friend.possibleTypes)
        assertSame(fixture.pet, name.nominalType)
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
            flattener(fixture.assumptions).flatten(
                typeInScope = typeInScope,
                selectionSet = selectionSet,
            )

        val x = result.single()
        assertSame(fixture.i3, x.nominalType)
        assertTrue(x.possibleTypes.isEmpty())
    }

    private fun flattener(assumptions: Assumptions): SpecSelectionFlattener {
        val injector =
            Guice.createInjector(
                object : AbstractModule() {
                    override fun configure() {
                        bind(Assumptions::class.java).toInstance(assumptions)
                    }
                },
            )

        return injector.getInstance(SpecSelectionFlattener::class.java)
    }

    private class SchemaFixture {
        val assumptions = Assumptions.of(GJSchema.fromSDL(SCHEMA_SDL), emptyMap())
        private val schema = assumptions.schema

        val query = schema.query
        val dog = schema.type("Dog") as Schema.ObjectType
        val cat = schema.type("Cat") as Schema.ObjectType
        val pet = schema.type("Pet") as Schema.InterfaceType
        val i1 = schema.type("I1") as Schema.InterfaceType
        val i3 = schema.type("I3") as Schema.InterfaceType
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
              pet: Pet
              pairwise: I1
            }
            """.trimIndent()
    }
}
