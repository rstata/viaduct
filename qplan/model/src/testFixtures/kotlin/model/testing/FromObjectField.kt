package model.testing

import model.Arguments
import model.ObjectEngineResult
import model.Fragment
import model.EngineInputData
import model.Schema
import model.SourceSchemaAdapter
import model.TypeExpr
import model.spec.SpecSelection
import model.requireField
import model.spec.flatten
import model.spec.flattenForMaterialization
import viaduct.graphql.utils.GraphQLTypeRelation

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
    internal val keyPath: List<ObjectEngineResult.Key>,
    internal val terminalType: TypeExpr<Schema.OutputTypeDef>,
    private val nullableTraversal: Boolean,
) : VariableDeclaration {
    internal fun mapVariables(
        transform: (Arguments.Variable) -> Arguments.Variable,
    ): FromObjectField =
        FromObjectField(
            responsePath = responsePath,
            objectFragment = objectFragment.mapVariables(transform),
            keyPath = keyPath.mapVariables(transform),
            terminalType = terminalType,
            nullableTraversal = nullableTraversal,
        )

    internal fun isCompatibleWith(
        locationType: TypeExpr<Schema.InputTypeDef>,
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
            bindings: Map<String, EngineInputData?>,
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
                    variableValues = bindings,
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
                        materializeSelections =
                            flattenForMaterialization(
                                schema,
                                parsed.nominalType,
                                parsed.selections,
                            ),
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
    bindings: Map<String, EngineInputData?> = emptyMap(),
): FromObjectField =
    FromObjectField.compile(
        schema = this as GJSchema,
        objectFragmentSource = objectFragmentSource,
        responsePath = responsePath,
        variableField = variableField,
        bindings = bindings,
    )

private data class CompiledPath(
    val keys: List<ObjectEngineResult.Key>,
    val terminalType: TypeExpr<Schema.OutputTypeDef>,
    val nullableTraversal: Boolean,
)

private data class MatchingField(
    val keys: List<ObjectEngineResult.Key>,
    val typeExpr: TypeExpr<Schema.OutputTypeDef>,
    val subselections: List<SpecSelection>,
    val lossyCondition: Pair<Schema.CompositeTypeDef, Schema.CompositeTypeDef>?,
)

private fun GJSchema.compilePath(
    typeInScope: Schema.CompositeTypeDef,
    selections: List<SpecSelection>,
    responsePath: List<String>,
    index: Int = 0,
    keys: List<ObjectEngineResult.Key> = emptyList(),
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
                "condition ${from.name} to ${to.name}",
        )
    }

    val distinctKeys = matches.map(MatchingField::keys).distinct()
    require(distinctKeys.size == 1) {
        "fromObjectField response key $responseKey does not identify one canonical field and " +
            "argument tuple"
    }
    val matchedKeys = distinctKeys.single()
    val key = matchedKeys.first()
    val distinctTypes = matches.map(MatchingField::typeExpr).distinct()
    require(distinctTypes.size == 1) {
        "fromObjectField response key $responseKey does not identify one output type"
    }
    val typeExpr = distinctTypes.single()
    val isTerminal = index == responsePath.lastIndex

    if (isTerminal) {
        require(typeExpr.baseTypeDef is Schema.SimpleTypeDef) {
            "fromObjectField path ${responsePath.joinToString(".")} must terminate at a scalar " +
                "or enum"
        }
        return CompiledPath(
            keys = keys + matchedKeys,
            terminalType = typeExpr,
            nullableTraversal = nullableTraversal,
        )
    }

    require(!typeExpr.isList && typeExpr.baseTypeDef is Schema.CompositeTypeDef) {
        "fromObjectField path ${responsePath.joinToString(".")} cannot traverse list or simple " +
            "field ${key.field.containingDef.name}/${key.field.name}"
    }
    return compilePath(
        typeInScope = typeExpr.baseTypeDef as Schema.CompositeTypeDef,
        selections = matches.flatMap(MatchingField::subselections),
        responsePath = responsePath,
        index = index + 1,
        keys = keys + matchedKeys,
        nullableTraversal = nullableTraversal || typeExpr.isNullable,
    )
}

