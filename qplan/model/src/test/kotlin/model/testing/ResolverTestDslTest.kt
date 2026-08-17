package model.testing

import model.Schema
import model.SourceSchemaAdapter
import model.Value
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
                world.resolverRegistry.resolver(containerField)(
                    world.resolverRegistry.resolveRootQuery(),
                    Value.Arguments.of(containerField, emptyMap()),
                ),
            )
        val total = schema.objectField("Container", "total")

        val result =
            world.resolverRegistry.resolver(total)(
                container,
                Value.Arguments.of(total, mapOf("extra" to 4)),
            )

        assertEquals(Value.Int.of(10), result)
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

        assertNull(
            world.resolverRegistry.resolver(nullable)(
                root,
                Value.Arguments.of(nullable, emptyMap()),
            ),
        )
        assertEquals(
            Value.Error,
            world.resolverRegistry.resolver(failed)(
                root,
                Value.Arguments.of(failed, emptyMap()),
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
                world.resolverRegistry.resolver(subject)(
                    world.resolverRegistry.resolveRootQuery(),
                    Value.Arguments.of(subject, emptyMap()),
                ),
            )

        assertEquals("Person", result.type.typeName)
        assertEquals(
            Value.Int.of(9),
            result.fieldValues.getValue(
                Value.GroundKey.of(world.schema.objectField("Person", "value"), emptyMap()),
            ),
        )
    }

    @Test
    fun `resolves Node references through globally keyed node results`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  viewer: User! @resolver(result: {id: "user-1"})
                }

                type User implements Node
                  @nodeResolver(result: [{id: "user-1", result: {score: 7}}]) {
                  id: ID!
                  score: Int!
                }
                """.trimIndent(),
            )
        val schema = world.schema
        val viewer = schema.objectField("Query", "viewer_V_A_node")
        val bridge =
            assertIs<Value.Object>(
                world.resolverRegistry.resolver(viewer)(
                    world.resolverRegistry.resolveRootQuery(),
                    Value.Arguments.of(viewer, emptyMap()),
                ),
            )
        val node = schema.objectField("User_V_A_Bridge", "node")
        val user =
            assertIs<Value.Object>(
                world.resolverRegistry.resolver(node)(
                    bridge,
                    Value.Arguments.of(node, emptyMap()),
                ),
            )

        assertEquals(
            Value.ID.of("user-1"),
            user.fieldValues.getValue(
                Value.GroundKey.of(schema.objectField("User", "id"), emptyMap()),
            ),
        )
        assertEquals(
            Value.Int.of(7),
            user.fieldValues.getValue(
                Value.GroundKey.of(schema.objectField("User", "score"), emptyMap()),
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
            world.resolverRegistry.resolver(item)(
                world.resolverRegistry.resolveRootQuery(),
                Value.Arguments.of(item, emptyMap()),
            )
        }
    }
}
