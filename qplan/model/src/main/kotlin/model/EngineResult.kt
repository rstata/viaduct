package model

import model.invariants.conformsToSchemaType

/**
 * A finite, well-founded field-resolution result.
 *
 * Equality depends on the result variant. [Value.Simple] values use structural equality, [Cell]
 * and [Object] values use reference equality, and [List] values use structural equality over their
 * type expression and positional cell equality. Schema definitions within those properties use
 * the canonical equality documented by [Schema].
 */
sealed interface EngineResult {
    /**
     * One result occurrence with independent write-once value and access-acceptance slots.
     *
     * A cell is used both for object fields and list elements. A completed `accessAccepted` value
     * of `true` means access is accepted and `false` means access is rejected. Cells use reference
     * equality and stable identity hashing because either slot may be completed after publication.
     */
    sealed interface Cell {
        /** @throws IllegalStateException when this cell has no value promise */
        fun getValue(): Promise<EngineResult?>

        /**
         * Returns the value promise, explicitly creating an unclaimed reader placeholder when this
         * mutable cell has no promise.
         */
        fun reserveValue(): Promise<EngineResult?>

        fun setValue(value: EngineResult?)

        fun createValuePromise(): Promise<EngineResult?>

        /** @throws IllegalStateException when this cell has no access-acceptance promise */
        fun getAccessAccepted(): Promise<Value.Boolean>

        fun setAccessAccepted(accept: Value.Boolean)

        fun createAccessAcceptedPromise(): Promise<Value.Boolean>
    }

    /**
     * A finite object result whose exact cells are installed once.
     *
     * Every present key belongs to [type], contains no variables, and its cell value completes only
     * with a result conforming to the field's type expression. [getCell] is a strict read.
     * [reserveCell] explicitly installs an unclaimed reader placeholder on a mutable object. A
     * writer claims the value placeholder through [Cell.createValuePromise] or [Cell.setValue].
     * [freeze] seals the key set and freezes every present cell's value slot. A claimed value
     * promise may complete after freezing.
     *
     * Objects use reference equality and stable identity hashing, so they may be used as map keys
     * while cells are installed or their slots are completed.
     */
    sealed interface Object : EngineResult {
        val type: Schema.ObjectType

        val keys: Set<Value.GroundKey>

        fun isCellSet(field: Value.GroundKey): Boolean = field in keys

        /** @throws MissingFieldException when [field] has no cell */
        fun getCell(field: Value.GroundKey): Cell

        /**
         * Returns the field cell, explicitly creating an unclaimed reader placeholder when this
         * mutable object is not frozen.
         *
         * @throws MissingFieldException when this object is immutable or frozen and has no cell
         */
        fun reserveCell(field: Value.GroundKey): Cell

        /**
         * Seals this object's cell-key set and freezes every present cell's value slot. Claimed
         * value promises may still complete.
         */
        fun freeze()

        companion object {
            /**
             * ### Invariant: object-engine-result-factory-schema-conformance
             *
             * Every initially present cell value satisfies its field's schema type. When [mutable]
             * is false, cell creation throws. When it is true, each absent exact cell may be
             * installed once and each slot of that cell may be installed once.
             */
            fun of(
                type: Schema.ObjectType,
                values: Map<Value.GroundKey, EngineResult?> = emptyMap(),
                accessAccepted: Map<Value.GroundKey, Value.Boolean> =
                    values.keys.associateWith { Value.Boolean.of(true) },
                mutable: Boolean = false,
            ): Object {
                val fields = values.keys + accessAccepted.keys
                fields.forEach { field ->
                    validateObjectField(type, field)
                }
                values.forEach { (field, value) -> validateObjectValue(field, value) }
                return ObjectResultImpl(
                    type = type,
                    cells =
                        fields.associateWith { field ->
                            CellImpl(
                                initialValue = values[field],
                                initiallyValueSet = field in values,
                                accessAccepted = accessAccepted[field],
                                mutable = mutable,
                                validateValue = { value -> validateObjectValue(field, value) },
                            )
                        },
                    mutable = mutable,
                )
            }
        }
    }

