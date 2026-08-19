package model

import model.invariants.conformsToInputSchemaType

/**
 * A schema-checked argument expression.
 *
 * This is an internal natural union of [EngineInputData], [Arguments.Variable],
 * [ArgumentResolutionError], lists, and input-object maps. Null represents GraphQL null. The
 * expected schema type supplies the distinctions erased by the broad Kotlin type.
 */
internal typealias ArgumentExpression = Any

private data class ArgumentsImpl(
    val fieldValues: Map<String, ArgumentExpression?>,
) : Arguments

private data class ArgumentsTemplateImpl(
    val fieldValues: Map<String, ArgumentExpression?>,
) : Arguments.Template {
    override fun stamp(
        expectedType: Schema.FieldArguments,
        selectionStamp: Stamp.Occurrence,
    ): Arguments =
        argumentsOfExpressions(
            fieldValues.mapValues { (_, value) ->
                value.stampVariables(selectionStamp)
            },
        ).validatedAgainst(expectedType)
}

internal fun argumentsOf(
    field: Schema.Field,
    fields: Map<String, Any?>,
): Arguments =
    argumentsOfExpressions(
        coerceArgumentFields(field.arguments, fields),
    )

internal fun argumentTemplateOf(
    expectedType: Schema.FieldArguments,
    arguments: Arguments,
): Arguments.Template {
    require(arguments != Arguments.Error) {
        "Erroneous arguments cannot become a registry template"
    }
    require(arguments.usedVariables().all(Arguments.Variable::isTemplate)) {
        "A registry argument template cannot contain stamped variables"
    }
    val template =
        if (arguments is Arguments.Template) {
            arguments
        } else {
            ArgumentsTemplateImpl(arguments.fieldExpressions())
        }
    return template.validatedAgainst(expectedType)
}

internal fun coerceArgumentExpression(
    typeExpr: TypeExpr<Schema.InputTypeDef>,
    value: Any?,
): ArgumentExpression? {
    if (value == null) {
        if (!typeExpr.isNullable) throw ClassCastException()
        return null
    }
    if (value == ArgumentResolutionError || value is Arguments.Variable) return value

    return when (typeExpr) {
        is TypeExpr.List -> {
            val values = value as? List<*> ?: throw ClassCastException()
            values.map { element ->
                coerceArgumentExpression(typeExpr.elementType, element)
            }
        }
        is TypeExpr.Named ->
            when (val type = typeExpr.baseType) {
                is Schema.SimpleTypeDef ->
                    requireNotNull(toEngineInputData(typeExpr, value)) {
                        "A non-null argument expression cannot coerce to null"
                    }
                is Schema.Input -> {
                    val fields = value.toStringKeyedArgumentMap()
                    coerceArgumentFields(type, fields)
                }
            }
    }
}

private fun coerceArgumentFields(
    type: Schema.InputObjectLike,
    fields: Map<String, Any?>,
): Map<String, ArgumentExpression?> {
    val suppliedFields =
        fields.mapValues { (fieldName, value) ->
            val field = type.field(fieldName) ?: throw ClassCastException()
            coerceArgumentExpression(field.type, value)
        }

    val values =
        type.fields
            .mapNotNull { field ->
                val defaultValue = field.defaultValue
                if (defaultValue is CoercedDefaultValue.Present) {
                    field.name to defaultValue.value
                } else {
                    null
                }
            }.toMap() + suppliedFields
    if (!values.keys.containsAll(type.requiredFieldNames())) throw ClassCastException()
    return values
}

private fun argumentsOfExpressions(
    fields: Map<String, ArgumentExpression?>,
): Arguments =
    when {
        fields.values.any(ArgumentExpression?::containsArgumentError) -> Arguments.Error
        fields.values.none(ArgumentExpression?::containsVariable) ->
            argumentsOfGround(
                fields.mapValues { (_, value) -> value.toGroundInputData() },
            )
        else -> ArgumentsImpl(fields)
    }

internal fun Arguments.fieldExpressions(): Map<String, ArgumentExpression?> =
    when (this) {
        Arguments.Error -> error("Erroneous arguments have no field expressions")
        is Arguments.Resolved -> fieldValues
        is ArgumentsImpl -> fieldValues
        is ArgumentsTemplateImpl -> fieldValues
    }

private fun <T : Arguments> T.validatedAgainst(
    expectedType: Schema.FieldArguments,
): T {
    require(conformsToArgumentDefinition(expectedType)) {
        "Argument expressions do not conform to the expected field"
    }
    return this
}

internal fun Arguments.conformsToArgumentDefinition(
    expectedType: Schema.FieldArguments,
): Boolean =
    when (this) {
        Arguments.Error -> true
        else ->
            fieldExpressions().let { fields ->
                fields.keys.containsAll(expectedType.requiredFieldNames()) &&
                    fields.all { (name, value) ->
                        val field = expectedType.field(name) ?: return@all false
                        value.conformsToArgumentType(field.type)
                    }
            }
    }

