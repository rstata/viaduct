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
            /**
             * Constructs the node reference containing [id] at [idField].
             *
             * ### Invariant: node-reference-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             *
             * [idField] must be the canonical argumentless `id: ID` field of a concrete type that
             * nominally implements the canonical `Node` interface.
             *
             * @throws IllegalArgumentException when [idField] is not a Node type's `id` field
             */
            context(world: Assumptions)
            fun nodeRef(
                idField: Schema.OutputField,
                id: Value.ID,
            ): Object {
                val type = idField.containingType
                require(type is Schema.ObjectType) {
                    "A node reference ID field must belong to a concrete object type"
                }
                require(world.schema.field(type.typeName, "id") == idField) {
                    "${type.typeName}/${idField.fieldName} is not its canonical id field"
                }
                require(idField.arguments == Schema.NoArguments) {
                    "Node reference ID field ${type.typeName}/id must take no arguments"
                }
                require(idField.typeExpr.baseType == Schema.IDType) {
                    "Node reference ID field ${type.typeName}/id must be ID-typed"
                }
                val nodeType = world.schema.type("Node")
                require(
                    nodeType is Schema.InterfaceType &&
                        world.schema.relation(nodeType, type) == Schema.TypeRelation.WIDER_THAN,
                ) {
                    "Node reference type ${type.typeName} must implement Node"
                }

                val idKey = Value.Key.of(idField, emptyMap())
                return of(
                    type = type,
                    cells = mapOf(idKey to Cell.of(id)),
                )
            }

            /**
             * ### Invariant: object-engine-result-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
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
                    require(cell.value.conformsToSchemaType(key.field.typeExpr)) {
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

private data class ObjectResultImpl(
    override val type: Schema.ObjectType,
    override val cells: Map<Value.Key, EngineResult.Cell>,
) : EngineResult.Object

private data class ListResultImpl(
    override val typeExpr: TypeExpr<Schema.OutputType>,
    private val cells: kotlin.collections.List<EngineResult.Cell>,
) : EngineResult.List,
    kotlin.collections.List<EngineResult.Cell> by cells

private fun EngineResult.Cell.union(other: EngineResult.Cell): EngineResult.Cell {
    require(check == other.check) { "Cannot union engine-result cells with unequal checks" }
    return EngineResult.Cell.of(
        value = value.union(other.value),
        check = check,
    )
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
