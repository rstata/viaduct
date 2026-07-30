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
            assert(selections.isNotEmpty())
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

        repeat(20) {
            val schema = Arb.schema(config).next(random)
            val query = schema.query(config).next(random)
            val invocations =
                ARGUMENT_INVOCATION
                    .findAll(query.source)
                    .map { match ->
                        match.groupValues[1] to match.groupValues[2]
                    }.toList()
            sawDistinctTuples =
                sawDistinctTuples ||
                invocations
                    .groupBy(Pair<String, String>::first)
                    .any { (_, values) ->
                        values.map(Pair<String, String>::second).distinct().size > 1
                    }
        }

        assertTrue(sawDistinctTuples)
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

    private companion object {
        val ARGUMENT_INVOCATION =
            Regex("""(?:alias\d+:\s+)?(\w+)\(arg:\s+([^)]+)\)""")

        val TEST_CONFIG = Config.default
    }
}
