package model

import model.invariants.conformsToSchemaType

/**
 * One step in an exact path through an engine-result tree.
 *
 * An [ObjectEngineResult.GroundKey] selects an object field, while a [ListEngineResult.Index]
 * selects a list element. Equality is structural within each variant.
 */
sealed interface PathComponent

/**
 * Returns this exact OER path as an object-key-only selection path.
 *
 * A null path or any path containing a [ListEngineResult.Index] has no corresponding selection
 * path and yields null.
 */
fun kotlin.collections.List<PathComponent>?.toSelectionPath():
    kotlin.collections.List<ObjectEngineResult.GroundKey>? =
    this?.map { component -> component as? ObjectEngineResult.GroundKey ?: return null }

/**
 * A finite, well-founded field-resolution result.
 *
 * Equality depends on the result variant. [SimpleEngineResult] values use structural equality,
 * [EngineResult.Cell] and [ObjectEngineResult] values use reference equality, and
 * [ListEngineResult] values use structural equality over their type expression and positional cell
 * equality. Schema definitions within those properties use the canonical equality documented by
 * [Schema].
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
}

/** A scalar or enum field-resolution result. */
sealed interface SimpleEngineResult : EngineResult {
    val type: Schema.SimpleType
}

/** A built-in scalar field-resolution result. */
sealed interface ScalarEngineResult : SimpleEngineResult {
    override val type: Schema.ScalarType
}

sealed interface IntEngineResult : ScalarEngineResult {
    override val type: Schema.IntType
        get() = Schema.IntType

    val intValue: Int

    companion object {
        fun of(value: Int): IntEngineResult = IntEngineResultImpl(value)
    }
}

sealed interface FloatEngineResult : ScalarEngineResult {
    override val type: Schema.FloatType
        get() = Schema.FloatType

    val floatValue: Double

    companion object {
        fun of(value: Double): FloatEngineResult {
            require(value.isFinite()) { "GraphQL Float results must be finite" }
            return FloatEngineResultImpl(value)
        }
    }
}

sealed interface StringEngineResult : ScalarEngineResult {
    override val type: Schema.StringType
        get() = Schema.StringType

    val stringValue: String

    companion object {
        fun of(value: String): StringEngineResult = StringEngineResultImpl(value)
    }
}

sealed interface BooleanEngineResult : ScalarEngineResult {
    override val type: Schema.BooleanType
        get() = Schema.BooleanType

    val booleanValue: Boolean

    companion object {
        fun of(value: Boolean): BooleanEngineResult = BooleanEngineResultImpl(value)
    }
}

sealed interface IDEngineResult : ScalarEngineResult {
    override val type: Schema.IDType
        get() = Schema.IDType

    val idValue: String

    companion object {
        fun of(value: String): IDEngineResult = IDEngineResultImpl(value)
    }
}

sealed interface EnumEngineResult : SimpleEngineResult {
    override val type: Schema.EnumType
    val enumValue: String

    companion object {
        fun of(
            type: Schema.EnumType,
            value: String,
        ): EnumEngineResult {
            require(value in type.values) { "$value is not a value of ${type.typeName}" }
            return EnumEngineResultImpl(type, value)
        }
    }
}

/**
 * The collapsed error result. Error metadata, paths, and multiplicity are intentionally omitted.
 */
data object ErrorEngineResult : EngineResult

/** Converts a simple resolver value into the corresponding independent engine result. */
fun Value.Simple.toEngineResult(): EngineResult =
    when (this) {
        Value.Error -> ErrorEngineResult
        is Value.Int -> IntEngineResult.of(intValue)
        is Value.Float -> FloatEngineResult.of(floatValue)
        is Value.String -> StringEngineResult.of(stringValue)
        is Value.Boolean -> BooleanEngineResult.of(booleanValue)
        is Value.ID -> IDEngineResult.of(idValue)
        is Value.Enum -> EnumEngineResult.of(type, enumValue)
    }

/** Converts a simple engine result into the corresponding resolver value. */
fun SimpleEngineResult.toValue(): Value.Simple =
    when (this) {
        is IntEngineResult -> Value.Int.of(intValue)
        is FloatEngineResult -> Value.Float.of(floatValue)
        is StringEngineResult -> Value.String.of(stringValue)
        is BooleanEngineResult -> Value.Boolean.of(booleanValue)
        is IDEngineResult -> Value.ID.of(idValue)
        is EnumEngineResult -> Value.Enum.of(type, enumValue)
    }

