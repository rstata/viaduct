package semantics.resolver26

import model.Assumptions
import model.InclusionCondition
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SelectionForest
import model.materializeSelectionForestOf
import model.merge
import model.guardedBy
import model.registry.FieldResolver
import model.registry.InstantiatedFieldPathDefinition
import model.registry.ResolverFragments
import model.registry.VariableInstanceDefinition
import model.schemaType
import model.outputValue
import model.satisfiableAlternatives
import semantics.correctresolution.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData
import semantics.shared.OEROccurrence

// Expands resolver object fragments until no new resolver keys or activation alternatives enter
// the object's demand. A previously expanded key can gain a late disjunct through another resolver,
// so each new satisfiable alternative must propagate independently into that key's prerequisites.
// Returns the merged demand together with the resolver and binding metadata used by later phases.
internal fun EngineObjectData.Sync.closeInputDemand(
    world: Assumptions,
    occurrence: OEROccurrence,
    initialDemand: SelectionForest,
): ClosedInputDemandContext {
    var accumulatedDemand: SelectionForest =
        initialDemand + initialDemand.inputParentDemand(world)
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
                    requiresStandardResolution(world, objectKey)
                }
        var propagatedNewAlternative = false

        resolverSelections.forEach { (objectKey, resolverSelection) ->
            val expansion =
                expansionAccumulators[objectKey]
                    ?: createResolverExpansion(
                        world = world,
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
                        guardedObjectFragment.inputParentDemand(world) +
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
            val fieldResolverOccurrences =
                expansionAccumulators.mapValues { (objectKey, accumulator) ->
                    accumulator.toFieldResolverOccurrence(
                        selection = mergedDemand.byKey().getValue(objectKey),
                    )
                }
            val referenceOccurrences = discoverRootFieldReferences(world, occurrence, mergedDemand)
            check(fieldResolverOccurrences.keys.intersect(referenceOccurrences.keys).isEmpty()) {
                "Resolver26 classified one field as both an ordinary resolver and a root reference"
            }
            return ClosedInputDemandContext(
                demand = mergedDemand,
                fieldResolverOccurrences = fieldResolverOccurrences,
                rootFieldReferenceOccurrences = referenceOccurrences,
                variableProviderReadsByResolverOccurrence =
                    expansionAccumulators.map { (objectKey, expansion) ->
                        val resolverOccurrenceId =
                            fieldResolverOccurrences.getValue(objectKey).resolverOccurrenceId
                        val providerReads =
                            if (
                                objectKey is ObjectEngineResult.GroundKey &&
                                objectKey.arguments.argumentsContainErrorValue()
                            ) {
                                emptyList()
                            } else {
                                expansion.fragments.objectFragment.pathVariableDefinitions.map {
                                        definition ->
                                    VariableProviderReadOccurrence(
                                        definition = definition,
                                        readerPath = occurrence.coordinate(objectKey),
                                        inclusionCondition =
                                            mergedDemand
                                                .byKey()
                                                .getValue(objectKey)
                                                .inclusionCondition,
                                    )
                                }
                            }
                        resolverOccurrenceId to providerReads
                    }.toMap(),
            )
        }
    }
    error("Resolver26 demand closure terminated unexpectedly")
}

