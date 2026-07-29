package model

/**
 * A finite, well-founded GraphQL value-type expression.
 *
 * ### Invariant: schema-type-expression-well-foundedness
 *
 * Nullability belongs independently to every named or list layer. [isNullable] describes the
 * outermost layer, [baseType] is the named type beneath every list wrapper, and
 * [isBaseTypeNullable] is that named layer's nullability. Wherever an expression is embedded in a
 * schema definition, [baseType] is that schema's canonical type definition.
 *
 * Type expressions use structural equality over their complete wrapper shape, nullability, and
 * canonical base type.
 */
sealed interface TypeExpr<out T : Schema.Type> {
    val baseType: T
    val isNullable: Boolean
    val isBaseTypeNullable: Boolean

    sealed interface Named<out T : Schema.Type> : TypeExpr<T> {
        override val isBaseTypeNullable: Boolean
            get() = isNullable

        companion object {
            fun <T : Schema.Type> of(
                baseType: T,
                isNullable: Boolean = true,
            ): Named<T> = NamedTypeExprImpl(baseType, isNullable)
        }
    }

    sealed interface List<out T : Schema.Type> : TypeExpr<T> {
        val elementType: TypeExpr<T>

        override val baseType: T
            get() = elementType.baseType

        override val isBaseTypeNullable: Boolean
            get() = elementType.isBaseTypeNullable

        companion object {
            fun <T : Schema.Type> of(
                elementType: TypeExpr<T>,
                isNullable: Boolean = true,
            ): List<T> = ListTypeExprImpl(elementType, isNullable)
        }
    }
}

private data class NamedTypeExprImpl<out T : Schema.Type>(
    override val baseType: T,
    override val isNullable: Boolean,
) : TypeExpr.Named<T>

private data class ListTypeExprImpl<out T : Schema.Type>(
    override val elementType: TypeExpr<T>,
    override val isNullable: Boolean,
) : TypeExpr.List<T>

/**
 * Whether this expected outer type expression can contain a value carrying [inner].
 *
 * A nullable outer layer accepts nullable or non-null inner layers; a non-null outer layer accepts
 * only a non-null inner layer. List wrappers recurse. Input named types must match exactly, while an
 * output composite may contain any canonical concrete object type in its possible-type set.
 */
context(world: Assumptions)
fun TypeExpr<Schema.Type>.canContain(
    inner: TypeExpr<Schema.Type>,
): Boolean {
    if (!isNullable && inner.isNullable) return false
    return when (this) {
        is TypeExpr.List ->
            inner is TypeExpr.List && elementType.canContain(inner.elementType)
        is TypeExpr.Named -> {
            if (inner !is TypeExpr.Named) return false
            val outerType = baseType
            val innerType = inner.baseType
            when {
                outerType is Schema.InputType || innerType is Schema.InputType ->
                    outerType == innerType
                outerType is Schema.CompositeType && innerType is Schema.CompositeType ->
                    world.schema.relation(outerType, innerType) in
                        setOf(
                            Schema.TypeRelation.SAME,
                            Schema.TypeRelation.WIDER_THAN,
                        )
                else -> outerType == innerType
            }
        }
    }
}

internal fun TypeExpr<Schema.Type>.canContainPure(
    inner: TypeExpr<Schema.Type>,
): Boolean {
    if (!isNullable && inner.isNullable) return false
    return when (this) {
        is TypeExpr.List ->
            inner is TypeExpr.List && elementType.canContainPure(inner.elementType)
        is TypeExpr.Named -> {
            if (inner !is TypeExpr.Named) return false
            val outerType = baseType
            val innerType = inner.baseType
            when {
                outerType is Schema.InputType || innerType is Schema.InputType ->
                    outerType == innerType
                outerType is Schema.CompositeType && innerType is Schema.ObjectType ->
                    innerType in outerType.possibleTypes
                else -> outerType == innerType
            }
        }
    }
}