/**
 * A finite object result whose exact cells are installed once.
 *
 * Every present key belongs to [type], contains no variables, and its cell value completes only
 * with a result conforming to the field's type expression. [getCell] is a strict read.
 * [reserveCell] explicitly installs an unclaimed reader placeholder on a mutable object. A writer
 * claims the value placeholder through [EngineResult.Cell.createValuePromise] or
 * [EngineResult.Cell.setValue]. [freeze] seals the key set and freezes every present cell's value
 * slot. A claimed value promise may complete after freezing.
 *
 * Objects use reference equality and stable identity hashing, so they may be used as map keys while
 * cells are installed or their slots are completed.
 */
sealed interface ObjectEngineResult : EngineResult {
    /**
     * One alias-free output-field coordinate consisting of a canonical field and its arguments.
     *
     * ### Invariant: key-argument-definition
     *
     * [arguments] recursively conforms to [field]'s argument definition.
     *
     * ### Invariant: object-key-field-classification
     *
     * A key's [field] is a [Schema.ObjectField] exactly when the key is an [ObjectKey].
     *
     * Ordinary-key equality is structural over [field] and [arguments], using canonical schema
     * equality. [Stamped] keys additionally include their opaque selection occurrence stamp;
     * callers that need resolver-visible identity must explicitly project them to ordinary keys.
     */
    sealed interface Key {
        val field: Schema.OutputField
        val arguments: OpenArguments

        /**
         * A key belonging to one variable-bearing source-selection occurrence.
         *
         * The stamp participates in structural equality before and after grounding.
         */
        sealed interface Stamped : Key {
            val selectionStamp: SelectionStamp

            companion object {
                fun of(
                    selectionStamp: SelectionStamp,
                    field: Schema.OutputField,
                    arguments: OpenArguments,
                ): Stamped {
                    require(arguments.conformsToArgumentDefinition(field.arguments)) {
                        "Stamped key arguments do not belong to its output field"
                    }
                    return when (field) {
                        is Schema.ObjectField ->
                            ObjectKey.Stamped.of(selectionStamp, field, arguments)
                        else ->
                            StampedKeyImpl(field, arguments, selectionStamp)
                    }
                }

                fun of(
                    selectionStamp: SelectionStamp,
                    field: Schema.ObjectField,
                    arguments: OpenArguments,
                ): ObjectKey.Stamped =
                    ObjectKey.Stamped.of(selectionStamp, field, arguments)
            }
        }

        companion object {
            /**
             * ### Invariant: map-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: Schema.OutputField,
                arguments: Map<String, Any?>,
            ): Key = of(field, OpenArguments.of(field, arguments))

            /** Constructs the precise key category for a field on a concrete object type. */
            fun of(
                field: Schema.ObjectField,
                arguments: Map<String, Any?>,
            ): ObjectKey = ObjectKey.of(field, arguments)

            /**
             * ### Invariant: arguments-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: Schema.OutputField,
                arguments: OpenArguments,
            ): Key {
                require(arguments.conformsToArgumentDefinition(field.arguments)) {
                    "Key arguments do not belong to its output field"
                }
                return when (field) {
                    is Schema.ObjectField -> ObjectKey.of(field, arguments)
                    else -> KeyImpl(field, arguments)
                }
            }

            /** Constructs the precise key category for a field on a concrete object type. */
            fun of(
                field: Schema.ObjectField,
                arguments: OpenArguments,
            ): ObjectKey = ObjectKey.of(field, arguments)

            /** Constructs the precise ground key category. */
            fun of(
                field: Schema.ObjectField,
                arguments: Value.Arguments,
            ): GroundKey = GroundKey.of(field, arguments)
        }
    }

    /**
     * A selection-only key marking one component of a stamped path-variable's provider path.
     *
     * The marker remains distinct from [ObjectKey] even when [field] belongs to a concrete object
     * type. [model.mergeWithVariables] is the explicit boundary that converts it to a ground key
     * and reports a binding when the path terminates at this component.
     */
    sealed interface VariableKey : Key {
        val variableDefinedByThisKey: Value.Variable.Stamped

        sealed interface Stamped : VariableKey, Key.Stamped

        companion object {
            fun of(
                key: Key,
                variableDefinedByThisKey: Value.Variable.Stamped,
            ): VariableKey =
                if (key is Key.Stamped) {
                    of(key, variableDefinedByThisKey)
                } else {
                    VariableKeyImpl(
                        field = key.field,
                        arguments = key.arguments,
                        variableDefinedByThisKey = variableDefinedByThisKey,
                    )
                }

            fun of(
                key: Key.Stamped,
                variableDefinedByThisKey: Value.Variable.Stamped,
            ): Stamped =
                StampedVariableKeyImpl(
                    field = key.field,
                    arguments = key.arguments,
                    variableDefinedByThisKey = variableDefinedByThisKey,
                    selectionStamp = key.selectionStamp,
                )
        }
    }

