package semantics

import model.EngineResult
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolveValueTest {
    @Test
    fun `constructs typename directly and returns collapsed resolver paths`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Profile {
                      raw: String!
                      rendered: String!
                    }

                    type User {
                      name: String!
                      profile: Profile!
                      computed: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "user") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.field("User", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> Value.String.of("computed") },
                        schema.field("Profile", "rendered") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> Value.String.of("rendered") },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val userType = schema.type("User") as Schema.ObjectType
        val profileType = schema.type("Profile") as Schema.ObjectType
        val typeNameKey = Value.Key.of(schema.field("User", "__typename"), emptyMap())
        val computedKey = Value.Key.of(schema.field("User", "computed"), emptyMap())
        val profileKey = Value.Key.of(schema.field("User", "profile"), emptyMap())
        val rawKey = Value.Key.of(schema.field("Profile", "raw"), emptyMap())
        val value =
            schema.objectOf("User") {
                "name" setTo "Ada"
                "profile" setTo
                    objectOf("Profile") {
                        "raw" setTo "engineer"
                    }
            }
        val selections =
            world.fragmentFrom(
                """
                fragment ignored on User {
                  __typename
                  name
                  computed
                  profile {
                    raw
                    rendered
                  }
                }
                """.trimIndent(),
            ).subselections

        val resolved =
            context(world) {
                value.resolveValue(selections)
            }

        val result = assertIs<EngineResult.Object>(resolved.engineResult)
        val typeName =
            assertIs<Value.String>(
                result.fetch(typeNameKey).value,
            )
        assertEquals("User", typeName.stringValue)
        assertTrue(computedKey !in result.keys)

        val profile = assertIs<EngineResult.Object>(result.fetch(profileKey).value)
        assertEquals(userType, result.type)
        assertEquals(profileType, profile.type)
        assertEquals(setOf(rawKey), profile.keys)
        assertEquals(
            setOf(emptyList(), listOf(profileKey)),
            resolved.pathsNeedingResolution.keys,
        )
        assertEquals(4, resolved.pathsNeedingResolution.getValue(emptyList()).size)
        assertEquals(2, resolved.pathsNeedingResolution.getValue(listOf(profileKey)).size)
    }
}
