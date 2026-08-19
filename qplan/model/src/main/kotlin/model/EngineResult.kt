package model

import model.invariants.conformsToResultSchemaType

/**
 * A finite, well-founded field-resolution result.
 *
 * The semantic union contains Int, finite Double, Boolean, String, [EngineIDResult],
 * [Schema.EnumValue], [ObjectEngineResult], [ListEngineResult], or [ErrorEngineResult]. Nullable
 * uses additionally represent GraphQL null. Membership and schema compatibility are enforced by
 * result constructors and cell completion boundaries.
 */
typealias EngineResult = Any

/** A structurally equal GraphQL ID result value. */
sealed interface EngineIDResult {
    val value: String

    companion object {
        fun of(value: String): EngineIDResult = EngineIDResultImpl(value)
    }
}

private data class EngineIDResultImpl(
    override val value: String,
) : EngineIDResult

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
internal fun List<PathComponent>?.toSelectionPath():
    List<ObjectEngineResult.GroundKey>? =
    this?.map { component -> component as? ObjectEngineResult.GroundKey ?: return null }

/**
 * One result occurrence with independent write-once value and access-result slots.
 *
 * The value slot contains [EngineResult] or GraphQL null. The access-result slot contains a Boolean
 * result or [ErrorEngineResult]. Cells use reference equality and stable identity hashing because
 * either slot may be completed after publication.
 */
sealed interface EngineResultCell {
    /** @throws IllegalStateException when this cell has no value promise */
    fun getValue(): Promise<EngineResult?>

    /**
     * Returns the value promise, explicitly creating an unclaimed reader placeholder when this
     * mutable cell has no promise.
     */
    fun reserveValue(): Promise<EngineResult?>

    fun setValue(value: EngineResult?)

    fun createValuePromise(): Promise<EngineResult?>

    /** @throws IllegalStateException when this cell has no access-result promise */
    fun getAccessResult(): Promise<EngineResult>

    fun setAccessResult(result: EngineResult)

    fun createAccessResultPromise(): Promise<EngineResult>
}

/**
 * The collapsed error result. Error metadata, paths, and multiplicity are intentionally omitted.
 *
 * Schema conformance admits this sibling result variant at every output type expression. It is not
 * a Kotlin bottom subtype and exposes no simple, object, or list result properties.
 */
data object ErrorEngineResult

/**
 * A finite object result whose exact cells are installed once.
 *
 * Every present key belongs to [type], contains no variables, and its cell value completes only
 * with a result conforming to the field's type expression. [getCell] is a strict read.
 * [reserveCell] explicitly installs an unclaimed reader placeholder on a mutable object. A writer
 * claims the value placeholder through [EngineResultCell.createValuePromise] or
 * [EngineResultCell.setValue]. [freeze] seals the key set and freezes every present cell's value
 * slot. A claimed value promise may complete after freezing.
 *
 * Objects use reference equality and stable identity hashing, so they may be used as map keys while
 * cells are installed or their slots are completed.
 */
sealed interface ObjectEngineResult {
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
     * Key equality is structural over [field], [arguments], and [stamp], using canonical schema
     * equality. Registry templates have a null stamp. Ordinary concrete keys carry
     * [Stamp.VariableFreeOccurrence], while keys explicitly created for a Resolver26 selection
     * occurrence carry [Stamp.Occurrence].
     */
    sealed interface Key {
        val field: Schema.Field
        val arguments: Arguments
        val stamp: Stamp?

        companion object {
            /**
             * Constructs a key belonging to one variable-bearing source-selection occurrence.
             *
             * The stamp participates in structural equality before and after grounding.
             */
            fun of(
                stamp: Stamp.Occurrence,
                field: Schema.Field,
                arguments: Arguments,
            ): Key {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Stamped key arguments do not belong to its output field"
                }
                return when (field) {
                    is Schema.ObjectField -> ObjectKey.of(stamp, field, arguments)
                    else -> StampedKeyImpl(field, arguments, stamp)
                }
            }

            /** Constructs the precise stamped key category for a concrete object field. */
            fun of(
                stamp: Stamp.Occurrence,
                field: Schema.ObjectField,
                arguments: Arguments,
            ): ObjectKey = ObjectKey.of(stamp, field, arguments)

