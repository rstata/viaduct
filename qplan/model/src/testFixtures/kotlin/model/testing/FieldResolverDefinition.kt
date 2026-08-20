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
import model.registry.FieldResolverFunction
import model.registry.VariableDefinition
import model.merge
import model.selectionForestOf
import model.variableTemplates

/**
 * A raw field-resolver definition accepted only by test-fixture composition.
 *
 * Definitions may be transformed while external coordinates are lowered. Registry assembly
 * consumes them and exposes only fully assembled canonical [FieldResolver] values.
 */
class FieldResolverDefinition private constructor(
    val objectFragment: Fragment,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    fun mapOutput(
        transform: (EngineOutputData?) -> EngineOutputData?,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            function = { input, arguments -> transform(function(input, arguments)) },
            projectionDemand = projectionDemand,
            applicationObserver = applicationObserver,
        )

    fun mapDemand(
        transform: (SelectionForest) -> SelectionForest,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            function = function,
            projectionDemand = { demand -> transform(projectionDemand(demand)) },
            applicationObserver = applicationObserver,
        )

    fun mapObjectFragment(transform: (Fragment) -> Fragment): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = transform(objectFragment),
            function = function,
            projectionDemand = projectionDemand,
            applicationObserver = applicationObserver,
        )

    fun observeApplications(
        observer: FieldResolverApplicationObserver,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            function = function,
            projectionDemand = projectionDemand,
            applicationObserver = { input, arguments, selections ->
                applicationObserver(input, arguments, selections)
                observer(input, arguments, selections)
            },
        )

    internal fun assemble(
        field: ViaductSchema.ObjectField,
        variables: Map<Arguments.Variable, VariableDefinition>,
        validateObjectFragment: (Fragment) -> Unit,
    ): FieldResolver {
        val objectType = field.containingDef

        fun normalize(
            fragment: Fragment,
            role: String,
        ): MaterializeSelectionForest {
            require(fragment.nominalType == objectType) {
                "$role type ${fragment.nominalType.name} does not match ${objectType.name}"
            }
            return fragment.materializeSelections.flatMap { selection ->
                if (objectType !in selection.possibleTypes) {
                    materializeSelectionForestOf()
                } else {
                    materializeSelectionForestOf(
                        MaterializeSelection.of(
                            responseKey = selection.responseKey,
                            key = selection.key.objectKey(objectType),
                            possibleTypes = setOf(objectType),
                            subselections = selection.subselections,
                        ),
                    )
                }
            }
        }

        validateObjectFragment(objectFragment)

        return FieldResolver.of(
            field = field,
            objectFragment = normalize(objectFragment, "Object fragment"),
            variables = variables,
            function = function,
            projectionDemand = projectionDemand,
            applicationObserver = applicationObserver,
        )
    }

    companion object {
        fun of(
            objectFragment: Fragment,
            function: FieldResolverFunction,
        ): FieldResolverDefinition =
            FieldResolverDefinition(
                objectFragment = objectFragment,
                function = function,
                projectionDemand = { it },
                applicationObserver = { _, _, _ -> },
            )
    }
}

fun fieldResolverOf(
    objectFragment: Fragment,
    function: FieldResolverFunction,
): FieldResolverDefinition = FieldResolverDefinition.of(objectFragment, function)
