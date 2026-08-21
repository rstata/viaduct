package model

import viaduct.graphql.schema.ViaductSchema

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
        expectedField: ViaductSchema.Field,
        selectionStamp: Stamp.Occurrence,
    ): Arguments =
        argumentsOfExpressions(
            fieldValues.mapValues { (_, value) ->
                value.stampVariables(selectionStamp)
            },
        ).validatedAgainst(expectedField)
}

internal fun argumentsOf(
    field: ViaductSchema.Field,
    fields: Map<String, Any?>,
): Arguments =
    argumentsOfExpressions(
        coerceArgumentFields(field, fields),
    )

internal fun argumentTemplateOf(
    expectedField: ViaductSchema.Field,
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
    return template.validatedAgainst(expectedField)
}

internal fun coerceArgumentExpression(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    value: Any?,
): ArgumentExpression? {
    if (value == null) {
        if (!typeExpr.isNullable) throw ClassCastException()
        return null
    }
    if (value == ArgumentResolutionError || value is Arguments.Variable) return value

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        val values = value as? List<*> ?: throw ClassCastException()
        return values.map { element ->
            coerceArgumentExpression(elementType, element)
        }
    }
    return when (val type = typeExpr.baseTypeDef) {
        is ViaductSchema.SimpleTypeDef ->
            requireNotNull(toEngineInputData(typeExpr, value)) {
                "A non-null argument expression cannot coerce to null"
            }
        is ViaductSchema.Input -> {
            val fields = value.toStringKeyedArgumentMap()
            coerceArgumentFields(type, fields)
        }
        else -> error("Unsupported input type: ${type.name}")
    }
}

private fun coerceArgumentFields(
    type: ViaductSchema.Input,
    fields: Map<String, Any?>,
): Map<String, ArgumentExpression?> {
    val suppliedFields =
        fields.mapValues { (fieldName, value) ->
            val field = type.field(fieldName) ?: throw ClassCastException()
            coerceArgumentExpression(field.inputType, value)
        }

    val values =
        type.fields
            .mapNotNull { field ->
                val defaultValue = field.coercedDefaultValue()
                if (defaultValue is CoercedDefaultValue.Present) {
                    field.name to defaultValue.value
                } else {
                    null
                }
            }.toMap() + suppliedFields
    if (!values.keys.containsAll(type.requiredFieldNames())) throw ClassCastException()
    return values
}

private fun coerceArgumentFields(
    field: ViaductSchema.Field,
    fields: Map<String, Any?>,
): Map<String, ArgumentExpression?> {
    val suppliedFields =
        fields.mapValues { (argName, value) ->
            val arg = field.arg(argName) ?: throw ClassCastException()
            coerceArgumentExpression(arg.inputType, value)
        }

    val values =
        field.args
            .mapNotNull { arg ->
                val defaultValue = arg.coercedDefaultValue()
                if (defaultValue is CoercedDefaultValue.Present) {
                    arg.name to defaultValue.value
                } else {
                    null
                }
            }.toMap() + suppliedFields
    if (!field.requiredArgsArePresentIn(values)) throw ClassCastException()
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
    expectedField: ViaductSchema.Field,
): T {
    require(conformsToArgumentDefinition(expectedField)) {
        "Argument expressions do not conform to the expected field"
    }
    return this
}

internal fun Arguments.conformsToArgumentDefinition(
    expectedField: ViaductSchema.Field,
): Boolean =
    when (this) {
        Arguments.Error -> true
        else ->
            fieldExpressions().let { fields ->
                expectedField.requiredArgsArePresentIn(fields) &&
                    fields.all { (name, value) ->
                        val arg = expectedField.arg(name) ?: return@all false
                        value.conformsToArgumentType(arg.inputType)
                    }
            }
    }