    /**
     * A typed list result whose elements are cells.
     *
     * [typeExpr] is the expected type of each cell value, including its nullability and nested
     * lists. Lists use structural equality over [typeExpr] and positional cell equality; cells and
     * object values therefore compare by reference.
     *
     * Including [typeExpr] in equality is intentional. The factory validates every completed cell
     * value against it, so it acts as a retained type witness: assigning a list to a compatible list
     * position requires comparing type expressions, not recursively revalidating its contents.
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
             * Every cell value satisfies `value.conformsToSchemaType(typeExpr)` in its reasoning
             * world.
             */
            fun of(
                typeExpr: TypeExpr<Schema.OutputType>,
                values: kotlin.collections.List<EngineResult?>,
                accessAccepted: kotlin.collections.List<Value.Boolean?> =
                    values.map { Value.Boolean.of(true) },
                mutableCells: Boolean = false,
            ): List {
                require(values.all { value -> value.conformsToSchemaType(typeExpr) }) {
                    "List engine result contains an element incompatible with $typeExpr"
                }
                require(accessAccepted.size == values.size) {
                    "List engine result access results must match its value count"
                }
                val cells =
                    values.mapIndexed { index, value ->
                        CellImpl(
                            initialValue = value,
                            initiallyValueSet = true,
                            accessAccepted = accessAccepted[index],
                            mutable = mutableCells,
                            validateValue = { updated ->
                                require(updated.conformsToSchemaType(typeExpr)) {
                                    "List engine result contains an element incompatible with " +
                                        typeExpr
                                }
                            },
                        )
                    }
                return ListResultImpl(typeExpr, cells)
            }
        }
    }
}

/**
 * Returns whether two completed result trees contain the same values and access results.
 *
 * This explicit extensional comparison is distinct from ordinary equality because
 * [EngineResult.Cell] and [EngineResult.Object] use reference equality. Both trees must be finite
 * and every present promise they contain must be completed. Its result is meaningful only after
 * both trees are quiescent; the comparison does not take an atomic snapshot while promises or
 * cells are being mutated concurrently.
 *
 * @throws UncompletedPromiseException when either tree contains an uncompleted promise
 */
fun EngineResult?.sameCompletedResultAs(other: EngineResult?): Boolean {
    val same = hasSameCompletedResultAs(other)
    if (!same) {
        requireCompleted()
        other.requireCompleted()
    }
    return same
}

private fun EngineResult?.hasSameCompletedResultAs(other: EngineResult?): Boolean {
    if (this == null || other == null) return this == other

    return when (this) {
        is Value.Simple -> other is Value.Simple && this == other
        is EngineResult.List ->
            other is EngineResult.List &&
                typeExpr == other.typeExpr &&
                size == other.size &&
                indices.all { index -> this[index].hasSameCompletedCellAs(other[index]) }
        is EngineResult.Object ->
            other is EngineResult.Object && sameCompletedObjectResultAs(other)
    }
}

