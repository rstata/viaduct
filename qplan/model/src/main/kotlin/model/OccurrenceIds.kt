package model

import viaduct.graphql.schema.ViaductSchema

/**
 * Opaque identity of one concrete field-resolver application.
 *
 * Equality is structural over a private diagnostic address. Runtime code may carry, compare,
 * hash, and print this value, but cannot decompose its address.
 */
sealed interface ResolverOccurrenceId {
    companion object {
        /**
         * Returns the resolver occurrence identified by its exact result-tree [path].
         *
         * This factory is used by resolver strategies whose applications already have ground keys.
         */
        fun at(path: List<PathComponent>): ResolverOccurrenceId =
            ResolverOccurrenceIdImpl(
                path = path.toList(),
            )
    }
}

/**
 * Opaque identity of one variable declaration instantiated for one resolver occurrence.
 *
 * Equality is structural over the resolver occurrence, defining field, and local variable name.
 */
sealed interface VariableInstanceId {
    /** The concrete resolver application that owns this variable instance. */
    val resolverOccurrenceId: ResolverOccurrenceId

    companion object {
        fun of(
            resolverOccurrenceId: ResolverOccurrenceId,
            resolverField: ViaductSchema.ObjectField,
            variableName: String,
        ): VariableInstanceId {
            require(variableName.isNotEmpty()) {
                "A variable instance requires a nonempty name"
            }
            return VariableInstanceIdImpl(
                resolverOccurrenceId = resolverOccurrenceId,
                resolverField = resolverField,
                variableName = variableName,
            )
        }
    }
}

private data class ResolverOccurrenceIdImpl(
    val path: List<PathComponent>,
) : ResolverOccurrenceId {
    override fun toString(): String =
        "ResolverOccurrenceId(" +
            "path=${path.renderOccurrencePath()}" +
            ")"
}

private class VariableInstanceIdImpl(
    override val resolverOccurrenceId: ResolverOccurrenceId,
    private val resolverField: ViaductSchema.ObjectField,
    private val variableName: String,
) : VariableInstanceId {
    override fun equals(other: Any?): Boolean =
        other is VariableInstanceIdImpl &&
            resolverOccurrenceId == other.resolverOccurrenceId &&
            resolverField == other.resolverField &&
            variableName == other.variableName

    override fun hashCode(): Int {
        var result = resolverOccurrenceId.hashCode()
        result = 31 * result + resolverField.hashCode()
        result = 31 * result + variableName.hashCode()
        return result
    }

    override fun toString(): String =
        "VariableInstanceId(" +
            "resolver=$resolverOccurrenceId, " +
            "variable=${resolverField.containingDef.name}/${resolverField.name}:$variableName" +
            ")"
}

private fun List<PathComponent>.renderOccurrencePath(): String =
    joinToString(prefix = "[", postfix = "]") { component ->
        when (component) {
            is ObjectEngineResult.ObjectKey ->
                "${component.field.containingDef.name}/${component.field.name}"
            is ListEngineResult.Index -> "index=${component.index}"
        }
    }
