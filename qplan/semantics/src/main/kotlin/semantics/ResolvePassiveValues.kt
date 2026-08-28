package semantics

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.Assumptions
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
import model.applicableGroundSelections
import model.invariants.conformsToOutputSchemaType
import model.schemaType
import model.requireField
import model.selectionForestOf
import model.toEngineResult

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

/**
 * Eagerly materializes every argumentless field present in this output.
 *
 * Selective worlds still require every present field to be included in [invocationDemand].
 * [constructionDemand] determines whether each root object occurrence requires orchestration.
 */
context(world: Assumptions)
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

context(world: Assumptions)
private fun EngineObjectData.Sync.resolvePassiveObjectValues(
    constructionDemand: SelectionForest,
    invocationDemand: SelectionForest,
    path: List<PathComponent>,
): ResolvePassiveValuesResult {
    val constructionDemandByKey =
        constructionDemand.applicableGroundSelections(schemaType).byGroundKey()
    val invocationDemandByKey =
        invocationDemand.applicableGroundSelections(schemaType).byGroundKey()
    if (world.selectiveResolvers) {
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
    val values =
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

context(world: Assumptions)
private fun EngineOutputData?.hasUnresolvedDemand(
    selections: SelectionForest,
): Boolean =
    when (this) {
        is EngineObjectData.Sync -> hasUnresolvedDemand(selections)
        is List<*> -> any { value -> value.hasUnresolvedDemand(selections) }
        else -> false
    }

context(world: Assumptions)
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
context(world: Assumptions)
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
