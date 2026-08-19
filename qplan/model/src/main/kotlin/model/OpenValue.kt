package model

import model.invariants.conformsToInputSchemaType

/**
 * An opaque, schema-checked input expression that may contain variables.
 *
 * Other than [Ground], implementations expose no structural operations; they can only be embedded
 * in another open expression or instantiated under one [Assumptions].
 */
sealed interface OpenValue {
    /**
     * A variable-free input expression containing canonical [EngineInputData].
     *
     * Equality is structural over [data].
     */
    sealed interface Ground : OpenValue {
        val data: EngineInputData

        companion object {
            /**
             * Constructs a ground expression checked against [typeExpr].
             *
             * ### Invariant: open-ground-input
             *
             * Every result contains recursively copied [EngineInputData] conforming to [typeExpr].
             */
            fun of(
                typeExpr: TypeExpr<Schema.InputType>,
                data: EngineInputData,
            ): Ground {
                return GroundOpenValueImpl(
                    requireNotNull(toEngineInputData(typeExpr, data)) {
                        "A ground open value cannot contain null"
                    },
                )
            }
        }
    }

    companion object {
        /**
         * Constructs one schema-checked open expression.
         *
         * [value] may be [EngineInputData], [Value.Variable], or another [OpenValue].
         */
        fun of(
            typeExpr: TypeExpr<Schema.InputType>,
            value: Any?,
        ): OpenValue? = coerceOpenInputValue(typeExpr, value)
    }
}

/**
 * One erroneous argument expression.
 *
 * Recursive argument construction collapses any occurrence of this sentinel to
 * [OpenArguments.Ground.Error]. It does not belong to [EngineInputData].
 */
data object ArgumentResolutionError : OpenValue

/**
 * An opaque argument tuple that may contain variables.
 *
 * Ground [Value.Arguments] values are also open arguments and retain their ordinary inspectable
 * field-value map.
 */
sealed interface OpenArguments {
    /** A fully grounded argument outcome. */
    sealed interface Ground : OpenArguments {
        /** The whole argument tuple is erroneous and has no individual argument values. */
        data object Error : Ground
    }

    /**
     * An argument tuple in a resolver-registry template.
     *
     * Every variable recursively contained by a template has [Value.Variable.isTemplate] set.
     * Stamping replaces every contained variable with one carrying the same [Stamp.Occurrence].
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
            selectionStamp: Stamp.Occurrence,
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
                require(arguments != Ground.Error) {
                    "Erroneous arguments cannot become a registry template"
                }
                require(arguments.usedVariables().all(Value.Variable::isTemplate)) {
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
         * Declared defaults are included. A variable-free result is either ordinary
         * [Value.Arguments] or [Ground.Error] when any supplied expression recursively contains
         * [ArgumentResolutionError].
         */
        fun of(
            field: Schema.OutputField,
            fields: Map<String, Any?>,
        ): OpenArguments {
            val values = coerceOpenInputLikeFields(field.arguments, fields)
            return openArgumentsOf(values)
        }
    }
}

/**
 * Returns this tuple checked against [expectedType], replacing the stamp on every recursively
 * contained selection-stamped variable.
 */
internal fun OpenArguments.restampSelectionVariables(
    expectedType: Schema.FieldArguments,
    selectionStamp: Stamp.Occurrence,
): OpenArguments {
    if (this == OpenArguments.Ground.Error) return this
    return openArgumentsOf(
        fields =
            fieldExpressions().mapValues { (_, value) ->
                value.restampVariables(selectionStamp)
            },
    )
}

private data class OpenListValueImpl(
    val values: List<OpenValue?>,
) : OpenValue

private data class OpenInputObjectValueImpl(
    val fieldValues: Map<String, OpenValue?>,
) : OpenValue

private data class GroundOpenValueImpl(
    override val data: EngineInputData,
) : OpenValue.Ground

private data class OpenArgumentsImpl(
    val fieldValues: Map<String, OpenValue?>,
) : OpenArguments

