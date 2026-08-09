package semantics.contract

import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.Schema
import model.Selection
import model.TypeExpr
import model.Value
import model.instantiateBindings
import model.objectKey
import model.selectionForestOf
import semantics.arbitrary.registeredResolverCells
import kotlin.test.assertEquals

/**
 * Independently reads every activated object-path provider from the completed result.
 */
context(world: Assumptions)
fun EngineResult.Object.validateObjectPathBindings() {
    registeredResolverCells(world.resolverRegistry).forEach { cell ->
        val resolver =
            world.resolverRegistry.resolver(
                world.schema.objectField(
                    cell.canonicalField.typeName,
                    cell.canonicalField.fieldName,
                ),
            )
        resolver
            .stampedObjectPathDefinitions(cell.occurrencePath)
            .forEach { definition ->
                val expected =
                    cell.containingObject.readCompletedProvider(definition.path)
                assertEquals(expected, world.binding(definition.variable))
            }
    }
}

context(world: Assumptions)
private fun EngineResult.Object.readCompletedProvider(
    path: List<Value.Key>,
): Value.Input? {
    var current = this
    path.forEachIndexed { index, openKey ->
        val specialized =
            Selection.of(
                key = openKey,
                possibleTypes = setOf(current.type),
                subselections = selectionForestOf(),
            ).objectKey(current.type)
        val key =
            ObjectSelectionForest.of(
                current.type,
                listOf(
                    ObjectSelection.of(
                        key = specialized,
                        possibleTypes = setOf(current.type),
                        subselections = selectionForestOf(),
                    ),
                ),
            ).instantiateBindings()
                .groundKeys()
                .single()
        val value = current.fetch(key).value
        if (value == null) return null
        if (value == Value.Error) return Value.Error
        if (index == path.lastIndex) return value.toInputValue()
        current =
            value as? EngineResult.Object
                ?: error("Completed provider path crossed a non-object at $key")
    }
    error("Provider path must be nonempty")
}

private fun EngineResult.toInputValue(): Value.Input =
    when (this) {
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.List -> toInputList()
        is EngineResult.Object ->
            error("An object-path provider cannot terminate at an object")
    }

@Suppress("UNCHECKED_CAST")
private fun EngineResult.List.toInputList(): Value.InputList {
    require(typeExpr.baseType is Schema.InputType)
    return Value.InputList.of(
        typeExpr = typeExpr as TypeExpr<Schema.InputType>,
        values = map { cell -> cell.value?.toInputValue() },
    )
}