            /**
             * ### Invariant: map-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: Schema.Field,
                arguments: Map<String, Any?>,
            ): Key = of(field, Arguments.of(field, arguments))

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
                field: Schema.Field,
                arguments: Arguments,
            ): Key {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Key arguments do not belong to its output field"
                }
                return when (field) {
                    is Schema.ObjectField -> ObjectKey.of(field, arguments)
                    else -> KeyImpl(field, arguments, arguments.inferredKeyStamp())
                }
            }

            /** Constructs the precise key category for a field on a concrete object type. */
            fun of(
                field: Schema.ObjectField,
                arguments: Arguments,
            ): ObjectKey = ObjectKey.of(field, arguments)

            /** Constructs the precise ground key category. */
            fun of(
                field: Schema.ObjectField,
                arguments: Arguments.Ground,
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
        val variableDefinedByThisKey: Arguments.Variable

        companion object {
            fun of(
                key: Key,
                variableDefinedByThisKey: Arguments.Variable,
            ): VariableKey {
                require(variableDefinedByThisKey.isStamped) {
                    "A provider-path key must define a stamped variable"
                }
                val stamp = key.stamp
                return if (stamp is Stamp.Occurrence) {
                    StampedVariableKeyImpl(
                        field = key.field,
                        arguments = key.arguments,
                        variableDefinedByThisKey = variableDefinedByThisKey,
                        stamp = stamp,
                    )
                } else {
                    VariableKeyImpl(
                        field = key.field,
                        arguments = key.arguments,
                        variableDefinedByThisKey = variableDefinedByThisKey,
                        stamp = stamp,
                    )
                }
            }
        }
    }

    /**
     * A key whose field belongs to a concrete object type.
     *
     * Every instance carries a [Schema.ObjectField] and [Arguments]. Equality includes the
     * key's [stamp], so an explicitly occurrence-stamped key remains distinct from an ordinary key
     * with equal visible arguments.
     */
    sealed interface ObjectKey : Key {
        override val field: Schema.ObjectField
        override val arguments: Arguments

        companion object {
            fun of(
                stamp: Stamp.Occurrence,
                field: Schema.ObjectField,
                arguments: Arguments,
            ): ObjectKey {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Stamped object-key arguments do not belong to its output field"
                }
                return if (arguments is Arguments.Ground) {
                    GroundKey.of(stamp, field, arguments)
                } else {
                    StampedObjectKeyImpl(field, arguments, stamp)
                }
            }

            fun of(
                field: Schema.ObjectField,
                arguments: Map<String, Any?>,
            ): ObjectKey = of(field, Arguments.of(field, arguments))

