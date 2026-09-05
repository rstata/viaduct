package model.registry

import kotlin.test.Test
import kotlin.test.assertFailsWith
import model.Arguments
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument

class ParentVariableValidationAdversarialTest {
    @Test
    fun `rejects variable below parent selected through abstract coordinate`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { user: UserImpl }
                    type UserImpl {
                      child: ChildImpl
                      localizedName(locale: String!): String
                      display(locale: String!): String
                    }
                    interface ChildIface { parent: UserImpl }
                    type ChildImpl implements ChildIface {
                      parent: UserImpl @parent
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    fun resolver(fragment: model.Fragment) =
                        fieldResolverOf(fragment) { _, _ -> error("not invoked") }
                    mapOf(
                        schema.requireObjectField("Query", "user") to
                            resolver(schema.emptyFragmentOf("Query")),
                        schema.requireObjectField("UserImpl", "child") to
                            resolver(schema.emptyFragmentOf("UserImpl")),
                        schema.requireObjectField("UserImpl", "localizedName") to
                            resolver(schema.emptyFragmentOf("UserImpl")),
                        schema.requireObjectField("UserImpl", "display") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on UserImpl {
                                      child {
                                        ... on ChildIface {
                                          parent { localizedName(locale: ${'$'}locale) }
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                    )
                },
                variableProviders = { schema ->
                    val display = schema.requireObjectField("UserImpl", "display")
                    mapOf(
                        Arguments.Variable.of(display, "locale") to
                            schema.fromArgument(display, "locale"),
                    )
                },
            )
        }
    }

    @Test
    fun `rejects query fragment variable below parent selected through abstract coordinate`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query {
                      user: UserImpl
                      result(locale: String!): String
                    }
                    type UserImpl {
                      child: ChildImpl
                      localizedName(locale: String!): String
                    }
                    interface ChildIface { parent: UserImpl }
                    type ChildImpl implements ChildIface {
                      parent: UserImpl @parent
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    fun resolver(fragment: model.Fragment) =
                        fieldResolverOf(fragment) { _, _ -> error("not invoked") }
                    mapOf(
                        schema.requireObjectField("Query", "user") to
                            resolver(schema.emptyFragmentOf("Query")),
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          user {
                                            child {
                                              ... on ChildIface {
                                                parent { localizedName(locale: ${'$'}locale) }
                                              }
                                            }
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                            ) { _, _, _ -> error("not invoked") },
                        schema.requireObjectField("UserImpl", "child") to
                            resolver(schema.emptyFragmentOf("UserImpl")),
                        schema.requireObjectField("UserImpl", "localizedName") to
                            resolver(schema.emptyFragmentOf("UserImpl")),
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "locale") to
                            schema.fromArgument(result, "locale"),
                    )
                },
            )
        }
    }
}
