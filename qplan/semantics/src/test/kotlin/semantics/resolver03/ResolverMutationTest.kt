package semantics.resolver03

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import model.fragmentFrom
import model.objectOf
import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverProgramMutation
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.registeredResolverCellCounts
import semantics.arbitrary.resolverTestBatch
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertTrue

class ResolverMutationTest {
    @Test
    fun `generated properties reject independent resolver program mutations`() {
        val counts = TestCaseCount(schemas = 12, registriesPerSchema = 3, queriesPerSchema = 5)
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.9) +
                (ExplicitFieldResolverWeight to 0.85) +
                (DuplicateSelectionWeight to 1.0) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (NodeResolversEnabled to false)
        val random = RandomSource.seeded(303_303L)
        val killed = ResolverProgramMutation.entries.associateWith { 0 }.toMutableMap()
        val exercised = ResolverProgramMutation.entries.associateWith { 0 }.toMutableMap()

        repeat(counts.schemas) {
            val batch = Arb.resolverTestBatch(counts, config).next(random)
            batch.registries.forEach { registry ->
                val ordinaryWorld = registry.world(batch.schema)

                batch.queries.forEach { query ->
                    val ordinaryFragment =
                        ordinaryWorld.assumptions.fragmentFrom(query.source)
                    registry.clearResolutionWitness()
                    val ordinary =
                        context(ordinaryWorld.assumptions) {
                            ordinaryWorld.assumptions
                                .objectOf("Query")
                                .resolve(ordinaryFragment.subselections)
                        }
                    assertTrue(
                        context(ordinaryWorld.assumptions) {
                            ordinary.correctResolution(ordinaryFragment)
                        },
                    )

                    ResolverProgramMutation.entries
                        .filterNot { it == ResolverProgramMutation.NONE }
                        .forEach { mutation ->
                        val mutantWorld =
                            registry.world(
                                schema = batch.schema,
                                resolverProgramMutation = mutation,
                            )
                        registry.clearResolutionWitness()
                        val mutantResult =
                            runCatching {
                                val fragment = mutantWorld.assumptions.fragmentFrom(query.source)
                                context(mutantWorld.assumptions) {
                                    mutantWorld.assumptions
                                        .objectOf("Query")
                                        .resolve(fragment.subselections)
                                }
                            }
                        val witness = registry.resolutionWitness()
                        if (witness.applications.isNotEmpty()) {
                            exercised[mutation] = exercised.getValue(mutation) + 1
                        }
                        val rejected =
                            mutantResult.fold(
                                onSuccess = { result ->
                                    when (mutation) {
                                        ResolverProgramMutation.DUPLICATE_APPLICATION ->
                                            witness.applicationCounts() !=
                                                result.registeredResolverCellCounts(
                                                    mutantWorld.assumptions.executorRegistry,
                                                )
                                        else -> result != ordinary
                                    }
                                },
                                onFailure = { true },
                            )
                        if (rejected) {
                            killed[mutation] = killed.getValue(mutation) + 1
                        }
                    }
                }
            }
        }

        ResolverProgramMutation.entries
            .filterNot { it == ResolverProgramMutation.NONE }
            .forEach { mutation ->
                assertTrue(
                    exercised.getValue(mutation) >= 100,
                    "$mutation was exercised too rarely: ${exercised.getValue(mutation)}",
                )
                assertTrue(
                    killed.getValue(mutation) >= 10,
                    "$mutation survived too often: killed=${killed.getValue(mutation)}",
                )
            }
    }
}
