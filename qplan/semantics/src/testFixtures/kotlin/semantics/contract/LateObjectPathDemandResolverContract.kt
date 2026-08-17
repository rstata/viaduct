package semantics.contract

import model.EngineResult
import model.Schema
import model.Value
import model.fragmentFrom
import model.merge
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

enum class LateAncestorDemandPolicy {
    RETAIN_OPEN_VARIABLE_BOUNDARY,
    CONTRIBUTE_PASSIVE_PREDECESSORS,
}

interface LateObjectPathDemandResolverContract : ResolverContract {
    val variableSelectionIdentityPolicy: VariableSelectionIdentityPolicy
    val lateAncestorDemandPolicy: LateAncestorDemandPolicy

    @Test
    fun `successor passive demand is materialized from selective resolver output`() {
        var payloadDemandFields: Set<String>? = null
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      payload: Payload! @resolver(result: {raw: 7})
                    }

                    type Payload {
                      computed: Int!
                        @resolver(of: "raw", result: "sum(raw)")
                      raw: Int!
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, demand ->
                    if (
                        field.containingType.typeName == "Query" &&
                        field.fieldName == "payload" &&
                        demand != null
                    ) {
                        payloadDemandFields =
                            demand
                                .merge(field.typeExpr.baseType as Schema.ObjectType)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { groundKey ->
                                    groundKey.field.fieldName
                                }
                    }
                },
            )
        val world = testWorld.assumptions
        val payloadKey = world.schema.contractKey("Query", "payload")
        val computedKey = world.schema.contractKey("Payload", "computed")
        val rawKey = world.schema.contractKey("Payload", "raw")
        val fragment =
            world.fragmentFrom(
                "fragment Query on Query { payload { computed } }",
            )

        val resolved =
            resolveAndValidate(world, fragment)
        val payload = resolved.getCell(payloadKey).get() as EngineResult.Object

        assertEquals(Value.Int.of(7), payload.getCell(computedKey).get())
        assertEquals(Value.Int.of(7), payload.getCell(rawKey).get())
        assertEquals(setOf("computed", "raw"), payloadDemandFields)
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }

    @Test
    fun `open resolver template closes demand before an argumentless descendant launches`() {
        var nodeApplications = 0
        var nodeDemandFields: Set<String>? = null
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      trigger: Int!
                        @resolver(
                          of: "late(arg: ${'$'}value) seed { node { first } }"
                          pathVars: [{name: "value", path: ["seed", "node", "first"]}]
                          result: "sum(late)"
                        )
                      late(arg: Int!): Int!
                        @resolver(
                          of: "seed { node { second } }"
                          result: "sum(seed.node.second)"
                        )
                      seed: Mid! @resolver(result: {})
                    }

                    type Mid {
                      node: Leaf! @resolver(result: {first: 1, second: 2})
                    }

                    type Leaf {
                      first: Int!
                      second: Int!
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, demand ->
                    if (
                        field.containingType.typeName == "Mid" &&
                        field.fieldName == "node" &&
                        demand != null
                    ) {
                        nodeApplications += 1
                        nodeDemandFields =
                            demand
                                .merge(field.typeExpr.baseType as Schema.ObjectType)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { groundKey ->
                                    groundKey.field.fieldName
                                }
                    }
                },
            )
        val world = testWorld.assumptions
        val triggerKey = world.schema.contractKey("Query", "trigger")

        val resolved = resolveAndValidate(world, "fragment Query on Query { trigger }")

        assertEquals(Value.Int.of(2), resolved.getCell(triggerKey).get())
        assertEquals(1, nodeApplications)
        assertEquals(setOf("first", "second"), nodeDemandFields)
        testWorld.applicationArguments.assertArguments(
            world.schema.objectField("Query", "late"),
            mapOf("arg" to 1),
        )
    }

    @Test
    fun `future variable boundary selects its passive predecessors`() {
        var parentApplications = 0
        var parentDemandFields: Set<String>? = null
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      late: Int!
                        @resolver(
                          of: "provider parent { computed(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["provider"]}]
                          result: "sum(parent.computed)"
                        )
                      provider: Int! @resolver(result: 7)
                      parent: Payload! @resolver(result: {source: 7})
                    }

                    type Payload {
                      source: Int!
                      computed(value: Int!): Int!
                        @resolver(of: "source", result: "sum(source)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, demand ->
                    if (
                        field.containingType.typeName == "Query" &&
                        field.fieldName == "parent" &&
                        demand != null
                    ) {
                        parentApplications += 1
                        parentDemandFields =
                            linkedSetOf<String>().also { fields ->
                                demand
                                    .merge(field.typeExpr.baseType as Schema.ObjectType)
                                    .forEach { selection ->
                                        fields += selection.key.field.fieldName
                                    }
                            }
                    }
                },
            )
        val world = testWorld.assumptions
        val lateKey = world.schema.contractKey("Query", "late")

        val resolved = resolveAndValidate(world, "fragment Query on Query { late }")

        assertEquals(Value.Int.of(7), resolved.getCell(lateKey).get())
        assertEquals(1, parentApplications)
        assertEquals(
            when (lateAncestorDemandPolicy) {
                LateAncestorDemandPolicy.RETAIN_OPEN_VARIABLE_BOUNDARY ->
                    setOf("source", "computed")
                LateAncestorDemandPolicy.CONTRIBUTE_PASSIVE_PREDECESSORS ->
                    setOf("source")
            },
            parentDemandFields,
        )
        testWorld.applicationArguments.assertArguments(
            world.schema.objectField("Payload", "computed"),
            mapOf("value" to 7),
        )
    }

    @Test
    fun `late variable selection crosses a passive object field`() {
        var holderApplications = 0
        var computedApplications = 0
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "provider holder { nested { computed(value: ${'$'}value) } }"
                          pathVars: [{name: "value", path: ["provider"]}]
                          result: "sum(holder.nested.computed)"
                        )
                      provider: Int! @resolver(result: 7)
                      holder: Holder! @resolver(result: {nested: {}})
                    }

                    type Holder {
                      nested: Nested!
                    }

                    type Nested {
                      computed(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, demand ->
                    if (demand != null) {
                        when (field.containingType.typeName to field.fieldName) {
                            "Query" to "holder" -> holderApplications += 1
                            "Nested" to "computed" -> computedApplications += 1
                        }
                    }
                },
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")

        val resolved = resolveAndValidate(world, "fragment Query on Query { result }")

        assertEquals(Value.Int.of(7), resolved.getCell(resultKey).get())
        assertEquals(1, holderApplications)
        assertEquals(1, computedApplications)
    }

    @Test
    fun `late equal child call follows the configured identity policy`() {
        var parentApplications = 0
        var childApplications = 0
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      early: Int!
                        @resolver(
                          of: "parent { child(value: 1) }"
                          result: "sum(parent.child)"
                        )
                      outer: Int!
                        @resolver(
                          of: "source parent { child(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(parent.child)"
                        )
                      source: Int!
                        @resolver(of: "delay", result: "sum(delay)")
                      delay: Int! @resolver(result: 1)
                      parent: Parent! @resolver(result: {})
                    }

                    type Parent {
                      child(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, demand ->
                    if (demand != null) {
                        when (field.containingType.typeName to field.fieldName) {
                            "Query" to "parent" -> parentApplications += 1
                            "Parent" to "child" -> childApplications += 1
                        }
                    }
                },
            )
        val world = testWorld.assumptions
        val outerKey = world.schema.contractKey("Query", "outer")
        val parentKey = world.schema.contractKey("Query", "parent")

        val resolved = resolveAndValidate(world, "fragment ignored on Query { early outer }")
        val parent = resolved.getCell(parentKey).get() as EngineResult.Object

        assertEquals(Value.Int.of(1), resolved.getCell(outerKey).get())
        assertEquals(1, parentApplications)
        val expectedChildApplications =
            when (variableSelectionIdentityPolicy) {
                VariableSelectionIdentityPolicy.MERGE_EQUAL_GROUNDED_KEYS -> 1
                VariableSelectionIdentityPolicy.PRESERVE_SELECTION_OCCURRENCES -> 2
            }
        assertEquals(expectedChildApplications, childApplications)
        assertEquals(
            expectedChildApplications,
            parent.keys.count { groundKey -> groundKey.field.fieldName == "child" },
        )
    }
}
