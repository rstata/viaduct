package semantics.contract

import model.requireObjectField
import model.Assumptions
import model.EngineInputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.Schema
import model.Selection
import model.Stamp
import model.TypeExpr
import model.VariableBinding
import model.instantiateBindings
import model.objectKey
import model.registry.FieldResolver
import model.registry.StampedObjectPathDefinition
import model.registry.VariableDefinition
import model.selectionForestOf
import model.toEngineInputListData
import model.toEngineSimpleData
import semantics.arbitrary.registeredResolverOccurrences
import kotlin.test.assertEquals

/**
 * Independently reads every activated object-path provider from the completed result.
 */
context(world: Assumptions)
fun ObjectEngineResult.validateObjectPathBindings() {
    this.registeredResolverOccurrences(world.resolverRegistry).forEach { cell ->
        val resolver =
            world.resolverRegistry.resolver(
                world.schema.requireObjectField(
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
    val selectionStamp = groundKey?.stamp as? Stamp.Occurrence
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
                    StampedObjectPathDefinition.of(
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
): VariableBinding {
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
        if (value == null) return VariableBinding.of(null)
        if (value == ErrorEngineResult) return VariableBinding.Error
        if (index == path.lastIndex) {
            return value.toVariableBinding(key.field.type)
        }
        current =
            value as? ObjectEngineResult
                ?: error("Completed provider path crossed a non-object at $key")
    }
    error("Provider path must be nonempty")
}

private fun EngineResult.toVariableBinding(
    expectedType: TypeExpr<Schema.OutputTypeDef>,
): VariableBinding =
    when (this) {
        ErrorEngineResult -> VariableBinding.Error
        is ListEngineResult -> toInputListBinding()
        is ObjectEngineResult ->
            error("An object-path provider cannot terminate at an object")
        else ->
            VariableBinding.of(
                toEngineSimpleData(expectedType.baseType as Schema.SimpleTypeDef),
            )
    }

@Suppress("UNCHECKED_CAST")
private fun ListEngineResult.toInputListBinding(): VariableBinding {
    require(typeExpr.baseType is Schema.InputTypeDef)
    val values = mutableListOf<EngineInputData?>()
    indices.forEach { index ->
        val result = get(index).get()
        val binding =
            if (result == null) {
                VariableBinding.of(null)
            } else {
                result.toVariableBinding(typeExpr)
            }
        when (binding) {
            VariableBinding.Error -> return VariableBinding.Error
            is VariableBinding.Input -> values += binding.value
        }
    }
    return VariableBinding.of(
        toEngineInputListData(
            expectedType =
                TypeExpr.List.of(
                    elementType = typeExpr as TypeExpr<Schema.InputTypeDef>,
                    isNullable = false,
                ),
            value = values,
        ),
    )
}
