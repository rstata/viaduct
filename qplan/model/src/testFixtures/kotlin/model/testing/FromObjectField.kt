package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.ObjectEngineResult
import model.Fragment
import model.EngineInputData
import model.SourceSchemaAdapter
import model.spec.SpecSelection
import model.requireField
import model.requireQueryTypeDef
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
    internal val terminalType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    internal val nullableTraversal: Boolean,
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
        locationType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
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
            variableField: ViaductSchema.ObjectField?,
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

/**
 * One external `fromQueryField` declaration compiled for canonical registry construction.
 *
 * [responsePath] contains GraphQL response keys, including aliases. Composition validates that
 * path against the alias-preserving Query-fragment source, then retains only the canonical
 * alias-free key path for semantic reasoning.
 */
class FromQueryField private constructor(
    val responsePath: List<String>,
    internal val queryFragment: Fragment,
    internal val keyPath: List<ObjectEngineResult.Key>,
    internal val terminalType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    private val nullableTraversal: Boolean,
) : VariableDeclaration {
    internal fun mapVariables(
        transform: (Arguments.Variable) -> Arguments.Variable,
    ): FromQueryField =
        FromQueryField(
            responsePath = responsePath,
            queryFragment = queryFragment.mapVariables(transform),
            keyPath = keyPath.mapVariables(transform),
            terminalType = terminalType,
            nullableTraversal = nullableTraversal,
        )

    internal fun isCompatibleWith(
        locationType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
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
            queryFragmentSource: String,
            responsePath: List<String>,
            variableField: ViaductSchema.ObjectField?,
            bindings: Map<String, EngineInputData?>,
        ): FromQueryField {
            val compiled =
                FromObjectField.compile(
                    schema = schema,
                    objectFragmentSource = queryFragmentSource,
                    responsePath = responsePath,
                    variableField = variableField,
                    bindings = bindings,
                )
            require(compiled.objectFragment.nominalType == schema.requireQueryTypeDef()) {
                "fromQueryField provider fragment must be rooted at Query"
            }
            return FromQueryField(
                responsePath = compiled.responsePath,
                queryFragment = compiled.objectFragment,
                keyPath = compiled.keyPath,
                terminalType = compiled.terminalType,
                nullableTraversal = compiled.nullableTraversal,
            )
        }
    }
}

/** Compiles a production-shaped response-key path against an alias-preserving object fragment. */
fun ViaductSchema.fromObjectField(
    objectFragmentSource: String,
    responsePath: List<String>,
    variableField: ViaductSchema.ObjectField? = null,
    bindings: Map<String, EngineInputData?> = emptyMap(),
): FromObjectField =
    FromObjectField.compile(
        schema = this as GJSchema,
        objectFragmentSource = objectFragmentSource,
        responsePath = responsePath,
        variableField = variableField,
        bindings = bindings,
    )

/** Compiles a production-shaped response-key path against an alias-preserving Query fragment. */
fun ViaductSchema.fromQueryField(
    queryFragmentSource: String,
    responsePath: List<String>,
    variableField: ViaductSchema.ObjectField? = null,
    bindings: Map<String, EngineInputData?> = emptyMap(),
): FromQueryField =
    FromQueryField.compile(
        schema = this as GJSchema,
        queryFragmentSource = queryFragmentSource,
        responsePath = responsePath,
        variableField = variableField,
        bindings = bindings,
    )

private data class CompiledPath(
    val keys: List<ObjectEngineResult.Key>,
    val terminalType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    val nullableTraversal: Boolean,
)

private data class MatchingField(
    val keys: List<ObjectEngineResult.Key>,
    val typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    val subselections: List<SpecSelection>,
    val lossyCondition: Pair<ViaductSchema.CompositeTypeDef, ViaductSchema.CompositeTypeDef>?,
)

private fun GJSchema.compilePath(
    typeInScope: ViaductSchema.CompositeTypeDef,
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
        require(typeExpr.baseTypeDef is ViaductSchema.SimpleTypeDef) {
            "fromObjectField path ${responsePath.joinToString(".")} must terminate at a scalar " +
                "or enum"
        }
        return CompiledPath(
            keys = keys + matchedKeys,
            terminalType = typeExpr,
            nullableTraversal = nullableTraversal,
        )
    }

    require(!typeExpr.isList && typeExpr.baseTypeDef is ViaductSchema.CompositeTypeDef) {
        "fromObjectField path ${responsePath.joinToString(".")} cannot traverse list or simple " +
            "field ${key.field.containingDef.name}/${key.field.name}"
    }
    return compilePath(
        typeInScope = typeExpr.baseTypeDef as ViaductSchema.CompositeTypeDef,
        selections = matches.flatMap(MatchingField::subselections),
        responsePath = responsePath,
        index = index + 1,
        keys = keys + matchedKeys,
        nullableTraversal = nullableTraversal || typeExpr.isNullable,
    )
}

private fun GJSchema.matchingFields(
    selections: List<SpecSelection>,
    typeInScope: ViaductSchema.CompositeTypeDef,
    responseKey: String,
    lossyCondition: Pair<ViaductSchema.CompositeTypeDef, ViaductSchema.CompositeTypeDef>? = null,
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
                            val bridgeType =
                                field.type.baseTypeDef as ViaductSchema.CompositeTypeDef
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
private fun ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>.asInputType(): ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef> {
    require(baseTypeDef is ViaductSchema.InputTypeDef)
    return this as ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>
}

internal tailrec fun compatibleTypes(
    locationType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    sourceType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
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
            val sourceElement =
                sourceType.unwrapList()
                    ?: sourceType.withNullable(false)
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

private fun <T : ViaductSchema.TypeDef> ViaductSchema.TypeExpr<T>.withNullable(nullable: Boolean): ViaductSchema.TypeExpr<T> {
    return if (!isList) {
        ViaductSchema.TypeExpr(baseTypeDef, nullable)
    } else {
        val wrappers = listNullable.copy()
        if (nullable) wrappers.set(0) else wrappers.clear(0)
        ViaductSchema.TypeExpr(baseTypeDef, baseTypeNullable, wrappers)
    }
}
