package model

/**
 * Whether this expected outer type expression can contain a value carrying [inner].
 *
 * A nullable outer layer accepts nullable or non-null inner layers; a non-null outer layer accepts
 * only a non-null inner layer. List wrappers recurse. Input named types must match exactly, while an
 * output composite may contain any canonical concrete object type in its possible-type set.
 */
context(world: Assumptions)
fun Schema.TypeExpr<Schema.Type>.canContain(
    inner: Schema.TypeExpr<Schema.Type>,
): Boolean {
    if (!isNullable && inner.isNullable) return false
    return when (this) {
        is Schema.TypeExpr.List ->
            inner is Schema.TypeExpr.List && elementType.canContain(inner.elementType)
        is Schema.TypeExpr.Named -> {
            if (inner !is Schema.TypeExpr.Named) return false
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
