package semantics.resolvers

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.EngineErrorData
import model.EngineOutputData
import model.ResolverOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.NodeReferenceIdentity
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.outputType
import model.outputValue
import model.engineObjectDataOf
import model.PathComponent
import model.SelectionForest
import model.RootFieldReferenceData
import viaduct.engine.api.EngineObjectData
import semantics.shared.applicableGroundSelections
import model.invariants.conformsToResolverOutputSchemaType
import model.schemaType
import model.merge
import model.nodeReferenceIdentityOrNull
import model.requireField
import model.isParentField
import model.selectionForestOf
import model.toEngineResult
import semantics.shared.SharedOperationContext

/**
 * An eagerly materialized result tree and its root object occurrences requiring resolver work.
 *
 * Descendant objects remain reachable through each root's paired source and result trees.
 */
internal class ResolvePassiveValuesResult(
    val engineResult: EngineResult?,
    val objectsNeedingResolution: List<PassiveObjectOccurrence>,
    /** Object roots whose original source position is symbolic and cannot be rediscovered later. */
    val referenceObjectsNeedingResolution: List<PassiveObjectOccurrence> = emptyList(),
)

internal class PassiveObjectOccurrence(
    val path: List<PathComponent>,
    val source: EngineObjectData.Sync,
    val selections: SelectionForest,
    val target: ObjectEngineResult,
)

/** Executes one root-field-reference hop at its stable publication occurrence. */
internal fun interface RootFieldReferenceResolver {
    suspend fun resolve(
        reference: RootFieldReferenceData,
        publicationRoot: ObjectEngineResult,
        publicationPath: List<PathComponent>,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        constructionDemand: SelectionForest,
        invocationDemand: SelectionForest,
    ): ResolverOutputData?
}

/** Installs every selected parent field as a reference to [parent] and returns its selections. */
context(operation: SharedOperationContext<*>)
internal fun ObjectEngineResult.installParentBackedges(
    selections: ObjectSelectionForest,
    parent: PassiveObjectOccurrence?,
    path: List<PathComponent>,
): List<model.ObjectSelection> =
    selections.byGroundKey().mapNotNull { (key, selection) ->
        if (key !is ObjectEngineResult.ParentKey) return@mapNotNull null
        val containingParent =
            parent ?: error("Parent field ${key.field.name} has no containing object occurrence")
        val producer =
            path
                .filterIsInstance<ObjectEngineResult.ObjectKey>()
                .lastOrNull()
                ?.field
        require(
            operation.world.parentFieldRelations[key.field] == producer,
        ) {
            "Parent field ${key.field.name} is not inverse to its containing producer occurrence"
        }
        val cell =
            if (isCellSet(key)) {
                getCell(key)
            } else {
                setCellValue(key, containingParent.target)
            }
        check(cell.getValue().get() === containingParent.target) {
            "Parent field ${key.field.name} does not reference its containing object occurrence"
        }
        selection
    }

/**
 * Eagerly materializes every argumentless field present in this output.
 *
 * Selective worlds still require every present field to be included in [invocationDemand].
 * [constructionDemand] determines whether each root object occurrence requires orchestration.
 */
