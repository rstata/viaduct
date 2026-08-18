package semantics.contract

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.Schema
import model.Selection
import model.TypeExpr
import model.Value
import model.instantiateBindings
import model.objectKey
import model.registry.FieldResolver
import model.registry.StampedObjectPathDefinition
import model.registry.VariableDefinition
import model.selectionForestOf
import model.toValue
import semantics.arbitrary.registeredResolverOccurrences
import kotlin.test.assertEquals

/**
 * Independently reads every activated object-path provider from the completed result.
 */
context(world: Assumptions)
fun ObjectEngineResult.validateObjectPathBindings() {
    registeredResolverOccurrences(world.resolverRegistry).forEach { cell ->
        val resolver =
            world.resolverRegistry.resolver(
                world.schema.objectField(
                    cell.canonicalField.typeName,
                    cell.canonicalField.fieldName,
                ),
            )
        resolver
            .boundObjectPathDefinitions(cell.occurrencePath)
            .forEach { definition ->
                val expected =
                    cell.containingObject.readCompletedProvider(definition.path)
                assertEquals(expected, world.getBinding(definition.variable))
            }
    }
}

context(world: Assumptions)
internal fun FieldResolver.boundObjectPathDefinitions(
    path: List<PathComponent>,
): List<StampedObjectPathDefinition> {
    val groundKey = path.lastOrNull() as? ObjectEngineResult.GroundKey
    val selectionStamp = groundKey?.selectionStamp
    val selectionStampedDefinitions =
        if (selectionStamp != null) {
            selectionStampedVariableDefinitionsFrom(selectionStamp)
        } else {
            selectionStampedVariableDefinitions(path)
        }
    val boundDefinitions =
        selectionStampedDefinitions
            .mapNotNull { stampedDefinition ->
                (stampedDefinition.definition as? VariableDefinition.FromObjectField)?.let {
                    StampedObjectPathDefinition(
                        variable = stampedDefinition.variable,
                        path = it.path,
                    )
                }
            }
    return boundDefinitions
        .takeIf { definitions ->
            definitions.isNotEmpty() &&
                definitions.all { definition -> world.isBound(definition.variable) }
        } ?: stampedPathVarDefinitions(path)
}

context(world: Assumptions)
private fun ObjectEngineResult.readCompletedProvider(
    path: List<ObjectEngineResult.Key>,
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
        val value = current.getCell(key).get()
        if (value == null) return null
        if (value == ErrorEngineResult) return Value.Error
        if (index == path.lastIndex) return value.toInputValue()
        current =
            value as? ObjectEngineResult
                ?: error("Completed provider path crossed a non-object at $key")
    }
    error("Provider path must be nonempty")
}

private fun EngineResult.toInputValue(): Value.Input =
    when (this) {
        ErrorEngineResult -> Value.Error
        is model.SimpleEngineResult -> toValue()
        is ListEngineResult -> toInputList()
        is ObjectEngineResult ->
            error("An object-path provider cannot terminate at an object")
    }

@Suppress("UNCHECKED_CAST")
private fun ListEngineResult.toInputList(): Value.InputList {
    require(typeExpr.baseType is Schema.InputType)
    return Value.InputList.of(
        typeExpr = typeExpr as TypeExpr<Schema.InputType>,
        values = map { cell -> cell.get()?.toInputValue() },
    )
}
