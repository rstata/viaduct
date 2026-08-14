package model

import model.invariants.conformsToSchemaType

/**
 * An opaque, schema-checked input expression that may contain variables.
 *
 * Ground [Value.Input] values are also open values. Other implementations expose no structural
 * operations; they can only be embedded in another open expression or instantiated under one
 * [Assumptions].
 */
sealed interface OpenValue {
    companion object {
        /**
         * Constructs one schema-checked open expression.
         *
         * [value] may be a ground [Value.Input], [Value.Variable], another [OpenValue], a Kotlin
         * scalar, a list, or a string-keyed map for an input object.
         */
        fun of(
            typeExpr: TypeExpr<Schema.InputType>,
            value: Any?,
        ): OpenValue? = coerceOpenInputValue(typeExpr, value)
    }
}

/**
 * An opaque argument tuple that may contain variables.
 *
 * Ground [Value.Arguments] values are also open arguments and retain their ordinary inspectable
 * field-value map.
 */
sealed interface OpenArguments {
    val type: Schema.FieldArguments

    /**
     * An argument tuple in a resolver-registry template.
     *
     * Every variable recursively contained by a template is a [Value.Variable.Template].
     * Stamping creates one occurrence-specific tuple and stamps every contained variable with the
     * same [SelectionStamp].
     */
    sealed interface Template : OpenArguments {
        /**
         * Returns this argument template stamped at [selectionStamp].
         *
         * Equal templates stamped with equal selection stamps yield equal results. The resulting
         * tuple contains only ground values and selection-stamped variables.
         */
        fun stamp(selectionStamp: SelectionStamp): Stamped

        companion object {
            /**
             * Wraps [arguments] as a registry argument template.
             *
             * [arguments] may contain only variable templates, never stamped variables.
             */
            fun of(arguments: OpenArguments): Template {
                if (arguments is Template) return arguments
                require(arguments !is Stamped) {
                    "A stamped argument tuple cannot become a registry template"
                }
                require(arguments.usedVariables().all { it is Value.Variable.Template }) {
                    "A registry argument template cannot contain stamped variables"
                }
                return OpenArgumentsTemplateImpl(
                    type = arguments.type,
                    fieldValues = arguments.fieldExpressions(),
                )
            }
        }
    }

    /**
     * An occurrence-specific argument tuple.
     *
     * [selectionStamp] participates in structural equality, and every recursively contained
     * variable carries that same stamp. Grounding preserves the selection stamp through
     * [Value.GroundKey.Stamped].
     */
    sealed interface Stamped : OpenArguments {
        val selectionStamp: SelectionStamp
    }

    companion object {
        /**
         * Constructs the schema-checked open argument tuple for [field].
         *
         * Declared defaults are included. When no supplied expression contains a variable, the
         * result is a ground [Value.Arguments].
         */
        fun of(
            field: Schema.OutputField,
            fields: Map<String, Any?>,
        ): OpenArguments {
            val values = coerceOpenInputLikeFields(field.arguments, fields)
            return if (values.values.all { it == null || it is Value.Input }) {
                argumentsOfGround(
                    field.arguments,
                    values.mapValues { (_, value) -> value as Value.Input? },
                )
            } else {
                OpenArgumentsImpl(field.arguments, values)
            }
        }
    }
}

/**
 * Returns this occurrence-specific tuple under [selectionStamp], replacing the stamp on every
 * recursively contained selection-stamped variable.
 */
fun OpenArguments.Stamped.restamp(
    selectionStamp: SelectionStamp,
): OpenArguments.Stamped =
    stampedArgumentsOf(
        type = type,
        fields =
            fieldExpressions().mapValues { (_, value) ->
                value.restampVariables(selectionStamp)
            },
        selectionStamp = selectionStamp,
    )

private data class OpenListValueImpl(
    val elementType: TypeExpr<Schema.InputType>,
    val values: List<OpenValue?>,
) : OpenValue

