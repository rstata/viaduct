package semantics

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.objectKey
import model.selectionForestOf

internal fun Fragment.variables(): Set<Value.Variable> =
    subselections.variables()

internal fun Selection.variables(): Set<Value.Variable> =
    key.arguments.variables() + subselections.variables()

internal fun SelectionForest.variables(): Set<Value.Variable> =
    flatMapToSet { it.variables() }

internal fun List<Value.Key>.variables(): Set<Value.Variable> =
    fold(emptySet()) { result, key -> result + key.arguments.variables() }

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

internal fun List<Value.Key>.instantiateVariables(
    bindings: Map<Value.Variable, Value.Input?>,
): List<Value.Key> =
    map { key ->
        Value.Key.of(
            field = key.field,
            arguments =
                key.arguments.fieldValues.mapValues { (_, value) ->
                    value.instantiateVariables(bindings)
                },
        )
    }

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

internal fun List<Value.Key>.providerSelection(
    rootType: Schema.ObjectType,
): Selection = providerSelection(setOf(rootType))

private fun List<Value.Key>.providerSelection(
    possibleTypes: Set<Schema.ObjectType>,
): Selection {
    val key = first()
    val remaining = drop(1)
    val outputType = key.field.typeExpr.baseType
    return Selection.of(
        key = key,
        possibleTypes = possibleTypes,
        subselections =
            if (remaining.isEmpty()) {
                selectionForestOf()
            } else {
                require(outputType is Schema.CompositeType)
                selectionForestOf(remaining.providerSelection(outputType.possibleTypes))
            },
    )
}

context(world: Assumptions)
internal fun EngineResult.Object.readVariable(path: List<Value.Key>): Value.Input? {
    val sourceKey = path.first()
    val key =
        Value.ObjectKey.of(
            field = world.schema.objectField(type.typeName, sourceKey.field.fieldName),
            arguments = sourceKey.arguments.fieldValues,
        )
    val value = fetch(key).value
    if (path.size == 1) return value.toVariableInput()
    return when (value) {
        null -> null
        Value.Error -> Value.Error
        is EngineResult.Object -> value.readVariable(path.drop(1))
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
