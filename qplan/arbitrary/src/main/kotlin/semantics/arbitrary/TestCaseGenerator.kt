package semantics.arbitrary

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import model.testing.TestWorld

data class ResolverTestCase(
    val schema: ArbitrarySchema,
    val registry: ArbitraryRegistry,
    val query: ArbitraryQuery,
) {
    fun failureContext(): String =
        """
        |Generated resolver test case
        |
        |Schema:
        |${schema.sdl.prependIndent("  ")}
        |
        |Registry:
        |${registry.toString().prependIndent("  ")}
        |
        |Query:
        |${query.source.prependIndent("  ")}
        """.trimMargin()
}

data class ResolverTestBatch(
    val schema: ArbitrarySchema,
    val registries: List<ArbitraryRegistry>,
    val queries: List<ArbitraryQuery>,
) {
    val cases: Sequence<ResolverTestCase>
        get() =
            registries.asSequence().flatMap { registry ->
                queries.asSequence().map { query ->
                    ResolverTestCase(schema, registry, query)
                }
            }
}

/**
 * Generates one schema together with independent registry and query samples for its full product.
 *
 * Kotest's outer property iteration count is `S`; [counts] supplies `R` and `Q`.
 */
fun Arb.Companion.resolverTestBatch(
    counts: TestCaseCount,
    config: Config = Config.default,
): Arb<ResolverTestBatch> =
    Arb.schema(config).flatMap { schema ->
        Arb.bind(
            Arb.list(
                schema.registry(config),
                counts.registriesPerSchema..counts.registriesPerSchema,
            ),
            Arb.list(
                schema.query(config),
                counts.queriesPerSchema..counts.queriesPerSchema,
            ),
        ) { registries, queries ->
            ResolverTestBatch(schema, registries, queries)
        }
    }

/**
 * Runs the full `S x R x Q` product while reusing one assembled world per generated registry.
 *
 * A callback that performs more than one independent resolution must use
 * [TestWorld.newAssumptions] for each resolution so request-local bindings are not shared.
 */
suspend fun checkResolverTestCases(
    counts: TestCaseCount,
    config: Config = Config.default,
    captureSuppliedDemand: Boolean = false,
    captureResolutionWitness: Boolean = true,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
) {
    checkAll(
        iterations = counts.schemas,
        Arb.resolverTestBatch(counts, config),
    ) { batch ->
        batch.registries.forEach { registry ->
            val world =
                registry.world(
                    schema = batch.schema,
                    captureSuppliedDemand = captureSuppliedDemand,
                    captureResolutionWitness = captureResolutionWitness,
                )
            batch.queries.forEach { query ->
                val testCase = ResolverTestCase(batch.schema, registry, query)
                try {
                    property(world, testCase)
                } catch (failure: Throwable) {
                    throw AssertionError(testCase.failureContext(), failure)
                }
            }
        }
    }
}
