package semantics.resolver26

import model.EngineResult
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LateStampedDemandTest {
    @Test
    fun `successor passive demand is materialized from selective resolver output`() {
        var payloadDemandFields: Set<String>? = null
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      computed: Int!
                      raw: Int!
                    }

                    type Query {
                      payload: Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val payloadType = schema.type("Payload") as Schema.ObjectType
                    val rawKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "raw"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "payload") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "raw" setTo 7
                                }
                            }.observeApplications { _, _, demand ->
                                if (demand != null) {
                                    payloadDemandFields =
                                        demand
                                            .merge(payloadType)
                                            .groundKeys()
                                            .mapTo(linkedSetOf()) { groundKey ->
                                                groundKey.field.fieldName
                                            }
                                }
                            },
                        schema.objectField("Payload", "computed") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Computed on Payload { raw }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(rawKey)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val payloadKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "payload"),
                emptyMap(),
            )
        val computedKey =
            Value.GroundKey.of(
                world.schema.objectField("Payload", "computed"),
                emptyMap(),
            )
        val rawKey =
            Value.GroundKey.of(
                world.schema.objectField("Payload", "raw"),
                emptyMap(),
            )
        val fragment =
            world.fragmentFrom(
                "fragment Query on Query { payload { computed } }",
            )

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val payload = resolved.getValue(payloadKey).get() as EngineResult.Object

        assertEquals(Value.Int.of(7), payload.getValue(computedKey).get())
        assertEquals(Value.Int.of(7), payload.getValue(rawKey).get())
        assertEquals(setOf("computed", "raw"), payloadDemandFields)
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }

    @Test
    fun `open resolver template closes demand before an argumentless descendant launches`() {
        var nodeApplications = 0
        var nodeDemandFields: Set<String>? = null
        val triggerFragment =
            """
            fragment Trigger on Query {
              late(arg: ${'$'}value)
              seed {
                node {
                  first
                }
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Leaf {
                      first: Int!
                      second: Int!
                    }

                    type Mid {
                      node: Leaf!
                    }

                    type Query {
                      trigger: Int!
                      late(arg: Int!): Int!
                      seed: Mid!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val seedKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "seed"),
                            emptyMap(),
                        )
                    val lateKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "late"),
                            mapOf("arg" to 1),
                        )
                    val nodeKey =
                        Value.GroundKey.of(
                            schema.objectField("Mid", "node"),
                            emptyMap(),
                        )
                    val secondKey =
                        Value.GroundKey.of(
                            schema.objectField("Leaf", "second"),
                            emptyMap(),
                        )
                    val leafType = schema.type("Leaf") as Schema.ObjectType
                    mapOf(
                        schema.objectField("Query", "trigger") to
                            fieldResolverOf(schema.fragmentFrom(triggerFragment)) { input, _ ->
                                input.fieldValues.getValue(lateKey)
                            },
                        schema.objectField("Query", "late") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Late on Query { seed { node { second } } }",
                                ),
                            ) { input, _ ->
                                val seed = input.fieldValues.getValue(seedKey) as Value.Object
                                val node = seed.fieldValues.getValue(nodeKey) as Value.Object
                                node.fieldValues.getValue(secondKey)
                            },
                        schema.objectField("Query", "seed") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Mid") {}
                            },
                        schema.objectField("Mid", "node") to
                            fieldResolverOf(schema.emptyFragmentOf("Mid")) { _, _ ->
                                schema.objectOf("Leaf") {
                                    "first" setTo 1
                                    "second" setTo 2
                                }
                            }.observeApplications { _, _, demand ->
                                if (demand != null) {
                                    nodeApplications += 1
                                    nodeDemandFields =
                                        demand
                                            .merge(leafType)
                                            .groundKeys()
                                            .mapTo(linkedSetOf()) { groundKey ->
                                                groundKey.field.fieldName
                                            }
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val trigger = schema.objectField("Query", "trigger")
                    mapOf(
                        Value.Variable.of(trigger, "value") to
                            schema.fromObjectField(
                                triggerFragment,
                                listOf("seed", "node", "first"),
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val triggerKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "trigger"),
                emptyMap(),
            )

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(
                    world.fragmentFrom(
                        "fragment Query on Query { trigger }",
                    ).subselections,
                )
            }

        assertEquals(Value.Int.of(2), resolved.getValue(triggerKey).get())
        assertEquals(1, nodeApplications)
        assertEquals(setOf("first", "second"), nodeDemandFields)
    }

    @Test
    fun `future variable boundary selects its passive predecessors`() {
        var parentApplications = 0
        var parentDemandFields: Set<String>? = null
        val lateFragment =
            """
            fragment Late on Query {
              provider
              parent {
                computed(value: ${'$'}value)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      source: Int!
                      computed(value: Int!): Int!
                    }

                    type Query {
                      late: Int!
                      provider: Int!
                      parent: Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val payloadType = schema.type("Payload") as Schema.ObjectType
                    val parentKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "parent"),
                            emptyMap(),
                        )
                    val computed = schema.objectField("Payload", "computed")
                    val computedKey = Value.GroundKey.of(computed, mapOf("value" to 7))
                    val sourceKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "source"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "late") to
                            fieldResolverOf(schema.fragmentFrom(lateFragment)) { input, _ ->
                                val parent = input.fieldValues.getValue(parentKey) as Value.Object
                                parent.fieldValues.getValue(computedKey)
                            },
                        schema.objectField("Query", "provider") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(7)
                            },
                        schema.objectField("Query", "parent") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "source" setTo 7
                                }
                            }.observeApplications { _, _, demand ->
                                if (demand != null) {
                                    parentApplications += 1
                                    parentDemandFields =
                                        demand
                                            .merge(payloadType)
                                            .groundKeys()
                                            .mapTo(linkedSetOf()) { groundKey ->
                                                groundKey.field.fieldName
                                            }
                                }
                            },
                        computed to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Computed on Payload { source }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(sourceKey)
                            },
                    )
                },
                variableProviders = { schema ->
                    val late = schema.objectField("Query", "late")
                    mapOf(
                        Value.Variable.of(late, "value") to
                            schema.fromObjectField(lateFragment, listOf("provider")),
                    )
                },
            )
        val world = testWorld.assumptions
        val lateKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "late"),
                emptyMap(),
            )

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(
                    world.fragmentFrom(
                        "fragment Query on Query { late }",
                    ).subselections,
                )
            }

        assertEquals(Value.Int.of(7), resolved.getValue(lateKey).get())
        assertEquals(1, parentApplications)
        assertEquals(setOf("source"), parentDemandFields)
    }

    @Test
    fun `late variable selection crosses a passive object field`() {
        var holderApplications = 0
        var computedApplications = 0
        val resultFragment =
            """
            fragment Result on Query {
              provider
              holder {
                nested {
                  computed(value: ${'$'}value)
                }
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Nested {
                      computed(value: Int!): Int!
                    }

                    type Holder {
                      nested: Nested!
                    }

                    type Query {
                      result: Int!
                      provider: Int!
                      holder: Holder!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val holderKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "holder"),
                            emptyMap(),
                        )
                    val nestedKey =
                        Value.GroundKey.of(
                            schema.objectField("Holder", "nested"),
                            emptyMap(),
                        )
                    val computed = schema.objectField("Nested", "computed")
                    val computedKey = Value.GroundKey.of(computed, mapOf("value" to 7))
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val holder = input.fieldValues.getValue(holderKey) as Value.Object
                                val nested =
                                    holder.fieldValues.getValue(nestedKey) as Value.Object
                                nested.fieldValues.getValue(computedKey)
                            },
                        schema.objectField("Query", "holder") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Holder") {
                                    "nested" setTo schema.objectOf("Nested")
                                }
                            }.observeApplications { _, _, demand ->
                                if (demand != null) holderApplications += 1
                            },
                        schema.objectField("Query", "provider") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(7)
                            },
                        computed to
                            fieldResolverOf(schema.emptyFragmentOf("Nested")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            }.observeApplications { _, _, demand ->
                                if (demand != null) computedApplications += 1
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(
                                resultFragment,
                                listOf("provider"),
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "result"),
                emptyMap(),
            )

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(
                    world.fragmentFrom(
                        "fragment Query on Query { result }",
                    ).subselections,
                )
            }

        assertEquals(Value.Int.of(7), resolved.getValue(resultKey).get())
        assertEquals(1, holderApplications)
        assertEquals(1, computedApplications)
    }

    @Test
    fun `late equal child call stays separate below a published argumentless parent`() {
        var parentApplications = 0
        var childApplications = 0
        val outerFragment =
            """
            fragment Outer on Query {
              source
              parent {
                child(value: ${'$'}value)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Parent {
                      child(value: Int!): Int!
                    }

                    type Query {
                      early: Int!
                      outer: Int!
                      source: Int!
                      delay: Int!
                      parent: Parent!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val parentKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "parent"),
                            emptyMap(),
                        )
                    val child = schema.objectField("Parent", "child")
                    val childKey = Value.GroundKey.of(child, mapOf("value" to 1))
                    mapOf(
                        schema.objectField("Query", "outer") to
                            fieldResolverOf(schema.fragmentFrom(outerFragment)) { input, _ ->
                                val parent = input.fieldValues.getValue(parentKey) as Value.Object
                                parent.fieldValues.getValue(childKey)
                            },
                        schema.objectField("Query", "early") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Early on Query { parent { child(value: 1) } }",
                                ),
                            ) { input, _ ->
                                val parent = input.fieldValues.getValue(parentKey) as Value.Object
                                parent.fieldValues.getValue(childKey)
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Source on Query { delay }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(
                                    Value.GroundKey.of(
                                        schema.objectField("Query", "delay"),
                                        emptyMap(),
                                    ),
                                )
                            },
                        schema.objectField("Query", "delay") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "parent") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Parent")
                            }.observeApplications { _, _, demand ->
                                if (demand != null) parentApplications += 1
                            },
                        child to
                            fieldResolverOf(schema.emptyFragmentOf("Parent")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            }.observeApplications { _, _, demand ->
                                if (demand != null) childApplications += 1
                            },
                    )
                },
                variableProviders = { schema ->
                    val outer = schema.objectField("Query", "outer")
                    mapOf(
                        Value.Variable.of(outer, "value") to
                            schema.fromObjectField(outerFragment, listOf("source")),
                    )
                },
            )
        val world = testWorld.assumptions
        val outerKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "outer"),
                emptyMap(),
            )
        val parentKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "parent"),
                emptyMap(),
            )

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(
                    world.fragmentFrom(
                        "fragment ignored on Query { early outer }",
                    ).subselections,
                )
            }
        val parent = resolved.getValue(parentKey).get() as EngineResult.Object

        assertEquals(Value.Int.of(1), resolved.getValue(outerKey).get())
        assertEquals(1, parentApplications)
        assertEquals(2, childApplications)
        assertEquals(
            2,
            parent.keys.count { groundKey -> groundKey.field.fieldName == "child" },
        )
    }
}