            fun of(
                field: Schema.ObjectField,
                arguments: Arguments,
            ): ObjectKey {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Key arguments do not belong to its output field"
                }
                return if (arguments is Arguments.Ground) {
                    GroundKeyImpl(field, arguments, Stamp.VariableFreeOccurrence)
                } else {
                    ObjectKeyImpl(field, arguments, arguments.inferredKeyStamp())
                }
            }
        }
    }

    /**
     * A concrete-object key whose arguments are ground and which can therefore select an OER field.
     */
    sealed interface GroundKey : ObjectKey, PathComponent {
        override val arguments: Arguments.Ground
        override val stamp: Stamp

        companion object {
            /**
             * Constructs a ground key produced from a variable-bearing source selection.
             *
             * [stamp] distinguishes different source selections even when their grounded arguments
             * agree.
             */
            fun of(
                stamp: Stamp.Occurrence,
                field: Schema.ObjectField,
                arguments: Arguments.Ground,
            ): GroundKey {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Ground arguments do not belong to the stamped selection field"
                }
                return StampedGroundKeyImpl(
                    field = field,
                    arguments = arguments,
                    stamp = stamp,
                )
            }

            fun of(
                field: Schema.ObjectField,
                arguments: Map<String, Any?>,
            ): GroundKey {
                val grounded = Arguments.of(field, arguments)
                require(grounded is Arguments.Ground) {
                    "Ground-key arguments cannot contain variables"
                }
                return of(field, grounded)
            }

            fun of(
                field: Schema.ObjectField,
                arguments: Arguments.Ground,
            ): GroundKey {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Key arguments do not belong to its output field"
                }
                return GroundKeyImpl(field, arguments, Stamp.VariableFreeOccurrence)
            }
        }
    }

    val type: Schema.Object

    val keys: Set<GroundKey>

    fun isCellSet(field: GroundKey): Boolean = field in keys

    /** @throws NoSuchElementException when [field] has no cell */
    fun getCell(field: GroundKey): EngineResultCell

    /**
     * Returns the field cell, explicitly creating an unclaimed reader placeholder when this
     * mutable object is not frozen.
     *
     * @throws NoSuchElementException when this object is immutable or frozen and has no cell
     */
    fun reserveCell(field: GroundKey): EngineResultCell

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
            type: Schema.Object,
            values: Map<GroundKey, EngineResult?> = emptyMap(),
            accessResults: Map<GroundKey, EngineResult> =
                values.keys.associateWith { true },
            mutable: Boolean = false,
        ): ObjectEngineResult {
            val fields = values.keys + accessResults.keys
            fields.forEach { field ->
                validateObjectField(type, field)
            }
            values.forEach { (field, value) -> validateObjectValue(field, value) }
            accessResults.values.forEach(::validateAccessResult)
            return ObjectResultImpl(
                type = type,
                cells =
                    fields.associateWith { field ->
                        CellImpl(
                            initialValue = values[field],
                            initiallyValueSet = field in values,
                            accessResult = accessResults[field],
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
sealed interface ListEngineResult : List<EngineResultCell> {
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

    val typeExpr: TypeExpr<Schema.OutputTypeDef>

    companion object {
        /**
         * ### Invariant: list-engine-result-factory-schema-conformance
         *
         * Every cell value satisfies `value.conformsToResultSchemaType(typeExpr)` in its reasoning
         * world.
         */
        fun of(
            typeExpr: TypeExpr<Schema.OutputTypeDef>,
            values: List<EngineResult?>,
            accessResults: List<EngineResult?> =
                values.map { true },
            mutableCells: Boolean = false,
        ): ListEngineResult {
            require(values.all { value -> value.conformsToResultSchemaType(typeExpr) }) {
                "List engine result contains an element incompatible with $typeExpr"
            }
            require(accessResults.size == values.size) {
                "List engine result access results must match its value count"
            }
            accessResults.filterNotNull().forEach(::validateAccessResult)
            val cells =
                values.mapIndexed { index, value ->
                    CellImpl(
                        initialValue = value,
                        initiallyValueSet = true,
                        accessResult = accessResults[index],
                        mutable = mutableCells,
                        validateValue = { updated ->
                            require(updated.conformsToResultSchemaType(typeExpr)) {
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
 * [EngineResultCell] and [ObjectEngineResult] use reference equality. Both trees must be finite
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
        is ListEngineResult ->
            other is ListEngineResult &&
                typeExpr == other.typeExpr &&
                size == other.size &&
                indices.all { index -> this[index].hasSameCompletedCellAs(other[index]) }
        is ObjectEngineResult ->
            other is ObjectEngineResult && sameCompletedObjectResultAs(other)
        else -> isScalarResultMember() && other?.isScalarResultMember() == true && this == other
    }
}

private fun EngineResultCell.hasSameCompletedCellAs(other: EngineResultCell): Boolean {
    val leftValue = completedValue
    val rightValue = other.completedValue
    return leftValue.hasSameCompletedResultAs(rightValue) &&
        completedAccessResult == other.completedAccessResult
}

/**
 * Returns the structural union of this nullable result and [other].
 *
 * Two null values have a null union. A null and non-null value have no union. For two non-null
 * values, this partial mathematical function is defined only for results of the same variant.
 *
 * @throws IllegalArgumentException when the union is undefined
 */
internal fun EngineResult?.union(other: EngineResult?): EngineResult? {
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
        else -> {
            require(isScalarResultMember() && other.isScalarResultMember()) {
                "Cannot union different engine-result variants"
            }
            require(this == other) { "Cannot union unequal scalar engine results" }
            this
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
        accessResult = unionAccessResult(accessResult, other.accessResult),
    )

/**
 * Returns the object result containing the union of every cell present in either operand.
 *
 * @throws IllegalArgumentException when the object types differ or any shared cell has no union
 */
internal fun ObjectEngineResult.union(other: ObjectEngineResult): ObjectEngineResult {
    require(type == other.type) {
        "Cannot union object engine results of different types"
    }

    val leftCells = implementation.completedCells.mapValues { (_, cell) -> cell.completed() }
    val rightCells = other.implementation.completedCells.mapValues { (_, cell) -> cell.completed() }
    val cells = unionMaps(leftCells, rightCells, CompletedCell::union)
    return ObjectEngineResult.of(
        type = type,
        values = cells.mapValues { (_, cell) -> cell.value },
        accessResults =
            cells.mapNotNull { (key, cell) ->
                cell.accessResult?.let { key to it }
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
internal fun ListEngineResult.union(other: ListEngineResult): ListEngineResult {
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
        accessResults = cells.map(CompletedCell::accessResult),
    )
}

private class CellImpl(
    initialValue: EngineResult? = null,
    initiallyValueSet: Boolean = false,
    accessResult: EngineResult? = null,
    private val mutable: Boolean,
    private val validateValue: (EngineResult?) -> Unit = {},
) : EngineResultCell {
    init {
        accessResult?.let(::validateAccessResult)
    }

    private val valueStore =
        CellValueStore(
            initialValue = initialValue,
            initiallySet = initiallyValueSet,
            mutable = mutable,
            validateValue = validateValue,
        )
    private val accessResultStore =
        promiseStore(accessResult?.let { mapOf(Unit to it) }.orEmpty())

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

    override fun getAccessResult(): Promise<EngineResult> =
        checkNotNull(accessResultStore.readOrNull(Unit)) {
            "Cell has no access result"
        }

    override fun setAccessResult(result: EngineResult) {
        checkMutable()
        validateAccessResult(result)
        accessResultStore.set(Unit, result)
    }

    override fun createAccessResultPromise(): Promise<EngineResult> {
        checkMutable()
        return accessResultStore.create(Unit, ::validateAccessResult)
    }

    fun freezeValue(cause: Throwable) {
        if (mutable) valueStore.freeze(cause)
    }

    fun requireCompleted() {
        valueStore.readOrNull()?.get().requireCompleted()
        accessResultStore.snapshot().values.forEach { promise -> promise.get() }
    }

    val completedValue: EngineResult?
        get() = checkNotNull(valueStore.readOrNull()) { "Cell has no value" }.get()

    val completedAccessResult: EngineResult?
        get() = accessResultStore.readOrNull(Unit)?.get()

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
    override val type: Schema.Object,
    cells: Map<ObjectEngineResult.GroundKey, EngineResultCell>,
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

    override fun getCell(field: ObjectEngineResult.GroundKey): EngineResultCell {
        validateObjectField(type, field)
        return cellStore.readOrNull(field)
            ?: throw missingResultCell(type, field)
    }

    override fun reserveCell(field: ObjectEngineResult.GroundKey): EngineResultCell {
        validateObjectField(type, field)
        return cellStore.reserve(field)
    }

    override fun freeze() {
        cellStore.freeze()
    }

    val completedCells: Map<ObjectEngineResult.GroundKey, EngineResultCell>
        get() = cellStore.completedCells()

    fun requireCompleted() {
        cellStore.cellValues.forEach { cell -> cell.implementation.requireCompleted() }
    }
}

private class ObjectCellStore(
    private val type: Schema.Object,
    cells: Map<ObjectEngineResult.GroundKey, EngineResultCell>,
    private val mutable: Boolean,
) {
    private val lock = Any()
    private val cells = cells.toMutableMap()
    private var frozen = !mutable

    val keys: Set<ObjectEngineResult.GroundKey>
        get() = synchronized(lock) { cells.keys.toSet() }

    val cellValues: List<EngineResultCell>
        get() = synchronized(lock) { cells.values.toList() }

    fun isSet(field: ObjectEngineResult.GroundKey): Boolean = synchronized(lock) { field in cells }

    fun readOrNull(field: ObjectEngineResult.GroundKey): EngineResultCell? =
        synchronized(lock) { cells[field] }

    fun reserve(field: ObjectEngineResult.GroundKey): EngineResultCell =
        synchronized(lock) {
            cells[field]
                ?: if (frozen) {
                    throw missingResultCell(type, field)
                } else {
                    mutableCell(field).also { cell ->
                        cells[field] = cell
                    }
                }
        }

    fun freeze() {
        val presentCells =
            synchronized(lock) {
                check(mutable) { "${type.name} result is immutable" }
                check(!frozen) { "${type.name} result is already frozen" }
                frozen = true
                cells.toMap()
            }
        presentCells.forEach { (field, cell) ->
            cell.implementation.freezeValue(
                missingResultCell(type, field),
            )
        }
    }

    fun completedCells(): Map<ObjectEngineResult.GroundKey, EngineResultCell> =
        synchronized(lock) {
            cells.mapValues { (_, cell) ->
                cell.also { it.implementation.requireCompleted() }
            }
        }

    private fun mutableCell(field: ObjectEngineResult.GroundKey): EngineResultCell =
        CellImpl(
            mutable = true,
            validateValue = { value -> validateObjectValue(field, value) },
        )
}

private fun missingResultCell(
    type: Schema.Object,
    field: ObjectEngineResult.GroundKey,
): NoSuchElementException =
    NoSuchElementException(
        "Missing engine-result cell: ${type.name}.${field.field.name}",
    )

private class ListResultImpl(
    override val typeExpr: TypeExpr<Schema.OutputTypeDef>,
    private val cells: List<EngineResultCell>,
) : ListEngineResult,
    List<EngineResultCell> by cells {
    override fun equals(other: Any?): Boolean =
        other is ListEngineResult &&
            typeExpr == other.typeExpr &&
            cells == other

    override fun hashCode(): Int = 31 * typeExpr.hashCode() + cells.hashCode()
}

private data class ListIndexImpl(
    override val index: Int,
) : ListEngineResult.Index

private data class KeyImpl(
    override val field: Schema.Field,
    override val arguments: Arguments,
    override val stamp: Stamp?,
) : ObjectEngineResult.Key

private data class StampedKeyImpl(
    override val field: Schema.Field,
    override val arguments: Arguments,
    override val stamp: Stamp.Occurrence,
) : ObjectEngineResult.Key

private data class VariableKeyImpl(
    override val field: Schema.Field,
    override val arguments: Arguments,
    override val variableDefinedByThisKey: Arguments.Variable,
    override val stamp: Stamp?,
) : ObjectEngineResult.VariableKey

private data class StampedVariableKeyImpl(
    override val field: Schema.Field,
    override val arguments: Arguments,
    override val variableDefinedByThisKey: Arguments.Variable,
    override val stamp: Stamp.Occurrence,
) : ObjectEngineResult.VariableKey

private data class ObjectKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: Arguments,
    override val stamp: Stamp?,
) : ObjectEngineResult.ObjectKey

private data class StampedObjectKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: Arguments,
    override val stamp: Stamp.Occurrence,
) : ObjectEngineResult.ObjectKey

private data class GroundKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: Arguments.Ground,
    override val stamp: Stamp,
) : ObjectEngineResult.GroundKey

private data class StampedGroundKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: Arguments.Ground,
    override val stamp: Stamp.Occurrence,
) : ObjectEngineResult.GroundKey

private fun Arguments.inferredKeyStamp(): Stamp? {
    val variables = usedVariables()
    return if (variables.any(Arguments.Variable::isTemplate)) {
        null
    } else {
        Stamp.VariableFreeOccurrence
    }
}

private data class CompletedCell(
    val value: EngineResult?,
    val accessResult: EngineResult?,
)

private fun EngineResultCell.completed(): CompletedCell =
    CompletedCell(
        value = completedValue,
        accessResult = completedAccessResult,
    )

private val EngineResultCell.implementation: CellImpl
    get() = this as CellImpl

private val EngineResultCell.completedValue: EngineResult?
    get() = implementation.completedValue

private val EngineResultCell.completedAccessResult: EngineResult?
    get() = implementation.completedAccessResult

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
        -> Unit
        is ListEngineResult ->
            indices.forEach { index -> get(index).implementation.requireCompleted() }
        is ObjectEngineResult -> implementation.requireCompleted()
        else -> check(isScalarResultMember()) { "Value is not an engine result: $this" }
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

private fun unionAccessResult(
    first: EngineResult?,
    second: EngineResult?,
): EngineResult? =
    when {
        first == null -> second
        second == null -> first
        else ->
            first.also {
                require(first == second) {
                    "Cannot union cells with unequal access results"
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
    type: Schema.Object,
    field: ObjectEngineResult.GroundKey,
): Unit =
    require(field.field.containingDef == type) {
        "${type.name} result contains a field owned by another type"
    }

private fun validateObjectValue(
    field: ObjectEngineResult.GroundKey,
    value: EngineResult?,
) {
    if (field.arguments == Arguments.Error) {
        require(value == ErrorEngineResult) {
            "A key with erroneous arguments must contain an error value"
        }
    }
    require(value.conformsToResultSchemaType(field.field.type)) {
        "${field.field.containingDef.name}/${field.field.name} result does not conform to " +
            field.field.type
    }
}

private fun EngineResult.isScalarResultMember(): Boolean =
    this is Int ||
        this is Double && isFinite() ||
        this is Boolean ||
        this is String ||
        this is EngineIDResult ||
        this is Schema.EnumValue

private fun validateAccessResult(result: EngineResult) {
    require(result is Boolean || result == ErrorEngineResult) {
        "Cell access result must be Boolean or ErrorEngineResult"
    }
}
