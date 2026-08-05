package semantics

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.selectionForestOf
import semantics.correctresolution.concreteObjectKey

internal fun Fragment.variables(): Set<Value.Variable> =
    subselections.variables()

internal fun Selection.variables(): Set<Value.Variable> =
    key.arguments.variables() + subselections.variables()

internal fun SelectionForest.variables(): Set<Value.Variable> =
    flatMapToSet { it.variables() }

internal fun Value.Arguments.variables(): Set<Value.Variable> =
    fieldValues.values.fold(emptySet()) { result, value -> result + value.variables() }

private fun Value.Input?.variables(): Set<Value.Variable> =
    when {
        this == null || this == Value.Error -> emptySet()
        this is Value.Variable -> setOf(this)
        this is Value.InputList ->
            values.fold(emptySet()) { result, value -> result + value.variables() }
        this is Value.InputObject ->
            fieldValues.values.fold(emptySet()) { result, value -> result + value.variables() }
        else -> emptySet()
    }

internal fun Fragment.instantiateVariables(
    bindings: Map<Value.Variable, Value.Input?>,
): Fragment =
    Fragment.of(
        nominalType,
        subselections.instantiateVariables(bindings),
    )

private fun SelectionForest.instantiateVariables(
    bindings: Map<Value.Variable, Value.Input?>,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(selection.instantiateVariables(bindings))
    }

internal fun Selection.instantiateVariables(
    bindings: Map<Value.Variable, Value.Input?>,
): Selection =
    Selection.of(
        key =
            Value.Key.of(
                field = key.field,
                arguments =
                    key.arguments.fieldValues.mapValues { (_, value) ->
                        value.instantiateVariables(bindings)
                    },
            ),
        possibleTypes = possibleTypes,
        subselections = subselections.instantiateVariables(bindings),
    )

private fun Value.Input?.instantiateVariables(
    bindings: Map<Value.Variable, Value.Input?>,
): Value.Input? =
    when {
        this == null || this == Value.Error -> this
        this is Value.Variable -> bindings.getValue(this)
        this is Value.InputList ->
            Value.InputList.of(
                typeExpr,
                values.map { it.instantiateVariables(bindings) },
            )
        this is Value.InputObject ->
            Value.InputObject.of(
                type,
                fieldValues.mapValues { (_, value) -> value.instantiateVariables(bindings) },
            )
        else -> this
    }

context(world: Assumptions)
internal fun EngineResult.Object.readVariable(selection: Selection): Value.Input? {
    require(type in selection.possibleTypes) {
        "Variable provider selection does not apply to ${type.typeName}"
    }
    val value = fetch(selection.concreteObjectKey(type)).value
    if (selection.subselections.isEmpty()) return value.toVariableInput()
    return when (value) {
        null -> null
        Value.Error -> Value.Error
        is EngineResult.Object -> {
            val applicable =
                selection.subselections.filter { value.type in it.possibleTypes }
            value.readVariable(applicable.single())
        }
        is EngineResult.List ->
            throw IllegalArgumentException("Variable provider paths cannot traverse lists")
        is Value.Simple ->
            throw IllegalArgumentException("Variable provider path continues below a simple value")
    }
}

private fun EngineResult?.toVariableInput(): Value.Input? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.Object ->
            throw IllegalArgumentException("Variable provider paths cannot terminate at objects")
        is EngineResult.List ->
            Value.InputList.of(
                typeExpr = typeExpr.toInputTypeExpr(),
                values = map { cell -> cell.value.toVariableInput() },
            )
    }

@Suppress("UNCHECKED_CAST")
private fun TypeExpr<Schema.OutputType>.toInputTypeExpr(): TypeExpr<Schema.InputType> {
    require(baseType is Schema.InputType) {
        "Variable provider list must contain input-compatible values"
    }
    return this as TypeExpr<Schema.InputType>
}