private data class OpenArgumentsTemplateImpl(
    val fieldValues: Map<String, OpenValue?>,
) : OpenArguments.Template {
    override fun stamp(
        expectedType: Schema.FieldArguments,
        selectionStamp: Stamp.Occurrence,
    ): OpenArguments =
        openArgumentsOf(
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

    val values =
        type.fields.values
            .mapNotNull { field ->
                val defaultValue = field.defaultValue
                if (defaultValue is Value.Default.Present) {
                    field.name to defaultValue.value.toOpenValue()
                } else {
                    null
                }
            }.toMap() + suppliedFields
    if (!values.keys.containsAll(type.requiredFieldNames())) throw ClassCastException()
    return values
}

private fun coerceOpenInputValue(
    typeExpr: TypeExpr<Schema.InputType>,
    value: Any?,
): OpenValue? {
    if (value == null) {
        if (!typeExpr.isNullable) throw ClassCastException()
        return null
    }
    if (value == ArgumentResolutionError) return ArgumentResolutionError
    if (value is Value.Variable) return value
    if (value is OpenValue.Ground) {
        return OpenValue.Ground.of(typeExpr, value.data)
    }
    if (value is OpenValue) {
        if (!value.conformsToOpenSchemaType(typeExpr)) {
            throw ClassCastException("Open value $value does not conform to $typeExpr")
        }
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
            openListValueOf(coerced)
        }
    }
}

private fun coerceOpenNamedInputValue(
    type: Schema.InputType,
    value: Any,
): OpenValue =
    when (type) {
        is Schema.SimpleType ->
            OpenValue.Ground.of(TypeExpr.Named.of(type, isNullable = false), value)
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
            openInputObjectValueOf(coerced)
        }
    }

private fun openListValueOf(
    values: List<OpenValue?>,
): OpenValue {
    if (values.any { value -> value != null && value !is OpenValue.Ground }) {
        return OpenListValueImpl(values)
    }
    val data: EngineInputListData =
        values.map { value -> (value as? OpenValue.Ground)?.data }
    return GroundOpenValueImpl(data)
}

private fun openInputObjectValueOf(
    fieldValues: Map<String, OpenValue?>,
): OpenValue {
    if (fieldValues.values.any { value -> value != null && value !is OpenValue.Ground }) {
        return OpenInputObjectValueImpl(fieldValues)
    }
    val data: EngineInputObjectData =
        fieldValues.mapValues { (_, value) -> (value as? OpenValue.Ground)?.data }
    return GroundOpenValueImpl(data)
}

