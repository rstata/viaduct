package model

import model.invariants.conformsToSchemaType

/**
 * A finite, well-founded field-resolution result.
 *
 * Equality depends on the result variant. [Value.Simple] values use structural equality, [Object]
 * values use reference equality, and [List] values use structural equality over their type
 * expression and elements. Schema definitions within those properties use the canonical equality
 * documented by [Schema].
 */
sealed interface EngineResult {
    /**
     * A finite object result whose value, field-check, and type-check promises are write-once.
     *
     * Every present value key belongs to [type], contains no variables, and completes only with a
     * value conforming to the field's type expression. [getValue] is a strict read.
     * [reserveValue] explicitly installs an unclaimed reader placeholder on a mutable object. A
     * writer claims that placeholder through [createValuePromise] or [setValue]. [freeze] seals the
     * key set and fails every unclaimed placeholder. A claimed promise may complete after freezing.
     *
     * Objects use reference equality and stable identity hashing, so they may be used as map keys
     * while promises are installed or completed.
     */
    sealed interface Object : EngineResult {
        val type: Schema.ObjectType

        val keys: Set<Value.GroundKey>

        fun isValueSet(field: Value.GroundKey): Boolean = field in keys

        /** @throws MissingFieldException when [field] has no value promise */
        fun getValue(field: Value.GroundKey): Promise<EngineResult?>

        /**
         * Returns the field promise, explicitly creating an unclaimed reader placeholder when this
         * mutable object is not frozen.
         *
         * @throws MissingFieldException when this object is immutable or frozen and has no promise
         */
        fun reserveValue(field: Value.GroundKey): Promise<EngineResult?>

        /** @throws MissingFieldException when [field] has no field-check promise */
        fun getFieldCheck(field: Value.GroundKey): Promise<Value.Boolean>

        /** @throws IllegalStateException when this object has no type-check promise */
        fun getTypeCheck(): Promise<Value.Boolean>

        fun setValue(
            field: Value.GroundKey,
            value: EngineResult?,
        )

        /** Sets whether the field check accepts access. */
        fun setFieldCheck(
            field: Value.GroundKey,
            accept: Value.Boolean,
        )

        /** Sets whether the type check accepts access. */
        fun setTypeCheck(accept: Value.Boolean)

        fun createValuePromise(field: Value.GroundKey): Promise<EngineResult?>

        fun createFieldCheckPromise(field: Value.GroundKey): Promise<Value.Boolean>

        fun createTypeCheckPromise(): Promise<Value.Boolean>

        /**
         * Seals this object's value-key set and fails every reader-created placeholder that no
         * writer claimed. Claimed promises may still complete.
         */
        fun freeze()

        companion object {
            /**
             * ### Invariant: object-engine-result-factory-schema-conformance
             *
             * Every initially present value satisfies its field's schema type. When [mutable] is
             * false, every set and promise-creation operation throws. When it is true, each absent
             * value, field check, and type check may be installed once.
             */
            fun of(
                type: Schema.ObjectType,
                values: Map<Value.GroundKey, EngineResult?> = emptyMap(),
                fieldChecks: Map<Value.GroundKey, Value.Boolean> =
                    values.keys.associateWith { Value.Boolean.of(true) },
                typeCheck: Value.Boolean? = Value.Boolean.of(true),
                mutable: Boolean = false,
            ): Object {
                values.forEach { (field, value) ->
                    validateObjectField(type, field)
                    validateObjectValue(field, value)
                }
                fieldChecks.keys.forEach { field ->
                    validateObjectField(type, field)
                }
                return ObjectResultImpl(
                    type = type,
                    values = values,
                    fieldChecks = fieldChecks,
                    typeCheck = typeCheck,
                    mutable = mutable,
                )
            }
        }
    }

    /**
     * A typed list result.
     *
     * [typeExpr] is the expected type of each element, including its nullability and nested lists.
     * Type-check state belongs to each object element's [Object], while the containing field's
     * field-check state belongs to its containing object. Lists use structural equality over
     * [typeExpr] and positional element equality; object elements therefore compare by reference.
     *
     * Including [typeExpr] in equality is intentional. The factory validates every element against
     * it, so it acts as a retained type witness: assigning a list to a compatible list position
     * requires comparing type expressions, not recursively revalidating its contents. Content-only
     * equality would equate lists with different assignability unless every assignment walked the
     * elements again.
     */
    sealed interface List : EngineResult {
        val typeExpr: TypeExpr<Schema.OutputType>
        val size: Int
        val indices: IntRange
            get() = 0 until size

        operator fun get(index: Int): EngineResult?

        fun <R> map(transform: (EngineResult?) -> R): kotlin.collections.List<R> =
            indices.map { index -> transform(get(index)) }

        fun all(predicate: (EngineResult?) -> Boolean): Boolean =
            indices.all { index -> predicate(get(index)) }

        fun forEachIndexed(action: (index: Int, EngineResult?) -> Unit) {
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
                values: kotlin.collections.List<EngineResult?>,
            ): List {
                require(values.all { it.conformsToSchemaType(typeExpr) }) {
                    "List engine result contains an element incompatible with $typeExpr"
                }
                return ListResultImpl(typeExpr, values)
            }
        }
    }
}

