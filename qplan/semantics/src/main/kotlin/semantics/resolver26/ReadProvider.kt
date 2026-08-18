package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Schema
import model.Selection
import model.SimpleEngineResult
import model.TypeExpr
import model.Value
import model.VariableBinding
import model.fetchBindings
import model.objectKey
import model.selectionForestOf
import model.toValue
import model.registry.StampedObjectPathDefinition
import semantics.RuntimeSupport

// Traverses a provider path through OER promises and returns its terminal input-compatible value.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
internal suspend fun ObjectEngineResult.readProvider(
    definition: StampedObjectPathDefinition,
    reader: List<PathComponent>,
): VariableBinding {
    var current = this
    definition.path.forEachIndexed { index, openKey ->
        val specializedKey: ObjectEngineResult.ObjectKey =
            Selection.of(
                key = openKey,
                possibleTypes = setOf(current.type),
                subselections = selectionForestOf(),
            ).objectKey(current.type)
        val groundKey: ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                field = specializedKey.field,
                arguments =
                    specializedKey.arguments.fetchBindings(
                        specializedKey.field.arguments,
                    ),
            )
        val cell = current.reserveCell(groundKey)
        diagnosticInstrumentation.cycleCheck(reader, cell)
        val value = cell.reserveValue().await()
        if (index == definition.path.lastIndex) {
            return value.toProviderBinding()
        }
        when (value) {
            null -> return VariableBinding.of(null)
            ErrorEngineResult -> return VariableBinding.Error
            is ObjectEngineResult -> current = value
            else -> error("Resolver26 provider path crossed a non-object at $groundKey")
        }
    }
    error("Resolver26 provider path must be nonempty")
}

// Converts a provider result to an input value and rejects object-valued terminals.
private fun EngineResult?.toProviderBinding(): VariableBinding =
    when (this) {
        null -> VariableBinding.of(null)
        ErrorEngineResult -> VariableBinding.Error
        is SimpleEngineResult -> VariableBinding.of(toValue())
        is ListEngineResult -> toProviderInputListBinding()
        is ObjectEngineResult ->
            error("A path-variable provider cannot terminate at an object")
    }

// Converts a provider list to an input list after checking its element type.
@Suppress("UNCHECKED_CAST")
private fun ListEngineResult.toProviderInputListBinding(): VariableBinding {
    require(typeExpr.baseType is Schema.InputType) {
        "A path-variable provider list must contain input-compatible simple values"
    }
    val values = mutableListOf<Value.Input?>()
    indices.forEach { index ->
        when (val binding = get(index).getValue().get().toProviderBinding()) {
            VariableBinding.Error -> return VariableBinding.Error
            is VariableBinding.Input -> values += binding.value
        }
    }
    return VariableBinding.of(
        Value.InputList.of(
            typeExpr = typeExpr as TypeExpr<Schema.InputType>,
            values = values,
        ),
    )
}
