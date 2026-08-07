package model.testing

import model.Fragment
import model.Schema
import model.TypeExpr
import model.Value
import model.spec.SpecSelection
import model.spec.flatten

/**
 * One external `fromObjectField` declaration compiled for canonical registry construction.
 *
 * [responsePath] contains GraphQL response keys, including aliases. Composition validates that
 * path against the alias-preserving object-fragment source, then retains only the canonical
 * alias-free key path for semantic reasoning.
 */
class FromObjectField private constructor(
    val responsePath: List<String>,
    internal val objectFragment: Fragment,
    internal val keyPath: List<Value.Key>,
    internal val terminalType: TypeExpr<Schema.OutputType>,
    private val nullableTraversal: Boolean,
) : VariableDeclaration {
    internal fun mapVariables(
        transform: (Value.Variable.Template) -> Value.Variable.Template,
    ): FromObjectField =
        FromObjectField(
            responsePath = responsePath,
            objectFragment = objectFragment.mapVariables(transform),
            keyPath = keyPath.mapVariables(transform),
            terminalType = terminalType,
            nullableTraversal = nullableTraversal,
        )

    internal fun isCompatibleWith(
        locationType: TypeExpr<Schema.InputType>,
        locationHasDefault: Boolean,
    ): Boolean =
        compatibleTypes(
            locationType = locationType,
            sourceType = terminalType.asInputType(),
            nullableTraversal = nullableTraversal,
            locationHasDefault = locationHasDefault,
        )

    companion object {
        internal fun compile(
            schema: GJSchema,
            objectFragmentSource: String,
            responsePath: List<String>,
            variableField: Schema.ObjectField?,
        ): FromObjectField {
            require(responsePath.isNotEmpty()) {
                "fromObjectField path must contain at least one response key"
            }
            require(responsePath.none(String::isBlank)) {
                "fromObjectField path cannot contain a blank response key"
            }

            val parsed =
                GJSelectionParser(
                    schema = schema,
                    variableValues = emptyMap(),
                    variableField = variableField,
                ).specSelectionsFrom(objectFragmentSource)
            val compiled =
                schema.compilePath(
                    typeInScope = parsed.nominalType,
                    selections = parsed.selections,
                    responsePath = responsePath,
                )
            return FromObjectField(
                responsePath = responsePath,
                objectFragment =
                    Fragment.of(
                        nominalType = parsed.nominalType,
                        subselections = flatten(schema, parsed.nominalType, parsed.selections),
                    ),
                keyPath = compiled.keys,
                terminalType = compiled.terminalType,
                nullableTraversal = compiled.nullableTraversal,
            )
        }
    }
}

/** Compiles a production-shaped response-key path against an alias-preserving object fragment. */
fun Schema.fromObjectField(
    objectFragmentSource: String,
    responsePath: List<String>,
    variableField: Schema.ObjectField? = null,
): FromObjectField =
    FromObjectField.compile(
        schema = this as GJSchema,
        objectFragmentSource = objectFragmentSource,
        responsePath = responsePath,
        variableField = variableField,
    )

private data class CompiledPath(
    val keys: List<Value.Key>,
    val terminalType: TypeExpr<Schema.OutputType>,
    val nullableTraversal: Boolean,
)

private data class MatchingField(
    val key: Value.Key,
    val typeExpr: TypeExpr<Schema.OutputType>,
    val subselections: List<SpecSelection>,
    val lossyCondition: Pair<Schema.CompositeType, Schema.CompositeType>?,
)

private fun GJSchema.compilePath(
    typeInScope: Schema.CompositeType,
    selections: List<SpecSelection>,
    responsePath: List<String>,
    index: Int = 0,
    keys: List<Value.Key> = emptyList(),
    nullableTraversal: Boolean = false,
): CompiledPath {
    val responseKey = responsePath[index]
    val matches =
        matchingFields(
            selections = selections,
            typeInScope = typeInScope,
            responseKey = responseKey,
        )
    require(matches.isNotEmpty()) {
        "fromObjectField path ${responsePath.joinToString(".")} has no selection for response " +
            "key $responseKey"
    }

    matches.firstNotNullOfOrNull(MatchingField::lossyCondition)?.let { (from, to) ->
        throw IllegalArgumentException(
            "fromObjectField path ${responsePath.joinToString(".")} traverses lossy type " +
                "condition ${from.typeName} to ${to.typeName}",
        )
    }

    val distinctKeys = matches.map(MatchingField::key).distinct()
    require(distinctKeys.size == 1) {
        "fromObjectField response key $responseKey does not identify one canonical field and " +
            "argument tuple"
    }
    val key = distinctKeys.single()
    val distinctTypes = matches.map(MatchingField::typeExpr).distinct()
    require(distinctTypes.size == 1) {
        "fromObjectField response key $responseKey does not identify one output type"
    }
    val typeExpr = distinctTypes.single()
    val isTerminal = index == responsePath.lastIndex

    if (isTerminal) {
        require(typeExpr.baseType is Schema.SimpleType) {
            "fromObjectField path ${responsePath.joinToString(".")} must terminate at a scalar " +
                "or enum"
        }
        return CompiledPath(
            keys = keys + key,
            terminalType = typeExpr,
            nullableTraversal = nullableTraversal,
        )
    }

    require(typeExpr is TypeExpr.Named && typeExpr.baseType is Schema.CompositeType) {
        "fromObjectField path ${responsePath.joinToString(".")} cannot traverse list or simple " +
            "field ${key.field.containingType.typeName}/${key.field.fieldName}"
    }
    return compilePath(
        typeInScope = typeExpr.baseType as Schema.CompositeType,
        selections = matches.flatMap(MatchingField::subselections),
        responsePath = responsePath,
        index = index + 1,
        keys = keys + key,
        nullableTraversal = nullableTraversal || typeExpr.isNullable,
    )
}