private fun ArgumentExpression?.conformsToArgumentType(
    typeExpr: TypeExpr<Schema.InputTypeDef>,
): Boolean =
    when {
        this == null -> typeExpr.isNullable
        this == ArgumentResolutionError || this is Arguments.Variable -> true
        typeExpr is TypeExpr.List ->
            this is List<*> &&
                all { element -> element.conformsToArgumentType(typeExpr.elementType) }
        typeExpr is TypeExpr.Named && typeExpr.baseType is Schema.Input -> {
            val fields = this as? Map<*, *> ?: return false
            val expectedType = typeExpr.baseType as Schema.Input
            val names = fields.keys
            names.all { it is String } &&
                names.containsAll(expectedType.requiredFieldNames()) &&
                fields.all { (name, value) ->
                    val field = expectedType.field(name as String) ?: return@all false
                    value.conformsToArgumentType(field.type)
                }
        }
        else -> conformsToInputSchemaType(typeExpr)
    }

/**
 * Returns this tuple checked against [expectedType], replacing the stamp on every recursively
 * contained selection-stamped variable.
 */
internal fun Arguments.restampSelectionVariables(
    expectedType: Schema.FieldArguments,
    selectionStamp: Stamp.Occurrence,
): Arguments {
    if (this == Arguments.Error) return this
    return argumentsOfExpressions(
        fieldExpressions().mapValues { (_, value) ->
            value.restampVariables(selectionStamp)
        },
    ).validatedAgainst(expectedType)
}

internal fun Arguments.stampVars(
    expectedType: Schema.FieldArguments,
    path: List<PathComponent>,
): Arguments {
    if (this == Arguments.Error) return this
    return argumentsOfExpressions(
        fieldExpressions().mapValues { (_, value) -> value.stampVars(path) },
    ).validatedAgainst(expectedType)
}

private fun ArgumentExpression?.stampVars(
    path: List<PathComponent>,
): ArgumentExpression? =
    when (this) {
        is Arguments.Variable -> if (isTemplate) stamp(path) else this
        is List<*> -> map { value -> value.stampVars(path) }
        is Map<*, *> ->
            toStringKeyedArgumentMap().mapValues { (_, value) -> value.stampVars(path) }
        else -> this
    }

private fun ArgumentExpression?.stampVariables(
    selectionStamp: Stamp.Occurrence,
): ArgumentExpression? =
    when (this) {
        is Arguments.Variable -> if (isTemplate) stamp(selectionStamp) else this
        is List<*> -> map { value -> value.stampVariables(selectionStamp) }
        is Map<*, *> ->
            toStringKeyedArgumentMap()
                .mapValues { (_, value) -> value.stampVariables(selectionStamp) }
        else -> this
    }

private fun ArgumentExpression?.restampVariables(
    selectionStamp: Stamp.Occurrence,
): ArgumentExpression? =
    when (this) {
        is Arguments.Variable ->
            if (stamp?.occurrenceLineage.isNullOrEmpty()) {
                this
            } else {
                Arguments.Variable
                    .of(
                        field = field,
                        variableName = variableName,
                    ).stamp(selectionStamp)
            }
        is List<*> -> map { value -> value.restampVariables(selectionStamp) }
        is Map<*, *> ->
            toStringKeyedArgumentMap()
                .mapValues { (_, value) -> value.restampVariables(selectionStamp) }
        else -> this
    }

internal fun Arguments.substituteTemplates(
    expectedType: Schema.FieldArguments,
    bindings: Map<Arguments.Variable, EngineInputData?>,
): Arguments {
    if (this == Arguments.Error) return this
    require(bindings.keys.all(Arguments.Variable::isTemplate)) {
        "Template substitution requires variable templates"
    }
    val substituted =
        fieldExpressions().mapValues { (name, value) ->
            value.substituteTemplates(
                bindings,
                expectedType.requireField(name).type,
            )
        }
    return argumentsOfExpressions(substituted).validatedAgainst(expectedType)
}

private fun ArgumentExpression?.substituteTemplates(
    bindings: Map<Arguments.Variable, EngineInputData?>,
    expectedType: TypeExpr<Schema.InputTypeDef>,
): ArgumentExpression? =
    when (this) {
        is Arguments.Variable ->
            if (isTemplate && this in bindings) {
                coerceArgumentExpression(expectedType, bindings[this])
            } else {
                this
            }
        is List<*> -> {
            check(expectedType is TypeExpr.List)
            map { value ->
                value.substituteTemplates(bindings, expectedType.elementType)
            }
        }
        is Map<*, *> -> {
            val expectedObjectType = (expectedType as TypeExpr.Named).baseType
            check(expectedObjectType is Schema.Input)
            toStringKeyedArgumentMap().mapValues { (name, value) ->
                value.substituteTemplates(
                    bindings,
                    expectedObjectType.requireField(name).type,
                )
            }
        }
        else -> this
    }

internal fun Arguments.mapVariableTemplates(
    expectedType: Schema.FieldArguments,
    transform: (Arguments.Variable) -> Arguments.Variable,
): Arguments {
    if (this == Arguments.Error) return this
    val mapped =
        fieldExpressions().mapValues { (_, value) ->
            value.mapVariableTemplates(transform)
        }
    return argumentsOfExpressions(mapped).validatedAgainst(expectedType)
}

