package model

/** An opaque identity assigned once to a selection occurrence in a resolver registry. */
class SelectionOccurrenceId internal constructor(
    internal val sourceKey: Value.Key,
)

/**
 * The concrete resolver occurrence and opaque lineage of one variable-bearing selection.
 *
 * [resolverPath] anchors the stamp at a concrete resolver occurrence. [occurrenceLineage] contains
 * registry-assigned identities crossed through ungrounded resolver boundaries. Selection equality
 * is undefined, so stamps compare only these stable opaque identities.
 */
class SelectionStamp internal constructor(
    val resolverPath: List<PathComponent>,
    internal val occurrenceLineage: List<SelectionOccurrenceId>,
) {
    /** The original registry key represented by the final occurrence in this lineage. */
    val sourceKey: Value.Key
        get() = occurrenceLineage.last().sourceKey

    init {
        require(occurrenceLineage.isNotEmpty()) {
            "Selection-stamp occurrence lineage must be nonempty"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SelectionStamp &&
            resolverPath == other.resolverPath &&
            occurrenceLineage == other.occurrenceLineage

    override fun hashCode(): Int = 31 * resolverPath.hashCode() + occurrenceLineage.hashCode()

    override fun toString(): String =
        "SelectionStamp(resolverPath=$resolverPath, occurrences=${occurrenceLineage.size})"
}

/**
 * A free commutative collection of opaque [Selection] members.
 *
 * ### Member Count
 *
 * [size] observes the number of current members. A forest returned directly by
 * [model.spec.flatten] has one member for each flattened GraphQL field occurrence, but that is a
 * postcondition of flattening rather than an invariant of every forest.
 *
 * ### Equality And Observation
 *
 * Selection and forest equality are undefined, and no operation compares whole [Selection] values
 * or exposes member order. [merge] is the explicit normalization boundary that compares structural
 * object keys and coalesces forest members.
 */
sealed interface SelectionForest {
    val size: Int

    fun isEmpty(): Boolean

    fun all(predicate: (Selection) -> Boolean): Boolean

    fun filter(predicate: (Selection) -> Boolean): SelectionForest

    fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest

    fun forEach(action: (Selection) -> Unit)

    fun single(): Selection

    operator fun plus(other: SelectionForest): SelectionForest
}

/**
 * A normalized selection forest specialized to one concrete parent object type.
 *
 * ### Invariant: object-selection-forest-normalization
 *
 * Every selection is an [ObjectSelection] whose key field belongs to [type], whose
 * `possibleTypes == setOf(type)`, and whose key occurs exactly once.
 */
sealed interface ObjectSelectionForest : SelectionForest {
    val type: Schema.ObjectType

    override fun filter(predicate: (Selection) -> Boolean): ObjectSelectionForest

    /** The concrete-object keys contributed by these normalized selections. */
    fun keys(): Set<Value.ObjectKey>

    /** Returns the unique selection for each key. */
    fun byKey(): Map<Value.ObjectKey, ObjectSelection>

    /**
     * Returns the ground keys contributed by these selections.
     *
     * @throws IllegalStateException when any key contains an open argument expression
     */
    fun groundKeys(): Set<Value.GroundKey>

    /**
     * Returns the unique selection for each ground key.
     *
     * @throws IllegalStateException when any key contains an open argument expression
     */
    fun byGroundKey(): Map<Value.GroundKey, ObjectSelection>

    /**
     * Returns the selection for [key].
     *
     * @throws NoSuchElementException when [key] is absent
     */
    operator fun get(key: Value.ObjectKey): ObjectSelection

    companion object {
        /** Constructs a normalized forest satisfying [ObjectSelectionForest]'s invariant. */
        fun of(
            type: Schema.ObjectType,
            selections: Iterable<ObjectSelection>,
        ): ObjectSelectionForest {
            val occurrences =
                buildList {
                    addAll(selections)
                }
            val byKey =
                buildMap {
                    occurrences.forEach { selection ->
                        require(
                            selection.key.field.containingType == type &&
                                selection.possibleTypes.size == 1 &&
                                type in selection.possibleTypes,
                        ) {
                            "Object selections must belong exclusively to ${type.typeName}"
                        }
                        require(put(selection.key, selection) == null) {
                            "Object selection keys must be unique"
                        }
                    }
                }
            return ObjectSelectionForestImpl(type, byKey, occurrences)
        }
    }
}

/** Constructs a [SelectionForest] containing the supplied occurrences. */
fun selectionForestOf(vararg selections: Selection): SelectionForest =
    SelectionForestImpl(selections.asList())

/** Constructs a [SelectionForest] from these occurrences. */
fun Iterable<Selection>.toSelectionForest(): SelectionForest =
    SelectionForestImpl(toList())

/** Maps each input to a forest and concatenates the results without comparing selections. */
fun <T : Any> Iterable<T>.flatMapToSelectionForest(
    transform: (T) -> SelectionForest,
): SelectionForest =
    SelectionForestImpl(
        buildList {
            this@flatMapToSelectionForest.forEach { element ->
                addAll(transform(element).occurrences())
            }
        },
    )

/** Concatenates these forests without comparing their selection occurrences. */
fun Iterable<SelectionForest>.concatenateSelectionForests(): SelectionForest =
    flatMapToSelectionForest { forest -> forest }

/**
 * Returns the demand applicable to concrete parent [type], normalized by structural object key.
 *
 * Applicability is tested before specialization, so an inapplicable occurrence contributes neither
 * its key nor its descendants. Each applicable key is reconstructed against the canonical field
 * on [type], including concrete argument defaults, and occurrences with equal reconstructed keys
 * are replaced by one selection whose subselections are their concatenated subselections.
 *
 * Arguments remain open. Merging must therefore be repeated after binding substitution can make
 * formerly distinct argument tuples equal. Subselections are not recursively merged because their
 * concrete runtime parent type is not yet known.
 */
fun SelectionForest.merge(type: Schema.ObjectType): ObjectSelectionForest {
    val childrenByKey =
        buildMap<Value.ObjectKey, MutableList<SelectionForest>> {
            occurrences().forEach { selection ->
                if (type in selection.possibleTypes) {
                    getOrPut(selection.objectKey(type), ::mutableListOf)
                        .add(selection.subselections)
                }
            }
        }
    return normalizedObjectSelectionForest(type, childrenByKey)
}

/**
 * Grounds and normalizes the demand applicable to [result], reporting completed path bindings.
 *
 * Every applicable occurrence contributes an ordinary ground selection, including an occurrence
 * whose [Value.VariableKey] is the only demand for that key. Equal keys are coalesced after binding
 * substitution. A terminal marker reports its completed simple, list, null, or error value. An
 * intermediate marker defers a completed object to its marked child but reports null or error as a
 * prematurely terminated binding. Absent and incomplete result keys report no binding.
 */
context(world: Assumptions)
suspend fun SelectionForest.mergeWithVariables(
    result: EngineResult.Object,
): Pair<ObjectSelectionForest, Map<Value.Variable.Stamped, Value.Input?>> {
    val type: Schema.ObjectType = result.type
    val childrenByKey: MutableMap<Value.ObjectKey, MutableList<SelectionForest>> =
        linkedMapOf()
    val groundKeyByVariable: MutableMap<Value.Variable.Stamped, Value.GroundKey> =
        linkedMapOf()
    val bindings: MutableMap<Value.Variable.Stamped, Value.Input?> =
        linkedMapOf()
    occurrences().forEach { selection ->
        if (type !in selection.possibleTypes) return@forEach

        val variable: Value.Variable.Stamped? =
            (selection.key as? Value.VariableKey)?.variableDefinedByThisKey
        val specializedKey: Value.ObjectKey = selection.objectKey(type)
        val groundKey: Value.GroundKey =
            specializedKey.ground(specializedKey.arguments.fetchBindings())
        childrenByKey
            .getOrPut(groundKey, ::mutableListOf)
            .add(selection.subselections)
        if (variable != null) {
            val previous: Value.GroundKey? = groundKeyByVariable.put(variable, groundKey)
            require(previous == null || previous == groundKey) {
                "Path-variable $variable is defined by conflicting keys: $previous and $groundKey"
            }
        }
        if (variable != null && result.isCellSet(groundKey)) {
            val promise = result.getCell(groundKey).getValue()
            if (!promise.isCompleted) return@forEach

            val value: EngineResult? = promise.get()
            val continues =
                selection.subselections.occurrences().any { child ->
                    (child.key as? Value.VariableKey)?.variableDefinedByThisKey == variable
                }
            val binding: Value.Input? =
                if (continues) {
                    when (value) {
                        null -> null
                        Value.Error -> Value.Error
                        is EngineResult.Object -> return@forEach
                        else ->
                            error(
                                "Path-variable $variable cannot continue through a non-object value",
                            )
                    }
                } else {
                    value.toPathVariableInput()
                }
            if (bindings.containsKey(variable)) {
                require(bindings[variable] == binding) {
                    "Path-variable $variable is defined by conflicting values: " +
                        "${bindings[variable]} and $binding"
                }
            } else {
                bindings[variable] = binding
            }
        }
    }
    return normalizedObjectSelectionForest(type, childrenByKey) to bindings
}

private fun EngineResult?.toPathVariableInput(): Value.Input? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.List -> toPathVariableInputList()
        is EngineResult.Object ->
            error("A path-variable provider cannot terminate at an object")
    }

@Suppress("UNCHECKED_CAST")
private fun EngineResult.List.toPathVariableInputList(): Value.InputList {
    require(typeExpr.baseType is Schema.InputType) {
        "A path-variable provider list must contain input-compatible simple values"
    }
    return Value.InputList.of(
        typeExpr = typeExpr as TypeExpr<Schema.InputType>,
        values = map { cell -> cell.getValue().get().toPathVariableInput() },
    )
}

/**
 * Instantiates this normalized forest's current bindings and normalizes by the resulting exact key.
 *
 * Every result key is a [Value.GroundKey]. Equal unstamped keys coalesce after substitution.
 * Distinct selection-stamped keys remain distinct even when their grounded argument values agree.
 *
 * @throws IllegalStateException when a key contains an unbound stamped variable or an unstamped
 * template
 */
context(world: Assumptions)
fun ObjectSelectionForest.instantiateBindings(): ObjectSelectionForest {
    val childrenByKey =
        buildMap<Value.ObjectKey, MutableList<SelectionForest>> {
            byKey().values.forEach { selection ->
                val key = selection.key.ground(selection.key.arguments.instantiateBindings())
                getOrPut(key, ::mutableListOf).add(selection.subselections)
            }
        }
    return normalizedObjectSelectionForest(type, childrenByKey)
}

/**
 * Awaits this normalized forest's bindings and normalizes by the resulting exact key.
 *
 * Descendant selections remain open until materialization reaches their concrete parent OER.
 */
context(world: Assumptions)
suspend fun ObjectSelectionForest.fetchBindings(): ObjectSelectionForest {
    val childrenByKey =
        buildMap<Value.ObjectKey, MutableList<SelectionForest>> {
            byKey().values.forEach { selection ->
                val key = selection.key.ground(selection.key.arguments.fetchBindings())
                getOrPut(key, ::mutableListOf).add(selection.subselections)
            }
        }
    return normalizedObjectSelectionForest(type, childrenByKey)
}

private fun Value.ObjectKey.ground(arguments: Value.Arguments): Value.GroundKey {
    val openArguments = this.arguments
    return when {
        this is Value.GroundKey.Stamped ->
            Value.GroundKey.Stamped.of(
                selectionStamp = selectionStamp,
                field = field,
                arguments = arguments,
            )
        openArguments is OpenArguments.Stamped ->
            Value.GroundKey.Stamped.of(
                selectionStamp = openArguments.selectionStamp,
                field = field,
                arguments = arguments,
            )
        else ->
            Value.GroundKey.of(
                field = field,
                arguments = arguments,
            )
    }
}

private fun normalizedObjectSelectionForest(
    type: Schema.ObjectType,
    childrenByKey: Map<Value.ObjectKey, List<SelectionForest>>,
): ObjectSelectionForest {
    // Specialization preserves local validity; grouping establishes normalized unique keys.
    val possibleTypes = setOf(type)
    val selectionsByKey =
        buildMap {
            childrenByKey.forEach { (key, children) ->
                put(
                    key,
                    ObjectSelectionImpl(
                        key = key,
                        possibleTypes = possibleTypes,
                        subselections = children.concatenateSelectionForests(),
                    ),
                )
            }
        }
    return ObjectSelectionForestImpl(type, selectionsByKey)
}

/**
 * Returns the selections applicable to concrete parent [type] with exact top-level keys.
 *
 * Descendant selections remain unspecialized until their concrete runtime parent is known.
 */
context(world: Assumptions)
fun SelectionForest.applicableGroundSelections(
    type: Schema.ObjectType,
): ObjectSelectionForest =
    merge(type).instantiateBindings()

/**
 * Extends every top-level selection-stamped key and provider marker through one concrete OER path.
 *
 * Descendant selections remain unchanged until traversal reaches their concrete parent OER.
 */
fun SelectionForest.localizeTopLevelSelectionStamps(
    path: List<PathComponent>,
): SelectionForest {
    if (path.isEmpty()) return this
    return flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key = selection.key.localizeSelectionStamps(path),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections,
            ),
        )
    }
}

