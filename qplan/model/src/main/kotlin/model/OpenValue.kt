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
    /**
     * An argument tuple in a resolver-registry template.
     *
     * Every variable recursively contained by a template is a [Value.Variable.Template].
     * Stamping replaces every contained variable with one carrying the same [SelectionStamp].
     */
    sealed interface Template : OpenArguments {
        /**
         * Returns this argument template stamped at [selectionStamp] and checked against
         * [expectedType].
         *
         * Equal templates stamped with equal selection stamps yield equal results. The resulting
         * tuple contains only ground values and selection-stamped variables.
         */
        fun stamp(
            expectedType: Schema.FieldArguments,
            selectionStamp: SelectionStamp,
        ): OpenArguments

        companion object {
            /**
             * Wraps [arguments] as a registry argument template checked against [expectedType].
             *
             * [arguments] may contain only variable templates, never stamped variables.
             */
            fun of(
                expectedType: Schema.FieldArguments,
                arguments: OpenArguments,
            ): Template {
                require(arguments.usedVariables().all { it is Value.Variable.Template }) {
                    "A registry argument template cannot contain stamped variables"
                }
                val template =
                    if (arguments is Template) {
                        arguments
                    } else {
                        OpenArgumentsTemplateImpl(
                            fieldValues = arguments.fieldExpressions(),
                        )
                    }
                return template.validatedAgainst(expectedType)
            }
        }
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
                OpenArgumentsImpl(values).validatedAgainst(field.arguments)
            }
        }
    }
}

/**
 * Returns this tuple checked against [expectedType], replacing the stamp on every recursively
 * contained selection-stamped variable.
 */
internal fun OpenArguments.restampSelectionVariables(
    expectedType: Schema.FieldArguments,
    selectionStamp: SelectionStamp,
): OpenArguments =
    openArgumentsOf(
        expectedType = expectedType,
        fields =
            fieldExpressions().mapValues { (_, value) ->
                value.restampVariables(selectionStamp)
            },
    )

private data class OpenListValueImpl(
    val values: List<OpenValue?>,
) : OpenValue

private data class OpenInputObjectValueImpl(
    val fieldValues: Map<String, OpenValue?>,
) : OpenValue

private data class OpenArgumentsImpl(
    val fieldValues: Map<String, OpenValue?>,
) : OpenArguments

private data class OpenArgumentsTemplateImpl(
    val fieldValues: Map<String, OpenValue?>,
) : OpenArguments.Template {
    override fun stamp(
        expectedType: Schema.FieldArguments,
        selectionStamp: SelectionStamp,
    ): OpenArguments =
        openArgumentsOf(
            expectedType = expectedType,
            fields =
                fieldValues.mapValues { (_, value) ->
                    value.stampVariables(selectionStamp)
                },
        )
}

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
                OpenListValueImpl(coerced)
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
                OpenInputObjectValueImpl(coerced)
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
                values.all { value ->
                    value.conformsToSchemaTypeOrNull(typeExpr.elementType)
                }
        is OpenInputObjectValueImpl -> {
            val expectedType = (typeExpr as? TypeExpr.Named)?.baseType
            expectedType is Schema.InputObjectType &&
                fieldValues.all { (name, value) ->
                    val field = expectedType.fields[name] ?: return@all false
                    value.conformsToSchemaTypeOrNull(field.typeExpr)
                }
        }
    }

private fun OpenValue?.conformsToSchemaTypeOrNull(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean =
    if (this == null) {
        typeExpr.isNullable
    } else {
        conformsToSchemaType(typeExpr)
    }

internal fun OpenArguments.conformsToArgumentDefinition(
    expectedType: Schema.FieldArguments,
): Boolean =
    fieldExpressions().all { (name, value) ->
        val field = expectedType.fields[name] ?: return@all false
        value.conformsToSchemaTypeOrNull(field.typeExpr)
    }

internal fun OpenArguments.fieldExpressions(): Map<String, OpenValue?> =
    when (this) {
        is Value.Arguments -> fieldValues
        is OpenArgumentsImpl -> fieldValues
        is OpenArgumentsTemplateImpl -> fieldValues
    }

private fun <T : OpenArguments> T.validatedAgainst(
    expectedType: Schema.FieldArguments,
): T {
    require(conformsToArgumentDefinition(expectedType)) {
        "Argument expressions do not conform to the expected field"
    }
    return this
}

internal fun OpenArguments.stampVars(
    expectedType: Schema.FieldArguments,
    path: List<PathComponent>,
): OpenArguments {
    val stamped = fieldExpressions().mapValues { (_, value) -> value.stampVars(path) }
    return if (stamped.values.all { it == null || it is Value.Input }) {
        argumentsOfGround(
            expectedType,
            stamped.mapValues { (_, value) -> value as Value.Input? },
        )
    } else {
        OpenArgumentsImpl(stamped).validatedAgainst(expectedType)
    }
}

private fun openArgumentsOf(
    expectedType: Schema.FieldArguments,
    fields: Map<String, OpenValue?>,
): OpenArguments =
    if (fields.values.all { it == null || it is Value.Input }) {
        argumentsOfGround(
            expectedType,
            fields.mapValues { (_, value) -> value as Value.Input? },
        )
    } else {
        OpenArgumentsImpl(fields).validatedAgainst(expectedType)
    }

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
    expectedType: Schema.FieldArguments,
    bindings: Map<Value.Variable.Template, Value.Input?>,
): OpenArguments {
    val substituted =
        fieldExpressions().mapValues { (_, value) -> value.substituteTemplates(bindings) }
    return if (substituted.values.all { it == null || it is Value.Input }) {
        argumentsOfGround(
            expectedType,
            substituted.mapValues { (_, value) -> value as Value.Input? },
        )
    } else {
        OpenArgumentsImpl(substituted).validatedAgainst(expectedType)
    }
}

