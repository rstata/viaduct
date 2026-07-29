package model

/**
 * A finite, well-founded value in an [ObjectEngineResult] tree.
 *
 * Engine-result equality is structural over the documented properties. Schema definitions within
 * those properties use the canonical equality documented by [Schema].
 */
sealed interface EngineResult {
    /**
     * One resolved value and its retained checker result.
     *
     * A cell is used both for object fields and list elements. [check] remains uninterpreted by the
     * current correctness judgment.
     */
    sealed interface Cell {
        val value: EngineResult?
        val check: Schema.BooleanValue

        companion object {
            fun of(
                value: EngineResult?,
                check: Schema.BooleanValue,
            ): Cell = CellImpl(value, check)
        }
    }
}

/**
 * A finite object result whose [cells] are exact, alias-free schema coordinates.
 *
 * Every key belongs to [type], contains no unresolved variables, and has a value that conforms
 * recursively to the field's type expression. A key containing [Schema.ErrorValue] has an error
 * value and error check.
 */
sealed interface ObjectEngineResult : EngineResult {
    val type: Schema.ObjectType
    val cells: Map<Schema.ObjectKey, EngineResult.Cell>

    val keys: Set<Schema.ObjectKey>
        get() = cells.keys

    /** @throws MissingFieldException when [key] is absent */
    fun fetch(key: Schema.ObjectKey): EngineResult.Cell =
        cells[key]
            ?: throw MissingFieldException(type.typeName, key.field.fieldName)

    companion object {
        fun of(
            type: Schema.ObjectType,
            cells: Map<Schema.ObjectKey, EngineResult.Cell>,
        ): ObjectEngineResult {
            require(cells.keys.all { it.field.containingType == type }) {
                "${type.typeName} result contains a field owned by another type"
            }
            require(cells.keys.none { it.arguments.containsVariableValue() }) {
                "${type.typeName} result keys cannot contain unresolved variables"
            }
            cells.forEach { (key, cell) ->
                if (key.arguments.containsErrorValue()) {
                    require(cell.value == Schema.ErrorValue && cell.check == Schema.ErrorValue) {
                        "A key containing an argument error must contain an error value and check"
                    }
                }
                require(cell.value.conformsTo(key.field.typeExpr)) {
                    "${type.typeName}/${key.field.fieldName} result does not conform to " +
                        key.field.typeExpr
                }
            }
            return ObjectEngineResultImpl(type, cells)
        }
    }
}

/**
 * A typed list result whose elements retain checker results.
 *
 * [typeExpr] is the expected type of each element, including its nullability and any nested list
 * wrappers.
 */
sealed interface ListEngineResult : EngineResult, List<EngineResult.Cell> {
    val typeExpr: Schema.TypeExpr<Schema.OutputType>

    companion object {
        fun of(
            typeExpr: Schema.TypeExpr<Schema.OutputType>,
            cells: List<EngineResult.Cell>,
        ): ListEngineResult {
            require(cells.all { it.value.conformsTo(typeExpr) }) {
                "List engine result contains an element incompatible with $typeExpr"
            }
            return ListEngineResultImpl(typeExpr, cells)
        }
    }
}

private data class CellImpl(
    override val value: EngineResult?,
    override val check: Schema.BooleanValue,
) : EngineResult.Cell

private data class ObjectEngineResultImpl(
    override val type: Schema.ObjectType,
    override val cells: Map<Schema.ObjectKey, EngineResult.Cell>,
) : ObjectEngineResult

private data class ListEngineResultImpl(
    override val typeExpr: Schema.TypeExpr<Schema.OutputType>,
    private val cells: List<EngineResult.Cell>,
) : ListEngineResult,
    List<EngineResult.Cell> by cells

internal fun EngineResult?.conformsTo(
    typeExpr: Schema.TypeExpr<Schema.OutputType>,
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        Schema.ErrorValue -> true
        is Schema.SimpleValue ->
            typeExpr is Schema.TypeExpr.Named && typeExpr.baseType == type
        is ObjectEngineResult ->
            if (typeExpr is Schema.TypeExpr.Named) {
                val declaredType = typeExpr.baseType
                declaredType is Schema.CompositeType && type in declaredType.possibleTypes
            } else {
                false
            }
        is ListEngineResult ->
            typeExpr is Schema.TypeExpr.List &&
                typeExpr.elementType.canContainPure(this.typeExpr)
    }

private fun Schema.ArgumentsValue.containsVariableValue(): Boolean =
    fieldValues.values.any { it.containsVariableValue() }

private fun Schema.ArgumentsValue.containsErrorValue(): Boolean =
    fieldValues.values.any { it.containsErrorValue() }

private fun Schema.InputValue?.containsVariableValue(): Boolean =
    when {
        this == null || this == Schema.ErrorValue -> false
        this is Schema.VariableValue -> true
        this is Schema.InputListValue -> values.any { it.containsVariableValue() }
        this is Schema.InputObjectValue -> fieldValues.values.any { it.containsVariableValue() }
        else -> false
    }

private fun Schema.InputValue?.containsErrorValue(): Boolean =
    when {
        this == Schema.ErrorValue -> true
        this is Schema.InputListValue -> values.any { it.containsErrorValue() }
        this is Schema.InputObjectValue -> fieldValues.values.any { it.containsErrorValue() }
        else -> false
    }
