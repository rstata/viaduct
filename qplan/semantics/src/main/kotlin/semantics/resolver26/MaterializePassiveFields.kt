package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.outputType
import model.outputValue
import model.toEngineResult
import semantics.ResolvePassiveValuesResult
import semantics.resolvePassiveValues
import viaduct.engine.api.EngineObjectData

// Copies demanded non-resolver fields from the source into the target result.
// Object-valued descendants receive their own orchestration tasks.
internal fun ObjectOrchestrationTask.materializePassiveFields(
    demand: ObjectSelectionForest,
) {
    context(world) {
        demand.byKey().forEach { (objectKey, selection) ->
            if (objectKey.field !in world.resolverRegistry) {
                val groundKey: ObjectEngineResult.GroundKey =
                    objectKey as? ObjectEngineResult.GroundKey
                        ?: error("Resolver26 found open arguments on passive key $objectKey")
                val arguments = groundKey.arguments
                require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
                    "Resolver26 passive field ${groundKey.field.containingDef.name}/" +
                        "${groundKey.field.name} must be argumentless"
                }
                val sourceValue: EngineOutputData? =
                    source.outputValue(groundKey.field.name)
                if (!target.isCellSet(groundKey)) {
                    val passiveValuesResult: ResolvePassiveValuesResult =
                        sourceValue.resolvePassiveValues(
                            expectedType = groundKey.field.outputType,
                            path = path + groundKey,
                            resolverDemand = selection.subselections,
                        )
                    target.reserveCell(groundKey).also { cell ->
                        cell.setValue(passiveValuesResult.engineResult)
                        cell.setAccessResult(true)
                    }
                }
                launchPassiveChildOrchestrations(
                    childPath = path + groundKey,
                    childSource = sourceValue,
                    childTarget = target.getCell(groundKey).getValue().get(),
                    expectedType = groundKey.field.outputType,
                    childDemand = selection.subselections,
                )
            }
        }
    }
}

// Validates that one passive source value matches its materialized result.
// Launches object orchestration at object descendants and delegates list traversal element by element.
private fun ObjectOrchestrationTask.launchPassiveChildOrchestrations(
    childPath: List<PathComponent>,
    childSource: EngineOutputData?,
    childTarget: EngineResult?,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    childDemand: SelectionForest,
) {
    when (childSource) {
        null -> {
            check(childTarget == null) {
                "Resolver26 passive null source has non-null result at $childPath"
            }
        }

        is EngineErrorData -> {
            check(
                childTarget is ErrorEngineResult &&
                    childTarget.errorData === childSource,
            ) {
                "Resolver26 passive error source has different result at $childPath"
            }
        }

        is Int,
        is Double,
        is String,
        is Boolean,
        -> {
            val simpleType = expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef
            check(childTarget == childSource.toEngineResult(simpleType)) {
                "Resolver26 passive simple source has different result at $childPath"
            }
        }

        is EngineObjectData.Sync -> {
            check(childTarget is ObjectEngineResult) {
                "Resolver26 passive object source has non-object result at $childPath"
            }
            support.launchObjectOrchestrationTask(
                ObjectOrchestrationTask(
                    world = world,
                    support = support,
                    path = childPath,
                    source = childSource,
                    target = childTarget,
                    initialDemand = childDemand,
                ),
            )
        }

        is List<*> ->
            launchPassiveListChildOrchestrations(
                childPath = childPath,
                childSource = childSource,
                childTarget = childTarget,
                expectedType = expectedType,
                childDemand = childDemand,
            )

        else -> error("Unsupported resolver output at $childPath: $childSource")
    }
}

// Walks an aligned passive source/result list and processes each element at its indexed result path.
private fun ObjectOrchestrationTask.launchPassiveListChildOrchestrations(
    childPath: List<PathComponent>,
    childSource: List<*>,
    childTarget: EngineResult?,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    childDemand: SelectionForest,
) {
    val elementType = checkNotNull(expectedType.unwrapList())
    check(childTarget is ListEngineResult && childTarget.size == childSource.size) {
        "Resolver26 passive list source has different result shape at $childPath"
    }
    childSource.forEachIndexed { index, value ->
        launchPassiveChildOrchestrations(
            childPath = childPath + ListEngineResult.Index.of(index),
            childSource = value,
            childTarget = childTarget[index].getValue().get(),
            expectedType = elementType,
            childDemand = childDemand,
        )
    }
}