private fun EngineObjectData.Sync.discoverRootFieldReferences(
    world: Assumptions,
    occurrence: OEROccurrence,
    demand: ObjectSelectionForest,
): Map<ObjectEngineResult.ObjectKey, RootFieldReferenceOccurrence> =
    buildMap {
        demand.byKey().forEach { (objectKey, selection) ->
            if (selection.inclusionCondition === InclusionCondition.Never) return@forEach
            if (!isPresent(objectKey.field.name)) return@forEach
            val reference = outputValue(objectKey.field.name) as? RootFieldReferenceData
                ?: return@forEach
            require(objectKey is ObjectEngineResult.GroundKey) {
                "Source-provided root-field reference has an open consumer key: $objectKey"
            }
            val consumerArguments = objectKey.arguments
            require(consumerArguments is model.Arguments.Resolved && consumerArguments.fieldValues.isEmpty()) {
                "Source-provided root-field reference must occupy an argumentless field: $objectKey"
            }
            require(reference.targetField in world.resolverRegistry) {
                "Root-field-reference target has no registered resolver: " +
                    "${reference.targetField.containingDef.name}/${reference.targetField.name}"
            }
            check(
                put(
                    objectKey,
                    RootFieldReferenceOccurrence(
                        selection = selection,
                        reference = reference,
                        publicationPath = occurrence.coordinate(objectKey),
                    ),
                ) == null,
            ) {
                "Resolver26 discovered a root-field reference twice: $objectKey"
            }
        }
    }

private fun createResolverExpansion(
    world: Assumptions,
    occurrence: OEROccurrence,
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
            invocationRoot = occurrence.root,
            invocationPath = occurrence.coordinate(objectKey),
            resolverOccurrenceId = resolverOccurrenceId,
            resolver = resolver,
            inputMaterializeSelections = materializeSelectionForestOf(),
            variableDefinitions = fragments.queryFragment.variableDefinitions,
            fragments = fragments,
        )
    }
    return ResolverExpansionAccumulator(
        invocationRoot = occurrence.root,
        invocationPath = occurrence.coordinate(objectKey),
        resolverOccurrenceId = resolverOccurrenceId,
        resolver = resolver,
        inputMaterializeSelections = fragments.objectFragment.materializeSelections,
        variableDefinitions = resolver.instantiatedVariableDefinitions(resolverOccurrenceId),
        fragments = fragments,
    )
}

// Returns true if the field is not present yet has a standard resolver, which means it needs standard resolution
private fun EngineObjectData.Sync.requiresStandardResolution(
    world: Assumptions,
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
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldResolver,
    val inputMaterializeSelections: MaterializeSelectionForest,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val fragments: ResolverFragments,
) {
    val propagatedAlternatives: MutableSet<InclusionCondition> = linkedSetOf()

    fun toFieldResolverOccurrence(
        selection: ObjectSelection,
    ): FieldResolverOccurrence =
        FieldResolverOccurrence(
            selection = selection,
            invocationRoot = invocationRoot,
            invocationPath = invocationPath,
            resolverOccurrenceId = resolverOccurrenceId,
            resolver = resolver,
            inputMaterializeSelections = inputMaterializeSelections,
            variableDefinitions = variableDefinitions,
            fragments = fragments,
        )
}

/**
 * Immutable inputs established by demand closure for one object orchestration.
 * Retained across binding declaration, dispatch validation, and field installation; bundles
 * closed demand, value-source occurrences, and the variable-provider reads they require.
 */
internal class ClosedInputDemandContext(
    val demand: ObjectSelectionForest,
    val fieldResolverOccurrences:
        Map<ObjectEngineResult.ObjectKey, FieldResolverOccurrence>,
    val rootFieldReferenceOccurrences:
        Map<ObjectEngineResult.ObjectKey, RootFieldReferenceOccurrence>,
    /** Object-fragment reads; Query-fragment reads are prepared by their owning field task. */
    val variableProviderReadsByResolverOccurrence:
        Map<ResolverOccurrenceId, List<VariableProviderReadOccurrence>>,
)

/**
 * One planned provider-path read that produces an instantiated variable binding.
 * The definition identifies the provider path and destination variable; the condition controls
 * execution, and the reader path identifies the consumer for cycle checking. The containing
 * object or Query result supplies the root from which the provider path is read.
 */
internal data class VariableProviderReadOccurrence(
    val definition: InstantiatedFieldPathDefinition,
    val readerPath: List<PathComponent>,
    val inclusionCondition: InclusionCondition,
)