private fun OpenValue.conformsToOpenSchemaType(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean =
    when (this) {
        ArgumentResolutionError, is Value.Variable -> true
        is OpenValue.Ground -> data.conformsToInputSchemaType(typeExpr)
        is OpenListValueImpl ->
            typeExpr is TypeExpr.List &&
                values.all { value ->
                    value.conformsToOpenSchemaTypeOrNull(typeExpr.elementType)
                }
        is OpenInputObjectValueImpl -> {
            val expectedType = (typeExpr as? TypeExpr.Named)?.baseType
            expectedType is Schema.InputObjectType &&
                fieldValues.all { (name, value) ->
                    val field = expectedType.fields[name] ?: return@all false
                    value.conformsToOpenSchemaTypeOrNull(field.typeExpr)
                }
        }
    }

private fun OpenValue?.conformsToOpenSchemaTypeOrNull(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean =
    if (this == null) {
        typeExpr.isNullable
    } else {
        conformsToOpenSchemaType(typeExpr)
    }

internal fun OpenArguments.conformsToArgumentDefinition(
    expectedType: Schema.FieldArguments,
): Boolean =
    when (this) {
        OpenArguments.Ground.Error -> true
        else ->
            fieldExpressions().let { fields ->
                fields.keys.containsAll(expectedType.requiredFieldNames()) &&
                    fields.all { (name, value) ->
                        val field = expectedType.fields[name] ?: return@all false
                        value.conformsToOpenSchemaTypeOrNull(field.typeExpr)
                    }
            }
    }

internal fun OpenArguments.fieldExpressions(): Map<String, OpenValue?> =
    when (this) {
        OpenArguments.Ground.Error -> error("Erroneous arguments have no field expressions")
        is Value.Arguments ->
            fieldValues.mapValues { (_, value) -> value.toOpenValue() }
        is OpenArgumentsImpl -> fieldValues
        is OpenArgumentsTemplateImpl -> fieldValues
    }

private fun EngineInputData?.toOpenValue(): OpenValue? =
    this?.let(::GroundOpenValueImpl)

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
    if (this == OpenArguments.Ground.Error) return this
    val stamped = fieldExpressions().mapValues { (_, value) -> value.stampVars(path) }
    return openArgumentsOf(stamped)
}

private fun openArgumentsOf(
    fields: Map<String, OpenValue?>,
): OpenArguments =
    when {
        fields.values.any { value -> value.containsErrorValue() } ->
            OpenArguments.Ground.Error
        fields.values.all { value -> value == null || value is OpenValue.Ground } ->
            argumentsOfGround(
                fields.mapValues { (_, value) -> (value as? OpenValue.Ground)?.data },
            )
        else -> OpenArgumentsImpl(fields)
    }

private fun OpenValue?.stampVars(path: List<PathComponent>): OpenValue? =
    when (this) {
        is Value.Variable -> if (isTemplate) stamp(path) else this
        is OpenListValueImpl -> copy(values = values.map { it.stampVars(path) })
        is OpenInputObjectValueImpl ->
            copy(fieldValues = fieldValues.mapValues { (_, value) -> value.stampVars(path) })
        else -> this
    }

private fun OpenValue?.stampVariables(selectionStamp: Stamp.Occurrence): OpenValue? =
    when (this) {
        is Value.Variable -> if (isTemplate) stamp(selectionStamp) else this
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

private fun OpenValue?.restampVariables(selectionStamp: Stamp.Occurrence): OpenValue? =
    when (this) {
        is Value.Variable ->
            if (stamp?.occurrenceLineage.isNullOrEmpty()) {
                this
            } else {
                Value.Variable
                    .of(
                        field = field,
                        variableName = variableName,
                    ).stamp(selectionStamp)
            }
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
    bindings: Map<Value.Variable, EngineInputData?>,
): OpenArguments {
    if (this == OpenArguments.Ground.Error) return this
    require(bindings.keys.all(Value.Variable::isTemplate)) {
        "Template substitution requires variable templates"
    }
    val substituted =
        fieldExpressions().mapValues { (name, value) ->
            value.substituteTemplates(
                bindings,
                expectedType.fields.getValue(name).typeExpr,
            )
        }
    return openArgumentsOf(substituted)
}

internal fun OpenArguments.mapVariableTemplates(
    expectedType: Schema.FieldArguments,
    transform: (Value.Variable) -> Value.Variable,
): OpenArguments {
    if (this == OpenArguments.Ground.Error) return this
    val mapped =
        fieldExpressions().mapValues { (_, value) -> value.mapVariableTemplates(transform) }
    return openArgumentsOf(mapped)
}

private fun OpenValue?.mapVariableTemplates(
    transform: (Value.Variable) -> Value.Variable,
): OpenValue? =
    when (this) {
        is Value.Variable ->
            if (isTemplate) {
                transform(this).also { transformed ->
                    require(transformed.isTemplate) {
                        "Variable-template mapping must return a template"
                    }
                }
            } else {
                throw IllegalArgumentException(
                    "Pre-reasoning expressions cannot contain stamped variables",
                )
            }
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
    if (this == OpenArguments.Ground.Error) {
        emptySet()
    } else {
        fieldExpressions().values.flatMapTo(linkedSetOf()) { value -> value.variables() }
    }

/** Returns the occurrence-specific variables used anywhere in this argument tuple. */
internal fun OpenArguments.stampedVariables(): Set<Value.Variable> =
    variables().filterTo(linkedSetOf(), Value.Variable::isStamped)

/** Returns every variable expression used anywhere in this argument tuple. */
fun OpenArguments.usedVariables(): Set<Value.Variable> = variables()

/** Returns the argument names whose values recursively contain at least one variable expression. */
fun OpenArguments.variableArgumentNames(): Set<String> =
    if (this == OpenArguments.Ground.Error) {
        emptySet()
    } else {
        fieldExpressions()
            .filterValues { value -> value.variables().isNotEmpty() }
            .keys
    }

/** Returns the source-selection identities carried by variables in this tuple. */
fun OpenArguments.variableSourceSelectionStamps(): Set<Stamp.Occurrence> =
    variables()
        .mapNotNullTo(linkedSetOf()) { variable ->
            variable.stamp?.takeIf { stamp -> stamp.occurrenceLineage.isNotEmpty() }
        }

private fun OpenValue?.variables(): Set<Value.Variable> =
    when (this) {
        is Value.Variable -> setOf(this)
        is OpenListValueImpl -> values.flatMapTo(linkedSetOf()) { value -> value.variables() }
        is OpenInputObjectValueImpl ->
            fieldValues.values.flatMapTo(linkedSetOf()) { value -> value.variables() }
        else -> emptySet()
    }

internal fun OpenArguments.variableTemplates(): Set<Value.Variable> =
    variables().filterTo(linkedSetOf(), Value.Variable::isTemplate)

internal fun OpenArguments.retarget(field: Schema.OutputField): OpenArguments {
    if (this == OpenArguments.Ground.Error) return this
    val retargeted = OpenArguments.of(field, fieldExpressions())
    return when (this) {
        is OpenArguments.Template -> OpenArguments.Template.of(field.arguments, retargeted)
        else -> retargeted
    }
}

internal fun OpenValue?.matchingVariableTypes(
    variable: Value.Variable,
    typeExpr: TypeExpr<Schema.InputType>,
    hasDefault: Boolean,
): List<Pair<TypeExpr<Schema.InputType>, Boolean>> {
    require(variable.isTemplate) { "Variable type matching requires a template" }
    return when {
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
}

/** Returns whether this entire ground argument outcome is erroneous. */
fun OpenArguments.containsErrorValue(): Boolean = this == OpenArguments.Ground.Error

private fun OpenValue?.containsErrorValue(): Boolean =
    when (this) {
        ArgumentResolutionError -> true
        is OpenListValueImpl -> values.any { value -> value.containsErrorValue() }
        is OpenInputObjectValueImpl ->
            fieldValues.values.any { value -> value.containsErrorValue() }
        else -> false
    }

private fun OpenValue?.substituteTemplates(
    bindings: Map<Value.Variable, EngineInputData?>,
    expectedType: TypeExpr<Schema.InputType>,
): OpenValue? =
    when (this) {
        is Value.Variable ->
            if (isTemplate && this in bindings) {
                bindings[this]?.let { value -> OpenValue.Ground.of(expectedType, value) }
            } else {
                this
            }
        is OpenListValueImpl -> {
            check(expectedType is TypeExpr.List)
            copy(
                values =
                    values.map { value ->
                        value.substituteTemplates(bindings, expectedType.elementType)
                    },
            )
        }
        is OpenInputObjectValueImpl -> {
            val expectedObjectType = (expectedType as TypeExpr.Named).baseType
            check(expectedObjectType is Schema.InputObjectType)
            copy(
                fieldValues =
                    fieldValues.mapValues { (name, value) ->
                        value.substituteTemplates(
                            bindings,
                            expectedObjectType.fields.getValue(name).typeExpr,
                        )
                    },
            )
        }
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
): OpenArguments.Ground {
    if (this == OpenArguments.Ground.Error) return OpenArguments.Ground.Error
    return groundedArguments(expectedType) { value, typeExpr ->
        value.instantiateBindings(typeExpr)
    }
}

/** Grounds this argument tuple under [expectedType], suspending for incomplete stamped variables. */
context(world: Assumptions)
suspend fun OpenArguments.fetchBindings(
    expectedType: Schema.FieldArguments,
): OpenArguments.Ground {
    if (this == OpenArguments.Ground.Error) return OpenArguments.Ground.Error
    return groundedArguments(expectedType) { value, typeExpr ->
        value.fetchBindings(typeExpr)
    }
}

private inline fun OpenArguments.groundedArguments(
    expectedType: Schema.FieldArguments,
    ground: (OpenValue?, TypeExpr<Schema.InputType>) -> VariableBinding,
): OpenArguments.Ground {
    val fields = linkedMapOf<String, EngineInputData?>()
    fieldExpressions().forEach { (name, value) ->
        val typeExpr = expectedType.fields.getValue(name).typeExpr
        when (val binding = ground(value, typeExpr)) {
            VariableBinding.Error -> return OpenArguments.Ground.Error
            is VariableBinding.Input -> fields[name] = binding.value
        }
    }
    return argumentsOfGround(fields)
}

context(world: Assumptions)
private fun OpenValue?.instantiateBindings(
    expectedType: TypeExpr<Schema.InputType>,
): VariableBinding =
    when (this) {
        null -> VariableBinding.of(null)
        ArgumentResolutionError -> VariableBinding.Error
        is OpenValue.Ground -> VariableBinding.of(data)
        is Value.Variable ->
            if (isStamped) {
                world.getBinding(this).coerceTo(expectedType)
            } else {
                error("Variable template $this must be stamped before it can be instantiated")
            }
        is OpenListValueImpl -> {
            require(expectedType is TypeExpr.List) {
                "Open list expression does not match $expectedType"
            }
            val grounded = mutableListOf<EngineInputData?>()
            values.forEach { value ->
                when (val binding = value.instantiateBindings(expectedType.elementType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded += binding.value
                }
            }
            val data: EngineInputListData = grounded.toList()
            VariableBinding.of(data)
        }
        is OpenInputObjectValueImpl -> {
            val expectedObjectType = (expectedType as? TypeExpr.Named)?.baseType
            require(expectedObjectType is Schema.InputObjectType) {
                "Open input object expression does not match $expectedType"
            }
            val grounded = linkedMapOf<String, EngineInputData?>()
            fieldValues.forEach { (name, value) ->
                val fieldType = expectedObjectType.fields.getValue(name).typeExpr
                when (val binding = value.instantiateBindings(fieldType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded[name] = binding.value
                }
            }
            val data: EngineInputObjectData = grounded.toMap()
            VariableBinding.of(data)
        }
    }

context(world: Assumptions)
private suspend fun OpenValue?.fetchBindings(
    expectedType: TypeExpr<Schema.InputType>,
): VariableBinding =
    when (this) {
        null -> VariableBinding.of(null)
        ArgumentResolutionError -> VariableBinding.Error
        is OpenValue.Ground -> VariableBinding.of(data)
        is Value.Variable ->
            if (isStamped) {
                world.fetchBinding(this).coerceTo(expectedType)
            } else {
                error("Variable template $this must be stamped before it can be instantiated")
            }
        is OpenListValueImpl -> {
            require(expectedType is TypeExpr.List) {
                "Open list expression does not match $expectedType"
            }
            val grounded = mutableListOf<EngineInputData?>()
            values.forEach { value ->
                when (val binding = value.fetchBindings(expectedType.elementType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded += binding.value
                }
            }
            val data: EngineInputListData = grounded.toList()
            VariableBinding.of(data)
        }
        is OpenInputObjectValueImpl -> {
            val expectedObjectType = (expectedType as? TypeExpr.Named)?.baseType
            require(expectedObjectType is Schema.InputObjectType) {
                "Open input object expression does not match $expectedType"
            }
            val grounded = linkedMapOf<String, EngineInputData?>()
            fieldValues.forEach { (name, value) ->
                val fieldType = expectedObjectType.fields.getValue(name).typeExpr
                when (val binding = value.fetchBindings(fieldType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded[name] = binding.value
                }
            }
            val data: EngineInputObjectData = grounded.toMap()
            VariableBinding.of(data)
        }
    }

private fun VariableBinding.coerceTo(
    expectedType: TypeExpr<Schema.InputType>,
): VariableBinding =
    when (this) {
        VariableBinding.Error -> this
        is VariableBinding.Input ->
            VariableBinding.of(toEngineInputData(expectedType, value))
    }