// Returns this key with each occurrence stamp extended through the concrete OER path.
private fun Value.Key.localizeSelectionStamps(
    path: List<PathComponent>,
): Value.Key {
    val stampedArguments = arguments as? OpenArguments.Stamped
    val localizedArguments =
        stampedArguments?.restamp(
            stampedArguments.selectionStamp.extendThrough(path),
        )
    val baseKey =
        when (this) {
            is Value.GroundKey.Stamped ->
                Value.GroundKey.Stamped.of(
                    selectionStamp = selectionStamp.extendThrough(path),
                    field = field,
                    arguments = arguments,
                )

            else ->
                Value.Key.of(
                    field = field,
                    arguments = localizedArguments ?: arguments,
                )
        }
    val marker = (this as? Value.VariableKey)?.variableDefinedByThisKey
    return if (marker == null) {
        baseKey
    } else {
        Value.VariableKey.of(
            key = baseKey,
            variableDefinedByThisKey = marker.localizeSelectionStamp(path),
        )
    }
}

// Returns this variable with its selection stamp extended through the concrete OER path.
private fun Value.Variable.Stamped.localizeSelectionStamp(
    path: List<PathComponent>,
): Value.Variable.Stamped =
    when (this) {
        is Value.Variable.SelectionStamped ->
            Value.Variable
                .of(field = field, variableName = variableName)
                .stamp(selectionStamp.extendThrough(path))

        else -> this
    }

