package model

import model.invariants.conformsToSchemaType

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

        /** The canonical cell whose value and check are both [Value.Error]. */
        data object Error : Cell {
            override val value: EngineResult = Value.Error
            override val check: Value.Boolean = Value.Error
        }

        companion object {
            fun of(
                value: EngineResult?,
                check: Value.Boolean = Value.Boolean.of(true),
            ): Cell = CellImpl(value, check)
        }
    }

    /**
     * A finite object result whose [cells] are exact, alias-free schema coordinates.
     *
     * Every present cell key belongs to [type], contains no variables, and has a value that
     * conforms recursively to the field's type expression. A mutable object may gain absent cells
     * through [write], but a present cell never changes.
     */
    sealed interface Object : EngineResult {
        val type: Schema.ObjectType

        /** A stable snapshot of the currently written cells. */
        val cells: Map<Value.GroundKey, Cell>

        val keys: Set<Value.GroundKey>
            get() = cells.keys

        /** Whether [key] has been written. */
        fun isSet(key: Value.GroundKey): Boolean = key in cells

        /** @throws MissingFieldException when [key] is absent */
        fun fetch(key: Value.GroundKey): Cell =
            cells[key]
                ?: throw MissingFieldException(type.typeName, key.field.fieldName)

        /**
         * Writes the first and only cell for [key].
         *
         * @throws IllegalStateException when this object is immutable or [key] is already written
         * @throws IllegalArgumentException when [key] or [cell] violates an object-result invariant
         */
        fun write(
            key: Value.GroundKey,
            cell: Cell,
        )

        companion object {
            /**
             * ### Invariant: object-engine-result-factory-schema-conformance
             *
             * Every result's present cells satisfy `result.conformsToSchema()` in its reasoning
             * world. When [mutable] is false, every [Object.write] throws. When it is true, each
             * absent key may be written once. A mutable result must not be used as a hash key while
             * writes may continue because structural equality and hashing observe current cells.
             */
            fun of(
                type: Schema.ObjectType,
                cells: Map<Value.GroundKey, Cell>,
                mutable: Boolean = false,
            ): Object {
                cells.forEach { (key, cell) ->
                    validateObjectCell(type, key, cell)
                }
                return ObjectResultImpl(type, cells, mutable)
            }
        }
    }

    /**
     * A typed list result whose elements retain checker results.
     *
     * [typeExpr] is the expected type of each element, including its nullability and nested lists.
     * This type exposes only the finite positional operations used by engine-result reasoning; it
     * is not a Kotlin [kotlin.collections.List].
     */
    sealed interface List : EngineResult {
        val typeExpr: TypeExpr<Schema.OutputType>
        val size: Int
        val indices: IntRange
            get() = 0 until size

        operator fun get(index: Int): Cell

        fun <R> map(transform: (Cell) -> R): kotlin.collections.List<R> =
            indices.map { index -> transform(get(index)) }

        fun all(predicate: (Cell) -> Boolean): Boolean =
            indices.all { index -> predicate(get(index)) }

        fun forEachIndexed(action: (index: Int, Cell) -> Unit) {
            indices.forEach { index -> action(index, get(index)) }
        }

        companion object {
            /**
             * ### Invariant: list-engine-result-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                typeExpr: TypeExpr<Schema.OutputType>,
                cells: kotlin.collections.List<Cell>,
            ): List {
                require(cells.all { it.value.conformsToSchemaType(typeExpr) }) {
                    "List engine result contains an element incompatible with $typeExpr"
                }
                return ListResultImpl(typeExpr, cells)
            }
        }
    }
}

/**
 * Returns the structural union of this nullable result and [other].
 *
 * Two null values have a null union. A null and non-null value have no union. For two non-null
 * values, this partial mathematical function is defined only for results of the same variant.
 *
 * @throws IllegalArgumentException when the union is undefined
 */
fun EngineResult?.union(other: EngineResult?): EngineResult? {
    if (this == null) {
        require(other == null) { "Cannot union null and non-null engine results" }
        return null
    }
    require(other != null) { "Cannot union null and non-null engine results" }

    return when (this) {
        is Value.Simple -> {
            require(other is Value.Simple) {
                "Cannot union different engine-result variants"
            }
            require(this == other) { "Cannot union unequal simple engine results" }
            this
        }

        is EngineResult.List -> {
            require(other is EngineResult.List) {
                "Cannot union different engine-result variants"
            }
            union(other)
        }

        is EngineResult.Object -> {
            require(other is EngineResult.Object) {
                "Cannot union different engine-result variants"
            }
            union(other)
        }
    }
}

/**
 * Returns the object result containing the union of every cell present in either operand.
 *
 * A cell present in both operands is unioned componentwise. A cell present in only one operand is
 * retained unchanged.
 *
 * @throws IllegalArgumentException when the object types differ or any shared cell has no union
 */
fun EngineResult.Object.union(other: EngineResult.Object): EngineResult.Object {
    require(type == other.type) {
        "Cannot union object engine results of different types"
    }

    val unionCells =
        (keys + other.keys).associateWith { key ->
            when {
                key !in keys -> other.fetch(key)
                key !in other.keys -> fetch(key)
                else -> fetch(key).union(other.fetch(key))
            }
        }
    return EngineResult.Object.of(type, unionCells)
}

/**
 * Returns the position-wise union of this list and [other].
 *
 * The operands must have equal element type expressions and lengths.
 *
 * @throws IllegalArgumentException when the type expressions or lengths differ, or when any
 * corresponding cells have no union
 */
fun EngineResult.List.union(other: EngineResult.List): EngineResult.List {
    require(typeExpr == other.typeExpr) {
        "Cannot union list engine results with different element types"
    }
    require(size == other.size) {
        "Cannot union list engine results of different lengths"
    }
    return EngineResult.List.of(
        typeExpr = typeExpr,
        cells = indices.map { index -> this[index].union(other[index]) },
    )
}

private data class CellImpl(
    override val value: EngineResult?,
    override val check: Value.Boolean,
) : EngineResult.Cell

private class ObjectResultImpl(
    override val type: Schema.ObjectType,
    cells: Map<Value.GroundKey, EngineResult.Cell>,
    private val mutable: Boolean,
) : EngineResult.Object {
    private val cellStore = OnceStore(cells)

    override val cells: Map<Value.GroundKey, EngineResult.Cell>
        get() = cellStore.snapshot()

    override fun isSet(key: Value.GroundKey): Boolean =
        cellStore.isSet(key)

    override fun fetch(key: Value.GroundKey): EngineResult.Cell {
        if (!cellStore.isSet(key)) {
            throw MissingFieldException(type.typeName, key.field.fieldName)
        }
        return cellStore.read(key)
    }

    override fun write(
        key: Value.GroundKey,
        cell: EngineResult.Cell,
    ) {
        check(mutable) {
            "${type.typeName} result is immutable"
        }
        validateObjectCell(type, key, cell)
        cellStore.write(key, cell)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is EngineResult.Object &&
                    type == other.type &&
                    cells == other.cells
            )

    override fun hashCode(): Int =
        31 * type.hashCode() + cells.hashCode()
}

private data class ListResultImpl(
    override val typeExpr: TypeExpr<Schema.OutputType>,
    private val cells: kotlin.collections.List<EngineResult.Cell>,
) : EngineResult.List {
    override val size: Int
        get() = cells.size

    override fun get(index: Int): EngineResult.Cell = cells[index]
}

private fun EngineResult.Cell.union(other: EngineResult.Cell): EngineResult.Cell {
    require(check == other.check) { "Cannot union engine-result cells with unequal checks" }
    return EngineResult.Cell.of(
        value = value.union(other.value),
        check = check,
    )
}

private fun validateObjectCell(
    type: Schema.ObjectType,
    key: Value.GroundKey,
    cell: EngineResult.Cell,
) {
    require(key.field.containingType == type) {
        "${type.typeName} result contains a field owned by another type"
    }
    if (key.arguments.containsErrorValue()) {
        require(cell.value == Value.Error && cell.check == Value.Error) {
            "A key containing an argument error must contain an error value and check"
        }
    }
    require(cell.value.conformsToSchemaType(key.field.typeExpr)) {
        "${type.typeName}/${key.field.fieldName} result does not conform to " +
            key.field.typeExpr
    }
}

private fun Value.Arguments.containsErrorValue(): Boolean =
    fieldValues.values.any { it.containsErrorValue() }

private fun Value.Input?.containsErrorValue(): Boolean =
    when {
        this == Value.Error -> true
        this is Value.InputList -> values.any { it.containsErrorValue() }
        this is Value.InputObject -> fieldValues.values.any { it.containsErrorValue() }
        else -> false
    }
