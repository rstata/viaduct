package semantics.contract

import model.requireQueryTypeDef
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import model.Assumptions
import viaduct.graphql.schema.ViaductSchema
import model.SelectionForest
import model.fragmentFrom
import model.instantiateBindings
import model.merge
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

/** Generated coverage for demand deepening through passive list-valued fields. */
interface ListPassiveDeepeningGeneratedResolverContract : ResolverContract {
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
                    val world = testWorld.newAssumptions(selectiveResolvers)
                    val fragment = world.fragmentFrom(query.source)
                    listDeepeningCases +=
                        context(world) {
                            countListPassiveDeepening(
                                fragment.subselections,
                                world.schema.requireQueryTypeDef(),
                            )
                        }
                    registry.clearResolutionWitness()
                    val result =
                        resolve(
                            world = world,
                            root = world.objectOf("Query"),
                            selections = fragment.subselections,
                        )
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
    type: ViaductSchema.Object,
): Int {
    val incoming = selections.merge(type).instantiateBindings()
    val incomingByKey = incoming.byGroundKey()
    var count = 0

    incoming.byGroundKey().forEach { (selectedKey, _) ->
        val field = selectedKey.field
        if (field !in world.resolverRegistry) return@forEach

        world.resolverRegistry
            .resolver(field)
            .objectFragment
            .merge(type)
            .instantiateBindings()
            .byGroundKey()
            .forEach { (requiredKey, requiredPassive) ->
                val passiveField = requiredKey.field
                val passiveType = passiveField.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
                    ?: return@forEach
                if (
                    passiveField in world.resolverRegistry ||
                    !passiveField.type.isList
                ) {
                    return@forEach
                }
                val selectedPassive =
                    incomingByKey[requiredKey] ?: return@forEach
                if (
                    hasMissingDemand(
                        requiredPassive.subselections,
                        selectedPassive.subselections,
                        passiveType.possibleObjectTypes,
                    )
                ) {
                    count += 1
                }
            }
    }

    incoming.byGroundKey().forEach { (key, selection) ->
        val childType = key.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
            ?: return@forEach
        childType.possibleObjectTypes.forEach { possibleType ->
            count += countListPassiveDeepening(selection.subselections, possibleType)
        }
    }
    return count
}

context(world: Assumptions)
private fun hasMissingDemand(
    required: SelectionForest,
    selected: SelectionForest,
    possibleTypes: Set<ViaductSchema.Object>,
): Boolean =
    possibleTypes.any { possibleType ->
        val available = selected.merge(possibleType).instantiateBindings().byGroundKey()
        !required
            .merge(possibleType)
            .instantiateBindings()
            .byGroundKey()
            .all { (requirementKey, requirement) ->
                val match = available[requirementKey]
                if (match == null) {
                    false
                } else {
                    val childType = requirementKey.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
                    childType == null ||
                        !hasMissingDemand(
                            requirement.subselections,
                            match.subselections,
                            childType.possibleObjectTypes,
                        )
                }
            }
    }