private fun GJSchema.matchingFields(
    selections: List<SpecSelection>,
    typeInScope: Schema.CompositeTypeDef,
    responseKey: String,
    lossyCondition: Pair<Schema.CompositeTypeDef, Schema.CompositeTypeDef>? = null,
): List<MatchingField> =
    selections.flatMap { selection ->
        when (selection) {
            is SpecSelection.Field -> {
                if ((selection.alias ?: selection.fieldName) != responseKey) {
                    emptyList()
                } else {
                    val field = requireField(typeInScope.name, selection.fieldName)
                    val loweredNodeField = isLoweredNodeField(field)
                    val payloadSelection =
                        if (loweredNodeField) {
                            selection.subselections
                                .orEmpty()
                                .single() as SpecSelection.Field
                        } else {
                            null
                        }
                    val payloadKey =
                        payloadSelection?.let { payload ->
                            val bridgeType = field.type.baseTypeDef as Schema.Object
                            ObjectEngineResult.Key.of(
                                field =
                                    this@matchingFields.requireField(
                                        bridgeType.name,
                                        payload.fieldName,
                                    ),
                                arguments = payload.arguments,
                            )
                        }
                    listOf(
                        MatchingField(
                            keys =
                                listOf(ObjectEngineResult.Key.of(field, selection.arguments)) +
                                    listOfNotNull(payloadKey),
                            typeExpr = SourceSchemaAdapter(this@matchingFields).typeExpr(field),
                            subselections =
                                payloadSelection?.subselections.orEmpty()
                                    .takeIf { loweredNodeField }
                                    ?: selection.subselections.orEmpty(),
                            lossyCondition = lossyCondition,
                        ),
                    )
                }
            }

            is SpecSelection.InlineFragment -> {
                val condition = selection.typeCondition
                val relation =
                    condition?.let {
                        typeRelations.relationUnwrapped(
                            sourceCompositeType(typeInScope),
                            sourceCompositeType(it),
                        )
                    }
                val nextLossyCondition =
                    lossyCondition
                        ?: condition
                            ?.takeIf {
                                relation in
                                    setOf(
                                        GraphQLTypeRelation.WiderThan,
                                        GraphQLTypeRelation.Coparent,
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
private fun TypeExpr<Schema.OutputTypeDef>.asInputType(): TypeExpr<Schema.InputTypeDef> {
    require(baseTypeDef is Schema.InputTypeDef)
    return this as TypeExpr<Schema.InputTypeDef>
}

private tailrec fun compatibleTypes(
    locationType: TypeExpr<Schema.InputTypeDef>,
    sourceType: TypeExpr<Schema.InputTypeDef>,
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
            if (sourceEffectivelyNullable) {
                false
            } else {
                compatibleTypes(
                    locationType = unwrappedLocation,
                    sourceType = unwrappedSource,
                    nullableTraversal = nullableTraversal,
                    locationHasDefault = false,
                )
            }
        }

        locationType.isList -> {
            val locationElement = checkNotNull(locationType.unwrapList())
            val sourceElement = sourceType.unwrapList() ?: return false
            compatibleTypes(
                locationType = locationElement,
                sourceType = sourceElement,
                nullableTraversal = false,
                locationHasDefault = false,
            )
        }

        else ->
            !sourceType.isList &&
                locationType.baseTypeDef == sourceType.baseTypeDef
    }
}

private fun <T : Schema.TypeDef> TypeExpr<T>.withNullable(nullable: Boolean): TypeExpr<T> {
    val elementType = unwrapList()
    return if (elementType == null) {
        TypeExpr.Named.of(baseTypeDef, nullable)
    } else {
        TypeExpr.List.of(elementType, nullable)
    }
}
