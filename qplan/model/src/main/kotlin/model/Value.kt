package model

import model.invariants.conformsToOutputSchemaType

/**
 * A ground GraphQL semantic value.
 *
 * Implementations are mathematical values: equality is value equality over the properties exposed
 * by the interface. No implementation contains a [Variable]. Variables are nested here for
 * namespacing but inhabit [OpenValue] rather than [Value]. Template-variable equality is structural
 * over its name and defining field; stamped-variable equality additionally distinguishes its
 * occurrence.
 *
 * ### Invariant: schema-value-canonicality
 *
 * Every schema definition carried by a value is the canonical definition from the
 * [Assumptions.schema] under which that value is interpreted.
 */
sealed interface Value {
    sealed interface Typed : Value {
        val type: Schema.Type
    }

    /**
     * The values supplied for one output field's complete argument definition.
     *
     * This tuple is ground and inspectable. [OpenArguments] represents a tuple that may contain
     * variables. Equality is structural over its field values. Occurrence identity belongs to
     * [ObjectEngineResult.Key.stamp], not the grounded argument value.
     */
    sealed interface Arguments : OpenArguments.Ground {
        val fieldValues: EngineInputObjectData

        companion object {
            /**
             * ### Invariant: arguments-value-factory-schema-conformance
             *
             * Every result recursively conforms to [field]'s argument definition.
             * Declared defaults are materialized for arguments absent from [fields].
             */
            fun of(
                field: Schema.OutputField,
                fields: Map<kotlin.String, Any?>,
            ): Arguments {
                val arguments = OpenArguments.of(field, fields)
                require(arguments is Arguments) {
                    "Ground arguments cannot contain variables or errors"
                }
                return arguments
            }
        }
    }

    /**
     * A possibly partial object output.
     *
     * ### Invariant: object-value-owner
     *
     * `fieldValues.containingType == type`. Object values are partial; resolver behavior is
     * responsible for supplying passive fields, including canonical `__typename`.
     */
    sealed interface Object : Value, Typed {
        override val type: Schema.ObjectType
        val fieldValues: ObjectFields

        companion object {
            /**
             * Constructs a passive object whose keys are canonical argumentless field names.
             *
             * ### Invariant: passive-object-fields
             *
             * Every key names an argumentless object field owned by [type].
             *
             * ### Invariant: object-value-factory-schema-conformance
             *
             * Every supplied value conforms to the corresponding schema field.
             */
            fun of(
                type: Schema.ObjectType,
                fields: Map<kotlin.String, EngineOutputData?> = emptyMap(),
            ): Object =
                of(
                    type = type,
                    fields =
                        fields.map { (name, value) ->
                            val field = type.fields[name]
                            require(field is Schema.ObjectField) {
                                "${type.typeName} has no canonical object field named $name"
                            }
                            require(field.arguments.fields.isEmpty()) {
                                "Passive object field ${type.typeName}/$name must be argumentless"
                            }
                            FieldValue.of(name, field, value)
                        },
                )

            /**
             * Constructs an object from entries whose producers retain each key's schema field
             * through validation. The resulting object stores only string keys and values.
             */
            fun of(
                type: Schema.ObjectType,
                fields: Iterable<FieldValue>,
            ): Object {
                val entries = fields.toList()
                entries.forEach { entry ->
                    require(entry.field.containingType == type) {
                        "${type.typeName} cannot contain output field " +
                            "${entry.field.containingType.typeName}/${entry.field.fieldName}"
                    }
                    require(entry.value.conformsToOutputSchemaType(entry.field.typeExpr)) {
                        "${type.typeName}/${entry.field.fieldName} value does not conform to " +
                            entry.field.typeExpr
                    }
                }
                val values = entries.associate { entry -> entry.key to entry.value }
                require(values.size == entries.size) {
                    "Object ${type.typeName} contains duplicate string field keys"
                }
                return objectValueOfValidatedFields(type, values)
            }
        }

        /** One construction-time object entry whose schema field is forgotten after validation. */
        sealed interface FieldValue {
            val key: kotlin.String
            val field: Schema.ObjectField
            val value: EngineOutputData?

            companion object {
                fun of(
                    key: kotlin.String,
                    field: Schema.ObjectField,
                    value: EngineOutputData?,
                ): FieldValue = ObjectFieldValueImpl(key, field, value)
            }
        }
    }

    /**
     * A finite map from externally visible string keys to values.
     *
     * Passive and resolver-produced objects use canonical argumentless field names. Resolver
     * inputs materialized from object fragments use GraphQL response keys.
     *
     * ### Invariant: object-field-values-owner
     *
     * [containingType] is the concrete object type whose fields these values inhabit.
     */
    sealed interface ObjectFields : Map<kotlin.String, EngineOutputData?> {
        val containingType: Schema.ObjectType

        /** @throws MissingFieldException when [key] is not present */
        override operator fun get(key: kotlin.String): EngineOutputData?

        /** @throws MissingFieldException when [key] is not present */
        fun getValue(key: kotlin.String): EngineOutputData?
    }

    /**
     * Identifier of an execution variable.
     *
     * Each field with a resolver can define variables from one of that field's arguments or from a
     * value resolved along a path in its object fragment. The registry contains variable templates
     * associated with the resolver. During resolution, those templates are stamped as they enter
     * occurrence-specific resolution structures. A resolver can occur multiple times in one
     * resolution; stamping distinguishes the variable instances belonging to those occurrences.
     */
    sealed interface Variable : OpenValue {
        val field: Schema.ObjectField
        val variableName: kotlin.String

        /** Whether this is the registry template rather than an occurrence-specific variable. */
        val isTemplate: kotlin.Boolean