context(operation: SharedOperationContext<*>)
internal suspend fun ResolverOutputData?.resolvePassiveValues(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    constructionDemand: SelectionForest,
    invocationDemand: SelectionForest = constructionDemand,
    publicationRoot: ObjectEngineResult? = null,
    rootFieldReferenceResolver: RootFieldReferenceResolver? = null,
    authoritativeNodeIdentity: NodeReferenceIdentity? = null,
): ResolvePassiveValuesResult {
    require(conformsToResolverOutputSchemaType(expectedType)) {
        "Resolver output does not conform to $expectedType"
    }
    return when (this) {
        null -> ResolvePassiveValuesResult(null, emptyList())
        is EngineErrorData ->
            ResolvePassiveValuesResult(ErrorEngineResult.of(this), emptyList())
        is RootFieldReferenceData -> {
            val nodeIdentity = authoritativeNodeIdentity ?: nodeReferenceIdentityOrNull()
            val resolver =
                requireNotNull(rootFieldReferenceResolver) {
                    "Root-field reference has no execution strategy"
                }
            val root =
                requireNotNull(publicationRoot) {
                    "Root-field reference has no publication root"
                }
            val referencedOutput =
                resolver.resolve(
                    reference = this,
                    publicationRoot = root,
                    publicationPath = path,
                    expectedType = expectedType,
                    constructionDemand = constructionDemand,
                    invocationDemand = invocationDemand,
                )
            val referencedResult =
                referencedOutput
                    .withAuthoritativeNodeId(nodeIdentity, invocationDemand)
                    .resolvePassiveValues(
                        expectedType = expectedType,
                        path = path,
                        constructionDemand = constructionDemand,
                        invocationDemand = invocationDemand,
                        publicationRoot = root,
                        rootFieldReferenceResolver = resolver,
                        authoritativeNodeIdentity = nodeIdentity,
                    )
            ResolvePassiveValuesResult(
                engineResult = referencedResult.engineResult,
                objectsNeedingResolution = referencedResult.objectsNeedingResolution,
                referenceObjectsNeedingResolution = referencedResult.objectsNeedingResolution,
            )
        }
        is EngineObjectData.Sync ->
            resolvePassiveObjectValues(
                constructionDemand = constructionDemand,
                invocationDemand = invocationDemand,
                path = path,
                publicationRoot = publicationRoot,
                rootFieldReferenceResolver = rootFieldReferenceResolver,
                retainInvocationDemand = authoritativeNodeIdentity != null,
            )
        is List<*> -> {
            val elementType = checkNotNull(expectedType.unwrapList())
            val objectsNeedingResolution = mutableListOf<PassiveObjectOccurrence>()
            val referenceObjectsNeedingResolution = mutableListOf<PassiveObjectOccurrence>()
            val values =
                buildList(this.size) {
                    this@resolvePassiveValues.forEachIndexed { index, value ->
                        val element =
                            value.resolvePassiveValues(
                                expectedType = elementType,
                                path = path + ListEngineResult.Index.of(index),
                                constructionDemand = constructionDemand,
                                invocationDemand = invocationDemand,
                                publicationRoot = publicationRoot,
                                rootFieldReferenceResolver = rootFieldReferenceResolver,
                            )
                        add(element.engineResult)
                        objectsNeedingResolution.addAll(element.objectsNeedingResolution)
                        referenceObjectsNeedingResolution.addAll(
                            element.referenceObjectsNeedingResolution,
                        )
                    }
                }
            ResolvePassiveValuesResult(
                engineResult = ListEngineResult.of(elementType, values),
                objectsNeedingResolution = objectsNeedingResolution,
                referenceObjectsNeedingResolution = referenceObjectsNeedingResolution,
            )
        }
        else ->
            ResolvePassiveValuesResult(
                toEngineResult(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef),
                emptyList(),
            )
    }
}

private fun ResolverOutputData?.withAuthoritativeNodeId(
    identity: NodeReferenceIdentity?,
    demand: SelectionForest,
): ResolverOutputData? {
    if (identity == null || this !is EngineObjectData.Sync) return this
    require(schemaType == identity.type) {
        "Node reference for ${identity.type.name} resolved to ${schemaType.name}"
    }
    val idField = identity.type.field("id")
        ?: throw IllegalArgumentException("Node type ${identity.type.name} has no id field")
    val idDemanded =
        demand.merge(identity.type).byKey().keys.any { key -> key.field == idField }
    if (!idDemanded) return this
    return engineObjectDataOf(
        identity.type,
        getSelections().associateWith(::outputValue) + (idField.name to identity.id),
    )
}

context(operation: SharedOperationContext<*>)
private suspend fun EngineObjectData.Sync.resolvePassiveObjectValues(
    constructionDemand: SelectionForest,
    invocationDemand: SelectionForest,
    path: List<PathComponent>,
    publicationRoot: ObjectEngineResult?,
    rootFieldReferenceResolver: RootFieldReferenceResolver?,
    retainInvocationDemand: Boolean,
): ResolvePassiveValuesResult {
    val constructionDemandByKey =
        constructionDemand.applicableGroundSelections(schemaType).byGroundKey()
    val invocationDemandByKey =
        invocationDemand.applicableGroundSelections(schemaType).byGroundKey()
    if (operation.selectiveResolvers) {
        val selectedFieldNames =
            invocationDemandByKey.keys.mapTo(linkedSetOf()) { key -> key.field.name }
        val unselectedKeys =
            getSelections()
                .filterNot { fieldName -> schemaType.requireField(fieldName).isParentField() }
                .toSet() - selectedFieldNames
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${schemaType.name} contains unselected fields: " +
                unselectedKeys.joinToString()
        }
    }

    val passiveDemandKeys =
        (constructionDemand + invocationDemand)
            .applicableGroundSelections(schemaType)
            .byGroundKey()
            .keys
    val selectedKeys =
        getSelections()
            .mapNotNull { fieldName ->
                val field = schemaType.requireField(fieldName)
                if (field.isParentField()) return@mapNotNull null
                require(field.args.isEmpty()) {
                    "Passive object field ${schemaType.name}/$fieldName must be argumentless"
                }
                val key = ObjectEngineResult.GroundKey.of(field, emptyMap())
                val output = outputValue(fieldName)
                if (output.containsImmediateRootFieldReference() && key !in passiveDemandKeys) {
                    null
                } else {
                    key
                }
            }.toSet()
    val referenceObjectsNeedingResolution = mutableListOf<PassiveObjectOccurrence>()
    val values: Map<ObjectEngineResult.ObjectKey, EngineResult?> =
        buildMap(selectedKeys.size) {
            selectedKeys.forEach { key ->
                val arguments = key.arguments
                require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
                    "Passive object field ${schemaType.name}/${key.field.name} must be argumentless"
                }
                val fieldValue =
                    outputValue(key.field.name)
                        .resolvePassiveValues(
                            expectedType = key.field.outputType,
                            path = path + key,
                            constructionDemand =
                                constructionDemandByKey[key]
                                    ?.subselections
                                    ?: selectionForestOf(),
                            invocationDemand =
                                invocationDemandByKey[key]
                                    ?.subselections
                                    ?: selectionForestOf(),
                            publicationRoot = publicationRoot,
                            rootFieldReferenceResolver = rootFieldReferenceResolver,
                        )
                put(key, fieldValue.engineResult)
                referenceObjectsNeedingResolution.addAll(
                    fieldValue.referenceObjectsNeedingResolution,
                )
            }
        }
    val engineResult = ObjectEngineResult.of(schemaType, values, mutable = true)
    val retainedDemand =
        if (retainInvocationDemand) {
            constructionDemand + invocationDemand
        } else {
            constructionDemand
        }
    val localResolution =
        if (hasUnresolvedDemand(retainedDemand)) {
            listOf(
                PassiveObjectOccurrence(
                    path = path,
                    source = this,
                    selections = retainedDemand,
                    target = engineResult,
                ),
            )
        } else {
            emptyList()
        }
    return ResolvePassiveValuesResult(
        engineResult = engineResult,
        objectsNeedingResolution = localResolution + referenceObjectsNeedingResolution,
        referenceObjectsNeedingResolution = referenceObjectsNeedingResolution,
    )
}

