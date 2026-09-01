package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.Assumptions
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.Selection
import model.SelectionForest
import model.Stamp
import model.localizeTopLevelSelectionStamps
import model.materializeSelectionForestOf
import model.merge
import model.registry.FieldResolver
import model.registry.ResolverObjectFragment
import model.registry.SelectionStampedVariableDefinition
import model.registry.StampedObjectPathDefinition
import model.schemaType
import model.selectionForestOf
import model.usedVariables
import semantics.correctresolution.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData

// Expands resolver object fragments until no new resolver keys enter the object's demand.
// Returns the merged demand together with the resolver and binding metadata used by later phases.
context(world: Assumptions)
internal fun EngineObjectData.Sync.closeInputDemand(
    path: List<PathComponent>,
    initialDemand: SelectionForest,
): CloseInputDemandResult {
    val localizedDemand: LocalizeTopLevelStampsResult =
        localizeTopLevelStamps(
            path = path,
            demand = initialDemand,
        )
    var accumulatedDemand: SelectionForest = localizedDemand.demand
    val expansions: MutableMap<ObjectEngineResult.ObjectKey, ResolverExpansion> =
        linkedMapOf()
    val pathVariableDefinitions: MutableList<StampedObjectPathDefinition> =
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
            val selectionStamps: List<Stamp.Occurrence> =
                mergedDemand.keys().mapNotNull { key -> key.stamp as? Stamp.Occurrence }
            check(selectionStamps.size == selectionStamps.toSet().size) {
                "Resolver26 closed demand contains duplicate selection stamps"
            }
            return CloseInputDemandResult(
                demand = mergedDemand,
                expansions = expansions,
                bindingAliases = localizedDemand.bindingAliases,
                pathVariableDefinitions = pathVariableDefinitions,
            )
        }

        newResolverSelections.forEach { (objectKey, _) ->
            val resolver: FieldResolver =
                world.resolverRegistry.resolver(objectKey.field)
            if (
                objectKey is ObjectEngineResult.GroundKey &&
                objectKey.arguments.argumentsContainErrorValue()
            ) {
                check(
                    expansions.put(
                        objectKey,
                        ResolverExpansion(
                            ownerKey = objectKey,
                            resolver = resolver,
                            inputDemand = selectionForestOf(),
                            inputMaterializeSelections = materializeSelectionForestOf(),
                            variableDefinitions = emptyList(),
                        ),
                    ) == null,
                ) {
                    "Resolver26 expanded error-valued object key twice: $objectKey"
                }
                return@forEach
            }
            val ownerStamp: Stamp.Occurrence? =
                objectKey.stamp as? Stamp.Occurrence
            val resolverPath: List<PathComponent> =
                if (ownerStamp == null) {
                    path + objectKey
                } else {
                    ownerStamp.resolverPath
                }
            val resolverStamp: Stamp.Occurrence =
                ownerStamp
                    ?: Stamp.Occurrence.of(resolverPath = resolverPath)
            val objectFragment: ResolverObjectFragment =
                resolver.instantiateObjectFragment(resolverStamp)
            val definitions: List<SelectionStampedVariableDefinition> =
                if (ownerStamp == null) {
                    resolver.selectionStampedVariableDefinitions(resolverPath)
                } else {
                    resolver.selectionStampedVariableDefinitionsFrom(ownerStamp)
                }
            val expansion =
                ResolverExpansion(
                    ownerKey = objectKey,
                    resolver = resolver,
                    inputDemand = objectFragment.constructionSelections,
                    inputMaterializeSelections = objectFragment.materializeSelections,
                    variableDefinitions = definitions,
                )
            check(expansions.put(objectKey, expansion) == null) {
                "Resolver26 expanded object key twice: $objectKey"
            }

            pathVariableDefinitions += objectFragment.pathVariableDefinitions
            accumulatedDemand += objectFragment.constructionSelections
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

// Rebases top-level occurrence stamps onto this object's concrete result path.
// Records aliases that connect each localized variable to its source occurrence.
private fun localizeTopLevelStamps(
    path: List<PathComponent>,
    demand: SelectionForest,
): LocalizeTopLevelStampsResult {
    if (path.isEmpty()) {
        return LocalizeTopLevelStampsResult(demand, emptyList())
    }
    val bindingAliases = linkedSetOf<BindingAlias>()
    val localizedDemand: SelectionForest =
        demand.flatMap { selection ->
            val localizedSelection: Selection =
                selectionForestOf(selection)
                    .localizeTopLevelSelectionStamps(path)
                    .single()
            val sourceVariables:
                Map<Pair<ViaductSchema.ObjectField, String>, Arguments.Variable> =
                selection.key
                    .selectionStampedVariables()
                    .associateBy { variable -> variable.field to variable.variableName }
            localizedSelection.key
                .selectionStampedVariables()
                .forEach { localizedVariable ->
                    val sourceVariable: Arguments.Variable =
                        sourceVariables.getValue(
                            localizedVariable.field to localizedVariable.variableName,
                        )
                    if (sourceVariable != localizedVariable) {
                        bindingAliases +=
                            BindingAlias(
                                sourceVariable = sourceVariable,
                                localizedVariable = localizedVariable,
                            )
                    }
                }
            selectionForestOf(localizedSelection)
        }
    return LocalizeTopLevelStampsResult(
        demand = localizedDemand,
        bindingAliases = bindingAliases.toList(),
    )
}

// Collects occurrence-stamped variables carried by this key's arguments or variable-definition marker.
private fun ObjectEngineResult.Key.selectionStampedVariables(): Set<Arguments.Variable> =
    buildSet {
        addAll(
            arguments
                .usedVariables()
                .filter { variable -> variable.stamp?.sourceKey != null },
        )
        val marker =
            (this@selectionStampedVariables as? ObjectEngineResult.VariableKey)
                ?.variableDefinedByThisKey
        if (marker?.stamp?.sourceKey != null) add(marker)
    }

internal data class ResolverExpansion(
    val ownerKey: ObjectEngineResult.ObjectKey,
    val resolver: FieldResolver,
    val inputDemand: SelectionForest,
    val inputMaterializeSelections: MaterializeSelectionForest,
    val variableDefinitions: List<SelectionStampedVariableDefinition>,
)

internal class CloseInputDemandResult(
    val demand: ObjectSelectionForest,
    val expansions: Map<ObjectEngineResult.ObjectKey, ResolverExpansion>,
    val bindingAliases: List<BindingAlias>,
    val pathVariableDefinitions: List<StampedObjectPathDefinition>,
) {
    var bindingDeclarationStarted: Boolean = false
}

private data class LocalizeTopLevelStampsResult(
    val demand: SelectionForest,
    val bindingAliases: List<BindingAlias>,
)

internal data class BindingAlias(
    val sourceVariable: Arguments.Variable,
    val localizedVariable: Arguments.Variable,
)
