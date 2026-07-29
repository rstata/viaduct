package model

/**
 * A finite, well-founded field-resolution result.
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
        val check: Value.Boolean

        companion object {
            fun of(
                value: EngineResult?,
                check: Value.Boolean,
            ): Cell = CellImpl(value, check)
        }
    }

    /**
     * A finite object result whose [cells] are exact, alias-free schema coordinates.
     *
     * Every key belongs to [type], contains no unresolved variables, and has a value that conforms
     * recursively to the field's type expression.
     */
    sealed interface Object : EngineResult {
        val type: Schema.ObjectType
        val cells: Map<Value.Key, Cell>

        val keys: Set<Value.Key>
            get() = cells.keys

        /** @throws MissingFieldException when [key] is absent */
        fun fetch(key: Value.Key): Cell =
            cells[key]
                ?: throw MissingFieldException(type.typeName, key.field.fieldName)

        companion object {
            fun of(
                type: Schema.ObjectType,
                cells: Map<Value.Key, Cell>,
            ): Object {
                require(cells.keys.all { it.field.containingType == type }) {
                    "${type.typeName} result contains a field owned by another type"
                }
                require(cells.keys.none { it.arguments.containsVariableValue() }) {
                    "${type.typeName} result keys cannot contain unresolved variables"
                }
                cells.forEach { (key, cell) ->
                    if (key.arguments.containsErrorValue()) {
                        require(cell.value == Value.Error && cell.check == Value.Error) {
                            "A key containing an argument error must contain an error value and check"
                        }
                    }
                    require(cell.value.conformsTo(key.field.typeExpr)) {
                        "${type.typeName}/${key.field.fieldName} result does not conform to " +
                            key.field.typeExpr
                    }
                }
                return ObjectResultImpl(type, cells)
            }
        }
    }

    /**
     * A typed list result whose elements retain checker results.
     *
     * [typeExpr] is the expected type of each element, including its nullability and nested lists.
     */
    sealed interface List : EngineResult, kotlin.collections.List<Cell> {
        val typeExpr: TypeExpr<Schema.OutputType>

        companion object {
            fun of(
                typeExpr: TypeExpr<Schema.OutputType>,
                cells: kotlin.collections.List<Cell>,
            ): List {
                require(cells.all { it.value.conformsTo(typeExpr) }) {
                    "List engine result contains an element incompatible with $typeExpr"
                }
                return ListResultImpl(typeExpr, cells)
            }
        }
    }
}

private data class CellImpl(
    override val value: EngineResult?,
    override val check: Value.Boolean,
) : EngineResult.Cell

private data class ObjectResultImpl(
    override val type: Schema.ObjectType,
    override val cells: Map<Value.Key, EngineResult.Cell>,
) : EngineResult.Object

private data class ListResultImpl(
    override val typeExpr: TypeExpr<Schema.OutputType>,
    private val cells: kotlin.collections.List<EngineResult.Cell>,
) : EngineResult.List,
    kotlin.collections.List<EngineResult.Cell> by cells

internal fun EngineResult?.conformsTo(
    typeExpr: TypeExpr<Schema.OutputType>,
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        Value.Error -> true
        is Value.Simple ->
            typeExpr is TypeExpr.Named && typeExpr.baseType == type
        is EngineResult.Object ->
            if (typeExpr is TypeExpr.Named) {
                val declaredType = typeExpr.baseType
                declaredType is Schema.CompositeType && type in declaredType.possibleTypes
            } else {
                false
            }
        is EngineResult.List ->
            typeExpr is TypeExpr.List &&
                typeExpr.elementType.canContainPure(this.typeExpr)
    }

private fun Value.Arguments.containsVariableValue(): Boolean =
    fieldValues.values.any { it.containsVariableValue() }

private fun Value.Arguments.containsErrorValue(): Boolean =
    fieldValues.values.any { it.containsErrorValue() }

private fun Value.Input?.containsErrorValue(): Boolean =
    when {
        this == Value.Error -> true
        this is Value.InputList -> values.any { it.containsErrorValue() }
        this is Value.InputObject -> fieldValues.values.any { it.containsErrorValue() }
        else -> false
    }
