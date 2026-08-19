package semantics.contract

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface ObjectPathNodeInteractionResolverContract : ResolverContract {
    @Test
    fun `retains potential demand through a passive node bridge`() {
        var triggerInputKeys: Set<String>? = null
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(result: {profile: {id: "profile"}})
                      source: Int! @resolver(result: 7)
                      driver: Profile!
                        @resolver(
                          of: "source item { trigger(value: ${'$'}sourceValue) }"
                          pathVars: [{name: "sourceValue", path: ["source"]}]
                          result: {id: "driver"}
                        )
                    }

                    type Item {
                      profile: Profile!
                      trigger(value: Int!): Int!
                        @resolver(
                          of: "account: profile { details { related { __typename } } }"
                          result: 7
                        )
                    }

                    type Profile implements Node
                      @nodeResolver(
                        result: [
                          {
                            id: "profile"
                            result: {details: {related: {id: "related"}}}
                          }
                          {id: "related", result: {}}
                          {id: "driver", result: {}}
                        ]
                      ) {
                      id: ID!
                      details: Details!
                    }

                    type Details {
                      related: Profile!
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    if (
                        field.containingType.typeName == "Item" &&
                        field.fieldName == "trigger"
                    ) {
                        triggerInputKeys = input.selectionValues().keys
                    }
                },
            )
        val world = testWorld.assumptions

        resolveAndValidate(
            world,
            """
                    fragment ignored on Query {
                      item {
                        profile {
                          details { __typename }
                        }
                      }
                      driver { id }
                    }
            """.trimIndent(),
        )

        testWorld.applicationArguments.assertApplicationCount(
            world.schema.objectField("Query", "driver_V_A_node"),
            1,
        )
        assertEquals(setOf("account"), triggerInputKeys)
    }
}
