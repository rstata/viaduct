package model

import viaduct.graphql.schema.ViaductSchema

/**
 * One schema-checked output-field argument tuple.
 *
 * A tuple may be ground, may recursively contain [Variable] expressions, or may be a
 * resolver-registry [Template]. Equality is structural over the represented argument expressions,
 * including each variable's defining field, name, occurrence stamp, and recursive expression
 * position. Equality and hashing never inspect a variable's eventual binding.
 *
 * ### Invariant: arguments-schema-canonicality
 *
 * Every schema definition carried by an argument tuple is the canonical definition from the
 * [Assumptions.schema] under which that tuple is interpreted.
 */
sealed interface Arguments {
    /** A fully grounded argument outcome. */
    sealed interface Ground : Arguments

    /**
     * The successfully resolved values supplied for one output field's complete argument
     * definition.
     *
     * This tuple is ground and inspectable. Equality is structural over its field values.
     */
    sealed interface Resolved : Ground {
        val fieldValues: EngineInputObjectData

        companion object {
            /**
             * ### Invariant: resolved-arguments-factory-schema-conformance
             *
             * Every result recursively conforms to [field]'s argument definition. Declared
             * defaults are materialized for arguments absent from [fields].
             */
            fun of(
                field: ViaductSchema.Field,
                fields: Map<String, Any?>,
            ): Resolved {
                val arguments = Arguments.of(field, fields)
                require(arguments is Resolved) {
                    "Resolved arguments cannot contain variables or errors"
                }
                return arguments
            }
        }
    }

    /** The whole argument tuple is erroneous and has no individual argument values. */
    data object Error : Ground

    /**
     * An argument tuple in a resolver-registry template.
     *
     * Every recursively contained variable is a [Variable.isTemplate]. Stamping replaces every
     * contained variable with one carrying the same [Stamp.Occurrence].
     */
    sealed interface Template : Arguments {
        /**
         * Returns this argument template stamped at [selectionStamp] and checked against
         * [expectedField].
         */
        fun stamp(
            expectedField: ViaductSchema.Field,
            selectionStamp: Stamp.Occurrence,
        ): Arguments

        companion object {
            /**
             * Wraps [arguments] as a registry argument template checked against [expectedField].
             *
             * [arguments] may contain only variable templates, never stamped variables.
             */
            fun of(
                expectedField: ViaductSchema.Field,
                arguments: Arguments,
            ): Template = argumentTemplateOf(expectedField, arguments)
        }
    }

    /**
     * Identifier of an execution variable used in argument expressions.
     *
     * Registry variables are templates. During resolution, templates are stamped as they enter
     * occurrence-specific structures so separate resolver occurrences have distinct variables.
     */
    sealed interface Variable {
        val field: ViaductSchema.ObjectField
        val variableName: String

        /** Whether this is the registry template rather than an occurrence-specific variable. */
        val isTemplate: Boolean

        /** The occurrence stamp, or null for a registry template. */
        val stamp: Stamp.Occurrence?

        val isStamped: Boolean
            get() = stamp != null

        /** Returns this variable template stamped at [path]. */
        fun stamp(path: List<PathComponent>): Variable {
            require(isTemplate) { "Only variable templates can be stamped" }
            return OccurrenceVariableImpl(
                variableName,
                field,
                Stamp.Occurrence.of(path),
            )
        }

        /** Returns this variable template at one variable-bearing source selection. */
        fun stamp(stamp: Stamp.Occurrence): Variable {
            require(isTemplate) { "Only variable templates can be stamped" }
            return OccurrenceVariableImpl(variableName, field, stamp)
        }

        companion object {
            /**
             * Returns the template named [variableName] defined by [field]. Equal arguments yield
             * equal templates.
             */
            fun of(
                field: ViaductSchema.ObjectField,
                variableName: String,
            ): Variable = TemplateVariableImpl(variableName, field)
        }
    }

    companion object {
        /**
         * Constructs the schema-checked argument tuple for [field].
         *
         * Declared defaults are included. A variable-free result is either [Resolved] or [Error]
         * when any supplied expression recursively contains [ArgumentResolutionError].
         */
        fun of(
            field: ViaductSchema.Field,
            fields: Map<String, Any?>,
        ): Arguments = argumentsOf(field, fields)
    }
}

/**
 * One erroneous argument expression.
 *
 * Recursive argument construction collapses any occurrence of this sentinel to [Arguments.Error].
 * It does not belong to [EngineInputData].
 */
data object ArgumentResolutionError

private data class ResolvedArgumentsImpl(
    override val fieldValues: EngineInputObjectData,
) : Arguments.Resolved

private val emptyResolvedArguments = ResolvedArgumentsImpl(emptyMap())

private data class TemplateVariableImpl(
    override val variableName: String,
    override val field: ViaductSchema.ObjectField,
) : Arguments.Variable {
    override val isTemplate: Boolean
        get() = true

    override val stamp: Stamp.Occurrence?
        get() = null

    override fun toString(): String =
        "Variable.Template(" +
            "name=$variableName, " +
            "field=${field.containingDef.name}/${field.name}" +
            ")"
}

private data class OccurrenceVariableImpl(
    override val variableName: String,
    override val field: ViaductSchema.ObjectField,
    override val stamp: Stamp.Occurrence,
) : Arguments.Variable {
    override val isTemplate: Boolean
        get() = false

    override fun toString(): String =
        "Variable.Occurrence(" +
            "name=$variableName, " +
            "field=${field.containingDef.name}/${field.name}, " +
            "path=${stamp.resolverPath.renderVariablePath()}" +
            ")"
}

private fun List<PathComponent>.renderVariablePath(): String =
    joinToString(prefix = "[", postfix = "]") { component ->
        when (component) {
            is ObjectEngineResult.ObjectKey ->
                "${component.field.containingDef.name}/${component.field.name}" +
                    when (val arguments = component.arguments) {
                        Arguments.Error -> "(error)"
                        is Arguments.Resolved ->
                            arguments.fieldValues
                                .takeIf { fields -> fields.isNotEmpty() }
                                ?.let { fields -> "($fields)" }
                                .orEmpty()
                        else -> "(symbolic)"
                    }
            is ListEngineResult.Index -> "index=${component.index}"
        }
    }

internal fun argumentsOfGround(
    fields: EngineInputObjectData,
): Arguments.Resolved =
    if (fields.isEmpty()) {
        emptyResolvedArguments
    } else {
        ResolvedArgumentsImpl(fields)
    }