private fun EngineResult.Cell.hasSameCompletedCellAs(other: EngineResult.Cell): Boolean {
    val leftValue = completedValue
    val rightValue = other.completedValue
    return leftValue.hasSameCompletedResultAs(rightValue) &&
        completedAccessAccepted == other.completedAccessAccepted
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
 * Returns the union of this completed cell and [other].
 *
 * @throws IllegalArgumentException when their values have no union or their access results differ
 */
private fun CompletedCell.union(other: CompletedCell): CompletedCell =
    CompletedCell(
        value = value.union(other.value),
        accessAccepted =
            unionAccessAccepted(accessAccepted, other.accessAccepted),
    )

/**
 * Returns the object result containing the union of every cell present in either operand.
 *
 * @throws IllegalArgumentException when the object types differ or any shared cell has no union
 */
fun EngineResult.Object.union(other: EngineResult.Object): EngineResult.Object {
    require(type == other.type) {
        "Cannot union object engine results of different types"
    }

    val leftCells = implementation.completedCells.mapValues { (_, cell) -> cell.completed() }
    val rightCells = other.implementation.completedCells.mapValues { (_, cell) -> cell.completed() }
    val cells = unionMaps(leftCells, rightCells, CompletedCell::union)
    return EngineResult.Object.of(
        type = type,
        values = cells.mapValues { (_, cell) -> cell.value },
        accessAccepted =
            cells.mapNotNull { (key, cell) ->
                cell.accessAccepted?.let { key to it }
            }.toMap(),
    )
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
    val cells = indices.map { index -> this[index].completed().union(other[index].completed()) }
    return EngineResult.List.of(
        typeExpr = typeExpr,
        values = cells.map(CompletedCell::value),
        accessAccepted = cells.map(CompletedCell::accessAccepted),
    )
}

private class CellImpl(
    initialValue: EngineResult? = null,
    initiallyValueSet: Boolean = false,
    accessAccepted: Value.Boolean? = null,
    private val mutable: Boolean,
    private val validateValue: (EngineResult?) -> Unit = {},
) : EngineResult.Cell {
    private val valueStore =
        CellValueStore(
            initialValue = initialValue,
            initiallySet = initiallyValueSet,
            mutable = mutable,
            validateValue = validateValue,
        )
    private val accessAcceptedStore =
        promiseStore(accessAccepted?.let { mapOf(Unit to it) }.orEmpty())

    override fun getValue(): Promise<EngineResult?> =
        checkNotNull(valueStore.readOrNull()) {
            "Cell has no value"
        }

    override fun reserveValue(): Promise<EngineResult?> = valueStore.reserve()

    override fun setValue(value: EngineResult?) {
        validateValue(value)
        valueStore.claimAndComplete(value)
    }

    override fun createValuePromise(): Promise<EngineResult?> = valueStore.claim()

    override fun getAccessAccepted(): Promise<Value.Boolean> =
        checkNotNull(accessAcceptedStore.readOrNull(Unit)) {
            "Cell has no access-acceptance result"
        }

    override fun setAccessAccepted(accept: Value.Boolean) {
        checkMutable()
        accessAcceptedStore.set(Unit, accept)
    }

    override fun createAccessAcceptedPromise(): Promise<Value.Boolean> {
        checkMutable()
        return accessAcceptedStore.create(Unit)
    }

    fun freezeValue(cause: Throwable) {
        if (mutable) valueStore.freeze(cause)
    }

    fun requireCompleted() {
        valueStore.readOrNull()?.get().requireCompleted()
        accessAcceptedStore.snapshot().values.forEach { promise -> promise.get() }
    }

    val completedValue: EngineResult?
        get() = checkNotNull(valueStore.readOrNull()) { "Cell has no value" }.get()

    val completedAccessAccepted: Value.Boolean?
        get() = accessAcceptedStore.readOrNull(Unit)?.get()

    private fun checkMutable() = check(mutable) { "Cell is immutable" }
}

private class CellValueStore(
    initialValue: EngineResult?,
    initiallySet: Boolean,
    private val mutable: Boolean,
    private val validateValue: (EngineResult?) -> Unit,
) {
    private val lock = Any()
    private var promise: Promise<EngineResult?>? =
        if (initiallySet) Promise.of(initialValue) else null
    private var claimed = initiallySet
    private var frozen = !mutable

    val isSet: Boolean
        get() = synchronized(lock) { promise != null }

    fun readOrNull(): Promise<EngineResult?>? = synchronized(lock) { promise }

    fun reserve(): Promise<EngineResult?> =
        synchronized(lock) {
            promise
                ?: if (frozen) {
                    error("Cell is immutable")
                } else {
                    Promise
                        .ofDeferred(validateValue)
                        .also { created -> promise = created }
                }
        }

    fun claim(): Promise<EngineResult?> =
        synchronized(lock) {
            check(!frozen) { "Cell value is frozen" }
            val existing = promise
            if (existing != null) {
                check(!claimed) { "Cell value already has a writer" }
                claimed = true
                existing
            } else {
                Promise
                    .ofDeferred(validateValue)
                    .also { created ->
                        promise = created
                        claimed = true
                    }
            }
        }

    fun claimAndComplete(value: EngineResult?) {
        claim().complete(value)
    }

    fun freeze(cause: Throwable) {
        val unclaimed =
            synchronized(lock) {
                check(mutable) { "Cell is immutable" }
                check(!frozen) { "Cell value is already frozen" }
                frozen = true
                promise?.takeUnless { claimed }
            }
        unclaimed?.fail(cause)
    }
}

private class ObjectResultImpl(
    override val type: Schema.ObjectType,
    cells: Map<Value.GroundKey, EngineResult.Cell>,
    mutable: Boolean,
) : EngineResult.Object {
    private val cellStore =
        ObjectCellStore(
            type = type,
            cells = cells,
            mutable = mutable,
        )

    override val keys: Set<Value.GroundKey>
        get() = cellStore.keys

    override fun isCellSet(field: Value.GroundKey): Boolean = cellStore.isSet(field)

    override fun getCell(field: Value.GroundKey): EngineResult.Cell {
        validateObjectField(type, field)
        return cellStore.readOrNull(field)
            ?: throw MissingFieldException(type.typeName, field.field.fieldName)
    }

    override fun reserveCell(field: Value.GroundKey): EngineResult.Cell {
        validateObjectField(type, field)
        return cellStore.reserve(field)
    }

    override fun freeze() {
        cellStore.freeze()
    }

    val completedCells: Map<Value.GroundKey, EngineResult.Cell>
        get() = cellStore.completedCells()

    fun requireCompleted() {
        cellStore.cellValues.forEach { cell -> cell.implementation.requireCompleted() }
    }
}

private class ObjectCellStore(
    private val type: Schema.ObjectType,
    cells: Map<Value.GroundKey, EngineResult.Cell>,
    private val mutable: Boolean,
) {
    private val lock = Any()
    private val cells = cells.toMutableMap()
    private var frozen = !mutable

    val keys: Set<Value.GroundKey>
        get() = synchronized(lock) { cells.keys.toSet() }

    val cellValues: kotlin.collections.List<EngineResult.Cell>
        get() = synchronized(lock) { cells.values.toList() }

    fun isSet(field: Value.GroundKey): Boolean = synchronized(lock) { field in cells }

    fun readOrNull(field: Value.GroundKey): EngineResult.Cell? =
        synchronized(lock) { cells[field] }

    fun reserve(field: Value.GroundKey): EngineResult.Cell =
        synchronized(lock) {
            cells[field]
                ?: if (frozen) {
                    throw MissingFieldException(type.typeName, field.field.fieldName)
                } else {
                    mutableCell(field).also { cell ->
                        cells[field] = cell
                    }
                }
        }

    fun freeze() {
        val presentCells =
            synchronized(lock) {
                check(mutable) { "${type.typeName} result is immutable" }
                check(!frozen) { "${type.typeName} result is already frozen" }
                frozen = true
                cells.toMap()
            }
        presentCells.forEach { (field, cell) ->
            cell.implementation.freezeValue(
                MissingFieldException(type.typeName, field.field.fieldName),
            )
        }
    }

    fun completedCells(): Map<Value.GroundKey, EngineResult.Cell> =
        synchronized(lock) {
            cells.mapValues { (_, cell) ->
                cell.also { it.implementation.requireCompleted() }
            }
        }

    private fun mutableCell(field: Value.GroundKey): EngineResult.Cell =
        CellImpl(
            mutable = true,
            validateValue = { value -> validateObjectValue(field, value) },
        )

}

private data class ListResultImpl(
    override val typeExpr: TypeExpr<Schema.OutputType>,
    private val cells: kotlin.collections.List<EngineResult.Cell>,
) : EngineResult.List {
    override val size: Int
        get() = cells.size

    override fun get(index: Int): EngineResult.Cell = cells[index]
}

private data class CompletedCell(
    val value: EngineResult?,
    val accessAccepted: Value.Boolean?,
)

private fun EngineResult.Cell.completed(): CompletedCell =
    CompletedCell(
        value = completedValue,
        accessAccepted = completedAccessAccepted,
    )

private val EngineResult.Cell.implementation: CellImpl
    get() = this as CellImpl

private val EngineResult.Cell.completedValue: EngineResult?
    get() = implementation.completedValue

private val EngineResult.Cell.completedAccessAccepted: Value.Boolean?
    get() = implementation.completedAccessAccepted

private val EngineResult.Object.implementation: ObjectResultImpl
    get() = this as ObjectResultImpl

/**
 * The result is meaningful only after both object trees are quiescent. Store snapshots and
 * recursive reads do not form one atomic snapshot while promises or cells are being mutated.
 */
private fun EngineResult.Object.sameCompletedObjectResultAs(other: EngineResult.Object): Boolean {
    val leftCells = implementation.completedCells
    val rightCells = other.implementation.completedCells

    return type == other.type &&
        leftCells.keys == rightCells.keys &&
        leftCells.all { (key, cell) ->
            cell.hasSameCompletedCellAs(rightCells.getValue(key))
        }
}

private fun EngineResult?.requireCompleted() {
    when (this) {
        null,
        is Value.Simple,
        -> Unit
        is EngineResult.List ->
            indices.forEach { index -> get(index).implementation.requireCompleted() }
        is EngineResult.Object -> implementation.requireCompleted()
    }
}

private fun <K, V> unionMaps(
    first: Map<K, V>,
    second: Map<K, V>,
    union: (V, V) -> V,
): Map<K, V> =
    (first.keys + second.keys).associateWith { key ->
        when {
            key !in first -> second.getValue(key)
            key !in second -> first.getValue(key)
            else -> union(first.getValue(key), second.getValue(key))
        }
    }

private fun unionAccessAccepted(
    first: Value.Boolean?,
    second: Value.Boolean?,
): Value.Boolean? =
    when {
        first == null -> second
        second == null -> first
        else ->
            first.also {
                require(first == second) {
                    "Cannot union cells with unequal access-acceptance results"
                }
            }
    }

private fun <K : Any, V> promiseStore(values: Map<K, V>): OnceStore<K, Promise<V>> =
    OnceStore(values.mapValues { (_, value) -> Promise.of(value) })

private fun <K : Any, V> OnceStore<K, Promise<V>>.readOrNull(key: K): Promise<V>? =
    if (isSet(key)) read(key) else null

private fun <K : Any, V> OnceStore<K, Promise<V>>.set(
    key: K,
    value: V,
) = write(key, Promise.of(value))

private fun <K : Any, V> OnceStore<K, Promise<V>>.create(
    key: K,
    validate: (V) -> Unit = {},
): Promise<V> =
    Promise
        .ofDeferred(validate)
        .also { write(key, it) }

private fun validateObjectField(
    type: Schema.ObjectType,
    field: Value.GroundKey,
): Unit =
    require(field.field.containingType == type) {
        "${type.typeName} result contains a field owned by another type"
    }

private fun validateObjectValue(
    field: Value.GroundKey,
    value: EngineResult?,
) {
    if (field.arguments.containsErrorValue()) {
        require(value == Value.Error) {
            "A key containing an argument error must contain an error value"
        }
    }
    require(value.conformsToSchemaType(field.field.typeExpr)) {
        "${field.field.containingType.typeName}/${field.field.fieldName} result does not conform to " +
            field.field.typeExpr
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