/**
 * Returns whether two completed result trees contain the same values and checks.
 *
 * This explicit extensional comparison is distinct from ordinary equality because [EngineResult.Object]
 * uses reference equality. Both trees must be finite and every present promise they contain must be
 * completed. Its result is meaningful only after both trees are quiescent; the comparison does not
 * take an atomic snapshot while promises or slots are being mutated concurrently.
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
                indices.all { index -> this[index].hasSameCompletedResultAs(other[index]) }
        is EngineResult.Object ->
            other is EngineResult.Object && sameCompletedObjectResultAs(other)
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
 * Returns the object result containing the union of every value and check present in either
 * operand.
 *
 * @throws IllegalArgumentException when the object types differ or any shared fact has no union
 */
fun EngineResult.Object.union(other: EngineResult.Object): EngineResult.Object {
    require(type == other.type) {
        "Cannot union object engine results of different types"
    }

    val left = implementation
    val right = other.implementation
    return EngineResult.Object.of(
        type = type,
        values = unionMaps(left.completedValues, right.completedValues, EngineResult?::union),
        fieldChecks =
            unionMaps(left.completedFieldChecks, right.completedFieldChecks) { first, second ->
                require(first == second) {
                    "Cannot union object engine results with unequal field checks"
                }
                first
            },
        typeCheck = unionTypeChecks(left.completedTypeCheck, right.completedTypeCheck),
    )
}

