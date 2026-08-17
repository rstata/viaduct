package model.testing

import model.ObjectEngineResult

import model.Schema
import model.SourceSchemaAdapter
import model.Value
import model.fieldExpressions
import model.objectOf
import model.registry.VariableDefinition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ResolverTestDslTest {
    @Test
    fun `evaluates argument and list-crossing field expressions`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container!
                    @resolver(result: {values: [{value: 2}, null, {value: 3}]})
                }

                type Container {
                  values: [Item]!
                  total(extra: Int!): Int!
                    @resolver(
                      of: "values { value }"
                      result: "sumplus1(values.value, ${'$'}extra)"
                    )
                }

                type Item {
                  value: Int!
                }
                """.trimIndent(),
            )
        val schema = world.schema
        val containerField = schema.objectField("Query", "container")
        val container =
            assertIs<Value.Object>(
                world.apply(containerField),
            )
        val total = schema.objectField("Container", "total")

        val result = world.apply(total, container, mapOf("extra" to 4))

        assertEquals(Value.Int.of(10), result)
        assertEquals(
            listOf(Value.Arguments.of(total, mapOf("extra" to 4))),
            world.applicationArguments.arguments(total),
        )
        assertEquals(
            mapOf<Schema.OutputField, List<Value.Arguments>>(
                containerField to
                    listOf(Value.Arguments.of(containerField, emptyMap())),
                total to listOf(Value.Arguments.of(total, mapOf("extra" to 4))),
            ),
            world.applicationArguments.all(),
        )
    }

    @Test
    fun `infers argument variables and compiles explicit path variables`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int!
                    @resolver(
                      of: "source byArg(value: ${'$'}seed) byPath(value: ${'$'}fromSource)"
                      pathVars: [{name: "fromSource", path: ["source"]}]
                      result: "sum(byArg, byPath)"
                    )
                  source: Int! @resolver(result: 7)
                  byArg(value: Int!): Int! @resolver(result: "sum(${ '$' }value)")
                  byPath(value: Int!): Int! @resolver(result: "sum(${ '$' }value)")
                }
                """.trimIndent(),
            )
        val result = world.schema.objectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(result)
        val seed = Value.Variable.of(result, "seed")
        val fromSource = Value.Variable.of(result, "fromSource")

        assertIs<VariableDefinition.FromArgument>(resolver.variables.getValue(seed))
        assertIs<VariableDefinition.FromObjectField>(resolver.variables.getValue(fromSource))
    }

    @Test
    fun `supports explicit null and error results`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  nullable: Int @resolver(result: null)
                  failed: Int! @resolver(result: "ERROR")
                }
                """.trimIndent(),
            )
        val root = world.resolverRegistry.resolveRootQuery()
        val nullable = world.schema.objectField("Query", "nullable")
        val failed = world.schema.objectField("Query", "failed")

        assertNull(world.apply(nullable, root))
        assertEquals(Value.Error, world.apply(failed, root))
    }

    @Test
    fun `compiles error sentinels in object-fragment arguments`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int!
                    @resolver(
                      of: "dependency(arg: \"ERROR\")"
                      result: 1
                    )
                  dependency(arg: Int!): Int! @resolver(result: 2)
                }
                """.trimIndent(),
            )
        val result = world.schema.objectField("Query", "result")
        val dependency =
            world.resolverRegistry
                .resolver(result)
                .objectFragment
                .single()

        assertEquals(
            Value.Error,
            dependency.key.arguments.fieldExpressions().getValue("arg"),
        )
    }

    @Test
    fun `value expressions preserve integer null and error values`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  echo(input: Int): Int
                    @resolver(result: "value(${'$'}input)")
                  echoPath: Int
                    @resolver(of: "source", result: "value(source)")
                  source: Int @resolver(result: null)
                }
                """.trimIndent(),
            )
        val root = world.resolverRegistry.resolveRootQuery()
        val echo = world.schema.objectField("Query", "echo")

        listOf<Value.Input?>(Value.Int.of(4), null, Value.Error).forEach { value ->
            assertEquals(
                value as Value.Output?,
                world.apply(echo, root, mapOf("input" to value)),
            )
        }

        val echoPath = world.schema.objectField("Query", "echoPath")
        assertNull(
            world.apply(
                echoPath,
                world.schema.objectOf("Query") {
                    "source" setTo null
                },
            ),
        )
    }

    @Test
    fun `uses typename only to select a non-Node abstract result`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  subject: Subject!
                    @resolver(result: {__typename: "Person", value: 9})
                }

                interface Subject {
                  value: Int!
                }

                type Person implements Subject {
                  value: Int!
                }
                """.trimIndent(),
            )
        val subject = world.schema.objectField("Query", "subject")

        val result =
            assertIs<Value.Object>(
                world.apply(subject),
            )

        assertEquals("Person", result.type.typeName)
        assertEquals(
            Value.Int.of(9),
            result.fieldValues.getValue(
                ObjectEngineResult.GroundKey.of(world.schema.objectField("Person", "value"), emptyMap()),
            ),
        )
    }

    @Test
    fun `resolves Node references using an ID argument`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  viewer(id: ID!): User!
                    @resolver(result: {id: "idFrom(${'$'}id)"})
                }

                type User implements Node
                  @nodeResolver(
                    result: [
                      {id: "user-1", result: {score: 7}},
                      {id: "user-2", result: {score: 8}}
                    ]
                  ) {
                  id: ID!
                  score: Int!
                }
                """.trimIndent(),
            )
        val schema = world.schema
        val viewer = schema.objectField("Query", "viewer_V_A_node")
        val bridge =
            assertIs<Value.Object>(
                world.apply(viewer, arguments = mapOf("id" to Value.ID.of("user-2"))),
            )
        val node = schema.objectField("User_V_A_Bridge", "node")
        val user =
            assertIs<Value.Object>(
                world.apply(node, bridge),
            )

        assertEquals(
            Value.ID.of("user-2"),
            user.fieldValues.getValue(
                ObjectEngineResult.GroundKey.of(schema.objectField("User", "id"), emptyMap()),
            ),
        )
        assertEquals(
            Value.Int.of(8),
            user.fieldValues.getValue(
                ObjectEngineResult.GroundKey.of(schema.objectField("User", "score"), emptyMap()),
            ),
        )
        val sourceSchema = SourceSchemaAdapter(schema)
        assertEquals(viewer, sourceSchema.field("Query", "viewer"))
    }

    @Test
    fun `requires a result argument even though it is nullable`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      value: Int @resolver
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(true, exception.message.orEmpty().contains("requires result"))
    }

    @Test
    fun `rejects resolver-owned fields in object results`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  item: Item! @resolver(result: {computed: 2})
                }

                type Item {
                  computed: Int! @resolver(result: 1)
                }
                """.trimIndent(),
            )
        val item = world.schema.objectField("Query", "item")

        assertThrows<IllegalArgumentException> {
            world.apply(item)
        }
    }
}

private fun TestWorld.apply(
    field: Schema.ObjectField,
    input: Value.Object = resolverRegistry.resolveRootQuery(),
    arguments: Map<String, Any?> = emptyMap(),
): Value.Output? =
    resolverRegistry.resolver(field)(
        input,
        Value.Arguments.of(field, arguments),
    )