// Appends one concrete OER path to this resolver-instance identity.
private fun SelectionStamp.extendThrough(
    path: List<PathComponent>,
): SelectionStamp =
    SelectionStamp(
        resolverPath = resolverPath + path,
        occurrenceLineage = occurrenceLineage,
    )

/** Returns this selection's ground key. */
fun ObjectSelection.groundKey(): Value.GroundKey =
    key as? Value.GroundKey
        ?: error("Object selection key contains open arguments: $key")

/** Returns the occurrence-specific variables used by this key's arguments. */
fun Value.Key.stampedVariables(): Set<Value.Variable.Stamped> =
    arguments.stampedVariables()

/** Returns the occurrence-specific variables used recursively by this selection. */
fun Selection.stampedVariables(): Set<Value.Variable.Stamped> =
    key.stampedVariables() + subselections.stampedVariables()

/** Returns the occurrence-specific variables used recursively by this forest. */
fun SelectionForest.stampedVariables(): Set<Value.Variable.Stamped> {
    val variables = linkedSetOf<Value.Variable.Stamped>()
    forEach { selection -> variables += selection.stampedVariables() }
    return variables
}

/** Returns every variable expression used recursively by this selection. */
fun Selection.usedVariables(): Set<Value.Variable> =
    key.arguments.usedVariables() + subselections.usedVariables()

