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
import semantics.shared.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData
import semantics.shared.OEROccurrence

/**
 * The result of construction-demand closure for one object orchestration.
 * Bundles closed demand, value-source occurrences, and the variable-provider
 * reads they require.  Retained across binding declaration, dispatch
 * validation, and field installation.
 */
internal class ClosedConstructionDemandContext(
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
internal class VariableProviderReadOccurrence(
    val definition: InstantiatedFieldPathDefinition,
    val readerPath: List<PathComponent>,
    val inclusionCondition: InclusionCondition,
)

/**
 * Closes demand for one object orchestration and returns the closed construction demand,
 * the field resolver and root field reference occurrences that satisfy it, and the object-fragment
 * variable-provider reads required by those resolver occurrences.
 */
internal fun EngineObjectData.Sync.closeConstructionDemand(
    world: Assumptions,
    occurrence: OEROccurrence,
    initialDemand: SelectionForest,
): ClosedConstructionDemandContext {
    // `accumulatedDemand` will become all construction demand rooted at this OER,
    // expressed through possibly abstract or concrete field coordinates.
    var accumulatedDemand: SelectionForest =
        initialDemand + initialDemand.liftParentConstructionDemand(world)

    // `requiredResolvers` will eventually contain the concrete top-level keys handled by
    // this OER's standard-resolution machinery.
    val requiredResolvers:
        MutableMap<ObjectEngineResult.ObjectKey, ResolverContext> =
        linkedMapOf()

    var demandNotClosed: Boolean
    do {
        // Assume optimistically that we've closed demand.  We might discover in the logic
        // below that we haven't, in which case we'll flip this flag
        demandNotClosed = false

        val mergedDemand: ObjectSelectionForest = accumulatedDemand.merge(schemaType)

        // Find the top-level selections in this concrete `mergedDemand` whose registered
        // resolvers are not superseded by values in this object's source EOD, i.e., existing
        // or potential members of `requiredResolvers`.
        val resolverSelections: Map<ObjectEngineResult.ObjectKey, ObjectSelection> =
            mergedDemand
                .byKey()
                .filter { (objectKey, _) ->
                    requiresStandardResolution(world, objectKey)
                }

        resolverSelections.forEach { (objectKey, resolverSelection) ->
            // This selection belongs in `requiredResolvers` - it might already
            // be there, but if not create an entry for it
            val resolverContext =
                requiredResolvers.getOrPut(objectKey) {
                    createResolverContext(
                        world = world,
                        occurrence = occurrence,
                        objectKey = objectKey,
                    )
                }

            if (
                objectKey is ObjectEngineResult.GroundKey &&
                objectKey.arguments.argumentsContainErrorValue()
            ) {
                // An occurrence with argument errors requires a `resolverContext` so that
                // the error gets published, but it cannot invoke the resolver or contribute
                // resolver-input demand, so we can stop processing it further
                return@forEach
            }

            // Whether or not this selection was in `requiredResolvers`, we may have discovered
            // new conditions under which its key is included. If so, demand has not closed.
            val newKeyInclusions =
                resolverSelection.inclusionCondition
                    .satisfiableAlternatives()
                    .filter(resolverContext.accumulatedKeyInclusions::add)
            if (newKeyInclusions.isEmpty()) return@forEach
            demandNotClosed = true

            // For each newly discovered condition under which `objectKey` may be included,
            // add the resolver's object-fragment construction demand, and any parent-induced
            // demand, guarded by the same condition.
            val objectFragment = resolverContext.fragments.objectFragment
            newKeyInclusions.forEach { keyInclusion ->
                val guardedObjectFragment =
                    objectFragment.constructionSelections.guardedBy(keyInclusion)
                accumulatedDemand +=
                    guardedObjectFragment +
                        guardedObjectFragment.liftParentConstructionDemand(world)
            }
        }
    } while (demandNotClosed)

    val closedDemand = accumulatedDemand.merge(schemaType)
    val requiredResolverSelections =
        closedDemand
            .byKey()
            .filter { (objectKey, _) ->
                requiresStandardResolution(world, objectKey)
            }
    check(requiredResolverSelections.keys == requiredResolvers.keys) {
        "Resolver26 closed demand and required resolvers are misaligned"
    }
    val fieldResolverOccurrences =
        requiredResolvers.mapValues { (objectKey, resolverContext) ->
            resolverContext.toFieldResolverOccurrence(
                selection = closedDemand.byKey().getValue(objectKey),
            )
        }
    val referenceOccurrences = discoverRootFieldReferences(world, occurrence, closedDemand)
    check(fieldResolverOccurrences.keys.intersect(referenceOccurrences.keys).isEmpty()) {
        "Resolver26 classified one field as both an ordinary resolver and a root reference"
    }
    return ClosedConstructionDemandContext(
        demand = closedDemand,
        fieldResolverOccurrences = fieldResolverOccurrences,
        rootFieldReferenceOccurrences = referenceOccurrences,
        variableProviderReadsByResolverOccurrence =
            requiredResolvers.map { (objectKey, resolverContext) ->
                val resolverOccurrenceId =
                    fieldResolverOccurrences.getValue(objectKey).resolverOccurrenceId
                val providerReads =
                    if (
                        objectKey is ObjectEngineResult.GroundKey &&
                        objectKey.arguments.argumentsContainErrorValue()
                    ) {
                        emptyList()
                    } else {
                        resolverContext.fragments.objectFragment.pathVariableDefinitions.map {
                                definition ->
                            VariableProviderReadOccurrence(
                                definition = definition,
                                readerPath = occurrence.coordinate(objectKey),
                                inclusionCondition =
                                    closedDemand
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

// Closure inputs and bookkeeping for one required standard resolver occurrence.
private data class ResolverContext(
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldResolver,
    val inputMaterializeSelections: MaterializeSelectionForest,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val fragments: ResolverFragments,
) {
    val accumulatedKeyInclusions: MutableSet<InclusionCondition> = linkedSetOf()

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

private fun createResolverContext(
    world: Assumptions,
    occurrence: OEROccurrence,
    objectKey: ObjectEngineResult.ObjectKey,
): ResolverContext {
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
        return ResolverContext(
            invocationRoot = occurrence.root,
            invocationPath = occurrence.coordinate(objectKey),
            resolverOccurrenceId = resolverOccurrenceId,
            resolver = resolver,
            inputMaterializeSelections = materializeSelectionForestOf(),
            variableDefinitions = fragments.queryFragment.variableDefinitions,
            fragments = fragments,
        )
    }
    return ResolverContext(
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