    /**
     * A key whose field belongs to a concrete object type.
     *
     * Every instance carries a [Schema.ObjectField] and [OpenArguments]. Ordinary instances use
     * structural key equality; [Stamped] instances additionally retain occurrence identity.
     */
    sealed interface ObjectKey : Key {
        override val field: Schema.ObjectField
        override val arguments: OpenArguments

        sealed interface Stamped : ObjectKey, Key.Stamped {
            companion object {
                fun of(
                    selectionStamp: SelectionStamp,
                    field: Schema.ObjectField,
                    arguments: OpenArguments,
                ): Stamped {
                    require(arguments.conformsToArgumentDefinition(field.arguments)) {
                        "Stamped object-key arguments do not belong to its output field"
                    }
                    return if (arguments is Value.Arguments) {
                        GroundKey.Stamped.of(selectionStamp, field, arguments)
                    } else {
                        StampedObjectKeyImpl(field, arguments, selectionStamp)
                    }
                }
            }
        }

        companion object {
            fun of(
                field: Schema.ObjectField,
                arguments: Map<String, Any?>,
            ): ObjectKey = of(field, OpenArguments.of(field, arguments))

            fun of(
                field: Schema.ObjectField,
                arguments: OpenArguments,
            ): ObjectKey {
                require(arguments.conformsToArgumentDefinition(field.arguments)) {
                    "Key arguments do not belong to its output field"
                }
                return if (arguments is Value.Arguments) {
                    GroundKeyImpl(field, arguments)
                } else {
                    ObjectKeyImpl(field, arguments)
                }
            }
        }
    }

    /**
     * A concrete-object key whose arguments are ground and which can therefore select an OER field.
     */
    sealed interface GroundKey : ObjectKey, PathComponent {
        override val arguments: Value.Arguments

        /**
         * A ground key produced from a variable-bearing source selection.
         *
         * [selectionStamp] identifies the variable-bearing source selection that was grounded. It
         * distinguishes different source selections even when their grounded arguments agree.
         */
        sealed interface Stamped : GroundKey, ObjectKey.Stamped {
            companion object {
                fun of(
                    selectionStamp: SelectionStamp,
                    field: Schema.ObjectField,
                    arguments: Value.Arguments,
                ): Stamped {
                    require(arguments.conformsToArgumentDefinition(field.arguments)) {
                        "Ground arguments do not belong to the stamped selection field"
                    }
                    return StampedGroundKeyImpl(
                        field = field,
                        arguments = arguments,
                        selectionStamp = selectionStamp,
                    )
                }
            }
        }

        companion object {
            fun of(
                field: Schema.ObjectField,
                arguments: Map<String, Any?>,
            ): GroundKey = of(field, Value.Arguments.of(field, arguments))

            fun of(
                field: Schema.ObjectField,
                arguments: Value.Arguments,
            ): GroundKey {
                require(arguments.conformsToArgumentDefinition(field.arguments)) {
                    "Key arguments do not belong to its output field"
                }
                return GroundKeyImpl(field, arguments)
            }
        }
    }

    val type: Schema.ObjectType

    val keys: Set<GroundKey>

    fun isCellSet(field: GroundKey): Boolean = field in keys

    /** @throws MissingFieldException when [field] has no cell */
    fun getCell(field: GroundKey): EngineResult.Cell

