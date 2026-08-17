package semantics.contract

import model.EngineResult
import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

interface AcyclicVariableDependencyResolverContract : ResolverContract {
    @Test
    fun `accepts an acyclic path-variable dependency chain`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      pa: Int! @resolver(result: 1)
                      b(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      a(seed: Int!): Int!
                        @resolver(
                          of: "pa b(value: ${'$'}fromPa)"
                          pathVars: [{name: "fromPa", path: ["pa"]}]
                          result: "sum(b)"
                        )
                      q: Int! @resolver(of: "a(seed: 1)", result: "sum(a)")
                      p2: Int! @resolver(result: 1)
                      d(seed: Int!): Int!
                        @resolver(
                          of: "p2 a(seed: ${'$'}fromP2)"
                          pathVars: [{name: "fromP2", path: ["p2"]}]
                          result: "sum(a)"
                        )
                      c: Int!
                        @resolver(
                          of: "q d(seed: ${'$'}fromQ)"
                          pathVars: [{name: "fromQ", path: ["q"]}]
                          result: "sum(d)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val cKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "c"),
                emptyMap(),
            )

        val resolved =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom("fragment ignored on Query { c }"),
            )

        assertEquals(Value.Int.of(1), resolved.getCell(cKey).get())
    }

    @Test
    fun `accepts an acyclic mixed-variable dependency chain`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      outer: Int!
                        @resolver(
                          of: "q5 q2(value: ${'$'}fromQ5)"
                          pathVars: [{name: "fromQ5", path: ["q5"]}]
                          result: "sum(q2)"
                        )
                      q1(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      q2(value: Int!): Int!
                        @resolver(
                          of: "q7(value: ${'$'}value)"
                          result: "sum(q7)"
                        )
                      q5: Int! @resolver(of: "q1(value: 1)", result: "sum(q1)")
                      q7(value: Int!): Int!
                        @resolver(
                          of: "q1(value: ${'$'}value)"
                          result: "sum(q1)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val outerKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "outer"),
                emptyMap(),
            )

        val resolved: EngineResult.Object =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom("fragment ignored on Query { outer }"),
            )

        assertEquals(Value.Int.of(1), resolved.getCell(outerKey).get())
    }
}
