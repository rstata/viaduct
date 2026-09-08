package semantics.resolver26

import model.Assumptions
import model.InclusionCondition
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOccurrenceId
import model.SelectionForest
import model.materializeSelectionForestOf
import model.merge
import model.guardedBy
import model.registry.FieldResolver
import model.registry.InstantiatedFieldPathDefinition
import model.registry.ResolverFragments
import model.registry.VariableInstanceDefinition
import model.schemaType
import model.satisfiableAlternatives
import semantics.correctresolution.argumentsContainErrorValue
import semantics.resolvers.inputParentDemand
import viaduct.engine.api.EngineObjectData

// Expands resolver object fragments until no new resolver keys or activation alternatives enter
// the object's demand. A previously expanded key can gain a late disjunct through another resolver,
// so each new satisfiable alternative must propagate independently into that key's prerequisites.
// Returns the merged demand together with the resolver and binding metadata used by later phases.
context(world: Assumptions)
internal fun EngineObjectData.Sync.closeInputDemand(
    occurrence: OEROccurrenceContext,
    initialDemand: SelectionForest,
): CloseInputDemandResult {
    var accumulatedDemand: SelectionForest =
        initialDemand + initialDemand.inputParentDemand()
    val expansionAccumulators:
        MutableMap<ObjectEngineResult.ObjectKey, ResolverExpansionAccumulator> =
        linkedMapOf()

    while (true) {
        val mergedDemand: ObjectSelectionForest =
            accumulatedDemand.merge(schemaType)
        val resolverSelections: Map<ObjectEngineResult.ObjectKey, ObjectSelection> =
            mergedDemand
                .byKey()
                .filter { (objectKey, _) ->
                    requiresStandardResolution(objectKey)
                }
        var propagatedNewAlternative = false

        resolverSelections.forEach { (objectKey, resolverSelection) ->
            val expansion =
                expansionAccumulators[objectKey]
                    ?: createResolverExpansion(
                        occurrence = occurrence,
                        objectKey = objectKey,
                    ).also { created ->
                        check(expansionAccumulators.put(objectKey, created) == null) {
                            "Resolver26 expanded object key twice: $objectKey"
                        }
                    }
            val newAlternatives =
                resolverSelection.inclusionCondition
                    .satisfiableAlternatives()
                    .filter(expansion.propagatedAlternatives::add)
            if (newAlternatives.isEmpty()) return@forEach
            propagatedNewAlternative = true

            if (
                objectKey is ObjectEngineResult.GroundKey &&
                objectKey.arguments.argumentsContainErrorValue()
            ) {
                return@forEach
            }
            val objectFragment = expansion.fragments.objectFragment
            newAlternatives.forEach { alternative ->
                val guardedObjectFragment =
                    objectFragment.constructionSelections.guardedBy(alternative)
                accumulatedDemand +=
                    guardedObjectFragment +
                        guardedObjectFragment.inputParentDemand() +
                        objectFragment.constructionSelections.providerDemand(
                            definitions = objectFragment.pathVariableDefinitions,
                            inclusionCondition = alternative,
                        )
            }
        }

        if (!propagatedNewAlternative) {
            check(
                resolverSelections.keys == expansionAccumulators.keys,
            ) {
                "Resolver26 closed demand and resolver expansions are misaligned"
            }
            return CloseInputDemandResult(
                demand = mergedDemand,
                fieldResolverOccurrenceContexts =
                    expansionAccumulators.mapValues { (objectKey, accumulator) ->
                        accumulator.toFieldResolverOccurrenceContext(
                            selection = mergedDemand.byKey().getValue(objectKey),
                        )
                    },
                objectProviderReads =
                    expansionAccumulators.flatMap { (objectKey, expansion) ->
                        if (
                            objectKey is ObjectEngineResult.GroundKey &&
                            objectKey.arguments.argumentsContainErrorValue()
                        ) {
                            return@flatMap emptyList()
                        }
                        expansion.fragments.objectFragment.pathVariableDefinitions.map { definition ->
                            ProviderDefinitionRead(
                                definition = definition,
                                readerPath = occurrence.coordinate(objectKey),
                                inclusionCondition =
                                    mergedDemand.byKey().getValue(objectKey).inclusionCondition,
                            )
                        }
                    },
            )
        }
    }
    error("Resolver26 demand closure terminated unexpectedly")
}

context(world: Assumptions)
private fun createResolverExpansion(
    occurrence: OEROccurrenceContext,
    objectKey: ObjectEngineResult.ObjectKey,
): ResolverExpansionAccumulator {
    val resolver: FieldResolver = world.resolverRegistry.resolver(objectKey.field)
    val resolverOccurrenceId =
        ResolverOccurrenceId.at(
            occurrence.root,
            occurrence.coordinate(objectKey),
        )
    val fragments = resolver.instantiateFragments(resolverOccurrenceId)
    if (
        objectKey is ObjectEngineResult.GroundKey &&
        objectKey.arguments.argumentsContainErrorValue()
    ) {
        return ResolverExpansionAccumulator(
            resolverOccurrenceId = resolverOccurrenceId,
            resolver = resolver,
            inputMaterializeSelections = materializeSelectionForestOf(),
            variableDefinitions = fragments.queryFragment.variableDefinitions,
            fragments = fragments,
        )
    }
    return ResolverExpansionAccumulator(
        resolverOccurrenceId = resolverOccurrenceId,
        resolver = resolver,
        inputMaterializeSelections = fragments.objectFragment.materializeSelections,
        variableDefinitions = resolver.instantiatedVariableDefinitions(resolverOccurrenceId),
        fragments = fragments,
    )
}

context(world: Assumptions)
// Returns true if the field is not present yet has a standard resolver, which means it needs standard resolution
private fun EngineObjectData.Sync.requiresStandardResolution(
    objectKey: ObjectEngineResult.ObjectKey,
): Boolean {
    if (objectKey.field !in world.resolverRegistry) return false
    if (!isPresent(objectKey.field.name)) return true

    require(objectKey.field.args.isEmpty()) {
        "Resolver output must not supply argument-bearing field " +
            "${schemaType.name}/${objectKey.field.name}"
    }
    return false
}

private data class ResolverExpansionAccumulator(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldResolver,
    val inputMaterializeSelections: MaterializeSelectionForest,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val fragments: ResolverFragments,
) {
    val propagatedAlternatives: MutableSet<InclusionCondition> = linkedSetOf()

    fun toFieldResolverOccurrenceContext(
        selection: ObjectSelection,
    ): FieldResolverOccurrenceContext =
        FieldResolverOccurrenceContext(
            selection = selection,
            resolverOccurrenceId = resolverOccurrenceId,
            resolver = resolver,
            inputMaterializeSelections = inputMaterializeSelections,
            variableDefinitions = variableDefinitions,
            fragments = fragments,
        )
}

internal class CloseInputDemandResult(
    val demand: ObjectSelectionForest,
    val fieldResolverOccurrenceContexts:
        Map<ObjectEngineResult.ObjectKey, FieldResolverOccurrenceContext>,
    val objectProviderReads: List<ProviderDefinitionRead>,
) {
    var bindingDeclarationStarted: Boolean = false
}

internal data class ProviderDefinitionRead(
    val definition: InstantiatedFieldPathDefinition,
    val readerPath: List<PathComponent>,
    val inclusionCondition: InclusionCondition,
)