/** Returns every variable expression used recursively by this forest. */
fun SelectionForest.usedVariables(): Set<Value.Variable> {
    val variables = linkedSetOf<Value.Variable>()
    forEach { selection -> variables += selection.usedVariables() }
    return variables
}

/**
 * Specializes this selection's field coordinate to concrete parent [type].
 *
 * This operation preserves open argument expressions while applying the argument definition,
 * including defaults, of the corresponding canonical object field.
 */
fun Selection.objectKey(type: Schema.ObjectType): Value.ObjectKey {
    val concreteField = type.fields.getValue(key.field.fieldName)
    val sourceKey = key
    if (sourceKey is Value.GroundKey.Stamped) {
        val groundedArguments = sourceKey.arguments.retarget(concreteField)
        check(groundedArguments is Value.Arguments)
        return Value.GroundKey.Stamped.of(
            selectionStamp = sourceKey.selectionStamp,
            field = concreteField,
            arguments = groundedArguments,
        )
    }
    return Value.ObjectKey.of(
        field = concreteField,
        arguments = key.arguments.retarget(concreteField),
    )
}

/**
 * A post-validation field-selection occurrence used for Viaduct field resolution.
 *
 * This is not a GraphQL AST selection or a description of field completion. Aliases, response
 * keys, source order, named fragments, inline-fragment nodes, and directives are absent. Inline
 * fragments have already been flattened into the field coordinate in [key] and the applicability
 * guard in [possibleTypes].
 *
 * ### Invariant: selection-local-coherence
 *
 * Selections constructed by this model use [of], which ensures:
 * - [possibleTypes] is a subset of the object types contained by [key]'s field owner.
 * - When [isLeaf] is true, [subselections] is empty. The converse does not hold: a composite
 *   selection may also have no subselections.
 * - A selection's [key] is a [Value.ObjectKey] exactly when it is an [ObjectSelection].
 *
 * ### Invariant: selection-well-foundedness
 *
 * A selection and its [subselections] form a finite, well-founded value.
 *
 * ### Equality
 *
 * Kotlin `equals` is currently undefined for [Selection]. The model does not yet assume that
 * selections can or need to be compared; proofs must not rely on selection equality until that
 * requirement is established.
 */
