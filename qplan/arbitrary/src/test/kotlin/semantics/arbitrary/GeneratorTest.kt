package semantics.arbitrary

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratorTest {
    @Test
    fun `generated schemas registries and queries form valid worlds`() {
        val random = RandomSource.seeded(90210L)

        repeat(100) {
            val schema = Arb.schema(TEST_CONFIG).next(random)
            val registry = schema.registry(TEST_CONFIG).next(random)
            val query = schema.query(TEST_CONFIG).next(random)
            val world = registry.world(schema)

            val (nominalType, selections) = world.selectionsFrom(query.source)

            assertEquals(world.schema.query, nominalType)
            assertFalse(selections.isEmpty())
        }
    }

    @Test
    fun `batch dimensions produce the full registry query product`() {
        val counts = TestCaseCount(schemas = 7, registriesPerSchema = 4, queriesPerSchema = 6)
        val batch =
            Arb.resolverTestBatch(counts)
                .next(RandomSource.seeded(1234L))

        assertEquals(4, batch.registries.size)
        assertEquals(6, batch.queries.size)
        assertEquals(24, batch.cases.count())
    }

    @Test
    fun `feature switches remove their schema and query features`() {
        val config =
            Config.default +
                (ArgumentsEnabled to false) +
                (InterfacesEnabled to false) +
                (ListsEnabled to false) +
                (QueryFragmentsEnabled to false) +
                (ResolverFragmentsEnabled to false) +
                (UnionsEnabled to false)
        val random = RandomSource.seeded(5678L)

        repeat(30) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val query = schema.query(config).next(random)

            assertFalse("interface " in schema.sdl)
            assertFalse("union " in schema.sdl)
            assertFalse("[" in schema.sdl)
            assertTrue(schema.query.fields.all { field -> field.arguments.isEmpty() })
            assertTrue(schema.objects.flatMap { it.fields }.all { field -> field.arguments.isEmpty() })
            assertTrue(registry.objectFragmentSources.values.all(String::isEmpty))
            assertFalse("... on " in query.source)
        }
    }

    @Test
    fun `queries generate distinct literal tuples for one argument field`() {
        val config =
            Config.default +
                (DuplicateSelectionWeight to 1.0) +
                (FieldArgumentWeight to 1.0)
        val random = RandomSource.seeded(2468L)
        var sawDistinctTuples = false
        var sawAliasedDistinctTuple = false

        repeat(20) {
            val schema = Arb.schema(config).next(random)
            val query = schema.query(config).next(random)
            val invocations =
                ARGUMENT_INVOCATION
                    .findAll(query.source)
                    .map { match ->
                        ArgumentInvocation(
                            alias = match.groupValues[1].ifEmpty { null },
                            fieldName = match.groupValues[2],
                            argument = match.groupValues[3],
                        )
                    }.toList()
            invocations
                .groupBy(ArgumentInvocation::fieldName)
                .values
                .filter { values ->
                    values.map(ArgumentInvocation::argument).distinct().size > 1
                }.forEach { values ->
                    sawDistinctTuples = true
                    if (values.any { it.alias != null }) {
                        sawAliasedDistinctTuple = true
                    }
                }
        }

        assertTrue(sawDistinctTuples)
        assertTrue(sawAliasedDistinctTuple)
    }

    @Test
    fun `resolver fragments generate exact literal argument demand`() {
        val config =
            Config.default +
                (ExplicitFieldResolverWeight to 1.0) +
                (FieldArgumentWeight to 1.0) +
                (ResolverFragmentsEnabled to true)
        val random = RandomSource.seeded(1357L)
        var sawArgumentDemand = false

        repeat(50) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            registry.world(schema)
            sawArgumentDemand =
                sawArgumentDemand ||
                    registry.objectFragmentSources.values.any { source ->
                        "(arg:" in source
                    }
        }

        assertTrue(sawArgumentDemand)
    }

    @Test
    fun `minimum selection depth forces a valid deep query path`() {
        val config =
            Config.default +
                (MinimumSelectionDepth to 4) +
                (MaxSelectionDepth to 6) +
                (SchemaObjectCount to 4..6)
        val random = RandomSource.seeded(97531L)

        repeat(30) {
            val schema = Arb.schema(config).next(random)
            val query = schema.query(config).next(random)
            val registry = schema.registry(config).next(random)

            assertTrue(query.selectionDepth >= 4)
            assertTrue(query.selectionDepth <= 6)
            registry.world(schema).selectionsFrom(query.source)
        }
    }

    @Test
    fun `resolver variables generate provider-backed fragment arguments`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.8) +
                (ExplicitFieldResolverWeight to 0.8) +
                (NullableTypeWeight to 0.1) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0)
        val random = RandomSource.seeded(86420L)
        var generatedVariables = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            generatedVariables += registry.variableProviderSources.size
            registry.variableProviderSources.keys.forEach { variableName ->
                assertTrue(
                    registry.objectFragmentSources.values.any { source ->
                        "\$$variableName" in source
                    },
                )
            }
        }

        assertTrue(generatedVariables > 0)
    }

    private companion object {
        val ARGUMENT_INVOCATION =
            Regex("""(?:(alias\d+):\s+)?(\w+)\(arg:\s+([^)]+)\)""")

        val TEST_CONFIG = Config.default
    }
}

private data class ArgumentInvocation(
    val alias: String?,
    val fieldName: String,
    val argument: String,
)