private fun GJSchema.matchingFields(
    selections: List<SpecSelection>,
    typeInScope: Schema.CompositeType,
    responseKey: String,
    lossyCondition: Pair<Schema.CompositeType, Schema.CompositeType>? = null,
): List<MatchingField> =
    selections.flatMap { selection ->
        when (selection) {
            is SpecSelection.Field -> {
                if ((selection.alias ?: selection.fieldName) != responseKey) {
                    emptyList()
                } else {
                    val field = field(typeInScope.typeName, selection.fieldName)
                    listOf(
                        MatchingField(
                            key = Value.Key.of(field, selection.arguments),
                            typeExpr = field.typeExpr,
                            subselections = selection.subselections.orEmpty(),
                            lossyCondition = lossyCondition,
                        ),
                    )
                }
            }

            is SpecSelection.InlineFragment -> {
                val condition = selection.typeCondition
                val relation =
                    condition?.let { relation(typeInScope, it) }
                val nextLossyCondition =
                    lossyCondition
                        ?: condition
                            ?.takeIf {
                                relation in
                                    setOf(
                                        Schema.TypeRelation.WIDER_THAN,
                                        Schema.TypeRelation.COPARENT,
                                    )
                            }?.let { typeInScope to it }
                matchingFields(
                    selections = selection.selections,
                    typeInScope = condition ?: typeInScope,
                    responseKey = responseKey,
                    lossyCondition = nextLossyCondition,
                )
            }
        }
    }

@Suppress("UNCHECKED_CAST")
private fun TypeExpr<Schema.OutputType>.asInputType(): TypeExpr<Schema.InputType> {
    require(baseType is Schema.InputType)
    return this as TypeExpr<Schema.InputType>
}

private tailrec fun compatibleTypes(
    locationType: TypeExpr<Schema.InputType>,
    sourceType: TypeExpr<Schema.InputType>,
    nullableTraversal: Boolean,
    locationHasDefault: Boolean,
): Boolean {
    val sourceEffectivelyNullable = nullableTraversal || sourceType.isNullable
    return when {
        locationHasDefault && sourceEffectivelyNullable ->
            compatibleTypes(
                locationType = locationType.withNullable(true),
                sourceType = sourceType,
                nullableTraversal = nullableTraversal,
                locationHasDefault = false,
            )

        !locationType.isNullable -> {
            val unwrappedLocation = locationType.withNullable(true)
            val unwrappedSource = sourceType.withNullable(true)
            when {
                unwrappedLocation is TypeExpr.List ->
                    compatibleTypes(
                        locationType = unwrappedLocation,
                        sourceType =
                            if (unwrappedSource is TypeExpr.List) {
                                unwrappedSource
                            } else {
                                sourceType
                            },
                        nullableTraversal = nullableTraversal,
                        locationHasDefault = false,
                    )
                sourceEffectivelyNullable -> false
                else ->
                    compatibleTypes(
                        locationType = unwrappedLocation,
                        sourceType = unwrappedSource,
                        nullableTraversal = nullableTraversal,
                        locationHasDefault = false,
                    )
            }
        }

        locationType is TypeExpr.List -> {
            val sourceElement =
                if (sourceType is TypeExpr.List) {
                    sourceType.elementType
                } else {
                    sourceType
                }
            compatibleTypes(
                locationType = locationType.elementType,
                sourceType = sourceElement,
                nullableTraversal = nullableTraversal,
                locationHasDefault = false,
            )
        }

        else ->
            sourceType is TypeExpr.Named &&
                locationType.baseType == sourceType.baseType
    }
}

private fun <T : Schema.Type> TypeExpr<T>.withNullable(nullable: Boolean): TypeExpr<T> =
    when (this) {
        is TypeExpr.Named -> TypeExpr.Named.of(baseType, nullable)
        is TypeExpr.List -> TypeExpr.List.of(elementType, nullable)
    }