sealed interface Selection {
    /**
     * Exact alias-free output-field coordinate being selected.
     *
     * ### Invariant: selection-argument-coercion
     *
     * The arguments form the post-validation tuple for the selected field: declared defaults have
     * been applied, every required argument is present, omitted optional arguments without defaults
     * are absent, and every present non-variable value conforms recursively to its argument type.
     *
     * ### Representation
     *
     * [Value.Key.field] is the canonical schema field intended by this selection, and
     * its containing type records the immediate field-lookup context. Non-variable argument values
     * are in their coerced semantic form. An argument may contain a [Value.Variable]. Variables
     * compare by name. Because a selection key is outside an OER or [Value.Object], its field may
     * belong to an abstract [Schema.InterfaceType] or [Schema.UnionType], and its arguments may
     * contain variables. Before a key is present in either value, its field must belong to the
     * applicable concrete [Schema.ObjectType] and its variables must be instantiated.
     *
     * Compared to GraphQL selections, model selections use an alias-free schema field coordinate
     * rather than a GraphQL response key.
     */
    val key: Value.Key

    /**
     * The concrete runtime parent-object types for which this selection applies: if during resolution
     * this selection is applied to a type outside of this set, then the selection will be dropped.
     * In prose this property is often called the "type condition" of the selection.
     * All of these objects must be contained in [Assumptions.schema].
     *
     * Compared to GraphQL selections, this is the intersection of all the possible types of all the
     * containing spreads and the type-level type of the containing document.  Unlike GraphQL
     * selection, this is the true intersection: even when a "broadening" type condition is
     * applied in the GraphQL selection, we won't broaden this corresponding type condition.
     */
    val possibleTypes: Set<Schema.ObjectType>

    /**
     * The subselections on the value this selection selects.
     *
     * This is empty when [isLeaf] is true. It may also be empty when this selection selects a
     * composite type but no fields within that value.
     *
     * Because of GraphQL's validation rules for type conditions, the relationship between the
     * field owner of a subselection and [key]'s field result type is complicated. Nested type
     * conditions need only overlap pairwise:
     * ```
     *    I1 = {A, B}
     *    I2 = {B, C}
     *    I3 = {C, D}
     * ```
     * Under an I1 field, ... on I2 { ... on I3 { x } } is valid, but I3 does not overlap I1.
     * So while we might someday need an invariant for the field owners of subselections, right now
     * we decline to state one.
     *
     * One might imagine an invariant that looks at the _actual_ object types that [key] could be applied
     * to (according to the local [possibleTypes]) and constrain those sub-[possibleTypes] to
     * contain the union of just those.  However, we don't think we need that invariant so currently
     * it is **not** an invariant of this data type.
     *
     * Compared to GraphQL selections, descending into this property _always_ descends a level
     * into the GraphQL object-value tree.  (In GraphQL selections, subselections can be spreads,
     * which do **not** descend into the object-value tree.)
     */
    val subselections: SelectionForest

    /**
     * Whether this selection's field has a simple base output type.
     *
     * The field's type expression may wrap that base type in lists or non-null constraints.
     */
    val isLeaf: Boolean
        get() = key.field.typeExpr.baseType is Schema.SimpleType