        /** The occurrence stamp, or null for a registry template. */
        val stamp: Stamp.Occurrence?

        val isStamped: kotlin.Boolean
            get() = stamp != null

        /**
         * Returns this variable template at [path].
         *
         * Equal templates stamped with equal paths yield equal results. A result is unequal to
         * every other variable except an equal template stamped with the same path.
         *
         * ### Invariant: stamped-variable-value-factory-schema-conformance
         *
         * Every [PathComponent] in [path] belongs to the template's reasoning world, and every
         * result satisfies `result.conformsToSchema()` in that world.
         */
        fun stamp(path: kotlin.collections.List<PathComponent>): Variable {
            require(isTemplate) { "Only variable templates can be stamped" }
            return OccurrenceVariableValueImpl(
                variableName,
                field,
                Stamp.Occurrence.of(path),
            )
        }

        /** Returns this variable template at one variable-bearing source selection. */
        fun stamp(stamp: Stamp.Occurrence): Variable {
            require(isTemplate) { "Only variable templates can be stamped" }
            return OccurrenceVariableValueImpl(variableName, field, stamp)
        }

        companion object {
            /**
             * Returns the template named [variableName] defined by [field]. Equal arguments yield
             * equal templates.
             *
             * ### Invariant: variable-value-factory-schema-conformance
             *
             * [field] is the canonical field of the resolver defining this variable, and every
             * result satisfies `result.conformsToSchema()` in that reasoning world.
             */
            fun of(
                field: Schema.ObjectField,
                variableName: kotlin.String,
            ): Variable = TemplateVariableValueImpl(variableName, field)
        }
    }

    /**
     * An optional, fully coerced semantic default.
     *
     * ### Invariant: schema-default-value-conformance
     *
     * [Absent] means that no default is declared. [Present.value] may be null, denoting an explicit
     * GraphQL null; absence and explicit null are distinct. When attached to a field or argument,
     * [Present] is valid for its declaring [TypeExpr]. It contains no [Variable].
     *
     * Default values use structural equality.
     */
    sealed interface Default {
        data object Absent : Default

        sealed interface Present : Default {
            val value: EngineInputData?
        }

        companion object {
            fun of(value: EngineInputData?): Present = PresentDefaultValueImpl(value)
        }
    }
}

private data class ObjectValueImpl(
    override val type: Schema.ObjectType,
    override val fieldValues: Value.ObjectFields,
) : Value.Object

private data class ObjectFieldValueImpl(
    override val key: String,
    override val field: Schema.ObjectField,
    override val value: EngineOutputData?,
) : Value.Object.FieldValue

private data class ArgumentsValueImpl(
    override val fieldValues: EngineInputObjectData,
) : Value.Arguments

private data class TemplateVariableValueImpl(
    override val variableName: String,
    override val field: Schema.ObjectField,
) : Value.Variable {
    override val isTemplate: Boolean
        get() = true

    override val stamp: Stamp.Occurrence?
        get() = null

    override fun toString(): String =
        "Variable.Template(" +
            "name=$variableName, " +
            "field=${field.containingType.typeName}/${field.fieldName}" +
            ")"
}

private data class OccurrenceVariableValueImpl(
    override val variableName: String,
    override val field: Schema.ObjectField,
    override val stamp: Stamp.Occurrence,
) : Value.Variable {
    override val isTemplate: Boolean
        get() = false

    override fun toString(): String =
        "Variable.Occurrence(" +
            "name=$variableName, " +
            "field=${field.containingType.typeName}/${field.fieldName}, " +
            "path=${stamp.resolverPath.renderVariablePath()}, " +
            "lineage=${stamp.occurrenceLineage.size}" +
            ")"
}

private fun kotlin.collections.List<PathComponent>.renderVariablePath(): String =
    joinToString(prefix = "[", postfix = "]") { component ->
        when (component) {
            is ObjectEngineResult.GroundKey ->
                "${component.field.containingType.typeName}/${component.field.fieldName}" +
                    when (val arguments = component.arguments) {
                        OpenArguments.Ground.Error -> "(error)"
                        is Value.Arguments ->
                            arguments.fieldValues
                                .takeIf { fields -> fields.isNotEmpty() }
                                ?.let { fields -> "($fields)" }
                                .orEmpty()
                    }
            is ListEngineResult.Index -> "index=${component.index}"
        }
    }

private data class PresentDefaultValueImpl(
    override val value: EngineInputData?,
) : Value.Default.Present

private class ObjectFieldValuesImpl(
    override val containingType: Schema.ObjectType,
    private val backingMap: Map<String, EngineOutputData?>,
) : Value.ObjectFields,
    Map<String, EngineOutputData?> by backingMap {
    override operator fun get(key: String): EngineOutputData? = getValue(key)

    override fun getValue(key: String): EngineOutputData? {
        if (!backingMap.containsKey(key)) {
            throw MissingFieldException(containingType.typeName, key)
        }
        return backingMap[key]
    }

    override fun equals(other: Any?): Boolean =
        other is Value.ObjectFields &&
            containingType == other.containingType &&
            entries == other.entries

    override fun hashCode(): Int = 31 * containingType.hashCode() + backingMap.hashCode()

    override fun toString(): String = backingMap.toString()
}

private fun objectValueOfValidatedFields(
    type: Schema.ObjectType,
    fields: Map<String, EngineOutputData?>,
): Value.Object =
    ObjectValueImpl(
        type = type,
        fieldValues = ObjectFieldValuesImpl(type, fields.toMap()),
    )

internal fun argumentsOfGround(
    fields: EngineInputObjectData,
): Value.Arguments =
    ArgumentsValueImpl(
        fieldValues = fields,
    )
