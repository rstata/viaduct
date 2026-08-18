package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.IntEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.instantiateBindings
import model.localizeTopLevelSelectionStamps
import model.merge
import model.objectOf
import model.stampedVariables
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArgumentStampingTest {
    @Test
    fun `grounding and localization commute when each stamp has its binding`() {
        val resultFragment: String =
            """
            fragment Result on Query {
              box {
                child(value: ${'$'}seed)
              }
            }
            """.trimIndent()
        val testWorld: TestWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Box {
                      child(value: Int!): Int!
                    }

                    type Query {
                      result(seed: Int!): Int!
                      box: Box!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { _, _ ->
                                Value.Int.of(0)
                            },
                        schema.objectField("Query", "box") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Box")
                            },
                        schema.objectField("Box", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Box")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                    )
                },
            )
        val world: Assumptions = testWorld.assumptions
        val resultField: Schema.ObjectField =
            world.schema.objectField("Query", "result")
        val resultKey: ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 7))
        val boxType: Schema.ObjectType =
            world.schema.type("Box") as Schema.ObjectType
        val boxKey: ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "box"),
                emptyMap(),
            )
        val childDemand: SelectionForest =
            world.resolverRegistry
                .resolver(resultField)
                .stamp(listOf(resultKey))
                .merge(world.schema.type("Query") as Schema.ObjectType)[boxKey]
                .subselections
        val sourceVariable: Value.Variable =
            childDemand.stampedVariables().single()
        world.bindVariable(sourceVariable, Value.Int.of(7))

        val groundThenLocalize: ObjectEngineResult.GroundKey =
            context(world) {
                childDemand
                    .merge(boxType)
                    .instantiateBindings()
                    .localizeTopLevelSelectionStamps(listOf(boxKey))
                    .merge(boxType)
                    .groundKeys()
                    .single()
            }
        val localizedDemand: SelectionForest =
            childDemand.localizeTopLevelSelectionStamps(listOf(boxKey))
        val localizedVariable: Value.Variable =
            localizedDemand.stampedVariables().single()
        val localizedOpenKey =
            assertIs<ObjectEngineResult.Key.Stamped>(
                localizedDemand.merge(boxType).keys().single(),
            )
        assertEquals(
            localizedOpenKey.selectionStamp,
            localizedVariable.selectionStamp,
        )
        world.bindVariable(localizedVariable, Value.Int.of(7))
        val localizeThenGround: ObjectEngineResult.GroundKey =
            context(world) {
                localizedDemand
                    .merge(boxType)
                    .instantiateBindings()
                    .groundKeys()
                    .single()
            }

        assertEquals(groundThenLocalize, localizeThenGround)
        assertEquals(
            listOf(resultKey, boxKey),
            assertIs<ObjectEngineResult.GroundKey.Stamped>(localizeThenGround)
                .selectionStamp
                .resolverPath,
        )
    }

    @Test
    fun `list element resolver instances include their concrete indices in selection stamps`() {
        val resultFragment =
            """
            fragment Result on Query {
              items {
                child(value: ${'$'}seed)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      child(value: Int!): Int!
                    }

                    type Query {
                      result(seed: Int!): Int!
                      items: [Item!]!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.objectField("Query", "result")
                    val items = schema.objectField("Query", "items")
                    val itemsKey = ObjectEngineResult.GroundKey.of(items, emptyMap())
                    val itemType =
                        (items.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                    val child = schema.objectField("Item", "child")
                    val visibleChildKey = ObjectEngineResult.GroundKey.of(child, mapOf("value" to 7))
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val itemValues =
                                    input.fieldValues.getValue(itemsKey) as Value.OutputList
                                Value.Int.of(
                                    itemValues.values.sumOf { value ->
                                        val item = value as Value.Object
                                        val childValue =
                                            item.fieldValues.getValue(visibleChildKey) as Value.Int
                                        childValue.intValue
                                    },
                                )
                            },
                        items to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.OutputList.of(
                                    typeExpr = itemType,
                                    values =
                                        listOf(
                                            schema.objectOf("Item"),
                                            schema.objectOf("Item"),
                                        ),
                                )
                            },
                        child to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "result"),
                mapOf("seed" to 7),
            )
        val itemsKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "items"),
                emptyMap(),
            )
        val fragment =
            world.fragmentFrom(
                "fragment Query on Query { result(seed: 7) }",
            )

        val resolved =
            context(world) {
                resolve(fragment.subselections)
            }
        val items = assertIs<ListEngineResult>(resolved.getCell(itemsKey).getValue().get())
        val stamps =
            items.indices.map { index ->
                val item =
                    assertIs<ObjectEngineResult>(items[index].getValue().get())
                val childKey =
                    item.keys.single { groundKey ->
                        groundKey.field.fieldName == "child"
                    }
                assertIs<ObjectEngineResult.GroundKey.Stamped>(childKey).selectionStamp
            }

        assertEquals(IntEngineResult.of(14), resolved.getCell(resultKey).getValue().get())
        assertEquals(
            listOf(
                listOf(resultKey, itemsKey, ListEngineResult.Index.of(0)),
                listOf(resultKey, itemsKey, ListEngineResult.Index.of(1)),
            ),
            stamps.map { stamp -> stamp.resolverPath },
        )
        assertEquals(2, stamps.toSet().size)
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }

    @Test
    fun `every variable selection remains separate after grounding`() {
        var frankApplications = 0
        val frankDemandFields = mutableListOf<Set<String>>()
        val resultFragment =
            """
            fragment Result on Query {
              frank(arg: "hi") { one }
              frank(arg: ${'$'}seed) { two }
              frank(arg: ${'$'}seed) { two }
              frank(arg: ${'$'}other) { one }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      one: Int!
                      two: Int!
                    }

                    type Query {
                      result(seed: String!, other: String!): Int!
                      frank(arg: String!): Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val frank = schema.objectField("Query", "frank")
                    val frankKey = ObjectEngineResult.GroundKey.of(frank, mapOf("arg" to "hi"))
                    val oneKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.objectField("Payload", "one"),
                            emptyMap(),
                        )
                    val twoKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.objectField("Payload", "two"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val payload = input.fieldValues.getValue(frankKey) as Value.Object
                                val one = payload.fieldValues.getValue(oneKey) as Value.Int
                                val two = payload.fieldValues.getValue(twoKey) as Value.Int
                                Value.Int.of(one.intValue + two.intValue)
                            },
                        frank to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            }.observeApplications { _, arguments, demand ->
                                if (demand != null) {
                                    frankApplications += 1
                                    frankDemandFields +=
                                        demand
                                            .merge(
                                                schema.type("Payload") as Schema.ObjectType,
                                            ).groundKeys()
                                            .mapTo(linkedSetOf()) { groundKey ->
                                                groundKey.field.fieldName
                                            }
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                        Value.Variable.of(result, "other") to
                            schema.fromArgument(result, "other"),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "result"),
                mapOf(
                    "seed" to "hi",
                    "other" to "hi",
                ),
            )
        val fragment =
            world.fragmentFrom(
                """fragment Query on Query { result(seed: "hi", other: "hi") }""",
            )

        val resolved =
            context(world) {
                resolve(fragment.subselections)
            }

        assertEquals(IntEngineResult.of(8), resolved.getCell(resultKey).getValue().get())
        assertEquals(4, frankApplications)
        assertEquals(
            mapOf(
                setOf("one") to 2,
                setOf("two") to 2,
            ),
            frankDemandFields.groupingBy { fields -> fields }.eachCount(),
        )
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }

    @Test
    fun `equal grounded calls from different resolver instances remain separate`() {
        val frankArguments = mutableListOf<Value.Arguments>()
        val frankDemandFields = mutableListOf<Set<String>>()
        val leftFragment =
            """fragment Left on Query { frank(arg: ${'$'}seed) { one } }"""
        val rightFragment =
            """fragment Right on Query { frank(arg: ${'$'}seed) { two } }"""
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      one: Int!
                      two: Int!
                    }

                    type Query {
                      left(seed: String!): Int!
                      right(seed: String!): Int!
                      frank(arg: String!): Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val payloadType = schema.type("Payload") as Schema.ObjectType
                    val frank = schema.objectField("Query", "frank")
                    val frankKey = ObjectEngineResult.GroundKey.of(frank, mapOf("arg" to "hi"))
                    val oneKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.objectField("Payload", "one"),
                            emptyMap(),
                        )
                    val twoKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.objectField("Payload", "two"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "left") to
                            fieldResolverOf(schema.fragmentFrom(leftFragment)) { input, _ ->
                                val payload = input.fieldValues.getValue(frankKey) as Value.Object
                                payload.fieldValues.getValue(oneKey)
                            },
                        schema.objectField("Query", "right") to
                            fieldResolverOf(schema.fragmentFrom(rightFragment)) { input, _ ->
                                val payload = input.fieldValues.getValue(frankKey) as Value.Object
                                payload.fieldValues.getValue(twoKey)
                            },
                        frank to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            }.observeApplications { _, arguments, demand ->
                                if (demand != null) {
                                    frankArguments += arguments
                                    frankDemandFields +=
                                        demand
                                            .merge(payloadType)
                                            .groundKeys()
                                            .mapTo(linkedSetOf()) { groundKey ->
                                                groundKey.field.fieldName
                                            }
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val left = schema.objectField("Query", "left")
                    val right = schema.objectField("Query", "right")
                    mapOf(
                        Value.Variable.of(left, "seed") to schema.fromArgument(left, "seed"),
                        Value.Variable.of(right, "seed") to schema.fromArgument(right, "seed"),
                    )
                },
            )
        val world = testWorld.assumptions
        val leftKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "left"),
                mapOf("seed" to "hi"),
            )
        val rightKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "right"),
                mapOf("seed" to "hi"),
            )
        val fragment =
            world.fragmentFrom(
                """
                fragment Query on Query {
                  left(seed: "hi")
                  right(seed: "hi")
                }
                """.trimIndent(),
            )

        val resolved =
            context(world) {
                resolve(fragment.subselections)
            }
        val frankKeys =
            resolved.keys.filter { groundKey -> groundKey.field.fieldName == "frank" }

        assertEquals(IntEngineResult.of(3), resolved.getCell(leftKey).getValue().get())
        assertEquals(IntEngineResult.of(5), resolved.getCell(rightKey).getValue().get())
        assertEquals(2, frankKeys.size)
        frankKeys.forEach { groundKey ->
            val stampedKey = assertIs<ObjectEngineResult.GroundKey.Stamped>(groundKey)
            assertTrue(stampedKey.selectionStamp.sourceKey !is ObjectEngineResult.Key.Stamped)
        }
        assertEquals(
            listOf(
                Value.Arguments.of(
                    world.schema.objectField("Query", "frank"),
                    mapOf("arg" to "hi"),
                ),
                Value.Arguments.of(
                    world.schema.objectField("Query", "frank"),
                    mapOf("arg" to "hi"),
                ),
            ),
            frankArguments,
        )
        assertEquals(
            setOf(setOf("one"), setOf("two")),
            frankDemandFields.toSet(),
        )
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }
}