    companion object {
        /**
         * Constructs a selection that satisfies the invariant documented on [Selection].
         */
        fun of(
            key: Value.Key,
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): Selection {
            validateSelection(key, possibleTypes, subselections)
            return when (key) {
                is Value.ObjectKey ->
                    ObjectSelectionImpl(
                        key = key,
                        possibleTypes = possibleTypes,
                        subselections = subselections,
                    )
                else ->
                    SelectionImpl(
                        key = key,
                        possibleTypes = possibleTypes,
                        subselections = subselections,
                    )
            }
        }

        /** Constructs the precise selection category for a concrete-object field key. */
        fun of(
            key: Value.ObjectKey,
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): ObjectSelection = ObjectSelection.of(key, possibleTypes, subselections)
    }
}

/** A selection whose field coordinate belongs to a concrete object type. */
sealed interface ObjectSelection : Selection {
    override val key: Value.ObjectKey

    companion object {
        fun of(
            key: Value.ObjectKey,
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): ObjectSelection {
            validateSelection(key, possibleTypes, subselections)
            return ObjectSelectionImpl(key, possibleTypes, subselections)
        }
    }
}

private fun validateSelection(
    key: Value.Key,
    possibleTypes: Set<Schema.ObjectType>,
    subselections: SelectionForest,
) {
    val fieldOwner = key.field.containingType
    require(possibleTypes.all { it in fieldOwner.possibleTypes }) {
        "Selection possible types must be contained by ${fieldOwner.typeName}"
    }
    require(
        key.field.typeExpr.baseType is Schema.CompositeType || subselections.isEmpty(),
    ) {
        "Leaf selection ${fieldOwner.typeName}.${key.field.fieldName} has subselections"
    }
}

private class SelectionImpl(
    override val key: Value.Key,
    override val possibleTypes: Set<Schema.ObjectType>,
    override val subselections: SelectionForest,
) : Selection

private class ObjectSelectionImpl(
    override val key: Value.ObjectKey,
    override val possibleTypes: Set<Schema.ObjectType>,
    override val subselections: SelectionForest,
) : ObjectSelection

private abstract class AbstractSelectionForest(
    val occurrences: List<Selection>,
) : SelectionForest {
    override val size: Int
        get() = occurrences.size

    override fun isEmpty(): Boolean = occurrences.isEmpty()

    override fun all(predicate: (Selection) -> Boolean): Boolean = occurrences.all(predicate)

    override fun filter(predicate: (Selection) -> Boolean): SelectionForest =
        SelectionForestImpl(occurrences.filter(predicate))

    override fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest =
        occurrences.flatMapToSelectionForest(transform)

    override fun forEach(action: (Selection) -> Unit) {
        occurrences.forEach(action)
    }

    override fun single(): Selection = occurrences.single()

    override fun plus(other: SelectionForest): SelectionForest =
        SelectionForestImpl(occurrences + other.occurrences())
}

private class SelectionForestImpl(
    occurrences: List<Selection>,
) : AbstractSelectionForest(occurrences)

private class ObjectSelectionForestImpl(
    override val type: Schema.ObjectType,
    private val selectionsByKey: Map<Value.ObjectKey, ObjectSelection>,
    occurrences: List<ObjectSelection> = selectionsByKey.values.toList(),
) : AbstractSelectionForest(occurrences),
    ObjectSelectionForest {
    override fun filter(predicate: (Selection) -> Boolean): ObjectSelectionForest =
        ObjectSelectionForestImpl(
            type,
            selectionsByKey.filterValues(predicate),
        )

    override fun keys(): Set<Value.ObjectKey> = selectionsByKey.keys

    override fun byKey(): Map<Value.ObjectKey, ObjectSelection> = selectionsByKey

    override fun groundKeys(): Set<Value.GroundKey> = byGroundKey().keys

    override fun byGroundKey(): Map<Value.GroundKey, ObjectSelection> =
        selectionsByKey.mapKeys { (key, _) ->
            key as? Value.GroundKey
                ?: error("Object selection forest contains open key: $key")
        }

    override fun get(key: Value.ObjectKey): ObjectSelection = selectionsByKey.getValue(key)
}

private fun SelectionForest.occurrences(): List<Selection> =
    (this as AbstractSelectionForest).occurrences
