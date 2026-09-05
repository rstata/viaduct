package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.invariants.conformsToOutputSchemaType
import model.isParentField
import model.merge
import model.outputType
import model.outputValue
import model.requireField
import model.schemaType
import model.selectionForestOf
import model.toEngineResult
import viaduct.engine.api.EngineObjectData

// Builds one passive result value, launching an orchestration lifecycle for every object it creates.
context(operation: Resolver26OperationContext)
internal fun EngineOutputData?.resolvePassiveValues(
    root: ObjectEngineResult,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    invocationDemand: SelectionForest,
    constructionDemand: SelectionForest,
    parent: OEROccurrenceContext? = null,
): EngineResult? {
    require(conformsToOutputSchemaType(expectedType)) {
        "Resolver output does not conform to $expectedType"
    }
    return when (this) {
        null -> null
        is EngineErrorData -> ErrorEngineResult.of(this)
        is EngineObjectData.Sync ->
            resolvePassiveObjectValues(
                root = root,
                path = path,
                invocationDemand = invocationDemand,
                constructionDemand = constructionDemand,
                parent = parent,
            )
        is List<*> -> {
            val elementType = checkNotNull(expectedType.unwrapList())
            ListEngineResult.of(
                typeExpr = elementType,
                values =
                    mapIndexed { index, value ->
                        value.resolvePassiveValues(
                            root = root,
                            expectedType = elementType,
                            path = path + ListEngineResult.Index.of(index),
                            invocationDemand = invocationDemand,
                            constructionDemand = constructionDemand,
                            parent = parent,
                        )
                    },
            )
        }
        else ->
            toEngineResult(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
    }
}

// Creates one mutable OER and enters its orchestration lifecycle before descending into children.
context(operation: Resolver26OperationContext)
private fun EngineObjectData.Sync.resolvePassiveObjectValues(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    invocationDemand: SelectionForest,
    constructionDemand: SelectionForest,
    parent: OEROccurrenceContext?,
): ObjectEngineResult {
    val target =
        ObjectEngineResult.of(
            type = schemaType,
            mutable = true,
        )
    val occurrence =
        OEROccurrenceContext(
            root = root,
            path = path,
            target = target,
            parent = parent,
        )
    val orchestration =
        ObjectOrchestrationTask(
            operation = operation,
            occurrence = occurrence,
            source = this,
            initialDemand = constructionDemand,
        )
    val closedDemand = orchestration.prepare()
    materializePassiveFields(
        occurrence = occurrence,
        invocationDemand = invocationDemand,
        closedDemand = closedDemand,
    )
    orchestration.launch()
    return target
}

// Copies resolver-returned passive fields except engine-provided parent backedges.
context(operation: Resolver26OperationContext)
private fun EngineObjectData.Sync.materializePassiveFields(
    occurrence: OEROccurrenceContext,
    invocationDemand: SelectionForest,
    closedDemand: ObjectSelectionForest,
) {
    val invocationDemandByKey = invocationDemand.merge(schemaType).byKey()
    if (operation.selectiveResolvers) {
        val selectedFieldNames =
            invocationDemandByKey.keys
                .mapTo(linkedSetOf()) { key -> key.field.name }
        val unselectedKeys = getSelections().toSet() - selectedFieldNames
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${schemaType.name} contains unselected fields: " +
                unselectedKeys.joinToString()
        }
    }

    val closedDemandByKey = closedDemand.byKey()
    getSelections().forEach { fieldName ->
        val field = schemaType.requireField(fieldName)
        if (field.isParentField()) return@forEach
        require(field.args.isEmpty()) {
            "Resolver output must not supply argument-bearing field " +
                "${schemaType.name}/$fieldName"
        }
        val demandedKeys = linkedSetOf<ObjectEngineResult.GroundKey>()
        (invocationDemandByKey.keys + closedDemandByKey.keys).forEach { key ->
            if (key.field == field) {
                check(key is ObjectEngineResult.GroundKey) {
                    "Passive returned field has an open key: $key"
                }
                demandedKeys += key
            }
        }
        if (demandedKeys.isEmpty()) {
            demandedKeys += ObjectEngineResult.GroundKey.of(field, emptyMap())
        }
        demandedKeys.forEach { key ->
            val childInvocationDemand =
                invocationDemandByKey[key]
                    ?.subselections
                    ?: selectionForestOf()
            val childConstructionDemand =
                closedDemandByKey[key]
                    ?.subselections
                    ?: selectionForestOf()
            val value =
                outputValue(key.field.name)
                    .resolvePassiveValues(
                        root = occurrence.root,
                        expectedType = key.field.outputType,
                        path = occurrence.coordinate(key),
                        invocationDemand = childInvocationDemand,
                        constructionDemand = childConstructionDemand,
                        parent = occurrence,
                    )
            occurrence.target.reserveCell(key).also { cell ->
                cell.setValue(value)
            }
        }
    }
}