private fun ArgumentExpression?.conformsToArgumentType(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
): Boolean {
    if (this == null) return typeExpr.isNullable
    if (this == ArgumentResolutionError || this is Arguments.Variable) return true

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        return this is List<*> &&
            all { element -> element.conformsToArgumentType(elementType) }
    }

    return when (val expectedType = typeExpr.baseTypeDef) {
        is ViaductSchema.Input -> {
            val fields = this as? Map<*, *> ?: return false
            val names = fields.keys
            names.all { it is String } &&
                names.containsAll(expectedType.requiredFieldNames()) &&
                fields.all { (name, value) ->
                    val field = expectedType.field(name as String) ?: return@all false
                    value.conformsToArgumentType(field.inputType)
                }
        }
        else -> conformsToInputSchemaType(typeExpr)
    }
}

/**
 * Returns this tuple checked against [expectedType], replacing the stamp on every recursively
 * contained selection-stamped variable.
 */
internal fun Arguments.restampSelectionVariables(
    expectedField: ViaductSchema.Field,
    selectionStamp: Stamp.Occurrence,
): Arguments {
    if (this == Arguments.Error) return this
    return argumentsOfExpressions(
        fieldExpressions().mapValues { (_, value) ->
            value.restampVariables(selectionStamp)
        },
    ).validatedAgainst(expectedField)
}

internal fun Arguments.stampVars(
    expectedField: ViaductSchema.Field,
    path: List<PathComponent>,
): Arguments {
    if (this == Arguments.Error) return this
    return argumentsOfExpressions(
        fieldExpressions().mapValues { (_, value) -> value.stampVars(path) },
    ).validatedAgainst(expectedField)
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
    expectedField: ViaductSchema.Field,
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
                expectedField.requireArg(name).inputType,
            )
        }
    return argumentsOfExpressions(substituted).validatedAgainst(expectedField)
}

private fun ArgumentExpression?.substituteTemplates(
    bindings: Map<Arguments.Variable, EngineInputData?>,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
): ArgumentExpression? =
    when (this) {
        is Arguments.Variable ->
            if (isTemplate && this in bindings) {
                coerceArgumentExpression(expectedType, bindings[this])
            } else {
                this
            }
        is List<*> -> {
            val elementType = checkNotNull(expectedType.unwrapList())
            map { value ->
                value.substituteTemplates(bindings, elementType)
            }
        }
        is Map<*, *> -> {
            check(!expectedType.isList)
            val expectedObjectType = expectedType.baseTypeDef
            check(expectedObjectType is ViaductSchema.Input)
            toStringKeyedArgumentMap().mapValues { (name, value) ->
                value.substituteTemplates(
                    bindings,
                    expectedObjectType.requireField(name).inputType,
                )
            }
        }
        else -> this
    }

internal fun Arguments.mapVariableTemplates(
    expectedField: ViaductSchema.Field,
    transform: (Arguments.Variable) -> Arguments.Variable,
): Arguments {
    if (this == Arguments.Error) return this
    val mapped =
        fieldExpressions().mapValues { (_, value) ->
            value.mapVariableTemplates(transform)
        }
    return argumentsOfExpressions(mapped).validatedAgainst(expectedField)
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

internal fun Arguments.retarget(field: ViaductSchema.Field): Arguments {
    if (this == Arguments.Error) return this
    val retargeted = Arguments.of(field, fieldExpressions())
    return when (this) {
        is Arguments.Template -> Arguments.Template.of(field, retargeted)
        else -> retargeted
    }
}

internal fun ArgumentExpression?.matchingVariableTypes(
    variable: Arguments.Variable,
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    hasDefault: Boolean,
): List<Pair<ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>, Boolean>> {
    require(variable.isTemplate) { "Variable type matching requires a template" }
    val elementType = typeExpr.unwrapList()
    return when {
        this == variable -> listOf(typeExpr to hasDefault)
        this is List<*> && elementType != null ->
            flatMap { value ->
                value.matchingVariableTypes(variable, elementType, false)
            }
        this is Map<*, *> &&
            !typeExpr.isList &&
            typeExpr.baseTypeDef is ViaductSchema.Input -> {
            val expectedType = typeExpr.baseTypeDef as ViaductSchema.Input
            toStringKeyedArgumentMap().flatMap { (name, value) ->
                val field = expectedType.requireField(name)
                value.matchingVariableTypes(
                    variable,
                    field.inputType,
                    field.hasDefault,
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
