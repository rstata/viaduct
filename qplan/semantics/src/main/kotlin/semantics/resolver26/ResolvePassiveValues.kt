package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.invariants.conformsToOutputSchemaType
import model.instantiateBindings
import model.merge
import model.outputType
import model.outputValue
import model.requireField
import model.schemaType
import model.selectionForestOf
import model.toEngineResult
import viaduct.engine.api.EngineObjectData

// Builds one passive result value, launching an orchestration lifecycle for every object it creates.
context(world: Assumptions, support: Resolver26Support)
internal fun EngineOutputData?.resolvePassiveValues(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
    constructionDemand: SelectionForest,
): EngineResult? {
    require(conformsToOutputSchemaType(expectedType)) {
        "Resolver output does not conform to $expectedType"
    }
    return when (this) {
        null -> null
        is EngineErrorData -> ErrorEngineResult.of(this)
        is EngineObjectData.Sync ->
            resolvePassiveObjectValues(
                path = path,
                resolverDemand = resolverDemand,
                constructionDemand = constructionDemand,
            )
        is List<*> -> {
            val elementType = checkNotNull(expectedType.unwrapList())
            ListEngineResult.of(
                typeExpr = elementType,
                values =
                    mapIndexed { index, value ->
                        value.resolvePassiveValues(
                            expectedType = elementType,
                            path = path + ListEngineResult.Index.of(index),
                            resolverDemand = resolverDemand,
                            constructionDemand = constructionDemand,
                        )
                    },
            )
        }
        else ->
            toEngineResult(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
    }
}

// Creates one mutable OER and enters its orchestration lifecycle before descending into children.
context(world: Assumptions, support: Resolver26Support)
private fun EngineObjectData.Sync.resolvePassiveObjectValues(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
    constructionDemand: SelectionForest,
): ObjectEngineResult {
    val target =
        ObjectEngineResult.of(
            type = schemaType,
            mutable = true,
        )
    val orchestration =
        ObjectOrchestrationTask(
            world = world,
            support = support,
            path = path,
            source = this,
            target = target,
            initialDemand = constructionDemand + resolverDemand,
        )
    val closedDemand = orchestration.prepare()
    materializePassiveFields(
        target = target,
        path = path,
        resolverDemand = resolverDemand,
        closedDemand = closedDemand,
    )
    orchestration.launch()
    return target
}

// Copies direct passive fields and recursively launches object orchestration in their values.
context(world: Assumptions, support: Resolver26Support)
private fun EngineObjectData.Sync.materializePassiveFields(
    target: ObjectEngineResult,
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
    closedDemand: ObjectSelectionForest,
) {
    val mergedResolverDemand =
        resolverDemand
            .merge(schemaType)
    val passiveDemandByKey =
        mergedResolverDemand
            .filter { selection ->
                (selection as ObjectSelection).key.field !in world.resolverRegistry
            }.instantiateBindings()
            .byGroundKey()
    if (world.selectiveResolvers) {
        val selectedFieldNames =
            mergedResolverDemand
                .keys()
                .mapTo(linkedSetOf()) { key -> key.field.name }
        val unselectedKeys = getSelections().toSet() - selectedFieldNames
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${schemaType.name} contains unselected fields: " +
                unselectedKeys.joinToString()
        }
    }

    selectedPassiveKeys(passiveDemandByKey).forEach { key ->
        val arguments = key.arguments
        require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
            "Passive object field ${schemaType.name}/${key.field.name} must be argumentless"
        }
        val projectionDemand =
            passiveDemandByKey[key]
                ?.subselections
                ?: selectionForestOf()
        val childConstructionDemand =
            closedDemand
                .byKey()[key]
                ?.subselections
                ?: selectionForestOf()
        val value =
            outputValue(key.field.name)
                .resolvePassiveValues(
                    expectedType = key.field.outputType,
                    path = path + key,
                    resolverDemand = projectionDemand,
                    constructionDemand = childConstructionDemand,
                )
        target.reserveCell(key).also { cell ->
            cell.setValue(value)
            cell.setAccessResult(true)
        }
    }
}

// Selects projected passive keys in selective worlds and every returned passive key otherwise.
context(world: Assumptions)
private fun EngineObjectData.Sync.selectedPassiveKeys(
    passiveDemandByKey: Map<ObjectEngineResult.GroundKey, ObjectSelection>,
): Set<ObjectEngineResult.GroundKey> =
    if (world.selectiveResolvers) {
        passiveDemandByKey.keys
    } else {
        getSelections()
            .map { fieldName ->
                val field = schemaType.requireField(fieldName)
                require(field.args.isEmpty()) {
                    "Passive object field ${schemaType.name}/$fieldName must be argumentless"
                }
                ObjectEngineResult.GroundKey.of(field, emptyMap())
            }.filter { key -> key.field !in world.resolverRegistry }
            .toSet()
    }