    /**
     * Returns the field cell, explicitly creating an unclaimed reader placeholder when this
     * mutable object is not frozen.
     *
     * @throws MissingFieldException when this object is immutable or frozen and has no cell
     */
    fun reserveCell(field: GroundKey): EngineResult.Cell

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
            values: Map<GroundKey, EngineResult?> = emptyMap(),
            accessAccepted: Map<GroundKey, Value.Boolean> =
                values.keys.associateWith { Value.Boolean.of(true) },
            mutable: Boolean = false,
        ): ObjectEngineResult {
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
 * [typeExpr] is the expected type of each cell value, including its nullability and nested lists.
 * Lists use structural equality over [typeExpr] and positional cell equality; cells and object
 * values therefore compare by reference.
 *
 * Including [typeExpr] in equality is intentional. The factory validates every completed cell
 * value against it, so it acts as a retained type witness: assigning a list to a compatible list
 * position requires comparing type expressions, not recursively revalidating its contents.
 */
sealed interface ListEngineResult : EngineResult {
    /** A non-negative position selecting one element of an engine-result list. */
    sealed interface Index : PathComponent {
        val index: Int

        companion object {
            fun of(index: Int): Index {
                require(index >= 0) { "List index must be non-negative" }
                return ListIndexImpl(index)
            }
        }
    }

    val typeExpr: TypeExpr<Schema.OutputType>
    val size: Int
    val indices: IntRange
        get() = 0 until size

    operator fun get(index: Int): EngineResult.Cell

    fun <R> map(transform: (EngineResult.Cell) -> R): kotlin.collections.List<R> =
        indices.map { index -> transform(get(index)) }

    fun all(predicate: (EngineResult.Cell) -> Boolean): Boolean =
        indices.all { index -> predicate(get(index)) }

    fun forEachIndexed(action: (index: Int, EngineResult.Cell) -> Unit) {
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
        ): ListEngineResult {
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

/**
 * Returns whether two completed result trees contain the same values and access results.
 *
 * This explicit extensional comparison is distinct from ordinary equality because
 * [EngineResult.Cell] and [ObjectEngineResult] use reference equality. Both trees must be finite
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
        ErrorEngineResult -> other == ErrorEngineResult
        is SimpleEngineResult -> other is SimpleEngineResult && this == other
        is ListEngineResult ->
            other is ListEngineResult &&
                typeExpr == other.typeExpr &&
                size == other.size &&
                indices.all { index -> this[index].hasSameCompletedCellAs(other[index]) }
        is ObjectEngineResult ->
            other is ObjectEngineResult && sameCompletedObjectResultAs(other)
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
        ErrorEngineResult -> {
            require(other == ErrorEngineResult) {
                "Cannot union error and non-error engine results"
            }
            ErrorEngineResult
        }

        is SimpleEngineResult -> {
            require(other is SimpleEngineResult) {
                "Cannot union different engine-result variants"
            }
            require(this == other) { "Cannot union unequal simple engine results" }
            this
        }

        is ListEngineResult -> {
            require(other is ListEngineResult) {
                "Cannot union different engine-result variants"
            }
            union(other)
        }

        is ObjectEngineResult -> {
            require(other is ObjectEngineResult) {
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
fun ObjectEngineResult.union(other: ObjectEngineResult): ObjectEngineResult {
    require(type == other.type) {
        "Cannot union object engine results of different types"
    }

    val leftCells = implementation.completedCells.mapValues { (_, cell) -> cell.completed() }
    val rightCells = other.implementation.completedCells.mapValues { (_, cell) -> cell.completed() }
    val cells = unionMaps(leftCells, rightCells, CompletedCell::union)
    return ObjectEngineResult.of(
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
fun ListEngineResult.union(other: ListEngineResult): ListEngineResult {
    require(typeExpr == other.typeExpr) {
        "Cannot union list engine results with different element types"
    }
    require(size == other.size) {
        "Cannot union list engine results of different lengths"
    }
    val cells = indices.map { index -> this[index].completed().union(other[index].completed()) }
    return ListEngineResult.of(
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
    cells: Map<ObjectEngineResult.GroundKey, EngineResult.Cell>,
    mutable: Boolean,
) : ObjectEngineResult {
    private val cellStore =
        ObjectCellStore(
            type = type,
            cells = cells,
            mutable = mutable,
        )

    override val keys: Set<ObjectEngineResult.GroundKey>
        get() = cellStore.keys

    override fun isCellSet(field: ObjectEngineResult.GroundKey): Boolean = cellStore.isSet(field)

    override fun getCell(field: ObjectEngineResult.GroundKey): EngineResult.Cell {
        validateObjectField(type, field)
        return cellStore.readOrNull(field)
            ?: throw MissingFieldException(type.typeName, field.field.fieldName)
    }

    override fun reserveCell(field: ObjectEngineResult.GroundKey): EngineResult.Cell {
        validateObjectField(type, field)
        return cellStore.reserve(field)
    }

    override fun freeze() {
        cellStore.freeze()
    }

    val completedCells: Map<ObjectEngineResult.GroundKey, EngineResult.Cell>
        get() = cellStore.completedCells()

    fun requireCompleted() {
        cellStore.cellValues.forEach { cell -> cell.implementation.requireCompleted() }
    }
}

private class ObjectCellStore(
    private val type: Schema.ObjectType,
    cells: Map<ObjectEngineResult.GroundKey, EngineResult.Cell>,
    private val mutable: Boolean,
) {
    private val lock = Any()
    private val cells = cells.toMutableMap()
    private var frozen = !mutable

    val keys: Set<ObjectEngineResult.GroundKey>
        get() = synchronized(lock) { cells.keys.toSet() }

    val cellValues: kotlin.collections.List<EngineResult.Cell>
        get() = synchronized(lock) { cells.values.toList() }

    fun isSet(field: ObjectEngineResult.GroundKey): Boolean = synchronized(lock) { field in cells }

    fun readOrNull(field: ObjectEngineResult.GroundKey): EngineResult.Cell? =
        synchronized(lock) { cells[field] }

    fun reserve(field: ObjectEngineResult.GroundKey): EngineResult.Cell =
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

    fun completedCells(): Map<ObjectEngineResult.GroundKey, EngineResult.Cell> =
        synchronized(lock) {
            cells.mapValues { (_, cell) ->
                cell.also { it.implementation.requireCompleted() }
            }
        }

    private fun mutableCell(field: ObjectEngineResult.GroundKey): EngineResult.Cell =
        CellImpl(
            mutable = true,
            validateValue = { value -> validateObjectValue(field, value) },
        )

}

private data class ListResultImpl(
    override val typeExpr: TypeExpr<Schema.OutputType>,
    private val cells: kotlin.collections.List<EngineResult.Cell>,
) : ListEngineResult {
    override val size: Int
        get() = cells.size

    override fun get(index: Int): EngineResult.Cell = cells[index]
}

private data class ListIndexImpl(
    override val index: Int,
) : ListEngineResult.Index

private data class IntEngineResultImpl(
    override val intValue: Int,
) : IntEngineResult

private data class FloatEngineResultImpl(
    override val floatValue: Double,
) : FloatEngineResult

private data class StringEngineResultImpl(
    override val stringValue: String,
) : StringEngineResult

private data class BooleanEngineResultImpl(
    override val booleanValue: Boolean,
) : BooleanEngineResult

private data class IDEngineResultImpl(
    override val idValue: String,
) : IDEngineResult

private data class EnumEngineResultImpl(
    override val type: Schema.EnumType,
    override val enumValue: String,
) : EnumEngineResult

private data class KeyImpl(
    override val field: Schema.OutputField,
    override val arguments: OpenArguments,
) : ObjectEngineResult.Key

private data class StampedKeyImpl(
    override val field: Schema.OutputField,
    override val arguments: OpenArguments,
    override val selectionStamp: SelectionStamp,
) : ObjectEngineResult.Key.Stamped

private data class VariableKeyImpl(
    override val field: Schema.OutputField,
    override val arguments: OpenArguments,
    override val variableDefinedByThisKey: Value.Variable.Stamped,
) : ObjectEngineResult.VariableKey

private data class StampedVariableKeyImpl(
    override val field: Schema.OutputField,
    override val arguments: OpenArguments,
    override val variableDefinedByThisKey: Value.Variable.Stamped,
    override val selectionStamp: SelectionStamp,
) : ObjectEngineResult.VariableKey.Stamped

private data class ObjectKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: OpenArguments,
) : ObjectEngineResult.ObjectKey

private data class StampedObjectKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: OpenArguments,
    override val selectionStamp: SelectionStamp,
) : ObjectEngineResult.ObjectKey.Stamped

private data class GroundKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: Value.Arguments,
) : ObjectEngineResult.GroundKey

private data class StampedGroundKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: Value.Arguments,
    override val selectionStamp: SelectionStamp,
) : ObjectEngineResult.GroundKey.Stamped

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

private val ObjectEngineResult.implementation: ObjectResultImpl
    get() = this as ObjectResultImpl

/**
 * The result is meaningful only after both object trees are quiescent. Store snapshots and
 * recursive reads do not form one atomic snapshot while promises or cells are being mutated.
 */
private fun ObjectEngineResult.sameCompletedObjectResultAs(other: ObjectEngineResult): Boolean {
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
        ErrorEngineResult,
        is SimpleEngineResult,
        -> Unit
        is ListEngineResult ->
            indices.forEach { index -> get(index).implementation.requireCompleted() }
        is ObjectEngineResult -> implementation.requireCompleted()
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
    field: ObjectEngineResult.GroundKey,
): Unit =
    require(field.field.containingType == type) {
        "${type.typeName} result contains a field owned by another type"
    }

private fun validateObjectValue(
    field: ObjectEngineResult.GroundKey,
    value: EngineResult?,
) {
    if (field.arguments.containsErrorValue()) {
        require(value == ErrorEngineResult) {
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