private fun ArgumentExpression?.mapVariableTemplates(
    transform: (Arguments.Variable) -> Arguments.Variable,
): ArgumentExpression? =
    when (this) {
        is Arguments.Variable ->
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
        is List<*> -> map { value -> value.mapVariableTemplates(transform) }
        is Map<*, *> ->
            toStringKeyedArgumentMap()
                .mapValues { (_, value) -> value.mapVariableTemplates(transform) }
        else -> this
    }

internal fun Arguments.variables(): Set<Arguments.Variable> =
    if (this == Arguments.Error) {
        emptySet()
    } else {
        fieldExpressions().values.flatMapTo(linkedSetOf()) { value -> value.variables() }
    }

/** Returns the occurrence-specific variables used anywhere in this argument tuple. */
internal fun Arguments.stampedVariables(): Set<Arguments.Variable> =
    variables().filterTo(linkedSetOf(), Arguments.Variable::isStamped)

/** Returns every variable expression used anywhere in this argument tuple. */
fun Arguments.usedVariables(): Set<Arguments.Variable> = variables()

/** Returns the argument names whose values recursively contain at least one variable expression. */
fun Arguments.variableArgumentNames(): Set<String> =
    if (this == Arguments.Error) {
        emptySet()
    } else {
        fieldExpressions()
            .filterValues(ArgumentExpression?::containsVariable)
            .keys
    }

/** Returns the source-selection identities carried by variables in this tuple. */
fun Arguments.variableSourceSelectionStamps(): Set<Stamp.Occurrence> =
    variables()
        .mapNotNullTo(linkedSetOf()) { variable ->
            variable.stamp?.takeIf { stamp -> stamp.occurrenceLineage.isNotEmpty() }
        }

private fun ArgumentExpression?.variables(): Set<Arguments.Variable> =
    when (this) {
        is Arguments.Variable -> setOf(this)
        is List<*> -> flatMapTo(linkedSetOf()) { value -> value.variables() }
        is Map<*, *> ->
            values.flatMapTo(linkedSetOf()) { value -> value.variables() }
        else -> emptySet()
    }

private fun ArgumentExpression?.containsVariable(): Boolean =
    when (this) {
        is Arguments.Variable -> true
        is List<*> -> any(ArgumentExpression?::containsVariable)
        is Map<*, *> -> values.any(ArgumentExpression?::containsVariable)
        else -> false
    }

internal fun Arguments.variableTemplates(): Set<Arguments.Variable> =
    variables().filterTo(linkedSetOf(), Arguments.Variable::isTemplate)

internal fun Arguments.retarget(field: Schema.Field): Arguments {
    if (this == Arguments.Error) return this
    val retargeted = Arguments.of(field, fieldExpressions())
    return when (this) {
        is Arguments.Template -> Arguments.Template.of(field.arguments, retargeted)
        else -> retargeted
    }
}

internal fun ArgumentExpression?.matchingVariableTypes(
    variable: Arguments.Variable,
    typeExpr: TypeExpr<Schema.InputTypeDef>,
    hasDefault: Boolean,
): List<Pair<TypeExpr<Schema.InputTypeDef>, Boolean>> {
    require(variable.isTemplate) { "Variable type matching requires a template" }
    return when {
        this == variable -> listOf(typeExpr to hasDefault)
        this is List<*> && typeExpr is TypeExpr.List ->
            flatMap { value ->
                value.matchingVariableTypes(variable, typeExpr.elementType, false)
            }
        this is Map<*, *> &&
            typeExpr is TypeExpr.Named &&
            typeExpr.baseType is Schema.Input -> {
            val expectedType = typeExpr.baseType as Schema.Input
            toStringKeyedArgumentMap().flatMap { (name, value) ->
                val field = expectedType.requireField(name)
                value.matchingVariableTypes(
                    variable,
                    field.type,
                    field.defaultValue is CoercedDefaultValue.Present,
                )
            }
        }
        else -> emptyList()
    }
}

/** Returns whether this entire ground argument outcome is erroneous. */
fun Arguments.containsErrorValue(): Boolean = this == Arguments.Error

private fun ArgumentExpression?.containsArgumentError(): Boolean =
    when (this) {
        ArgumentResolutionError -> true
        is List<*> -> any(ArgumentExpression?::containsArgumentError)
        is Map<*, *> -> values.any(ArgumentExpression?::containsArgumentError)
        else -> false
    }

private fun ArgumentExpression?.toGroundInputData(): EngineInputData? =
    when (this) {
        null -> null
        is List<*> -> map { value -> value.toGroundInputData() }
        is Map<*, *> ->
            toStringKeyedArgumentMap()
                .mapValues { (_, value) -> value.toGroundInputData() }
        ArgumentResolutionError, is Arguments.Variable ->
            error("An open argument expression cannot become ground input data")
        else -> this
    }

internal fun Any.toStringKeyedArgumentMap(): Map<String, ArgumentExpression?> {
    val values = this as? Map<*, *> ?: throw ClassCastException()
    return values.entries.associate { (key, value) ->
        if (key !is String) throw ClassCastException()
        key to value
    }
}
