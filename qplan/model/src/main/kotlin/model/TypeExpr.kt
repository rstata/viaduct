package model

/**
 * A finite, well-founded GraphQL value-type expression.
 *
 * ### Invariant: schema-type-expression-well-foundedness
 *
 * Nullability belongs independently to every named or list layer. [isNullable] describes the
 * outermost layer, and [baseTypeDef] is the named type beneath every list wrapper. Wherever an
 * expression is embedded in a schema definition, [baseTypeDef] is that schema's canonical type
 * definition.
 *
 * Type expressions use structural equality over their complete wrapper shape, nullability, and
 * canonical base type.
 */
sealed interface TypeExpr<out T : Schema.TypeDef> {
    val baseTypeDef: T
    val isNullable: Boolean

    val isList: Boolean
        get() = this is List

    /** Returns this expression with one outer list wrapper removed, or null when it is not a list. */
    fun unwrapList(): TypeExpr<T>? = (this as? List)?.elementType

    sealed interface Named<out T : Schema.TypeDef> : TypeExpr<T> {
        companion object {
            fun <T : Schema.TypeDef> of(
                baseType: T,
                isNullable: Boolean = true,
            ): Named<T> = NamedTypeExprImpl(baseType, isNullable)
        }
    }

    sealed interface List<out T : Schema.TypeDef> : TypeExpr<T> {
        val elementType: TypeExpr<T>

        override val baseTypeDef: T
            get() = elementType.baseTypeDef

        companion object {
            fun <T : Schema.TypeDef> of(
                elementType: TypeExpr<T>,
                isNullable: Boolean = true,
            ): List<T> = ListTypeExprImpl(elementType, isNullable)
        }
    }
}

private data class NamedTypeExprImpl<out T : Schema.TypeDef>(
    override val baseTypeDef: T,
    override val isNullable: Boolean,
) : TypeExpr.Named<T>

private data class ListTypeExprImpl<out T : Schema.TypeDef>(
    override val elementType: TypeExpr<T>,
    override val isNullable: Boolean,
) : TypeExpr.List<T>

internal fun TypeExpr<Schema.TypeDef>.canContainPure(
    inner: TypeExpr<Schema.TypeDef>,
): Boolean {
    if (!isNullable && inner.isNullable) return false
    val outerElement = unwrapList()
    val innerElement = inner.unwrapList()
    if (outerElement != null || innerElement != null) {
        return outerElement != null &&
            innerElement != null &&
            outerElement.canContainPure(innerElement)
    }

    val outerType = baseTypeDef
    val innerType = inner.baseTypeDef
    return when {
        outerType is Schema.InputTypeDef || innerType is Schema.InputTypeDef ->
            outerType == innerType
        outerType is Schema.CompositeTypeDef && innerType is Schema.Object ->
            innerType in outerType.possibleObjectTypes
        else -> outerType == innerType
    }
}