/**
 * Returns the position-wise union of this list and [other].
 *
 * The operands must have equal element type expressions and lengths.
 *
 * @throws IllegalArgumentException when the type expressions or lengths differ, or when any
 * corresponding values have no union
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
        values = indices.map { index -> this[index].union(other[index]) },
    )
}

private class ObjectResultImpl(
    override val type: Schema.ObjectType,
    values: Map<Value.GroundKey, EngineResult?>,
    fieldChecks: Map<Value.GroundKey, Value.Boolean>,
    typeCheck: Value.Boolean?,
    private val mutable: Boolean,
) : EngineResult.Object {
    private val valueStore =
        ObjectValueStore(
            type = type,
            values = values,
            mutable = mutable,
        )
    private val fieldCheckStore = promiseStore(fieldChecks)
    private val typeCheckStore = promiseStore(typeCheck?.let { mapOf(Unit to it) }.orEmpty())

    override val keys: Set<Value.GroundKey>
        get() = valueStore.keys

    override fun isValueSet(field: Value.GroundKey): Boolean = valueStore.isSet(field)

    override fun getValue(field: Value.GroundKey): Promise<EngineResult?> {
        validateObjectField(type, field)
        return valueStore.readOrNull(field)
            ?: throw MissingFieldException(type.typeName, field.field.fieldName)
    }

    override fun reserveValue(field: Value.GroundKey): Promise<EngineResult?> {
        validateObjectField(type, field)
        return valueStore.reserve(field)
    }

    override fun getFieldCheck(field: Value.GroundKey): Promise<Value.Boolean> =
        fieldCheckStore.readOrNull(field)
            ?: throw MissingFieldException(type.typeName, field.field.fieldName)

    override fun getTypeCheck(): Promise<Value.Boolean> =
        checkNotNull(typeCheckStore.readOrNull(Unit)) {
            "${type.typeName} result has no type check"
        }

    override fun setValue(
        field: Value.GroundKey,
        value: EngineResult?,
    ) {
        validateObjectField(type, field)
        validateObjectValue(field, value)
        valueStore.claimAndComplete(field, value)
    }

    override fun setFieldCheck(
        field: Value.GroundKey,
        accept: Value.Boolean,
    ) {
        checkWritable(field)
        fieldCheckStore.set(field, accept)
    }

    override fun setTypeCheck(accept: Value.Boolean) {
        checkMutable()
        typeCheckStore.set(Unit, accept)
    }

    override fun createValuePromise(field: Value.GroundKey): Promise<EngineResult?> {
        validateObjectField(type, field)
        return valueStore.claim(field)
    }

    override fun createFieldCheckPromise(field: Value.GroundKey): Promise<Value.Boolean> {
        checkWritable(field)
        return fieldCheckStore.create(field)
    }

    override fun createTypeCheckPromise(): Promise<Value.Boolean> {
        checkMutable()
        return typeCheckStore.create(Unit)
    }

    override fun freeze() {
        valueStore.freeze()
    }

    val completedValues: Map<Value.GroundKey, EngineResult?> get() = valueStore.completedValues()

    val completedFieldChecks: Map<Value.GroundKey, Value.Boolean> get() =
        fieldCheckStore.completedValues()

    val completedTypeCheck: Value.Boolean? get() = typeCheckStore.readOrNull(Unit)?.get()

    private fun checkWritable(field: Value.GroundKey) {
        checkMutable()
        validateObjectField(type, field)
    }

    private fun checkMutable() = check(mutable) { "${type.typeName} result is immutable" }

    fun requireCompleted() {
        valueStore.promises.forEach { promise ->
            promise.get().requireCompleted()
        }
        fieldCheckStore.snapshot().values.forEach { promise -> promise.get() }
        typeCheckStore.snapshot().values.forEach { promise -> promise.get() }
    }
}

private class ObjectValueStore(
    private val type: Schema.ObjectType,
    values: Map<Value.GroundKey, EngineResult?>,
    private val mutable: Boolean,
) {
    private val lock = Any()
    private val slots =
        values
            .mapValuesTo(linkedMapOf()) { (_, value) ->
                ValueSlot(
                    promise = Promise.of(value),
                    claimed = true,
                )
            }
    private var frozen = !mutable

    val keys: Set<Value.GroundKey>
        get() = synchronized(lock) { slots.keys.toSet() }

    val promises: List<Promise<EngineResult?>>
        get() = synchronized(lock) { slots.values.map { slot -> slot.promise } }

    fun isSet(field: Value.GroundKey): Boolean = synchronized(lock) { field in slots }

    fun readOrNull(field: Value.GroundKey): Promise<EngineResult?>? =
        synchronized(lock) { slots[field]?.promise }

    fun reserve(field: Value.GroundKey): Promise<EngineResult?> =
        synchronized(lock) {
            slots[field]?.promise
                ?: if (frozen) {
                    throw MissingFieldException(type.typeName, field.field.fieldName)
                } else {
                    Promise
                        .ofDeferred<EngineResult?> { value ->
                            validateObjectValue(field, value)
                        }.also { promise ->
                            slots[field] =
                                ValueSlot(
                                    promise = promise,
                                    claimed = false,
                                )
                        }
                }
        }

    fun claim(field: Value.GroundKey): Promise<EngineResult?> =
        synchronized(lock) {
            check(!frozen) { "${type.typeName} result is frozen" }
            val existing = slots[field]
            if (existing != null) {
                check(!existing.claimed) { "$field already has a writer" }
                existing.claimed = true
                existing.promise
            } else {
                Promise
                    .ofDeferred<EngineResult?> { value ->
                        validateObjectValue(field, value)
                    }.also { promise ->
                        slots[field] =
                            ValueSlot(
                                promise = promise,
                                claimed = true,
                            )
                    }
            }
        }

    fun claimAndComplete(
        field: Value.GroundKey,
        value: EngineResult?,
    ) {
        claim(field).complete(value)
    }

    fun freeze() {
        val unclaimed =
            synchronized(lock) {
                check(mutable) { "${type.typeName} result is immutable" }
                check(!frozen) { "${type.typeName} result is already frozen" }
                frozen = true
                slots
                    .filterValues { slot -> !slot.claimed }
                    .map { (field, slot) -> field to slot.promise }
            }
        unclaimed.forEach { (field, promise) ->
            promise.fail(MissingFieldException(type.typeName, field.field.fieldName))
        }
    }

    fun completedValues(): Map<Value.GroundKey, EngineResult?> =
        synchronized(lock) {
            slots.mapValues { (_, slot) -> slot.promise.get() }
        }

    private data class ValueSlot(
        val promise: Promise<EngineResult?>,
        var claimed: Boolean,
    )
}

private data class ListResultImpl(
    override val typeExpr: TypeExpr<Schema.OutputType>,
    private val values: kotlin.collections.List<EngineResult?>,
) : EngineResult.List {
    override val size: Int
        get() = values.size

    override fun get(index: Int): EngineResult? = values[index]
}

private val EngineResult.Object.implementation: ObjectResultImpl
    get() = this as ObjectResultImpl

/**
 * The result is meaningful only after both object trees are quiescent. Store snapshots and
 * recursive reads do not form one atomic snapshot while promises or slots are being mutated.
 */
private fun EngineResult.Object.sameCompletedObjectResultAs(other: EngineResult.Object): Boolean {
    val left = implementation
    val right = other.implementation
    val leftValues = left.completedValues
    val rightValues = right.completedValues
    val leftFieldChecks = left.completedFieldChecks
    val rightFieldChecks = right.completedFieldChecks
    val leftTypeCheck = left.completedTypeCheck
    val rightTypeCheck = right.completedTypeCheck

    return type == other.type &&
        leftValues.keys == rightValues.keys &&
        leftValues.all { (key, value) ->
            value.hasSameCompletedResultAs(rightValues.getValue(key))
        } &&
        leftFieldChecks == rightFieldChecks &&
        leftTypeCheck == rightTypeCheck
}

private fun EngineResult?.requireCompleted() {
    when (this) {
        null,
        is Value.Simple,
        -> Unit
        is EngineResult.List -> indices.forEach { index -> get(index).requireCompleted() }
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

private fun unionTypeChecks(
    first: Value.Boolean?,
    second: Value.Boolean?,
): Value.Boolean? =
    when {
        first == null -> second
        second == null -> first
        else ->
            first.also {
                require(first == second) {
                    "Cannot union object engine results with unequal type checks"
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

private fun <K : Any, V> OnceStore<K, Promise<V>>.completedValues(): Map<K, V> =
    snapshot().mapValues { (_, promise) -> promise.get() }

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