private fun ResolverOutputData?.containsImmediateRootFieldReference(): Boolean =
    when (this) {
        is RootFieldReferenceData -> true
        is List<*> -> any { value -> value.containsImmediateRootFieldReference() }
        else -> false
    }

context(operation: SharedOperationContext<*>)
private fun EngineOutputData?.hasUnresolvedDemand(
    selections: SelectionForest,
): Boolean =
    when (this) {
        is EngineObjectData.Sync -> hasUnresolvedDemand(selections)
        is List<*> -> any { value -> value.hasUnresolvedDemand(selections) }
        else -> false
    }

context(operation: SharedOperationContext<*>)
private fun EngineObjectData.Sync.hasUnresolvedDemand(
    selections: SelectionForest,
): Boolean =
    selections
        .applicableGroundSelections(schemaType)
        .byGroundKey()
        .any { (key, selection) ->
            if (key is ObjectEngineResult.ParentKey || !isPresent(key.field.name)) {
                true
            } else {
                require(key.field.args.isEmpty()) {
                    "Resolver output must not supply argument-bearing field " +
                        "${schemaType.name}/${key.field.name}"
                }
                outputValue(key.field.name).hasUnresolvedDemand(selection.subselections)
            }
        }

/**
 * Returns demanded, already-materialized child object occurrences at this exact object.
 */
context(operation: SharedOperationContext<*>)
internal fun EngineObjectData.Sync.materializedChildOccurrences(
    path: List<PathComponent>,
    selections: ObjectSelectionForest,
    resolved: ObjectEngineResult,
): List<PassiveObjectOccurrence> =
    selections.byGroundKey().flatMap { (key, selection) ->
        if (key is ObjectEngineResult.ParentKey || !isPresent(key.field.name)) {
            emptyList()
        } else {
            require(key.field.args.isEmpty()) {
                "Resolver output must not supply argument-bearing field " +
                    "${schemaType.name}/${key.field.name}"
            }
            outputValue(key.field.name).materializedObjectOccurrences(
                path = path + key,
                selections = selection.subselections,
                resolved = resolved.getCell(key).getValue().get(),
            )
        }
    }

private fun EngineOutputData?.materializedObjectOccurrences(
    path: List<PathComponent>,
    selections: SelectionForest,
    resolved: EngineResult?,
): List<PassiveObjectOccurrence> =
    when (this) {
        is EngineObjectData.Sync ->
            listOf(
                PassiveObjectOccurrence(
                    path = path,
                    source = this,
                    selections = selections,
                    target = resolved as ObjectEngineResult,
                ),
            )

        is List<*> -> {
            val result = resolved as ListEngineResult
            flatMapIndexed { index, value ->
                value.materializedObjectOccurrences(
                    path = path + ListEngineResult.Index.of(index),
                    selections = selections,
                    resolved = result.get(index).getValue().get(),
                )
            }
        }

        else -> emptyList()
    }

/** Resolves the retained object occurrences deepest first without replacing any result value. */
internal fun ResolvePassiveValuesResult.resolveRetainedObjects(
    resolveObject: (PassiveObjectOccurrence) -> Unit,
): EngineResult? {
    objectsNeedingResolution
        .sortedByDescending { passiveObjectOccurrence -> passiveObjectOccurrence.path.size }
        .forEach(resolveObject)
    return engineResult
}
