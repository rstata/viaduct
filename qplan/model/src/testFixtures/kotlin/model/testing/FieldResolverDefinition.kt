package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.Arguments

import model.Fragment
import model.EngineOutputData
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.SelectionForest
import model.materializeSelectionForestOf
import model.objectKey
import model.registry.FieldResolver
import model.registry.FieldResolverApplicationObserver
import model.registry.NonselectiveFieldResolverFunction
import model.registry.SelectiveFieldResolverFunction
import model.registry.VariableDefinition
import model.merge
import model.selectionForestOf
import model.variableTemplates
import viaduct.engine.api.EngineObjectData

/**
 * A raw field-resolver definition accepted only by test-fixture composition.
 *
 * Definitions may be transformed while external coordinates are lowered. Registry assembly
 * consumes them and exposes only fully assembled canonical [FieldResolver] values.
 */
class FieldResolverDefinition private constructor(
    val objectFragment: Fragment,
    val queryFragment: Fragment?,
    private val function: SelectiveFieldResolverFunction,
    private val selective: Boolean,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    fun mapOutput(
        transform: (EngineOutputData?) -> EngineOutputData?,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = { input, queryValue, arguments, selections ->
                transform(function(input, queryValue, arguments, selections))
            },
            selective = selective,
            projectionDemand = projectionDemand,
            applicationObserver = applicationObserver,
        )

    fun mapDemand(
        transform: (SelectionForest) -> SelectionForest,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = function,
            selective = selective,
            projectionDemand = { demand -> transform(projectionDemand(demand)) },
            applicationObserver = applicationObserver,
        )

    fun mapObjectFragment(transform: (Fragment) -> Fragment): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = transform(objectFragment),
            queryFragment = queryFragment,
            function = function,
            selective = selective,
            projectionDemand = projectionDemand,
            applicationObserver = applicationObserver,
        )

    fun mapQueryFragment(transform: (Fragment) -> Fragment): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment?.let(transform),
            function = function,
            selective = selective,
            projectionDemand = projectionDemand,
            applicationObserver = applicationObserver,
        )

    fun observeApplications(
        observer: FieldResolverApplicationObserver,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = function,
            selective = selective,
            projectionDemand = projectionDemand,
            applicationObserver = { input, arguments, selections ->
                applicationObserver(input, arguments, selections)
                observer(input, arguments, selections)
            },
        )

    internal fun assemble(
        field: ViaductSchema.ObjectField,
        queryType: ViaductSchema.Object,
        variables: Map<Arguments.Variable, VariableDefinition>,
        validateObjectFragment: (Fragment) -> Unit,
    ): FieldResolver {
        val objectType = field.containingDef

        fun normalize(
            fragment: Fragment,
            expectedType: ViaductSchema.Object,
            role: String,
        ): MaterializeSelectionForest {
            require(fragment.nominalType == expectedType) {
                "$role type ${fragment.nominalType.name} does not match ${expectedType.name}"
            }
            return fragment.materializeSelections.flatMap { selection ->
                if (expectedType !in selection.possibleTypes) {
                    materializeSelectionForestOf()
                } else {
                    materializeSelectionForestOf(
                        MaterializeSelection.of(
                            responseKey = selection.responseKey,
                            key = selection.key.objectKey(expectedType),
                            possibleTypes = setOf(expectedType),
                            subselections = selection.subselections,
                        ),
                    )
                }
            }
        }

        validateObjectFragment(objectFragment)
        val normalizedQueryFragment =
            queryFragment?.let { fragment ->
                require(fragment.nominalType == queryType) {
                    "Query fragment type ${fragment.nominalType.name} does not match ${queryType.name}"
                }
                normalize(fragment, queryType, "Query fragment")
            } ?: materializeSelectionForestOf()

        val normalizedObjectFragment = normalize(objectFragment, objectType, "Object fragment")
        return if (selective) {
            FieldResolver.ofSelective(
                field = field,
                objectFragment = normalizedObjectFragment,
                queryFragment = normalizedQueryFragment,
                queryType = queryType,
                variables = variables,
                function = { input, queryValue, arguments, selections ->
                    function(input, queryValue, arguments, projectionDemand(selections))
                },
                applicationObserver = applicationObserver,
            )
        } else {
            FieldResolver.of(
                field = field,
                objectFragment = normalizedObjectFragment,
                queryFragment = normalizedQueryFragment,
                queryType = queryType,
                variables = variables,
                function = { input, queryValue, arguments ->
                    function(input, queryValue, arguments, selectionForestOf())
                },
                projectionDemand = projectionDemand,
                applicationObserver = applicationObserver,
            )
        }
    }

    companion object {
        fun of(
            objectFragment: Fragment,
            queryFragment: Fragment?,
            function: NonselectiveFieldResolverFunction,
        ): FieldResolverDefinition =
            FieldResolverDefinition(
                objectFragment = objectFragment,
                queryFragment = queryFragment,
                function = { input, queryValue, arguments, _ ->
                    function(input, queryValue, arguments)
                },
                selective = false,
                projectionDemand = { it },
                applicationObserver = { _, _, _ -> },
            )

        fun ofSelective(
            objectFragment: Fragment,
            queryFragment: Fragment?,
            function: SelectiveFieldResolverFunction,
        ): FieldResolverDefinition =
            FieldResolverDefinition(
                objectFragment = objectFragment,
                queryFragment = queryFragment,
                function = function,
                selective = true,
                projectionDemand = { it },
                applicationObserver = { _, _, _ -> },
            )

        fun of(
            objectFragment: Fragment,
            function: (EngineObjectData.Sync, Arguments.Resolved) -> EngineOutputData?,
        ): FieldResolverDefinition =
            of(
                objectFragment = objectFragment,
                queryFragment = null,
                function = { input, _, arguments -> function(input, arguments) },
            )
    }
}

fun fieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: NonselectiveFieldResolverFunction,
): FieldResolverDefinition =
    FieldResolverDefinition.of(objectFragment, queryFragment, function)

fun fieldResolverOf(
    objectFragment: Fragment,
    function: (EngineObjectData.Sync, Arguments.Resolved) -> EngineOutputData?,
): FieldResolverDefinition = FieldResolverDefinition.of(objectFragment, function)

fun selectiveFieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: SelectiveFieldResolverFunction,
): FieldResolverDefinition =
    FieldResolverDefinition.ofSelective(objectFragment, queryFragment, function)

fun selectiveFieldResolverOf(
    objectFragment: Fragment,
    function: (
        EngineObjectData.Sync,
        Arguments.Resolved,
        SelectionForest,
    ) -> EngineOutputData?,
): FieldResolverDefinition =
    FieldResolverDefinition.ofSelective(
        objectFragment = objectFragment,
        queryFragment = null,
        function = { input, _, arguments, selections -> function(input, arguments, selections) },
    )
