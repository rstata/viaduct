package semantics

import model.Assumptions
import model.EngineResult
import model.Schema
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import semantics.resolver01.resolve as resolve01
import semantics.resolver02.resolve as resolve02
import semantics.resolver03.resolve as resolve03
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NodeBridgeResolutionTest {
    @Test
    fun `resolver01 dispatches argument-bearing abstract node bridge lists`() {
        assertNodeDispatch { world, root, selections ->
            context(world) {
                root.resolve01(selections)
            }
        }
    }

    @Test
    fun `resolver02 dispatches argument-bearing abstract node bridge lists`() {
        assertNodeDispatch { world, root, selections ->
            context(world) {
                root.resolve02(selections)
            }
        }
    }

    @Test
    fun `resolver03 dispatches argument-bearing abstract node bridge lists`() {
        assertNodeDispatch { world, root, selections ->
            context(world) {
                root.resolve03(selections)
            }
        }
    }

    @Test
    fun `resolver03 dispatches every nested node-list bridge occurrence`() {
        val observedFields = mutableListOf<String>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                      name: String!
                    }

                    type Query {
                      matrix: [[User!]!]!
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, _ ->
                    observedFields += field.fieldName
                },
                nodeResolvers = { schema ->
                    mapOf(
                        schema.objectType("User") to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-${id.idValue}"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val matrix = schema.field("Query", "matrix")
                    val outer = matrix.typeExpr as TypeExpr.List<Schema.OutputType>
                    val inner = outer.elementType as TypeExpr.List<Schema.OutputType>
                    fun row(vararg ids: String): Value.OutputList =
                        Value.OutputList.of(
                            typeExpr = inner.elementType,
                            values =
                                ids.map { id ->
                                    schema.objectOf("User") {
                                        "id" setTo id
                                    }
                                },
                        )
                    mapOf(
                        matrix to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    Value.OutputList.of(
                                        typeExpr = outer.elementType,
                                        values = listOf(row("a", "b"), row("c")),
                                    )
                                },
                            ),
                    )
                },
            )
        val world = testWorld.newAssumptions()
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  matrix {
                    id
                    name
                  }
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve03(fragment.subselections)
            }

        val matrix =
            assertIs<EngineResult.List>(
                result.fetch(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "matrix\$bridge"),
                        emptyMap(),
                    ),
                ).value,
            )
        val payloadTypes =
            matrix.map { rowCell ->
                assertIs<EngineResult.List>(rowCell.value).map { bridgeCell ->
                    val bridge = assertIs<EngineResult.Object>(bridgeCell.value)
                    assertIs<EngineResult.Object>(
                        bridge.fetch(
                            Value.GroundKey.of(
                                world.schema.objectField("User\$Bridge", "\$node"),
                                emptyMap(),
                            ),
                        ).value,
                    ).type.typeName
                }
            }.flatten()
        assertEquals(listOf("User", "User", "User"), payloadTypes)
        assertEquals(3, observedFields.count { it == "\$node" })
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    private fun assertNodeDispatch(
        resolve:
            (
                Assumptions,
                Value.Object,
                SelectionForest,
            ) -> EngineResult.Object,
    ) {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = { schema ->
                    mapOf(
                        schema.objectType("User") to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-${id.idValue}"
                                }
                            },
                        schema.objectType("Admin") to
                            model.testing.nodeResolverOf { id ->
                                schema.objectOf("Admin") {
                                    "id" setTo id
                                    "level" setTo 7
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val nodes = schema.field("Query", "nodes")
                    val elementType =
                        (nodes.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    mapOf(
                        nodes to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, arguments ->
                                    val group =
                                        arguments.fieldValues.getValue("group") as Value.String
                                    Value.OutputList.of(
                                        typeExpr = elementType,
                                        values =
                                            listOf(
                                                schema.objectOf("User") {
                                                    "id" setTo "${group.stringValue}-user"
                                                },
                                                schema.objectOf("Admin") {
                                                    "id" setTo "${group.stringValue}-admin"
                                                },
                                            ),
                                    )
                                },
                            ),
                    )
                },
            )
        val world = testWorld.newAssumptions()
        val schema = world.schema
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  first: nodes(group: "first") {
                    id
                    ... on User {
                      name
                    }
                    ... on Admin {
                      level
                    }
                  }
                  second: nodes(group: "second") {
                    id
                  }
                }
                """.trimIndent(),
            )

        val result =
            resolve(
                world,
                world.objectOf("Query"),
                fragment.subselections,
            )

        val bridge = schema.objectField("Query", "nodes\$bridge")
        val bridgeType = schema.objectType("Node\$Bridge")
        val payload = schema.objectField("Node\$Bridge", "\$node")
        val firstKey = Value.GroundKey.of(bridge, mapOf("group" to "first"))
        val secondKey = Value.GroundKey.of(bridge, mapOf("group" to "second"))
        assertEquals(
            setOf(firstKey, secondKey),
            result.keys,
        )

        val first = assertIs<EngineResult.List>(result.fetch(firstKey).value)
        assertEquals(
            listOf("User", "Admin"),
            first.map { cell ->
                val bridgeObject = assertIs<EngineResult.Object>(cell.value)
                assertEquals(bridgeType, bridgeObject.type)
                assertIs<EngineResult.Object>(
                    bridgeObject.fetch(Value.GroundKey.of(payload, emptyMap())).value,
                ).type.typeName
            },
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
            }

            type Admin implements Node {
              id: ID!
              level: Int!
            }

            type Query {
              nodes(group: String!): [Node!]!
            }
            """.trimIndent()
    }
}

private fun Schema.objectType(typeName: String): Schema.ObjectType =
    type(typeName) as Schema.ObjectType
