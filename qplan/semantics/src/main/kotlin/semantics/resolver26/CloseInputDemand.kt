package semantics.resolver26

import model.Assumptions
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOccurrenceId
import model.SelectionForest
import model.materializeSelectionForestOf
import model.merge
import model.registry.FieldResolver
import model.registry.InstantiatedFieldPathDefinition
import model.registry.ResolverFragments
import model.registry.VariableInstanceDefinition
import model.schemaType
import model.selectionForestOf
import semantics.correctresolution.argumentsContainErrorValue
import semantics.resolvers.inputParentDemand
import viaduct.engine.api.EngineObjectData

// Expands resolver object fragments until no new resolver keys enter the object's demand.
// Returns the merged demand together with the resolver and binding metadata used by later phases.
context(world: Assumptions)
internal fun EngineObjectData.Sync.closeInputDemand(
    occurrence: OEROccurrenceContext,
    initialDemand: SelectionForest,
): CloseInputDemandResult {
    var accumulatedDemand: SelectionForest =
        initialDemand + initialDemand.inputParentDemand()
    val expansions: MutableMap<ObjectEngineResult.ObjectKey, ResolverExpansion> =
        linkedMapOf()
    val objectProviderReads: MutableList<ProviderDefinitionRead> =
        mutableListOf()

    while (true) {
        val mergedDemand: ObjectSelectionForest =
            accumulatedDemand.merge(schemaType)
        val newResolverSelections: Map<ObjectEngineResult.ObjectKey, ObjectSelection> =
            mergedDemand
                .byKey()
                .filter { (objectKey, _) ->
                    requiresStandardResolution(objectKey) &&
                        objectKey !in expansions
                }
        if (newResolverSelections.isEmpty()) {
            check(
                mergedDemand
                    .byKey()
                    .filterKeys { objectKey ->
                        requiresStandardResolution(objectKey)
                    }.keys == expansions.keys,
            ) {
                "Resolver26 closed demand and resolver expansions are misaligned"
            }
            return CloseInputDemandResult(
                demand = mergedDemand,
                expansions = expansions,
                objectProviderReads = objectProviderReads,
            )
        }

        newResolverSelections.forEach { (objectKey, _) ->
            val resolver: FieldResolver =
                world.resolverRegistry.resolver(objectKey.field)
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
                check(
                    expansions.put(
                        objectKey,
                        ResolverExpansion(
                            ownerKey = objectKey,
                            resolverOccurrenceId = resolverOccurrenceId,
                            resolver = resolver,
                            inputDemand = selectionForestOf(),
                            inputMaterializeSelections = materializeSelectionForestOf(),
                            variableDefinitions = fragments.queryFragment.variableDefinitions,
                            fragments = fragments,
                        ),
                    ) == null,
                ) {
                    "Resolver26 expanded error-valued object key twice: $objectKey"
                }
                return@forEach
            }
            val readerPath = occurrence.coordinate(objectKey)
            val objectFragment = fragments.objectFragment
            val expansion =
                ResolverExpansion(
                    ownerKey = objectKey,
                    resolverOccurrenceId = resolverOccurrenceId,
                    resolver = resolver,
                    inputDemand = objectFragment.constructionSelections,
                    inputMaterializeSelections = objectFragment.materializeSelections,
                    variableDefinitions =
                        resolver.instantiatedVariableDefinitions(resolverOccurrenceId),
                    fragments = fragments,
                )
            check(expansions.put(objectKey, expansion) == null) {
                "Resolver26 expanded object key twice: $objectKey"
            }

            objectProviderReads +=
                objectFragment.pathVariableDefinitions.map { definition ->
                    ProviderDefinitionRead(
                        definition = definition,
                        readerPath = readerPath,
                    )
                }
            accumulatedDemand +=
                objectFragment.constructionSelections +
                    objectFragment.constructionSelections.inputParentDemand()
        }
    }
    error("Resolver26 demand closure terminated unexpectedly")
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

internal data class ResolverExpansion(
    val ownerKey: ObjectEngineResult.ObjectKey,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldResolver,
    val inputDemand: SelectionForest,
    val inputMaterializeSelections: MaterializeSelectionForest,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val fragments: ResolverFragments,
)

internal class CloseInputDemandResult(
    val demand: ObjectSelectionForest,
    val expansions: Map<ObjectEngineResult.ObjectKey, ResolverExpansion>,
    val objectProviderReads: List<ProviderDefinitionRead>,
) {
    var bindingDeclarationStarted: Boolean = false
}

internal data class ProviderDefinitionRead(
    val definition: InstantiatedFieldPathDefinition,
    val readerPath: List<PathComponent>,
)