private data class OpenInputObjectValueImpl(
    val objectType: Schema.InputObjectType,
    val fieldValues: Map<String, OpenValue?>,
) : OpenValue

private data class OpenArgumentsImpl(
    override val type: Schema.FieldArguments,
    val fieldValues: Map<String, OpenValue?>,
) : OpenArguments

private data class OpenArgumentsTemplateImpl(
    override val type: Schema.FieldArguments,
    val fieldValues: Map<String, OpenValue?>,
) : OpenArguments.Template {
    override fun stamp(selectionStamp: SelectionStamp): OpenArguments.Stamped =
        stampedArgumentsOf(
            type = type,
            fields =
                fieldValues.mapValues { (_, value) ->
                    value.stampVariables(selectionStamp)
                },
            selectionStamp = selectionStamp,
        )
}

private data class OpenArgumentsStampedImpl(
    override val type: Schema.FieldArguments,
    val fieldValues: Map<String, OpenValue?>,
    override val selectionStamp: SelectionStamp,
) : OpenArguments.Stamped

private fun coerceOpenInputLikeFields(
    type: Schema.InputObjectLike,
    fields: Map<String, Any?>,
): Map<String, OpenValue?> {
    val suppliedFields =
        fields.mapValues { (fieldName, value) ->
            val field = type.fields[fieldName] ?: throw ClassCastException()
            coerceOpenInputValue(field.typeExpr, value)
        }

    return type.fields.values
        .mapNotNull { field ->
            val defaultValue = field.defaultValue
            if (defaultValue is Value.Default.Present) {
                field.name to defaultValue.value
            } else {
                null
            }
        }.toMap() + suppliedFields
}

private fun coerceOpenInputValue(
    typeExpr: TypeExpr<Schema.InputType>,
    value: Any?,
): OpenValue? {
    if (value == null) {
        if (!typeExpr.isNullable) throw ClassCastException()
        return null
    }
    if (value == Value.Error) return Value.Error
    if (value is Value.Variable) return value
    if (value is Value.Input) {
        if (!value.conformsToSchemaType(typeExpr)) throw ClassCastException()
        return value
    }
    if (value is OpenValue) {
        if (!value.conformsToSchemaType(typeExpr)) throw ClassCastException()
        return value
    }

    return when (typeExpr) {
        is TypeExpr.Named -> coerceOpenNamedInputValue(typeExpr.baseType, value)
        is TypeExpr.List -> {
            val elements =
                when (value) {
                    is List<*> -> value
                    else -> throw ClassCastException()
                }
            val coerced = elements.map { coerceOpenInputValue(typeExpr.elementType, it) }
            if (coerced.all { it == null || it is Value.Input }) {
                Value.InputList.of(
                    typeExpr = typeExpr.elementType,
                    values = coerced.map { it as Value.Input? },
                )
            } else {
                OpenListValueImpl(typeExpr.elementType, coerced)
            }
        }
    }
}

private fun coerceOpenNamedInputValue(
    type: Schema.InputType,
    value: Any,
): OpenValue =
    when (type) {
        Schema.IntType ->
            when (value) {
                is Int -> Value.Int.of(value)
                else -> throw ClassCastException()
            }
        Schema.FloatType ->
            when (value) {
                is Double -> Value.Float.of(value)
                else -> throw ClassCastException()
            }
        Schema.StringType ->
            when (value) {
                is String -> Value.String.of(value)
                else -> throw ClassCastException()
            }
        Schema.BooleanType ->
            when (value) {
                is Boolean -> Value.Boolean.of(value)
                else -> throw ClassCastException()
            }
        Schema.IDType ->
            when (value) {
                is String -> Value.ID.of(value)
                else -> throw ClassCastException()
            }
        is Schema.EnumType ->
            when (value) {
                is String -> Value.Enum.of(type, value)
                else -> throw ClassCastException()
            }
        is Schema.InputObjectType -> {
            val fields =
                when (value) {
                    is Map<*, *> ->
                        value.entries.associate { (key, fieldValue) ->
                            if (key !is String) throw ClassCastException()
                            key to fieldValue
                        }
                    else -> throw ClassCastException()
                }
            val coerced = coerceOpenInputLikeFields(type, fields)
            if (coerced.values.all { it == null || it is Value.Input }) {
                Value.InputObject.of(
                    type,
                    coerced.mapValues { (_, fieldValue) -> fieldValue as Value.Input? },
                )
            } else {
                OpenInputObjectValueImpl(type, coerced)
            }
        }
    }