internal fun OpenArguments.mapVariableTemplates(
    expectedType: Schema.FieldArguments,
    transform: (Value.Variable.Template) -> Value.Variable.Template,
): OpenArguments {
    val mapped =
        fieldExpressions().mapValues { (_, value) -> value.mapVariableTemplates(transform) }
    return if (mapped.values.all { it == null || it is Value.Input }) {
        argumentsOfGround(
            expectedType,
            mapped.mapValues { (_, value) -> value as Value.Input? },
        )
    } else {
        OpenArgumentsImpl(mapped).validatedAgainst(expectedType)
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
        is OpenArguments.Template -> OpenArguments.Template.of(field.arguments, retargeted)
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
        this is OpenInputObjectValueImpl &&
            typeExpr is TypeExpr.Named &&
            typeExpr.baseType is Schema.InputObjectType -> {
            val expectedType = typeExpr.baseType as Schema.InputObjectType
            fieldValues.flatMap { (name, value) ->
                val field = expectedType.fields.getValue(name)
                value.matchingVariableTypes(
                    variable,
                    field.typeExpr,
                    field.defaultValue is Value.Default.Present,
                )
            }
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
 * Grounds this argument tuple under [world] and [expectedType].
 *
 * @throws IllegalStateException when a stamped variable is unbound or a template is unstamped
 */
context(world: Assumptions)
internal fun OpenArguments.instantiateBindings(
    expectedType: Schema.FieldArguments,
): Value.Arguments {
    return groundedArguments(expectedType) { value, typeExpr ->
        value.instantiateBindings(typeExpr)
    }
}

/** Grounds this argument tuple under [expectedType], suspending for incomplete stamped variables. */
context(world: Assumptions)
suspend fun OpenArguments.fetchBindings(
    expectedType: Schema.FieldArguments,
): Value.Arguments {
    return groundedArguments(expectedType) { value, typeExpr ->
        value.fetchBindings(typeExpr)
    }
}

private inline fun OpenArguments.groundedArguments(
    expectedType: Schema.FieldArguments,
    ground: (OpenValue?, TypeExpr<Schema.InputType>) -> Value.Input?,
): Value.Arguments =
    argumentsOfGround(
        expectedType,
        fieldExpressions().mapValues { (name, value) ->
            val typeExpr = expectedType.fields.getValue(name).typeExpr
            coerceInputValue(typeExpr, ground(value, typeExpr))
        },
    )

context(world: Assumptions)
private fun OpenValue?.instantiateBindings(
    expectedType: TypeExpr<Schema.InputType>,
): Value.Input? =
    when (this) {
        null -> coerceInputValue(expectedType, null)
        is Value.Input -> coerceInputValue(expectedType, this)
        is Value.Variable.Stamped -> coerceInputValue(expectedType, world.getBinding(this))
        is Value.Variable.Template ->
            error("Variable template $this must be stamped before it can be instantiated")
        is OpenListValueImpl -> {
            require(expectedType is TypeExpr.List) {
                "Open list expression does not match $expectedType"
            }
            Value.InputList.of(
                expectedType.elementType,
                values.map { value -> value.instantiateBindings(expectedType.elementType) },
            )
        }
        is OpenInputObjectValueImpl -> {
            val expectedObjectType = (expectedType as? TypeExpr.Named)?.baseType
            require(expectedObjectType is Schema.InputObjectType) {
                "Open input object expression does not match $expectedType"
            }
            Value.InputObject.of(
                expectedObjectType,
                fieldValues.mapValues { (name, value) ->
                    value.instantiateBindings(expectedObjectType.fields.getValue(name).typeExpr)
                },
            )
        }
    }

context(world: Assumptions)
private suspend fun OpenValue?.fetchBindings(
    expectedType: TypeExpr<Schema.InputType>,
): Value.Input? =
    when (this) {
        null -> coerceInputValue(expectedType, null)
        is Value.Input -> coerceInputValue(expectedType, this)
        is Value.Variable.Stamped -> coerceInputValue(expectedType, world.fetchBinding(this))
        is Value.Variable.Template ->
            error("Variable template $this must be stamped before it can be instantiated")
        is OpenListValueImpl -> {
            require(expectedType is TypeExpr.List) {
                "Open list expression does not match $expectedType"
            }
            Value.InputList.of(
                expectedType.elementType,
                values.map { value -> value.fetchBindings(expectedType.elementType) },
            )
        }
        is OpenInputObjectValueImpl -> {
            val expectedObjectType = (expectedType as? TypeExpr.Named)?.baseType
            require(expectedObjectType is Schema.InputObjectType) {
                "Open input object expression does not match $expectedType"
            }
            Value.InputObject.of(
                expectedObjectType,
                fieldValues.mapValues { (name, value) ->
                    value.fetchBindings(expectedObjectType.fields.getValue(name).typeExpr)
                },
            )
        }
    }
