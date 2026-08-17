package semantics.contract

import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface PassiveFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `closes potential demand before descending through a passive object`() {
        val applications = linkedMapOf<String, Int>()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      container: Container!
                        @resolver(result: {bridge: {id: 1}})
                      result(value: Int!): Int!
                        @resolver(
                          of: "container { trigger(value: ${'$'}value) }"
                          result: "sum(container.trigger)"
                        )
                    }

                    type Container {
                      bridge: Bridge!
                      trigger(value: Int!): Int!
                        @resolver(
                          of: "bridge { load { __typename } }"
                          result: 1
                        )
                    }

                    type Bridge {
                      id: Int!
                      load: Entity!
                        @resolver(of: "id", result: {name: 2})
                    }

                    type Entity {
                      name: Int!
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, _ ->
                    applications.merge(field.fieldName, 1, Int::plus)
                },
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom(
                    """
                    fragment ignored on Query {
                      container {
                        bridge {
                          load { name }
                        }
                      }
                      result(value: 7)
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            Value.Int.of(1),
            resolved.getCell(
                Value.GroundKey.of(
                    world.schema.objectField("Query", "result"),
                    mapOf("value" to 7),
                ),
            ).get(),
        )
        assertEquals(1, applications.getValue("load"))
        assertEquals(1, applications.getValue("trigger"))
    }
}