private fun OpenValue.conformsToSchemaType(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean =
    when (this) {
        Value.Error, is Value.Variable -> true
        is Value.Input -> this.conformsToSchemaType(typeExpr)
        is OpenListValueImpl ->
            typeExpr is TypeExpr.List &&
                typeExpr.elementType.canContainPure(elementType)
        is OpenInputObjectValueImpl ->
            typeExpr is TypeExpr.Named && typeExpr.baseType == objectType
    }

internal fun OpenArguments.fieldExpressions(): Map<String, OpenValue?> =
    when (this) {
        is Value.Arguments -> fieldValues
        is OpenArgumentsImpl -> fieldValues
        is OpenArgumentsTemplateImpl -> fieldValues
        is OpenArgumentsStampedImpl -> fieldValues
    }

internal fun OpenArguments.stampVars(
    path: List<PathComponent>,
): OpenArguments {
    val stamped = fieldExpressions().mapValues { (_, value) -> value.stampVars(path) }
    return if (stamped.values.all { it == null || it is Value.Input }) {
        argumentsOfGround(
            type,
            stamped.mapValues { (_, value) -> value as Value.Input? },
        )
    } else {
        OpenArgumentsImpl(type, stamped)
    }
}

private fun stampedArgumentsOf(
    type: Schema.FieldArguments,
    fields: Map<String, OpenValue?>,
    selectionStamp: SelectionStamp,
): OpenArguments.Stamped =
    OpenArgumentsStampedImpl(
        type = type,
        fieldValues = fields,
        selectionStamp = selectionStamp,
    )

private fun OpenValue?.stampVars(path: List<PathComponent>): OpenValue? =
    when (this) {
        is Value.Variable.Template -> stamp(path)
        is OpenListValueImpl -> copy(values = values.map { it.stampVars(path) })
        is OpenInputObjectValueImpl ->
            copy(fieldValues = fieldValues.mapValues { (_, value) -> value.stampVars(path) })
        else -> this
    }

private fun OpenValue?.stampVariables(selectionStamp: SelectionStamp): OpenValue? =
    when (this) {
        is Value.Variable.Template -> stamp(selectionStamp)
        is OpenListValueImpl ->
            copy(values = values.map { value -> value.stampVariables(selectionStamp) })
        is OpenInputObjectValueImpl ->
            copy(
                fieldValues =
                    fieldValues.mapValues { (_, value) ->
                        value.stampVariables(selectionStamp)
                    },
            )
        else -> this
    }

private fun OpenValue?.restampVariables(selectionStamp: SelectionStamp): OpenValue? =
    when (this) {
        is Value.Variable.SelectionStamped ->
            Value.Variable
                .of(
                    field = field,
                    variableName = variableName,
                ).stamp(selectionStamp)
        is OpenListValueImpl ->
            copy(values = values.map { value -> value.restampVariables(selectionStamp) })
        is OpenInputObjectValueImpl ->
            copy(
                fieldValues =
                    fieldValues.mapValues { (_, value) ->
                        value.restampVariables(selectionStamp)
                    },
            )
        else -> this
    }

internal fun OpenArguments.substituteTemplates(
    bindings: Map<Value.Variable.Template, Value.Input?>,
): OpenArguments {
    val substituted =
        fieldExpressions().mapValues { (_, value) -> value.substituteTemplates(bindings) }
    return if (substituted.values.all { it == null || it is Value.Input }) {
        argumentsOfGround(
            type,
            substituted.mapValues { (_, value) -> value as Value.Input? },
        )
    } else {
        OpenArgumentsImpl(type, substituted)
    }
}

internal fun OpenArguments.mapVariableTemplates(
    transform: (Value.Variable.Template) -> Value.Variable.Template,
): OpenArguments {
    val mapped =
        fieldExpressions().mapValues { (_, value) -> value.mapVariableTemplates(transform) }
    return if (mapped.values.all { it == null || it is Value.Input }) {
        argumentsOfGround(
            type,
            mapped.mapValues { (_, value) -> value as Value.Input? },
        )
    } else {
        OpenArgumentsImpl(type, mapped)
    }
}

private fun OpenValue?.mapVariableTemplates(
    transform: (Value.Variable.Template) -> Value.Variable.Template,
): OpenValue? =
    when (this) {
        is Value.Variable.Template -> transform(this)
        is Value.Variable.Stamped ->
            throw IllegalArgumentException("Pre-reasoning expressions cannot contain stamped variables")
        is OpenListValueImpl ->
            copy(values = values.map { value -> value.mapVariableTemplates(transform) })
        is OpenInputObjectValueImpl ->
            copy(
                fieldValues =
                    fieldValues.mapValues { (_, value) ->
                        value.mapVariableTemplates(transform)
                    },
            )
        else -> this
    }

internal fun OpenArguments.variables(): Set<Value.Variable> =
    fieldExpressions().values.flatMapTo(linkedSetOf()) { value -> value.variables() }

/** Returns the occurrence-specific variables used anywhere in this argument tuple. */
fun OpenArguments.stampedVariables(): Set<Value.Variable.Stamped> =
    variables().filterIsInstanceTo(linkedSetOf())

/** Returns every variable expression used anywhere in this argument tuple. */
fun OpenArguments.usedVariables(): Set<Value.Variable> = variables()

/** Returns the argument names whose values recursively contain at least one variable expression. */
fun OpenArguments.variableArgumentNames(): Set<String> =
    fieldExpressions()
        .filterValues { value -> value.variables().isNotEmpty() }
        .keys

/** Returns the source-selection identities carried by variables in this tuple. */
fun OpenArguments.variableSourceSelectionStamps(): Set<SelectionStamp> =
    variables()
        .filterIsInstance<Value.Variable.SelectionStamped>()
        .mapTo(linkedSetOf()) { variable -> variable.selectionStamp }

private fun OpenValue?.variables(): Set<Value.Variable> =
    when (this) {
        is Value.Variable -> setOf(this)
        is OpenListValueImpl -> values.flatMapTo(linkedSetOf()) { value -> value.variables() }
        is OpenInputObjectValueImpl ->
            fieldValues.values.flatMapTo(linkedSetOf()) { value -> value.variables() }
        else -> emptySet()
    }

internal fun OpenArguments.variableTemplates(): Set<Value.Variable.Template> =
    variables().filterIsInstanceTo(linkedSetOf())

internal fun OpenArguments.retarget(field: Schema.OutputField): OpenArguments {
    val retargeted = OpenArguments.of(field, fieldExpressions())
    return when (this) {
        is OpenArguments.Template -> OpenArguments.Template.of(retargeted)
        is OpenArguments.Stamped ->
            stampedArgumentsOf(
                type = field.arguments,
                fields = retargeted.fieldExpressions(),
                selectionStamp = selectionStamp,
            )
        else -> retargeted
    }
}

internal fun OpenValue?.matchingVariableTypes(
    variable: Value.Variable.Template,
    typeExpr: TypeExpr<Schema.InputType>,
    hasDefault: Boolean,
): List<Pair<TypeExpr<Schema.InputType>, Boolean>> =
    when {
        this == variable -> listOf(typeExpr to hasDefault)
        this is OpenListValueImpl && typeExpr is TypeExpr.List ->
            values.flatMap { value ->
                value.matchingVariableTypes(variable, typeExpr.elementType, false)
            }
        this is OpenInputObjectValueImpl ->
            fieldValues.flatMap { (name, value) ->
                val field = objectType.fields.getValue(name)
                value.matchingVariableTypes(
                    variable,
                    field.typeExpr,
                    field.defaultValue is Value.Default.Present,
                )
            }
        else -> emptyList()
    }

/** Returns whether any argument expression recursively contains [Value.Error]. */
fun OpenArguments.containsErrorValue(): Boolean =
    fieldExpressions().values.any { value -> value.containsErrorValue() }

private fun OpenValue?.containsErrorValue(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.InputList -> values.any { value -> value.containsGroundErrorValue() }
        is Value.InputObject ->
            fieldValues.values.any { value -> value.containsGroundErrorValue() }
        is OpenListValueImpl -> values.any { value -> value.containsErrorValue() }
        is OpenInputObjectValueImpl ->
            fieldValues.values.any { value -> value.containsErrorValue() }
        else -> false
    }

private fun Value.Input?.containsGroundErrorValue(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.InputList -> values.any { value -> value.containsGroundErrorValue() }
        is Value.InputObject ->
            fieldValues.values.any { value -> value.containsGroundErrorValue() }
        else -> false
    }

private fun OpenValue?.substituteTemplates(
    bindings: Map<Value.Variable.Template, Value.Input?>,
): OpenValue? =
    when (this) {
        is Value.Variable.Template ->
            if (this in bindings) bindings[this] else this
        is OpenListValueImpl ->
            copy(values = values.map { value -> value.substituteTemplates(bindings) })
        is OpenInputObjectValueImpl ->
            copy(
                fieldValues =
                    fieldValues.mapValues { (_, value) ->
                        value.substituteTemplates(bindings)
                    },
            )
        else -> this
    }

/**
 * Grounds this argument tuple under [world].
 *
 * @throws IllegalStateException when a stamped variable is unbound or a template is unstamped
 */
context(world: Assumptions)
internal fun OpenArguments.instantiateBindings(): Value.Arguments =
    groundedArguments(
        fieldExpressions().mapValues { (_, value) -> value.instantiateBindings() },
    )

/** Grounds this argument tuple, suspending until every stamped variable is complete. */
context(world: Assumptions)
suspend fun OpenArguments.fetchBindings(): Value.Arguments =
    groundedArguments(
        fieldExpressions().mapValues { (_, value) -> value.fetchBindings() },
    )

private fun OpenArguments.groundedArguments(
    fields: Map<String, Value.Input?>,
): Value.Arguments =
    argumentsOfGround(
        type,
        fields.mapValues { (name, value) ->
            coerceInputValue(type.fields.getValue(name).typeExpr, value)
        },
    )

context(world: Assumptions)
private fun OpenValue?.instantiateBindings(): Value.Input? =
    when (this) {
        null -> null
        is Value.Input -> this
        is Value.Variable.Stamped -> world.getBinding(this)
        is Value.Variable.Template ->
            error("Variable template $this must be stamped before it can be instantiated")
        is OpenListValueImpl ->
            Value.InputList.of(
                elementType,
                values.map { value -> value.instantiateBindings() },
            )
        is OpenInputObjectValueImpl ->
            Value.InputObject.of(
                objectType,
                fieldValues.mapValues { (_, value) -> value.instantiateBindings() },
            )
    }

context(world: Assumptions)
private suspend fun OpenValue?.fetchBindings(): Value.Input? =
    when (this) {
        null -> null
        is Value.Input -> this
        is Value.Variable.Stamped -> world.fetchBinding(this)
        is Value.Variable.Template ->
            error("Variable template $this must be stamped before it can be instantiated")
        is OpenListValueImpl ->
            Value.InputList.of(
                elementType,
                values.map { value -> value.fetchBindings() },
            )
        is OpenInputObjectValueImpl ->
            Value.InputObject.of(
                objectType,
                fieldValues.mapValues { (_, value) -> value.fetchBindings() },
            )
    }
