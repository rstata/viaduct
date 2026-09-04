package semantics.resolvers

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.outputType
import model.outputValue
import model.PathComponent
import model.SelectionForest
import viaduct.engine.api.EngineObjectData
import semantics.shared.applicableGroundSelections
import model.invariants.conformsToOutputSchemaType
import model.schemaType
import model.requireField
import model.selectionForestOf
import model.toEngineResult
import semantics.shared.OperationContext

/**
 * An eagerly materialized result tree and its root object occurrences requiring resolver work.
 *
 * Descendant objects remain reachable through each root's paired source and result trees.
 */
internal class ResolvePassiveValuesResult(
    val engineResult: EngineResult?,
    val objectsNeedingResolution: List<PassiveObjectOccurrence>,
)

internal class PassiveObjectOccurrence(
    val path: List<PathComponent>,
    val source: EngineObjectData.Sync,
    val selections: SelectionForest,
    val target: ObjectEngineResult,
)

/** Installs every selected parent field as a reference to [parent] and returns its selections. */
context(operation: OperationContext)
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
                reserveCell(key).also { parentCell ->
                    parentCell.setValue(containingParent.target)
                    parentCell.setAccessResult(true)
                }
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
context(operation: OperationContext)
internal fun EngineOutputData?.resolvePassiveValues(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    constructionDemand: SelectionForest,
    invocationDemand: SelectionForest = constructionDemand,
): ResolvePassiveValuesResult {
    require(conformsToOutputSchemaType(expectedType)) {
        "Resolver output does not conform to $expectedType"
    }
    return when (this) {
        null -> ResolvePassiveValuesResult(null, emptyList())
        is EngineErrorData ->
            ResolvePassiveValuesResult(ErrorEngineResult.of(this), emptyList())
        is EngineObjectData.Sync ->
            resolvePassiveObjectValues(
                constructionDemand = constructionDemand,
                invocationDemand = invocationDemand,
                path = path,
            )
        is List<*> -> {
            val elementType = checkNotNull(expectedType.unwrapList())
            val objectsNeedingResolution = mutableListOf<PassiveObjectOccurrence>()
            val values =
                buildList(this.size) {
                    this@resolvePassiveValues.forEachIndexed { index, value ->
                        val element =
                            value.resolvePassiveValues(
                                expectedType = elementType,
                                path = path + ListEngineResult.Index.of(index),
                                constructionDemand = constructionDemand,
                                invocationDemand = invocationDemand,
                            )
                        add(element.engineResult)
                        objectsNeedingResolution.addAll(element.objectsNeedingResolution)
                    }
                }
            ResolvePassiveValuesResult(
                engineResult = ListEngineResult.of(elementType, values),
                objectsNeedingResolution = objectsNeedingResolution,
            )
        }
        else ->
            ResolvePassiveValuesResult(
                toEngineResult(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef),
                emptyList(),
            )
    }
}

context(operation: OperationContext)
private fun EngineObjectData.Sync.resolvePassiveObjectValues(
    constructionDemand: SelectionForest,
    invocationDemand: SelectionForest,
    path: List<PathComponent>,
): ResolvePassiveValuesResult {
    val constructionDemandByKey =
        constructionDemand.applicableGroundSelections(schemaType).byGroundKey()
    val invocationDemandByKey =
        invocationDemand.applicableGroundSelections(schemaType).byGroundKey()
    if (operation.selectiveResolvers) {
        val selectedFieldNames =
            invocationDemandByKey.keys.mapTo(linkedSetOf()) { key -> key.field.name }
        val unselectedKeys = getSelections().toSet() - selectedFieldNames
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${schemaType.name} contains unselected fields: " +
                unselectedKeys.joinToString()
        }
    }

    val selectedKeys =
        getSelections()
            .map { fieldName ->
                val field = schemaType.requireField(fieldName)
                require(field.args.isEmpty()) {
                    "Passive object field ${schemaType.name}/$fieldName must be argumentless"
                }
                ObjectEngineResult.GroundKey.of(field, emptyMap())
            }.toSet()
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
                        )
                put(key, fieldValue.engineResult)
            }
        }
    val engineResult = ObjectEngineResult.of(schemaType, values, mutable = true)
    val localResolution =
        if (hasUnresolvedDemand(constructionDemand)) {
            listOf(
                PassiveObjectOccurrence(
                    path = path,
                    source = this,
                    selections = constructionDemand,
                    target = engineResult,
                ),
            )
        } else {
            emptyList()
        }
    return ResolvePassiveValuesResult(
        engineResult = engineResult,
        objectsNeedingResolution = localResolution,
    )
}

context(operation: OperationContext)
private fun EngineOutputData?.hasUnresolvedDemand(
    selections: SelectionForest,
): Boolean =
    when (this) {
        is EngineObjectData.Sync -> hasUnresolvedDemand(selections)
        is List<*> -> any { value -> value.hasUnresolvedDemand(selections) }
        else -> false
    }

context(operation: OperationContext)
private fun EngineObjectData.Sync.hasUnresolvedDemand(
    selections: SelectionForest,
): Boolean =
    selections
        .applicableGroundSelections(schemaType)
        .byGroundKey()
        .any { (key, selection) ->
            if (!isPresent(key.field.name)) {
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
context(operation: OperationContext)
internal fun EngineObjectData.Sync.materializedChildOccurrences(
    path: List<PathComponent>,
    selections: ObjectSelectionForest,
    resolved: ObjectEngineResult,
): List<PassiveObjectOccurrence> =
    selections.byGroundKey().flatMap { (key, selection) ->
        if (!isPresent(key.field.name)) {
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
