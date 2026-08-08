package semantics.resolver03

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import model.Assumptions
import model.Schema
import model.SelectionForest
import model.TypeExpr
import model.fragmentFrom
import model.mergeToGround
import model.objectOf
import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.RecursiveOutputEdgesEnabled
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.resolverTestBatch
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverListDeepeningGeneratedTest {
    @Test
    fun `list-heavy passive deepening worlds resolve with exact witnessed applications`() {
        val counts = TestCaseCount(schemas = 12, registriesPerSchema = 2, queriesPerSchema = 4)
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.0) +
                (ExplicitFieldResolverWeight to 0.8) +
                (DuplicateSelectionWeight to 0.8) +
                (ListTypeWeight to 1.0) +
                (ListValueSize to 2..3) +
                (NullValueWeight to 0.0) +
                (ErrorValueWeight to 0.0) +
                (RecursiveOutputEdgesEnabled to false) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (NodeResolversEnabled to false)
        val random = RandomSource.seeded(817_206L)
        var verifiedCases = 0
        var listDeepeningCases = 0

        repeat(counts.schemas) {
            val batch = Arb.resolverTestBatch(counts, config).next(random)
            batch.registries.forEach { registry ->
                val testWorld = registry.world(batch.schema)
                batch.queries.forEach { query ->
                    val world = testWorld.newAssumptions()
                    val fragment = world.fragmentFrom(query.source)
                    listDeepeningCases +=
                        context(world) {
                            countListPassiveDeepening(
                                fragment.subselections,
                                world.schema.query,
                            )
                        }
                    registry.clearResolutionWitness()
                    val result =
                        context(world) {
                            world.objectOf("Query").resolve(fragment.subselections)
                        }
                    val witness = registry.resolutionWitness()

                    assertEquals(
                        context(world) {
                            result.registeredResolverApplicationIdentityCounts()
                        },
                        witness.applicationIdentityCounts(),
                    )
                    assertTrue(context(world) { result.correctResolution(fragment) })
                    verifiedCases += 1
                }
            }
        }

        assertEquals(96, verifiedCases)
        assertTrue(listDeepeningCases > 0)
    }
}

context(world: Assumptions)
private fun countListPassiveDeepening(
    selections: SelectionForest,
    type: Schema.ObjectType,
): Int {
    val incoming = selections.mergeToGround(type)
    val incomingByKey = incoming.byKey()
    var count = 0

    incoming.byKey().values.forEach { selectedResolver ->
        val field = selectedResolver.key.field
        if (field !in world.resolverRegistry) return@forEach

        world.resolverRegistry
            .resolver(field)
            .objectFragment
            .mergeToGround(type)
            .byKey()
            .values
            .forEach { requiredPassive ->
                val passiveField = requiredPassive.key.field
                val passiveType = passiveField.typeExpr.baseType as? Schema.CompositeType
                    ?: return@forEach
                if (
                    passiveField in world.resolverRegistry ||
                    passiveField.typeExpr !is TypeExpr.List
                ) {
                    return@forEach
                }
                val selectedPassive =
                    incomingByKey[requiredPassive.key] ?: return@forEach
                if (
                    hasMissingDemand(
                        requiredPassive.subselections,
                        selectedPassive.subselections,
                        passiveType.possibleTypes,
                    )
                ) {
                    count += 1
                }
            }
    }

    incoming.byKey().values.forEach { selection ->
        val childType = selection.key.field.typeExpr.baseType as? Schema.CompositeType
            ?: return@forEach
        childType.possibleTypes.forEach { possibleType ->
            count += countListPassiveDeepening(selection.subselections, possibleType)
        }
    }
    return count
}

context(world: Assumptions)
private fun hasMissingDemand(
    required: SelectionForest,
    selected: SelectionForest,
    possibleTypes: Set<Schema.ObjectType>,
): Boolean =
    possibleTypes.any { possibleType ->
        val available = selected.mergeToGround(possibleType).byKey()
        !required.mergeToGround(possibleType).byKey().values.all { requirement ->
            val match = available[requirement.key]
            if (match == null) {
                false
            } else {
                val childType = requirement.key.field.typeExpr.baseType as? Schema.CompositeType
                childType == null ||
                    !hasMissingDemand(
                        requirement.subselections,
                        match.subselections,
                        childType.possibleTypes,
                    )
            }
        }
    }
