package semantics.contract

import model.requireQueryTypeDef
import model.requireObjectField
import model.EngineResult
import model.ObjectEngineResult
import model.Schema
import model.instantiateBindings
import model.merge
import model.operationSelectionsFrom
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
    fun `disjoint successor demand is closed before one selective producer application`() {
        var fooApplications = 0
        var fooDemandFields: Set<String>? = null
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      foo: Foo! @resolver(result: {z: 2, w: 3})
                    }

                    type Foo {
                      x: Int!
                        @resolver(of: "z", result: "sum(z, z, z, z, z)")
                      y: Int!
                        @resolver(of: "w", result: "sum(w, w, w, w, w, w, w)")
                      z: Int!
                      w: Int!
                    }
                    """.trimIndent(),
                applicationObserver = { field, _, _, demand ->
                    if (
                        field.containingDef.name == "Query" &&
                        field.name == "foo" &&
                        demand != null
                    ) {
                        fooApplications += 1
                        fooDemandFields =
                            demand
                                .merge(field.type.baseType as Schema.Object)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { groundKey ->
                                    groundKey.field.name
                                }
                    }
                },
            )
        val world = testWorld.assumptions
        val fooKey = world.schema.contractKey("Query", "foo")
        val xKey = world.schema.contractKey("Foo", "x")
        val yKey = world.schema.contractKey("Foo", "y")
        val selections =
            world.operationSelectionsFrom(
                "query { foo { x y } }",
            )

        val resolved =
            resolveAndValidate(world, selections)
        val foo = resolved.getCell(fooKey).get() as ObjectEngineResult

        assertEquals(10, foo.getCell(xKey).get())
        assertEquals(21, foo.getCell(yKey).get())
        assertEquals(1, fooApplications)
        assertEquals(setOf("x", "y", "z", "w"), fooDemandFields)
        assertTrue(
            context(world) {
                resolved.correctResolution(
                    selections
                        .merge(world.schema.requireQueryTypeDef())
                        .instantiateBindings(),
                )
            },
        )
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
                        field.containingDef.name == "Mid" &&
                        field.name == "node" &&
                        demand != null
                    ) {
                        nodeApplications += 1
                        nodeDemandFields =
                            demand
                                .merge(field.type.baseType as Schema.Object)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { groundKey ->
                                    groundKey.field.name
                                }
                    }
                },
            )
        val world = testWorld.assumptions
        val triggerKey = world.schema.contractKey("Query", "trigger")

        val resolved = resolveAndValidate(world, "query { trigger }")

        assertEquals(2, resolved.getCell(triggerKey).get())
        assertEquals(1, nodeApplications)
        assertEquals(setOf("first", "second"), nodeDemandFields)
        testWorld.applicationArguments.assertArguments(
            world.schema.requireObjectField("Query", "late"),
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
                        field.containingDef.name == "Query" &&
                        field.name == "parent" &&
                        demand != null
                    ) {
                        parentApplications += 1
                        parentDemandFields =
                            linkedSetOf<String>().also { fields ->
                                demand
                                    .merge(field.type.baseType as Schema.Object)
                                    .forEach { selection ->
                                        fields += selection.key.field.name
                                    }
                            }
                    }
                },
            )
        val world = testWorld.assumptions
        val lateKey = world.schema.contractKey("Query", "late")

        val resolved = resolveAndValidate(world, "query { late }")

        assertEquals(7, resolved.getCell(lateKey).get())
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
            world.schema.requireObjectField("Payload", "computed"),
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
                        when (field.containingDef.name to field.name) {
                            "Query" to "holder" -> holderApplications += 1
                            "Nested" to "computed" -> computedApplications += 1
                        }
                    }
                },
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")

        val resolved = resolveAndValidate(world, "query { result }")

        assertEquals(7, resolved.getCell(resultKey).get())
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
                        when (field.containingDef.name to field.name) {
                            "Query" to "parent" -> parentApplications += 1
                            "Parent" to "child" -> childApplications += 1
                        }
                    }
                },
            )
        val world = testWorld.assumptions
        val outerKey = world.schema.contractKey("Query", "outer")
        val parentKey = world.schema.contractKey("Query", "parent")

        val resolved = resolveAndValidate(world, "query { early outer }")
        val parent = resolved.getCell(parentKey).get() as ObjectEngineResult

        assertEquals(1, resolved.getCell(outerKey).get())
        assertEquals(1, parentApplications)
        val expectedChildApplications =
            when (variableSelectionIdentityPolicy) {
                VariableSelectionIdentityPolicy.MERGE_EQUAL_GROUNDED_KEYS -> 1
                VariableSelectionIdentityPolicy.PRESERVE_RESPONSE_GROUP_OCCURRENCES -> 2
            }
        assertEquals(expectedChildApplications, childApplications)
        assertEquals(
            expectedChildApplications,
            parent.keys.count { groundKey -> groundKey.field.name == "child" },
        )
    }
}
