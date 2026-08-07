package model.testing

import model.Fragment
import model.ObjectSelection
import model.ObjectSelectionForest
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.registry.FieldResolver
import model.registry.FieldResolverApplicationObserver
import model.registry.FieldResolverFunction
import model.registry.VariableDefinition
import model.objectKey
import model.selectionForestOf

/**
 * A raw field-resolver definition accepted only by test-fixture composition.
 *
 * Definitions may be transformed while external coordinates are lowered. Registry assembly
 * consumes them and exposes only fully assembled canonical [FieldResolver] values.
 */
class FieldResolverDefinition private constructor(
    val objectFragment: Fragment,
    private val objectFragmentFunction: (Value.Arguments) -> Fragment,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    fun objectFragment(arguments: Value.Arguments): Fragment =
        objectFragmentFunction(arguments)

    fun mapOutput(transform: (Value.Output?) -> Value.Output?): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            objectFragmentFunction = objectFragmentFunction,
            function = { input, arguments -> transform(function(input, arguments)) },
            projectionDemand = projectionDemand,
            applicationObserver = applicationObserver,
        )

    fun mapDemand(
        transform: (SelectionForest) -> SelectionForest,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            objectFragmentFunction = objectFragmentFunction,
            function = function,
            projectionDemand = { demand -> transform(projectionDemand(demand)) },
            applicationObserver = applicationObserver,
        )

    fun mapObjectFragment(transform: (Fragment) -> Fragment): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = transform(objectFragment),
            objectFragmentFunction = { arguments ->
                transform(objectFragment(arguments))
            },
            function = function,
            projectionDemand = projectionDemand,
            applicationObserver = applicationObserver,
        )

    fun observeApplications(
        observer: FieldResolverApplicationObserver,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            objectFragmentFunction = objectFragmentFunction,
            function = function,
            projectionDemand = projectionDemand,
            applicationObserver = { input, arguments, selections ->
                applicationObserver(input, arguments, selections)
                observer(input, arguments, selections)
            },
        )

    internal fun assemble(
        objectType: Schema.ObjectType,
        variables: Map<Value.Variable.Template, VariableDefinition>,
        predecessorDemand: Fragment,
        predecessorDemandFunction: (Fragment) -> Fragment,
        validateObjectFragment: (Fragment) -> Unit,
    ): FieldResolver {
        fun normalize(
            fragment: Fragment,
            role: String,
        ): ObjectSelectionForest {
            require(fragment.nominalType == objectType) {
                "$role type ${fragment.nominalType.typeName} does not match ${objectType.typeName}"
            }
            val selections =
                fragment.subselections
                    .filter { selection -> objectType in selection.possibleTypes }
                    .groupBy { selection -> selection.objectKey(objectType) }
                    .entries
                    .map { (key, occurrences) ->
                        ObjectSelection.of(
                            key = key,
                            possibleTypes = setOf(objectType),
                            subselections =
                                occurrences.flatMap { occurrence ->
                                    occurrence.subselections
                                },
                        )
                    }
            return ObjectSelectionForest.of(objectType, selections)
        }

        fun exactObjectFragment(arguments: Value.Arguments): Fragment =
            objectFragment(arguments).also(validateObjectFragment)

        return FieldResolver.of(
            objectFragment = normalize(objectFragment, "Object fragment"),
            variables = variables,
            predecessorDemand = normalize(predecessorDemand, "Predecessor demand"),
            objectFragmentFunction = { arguments ->
                normalize(exactObjectFragment(arguments), "Exact object fragment")
            },
            predecessorDemandFunction = { arguments ->
                normalize(
                    predecessorDemandFunction(exactObjectFragment(arguments)),
                    "Exact predecessor demand",
                )
            },
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
                objectFragmentFunction = { objectFragment },
                function = function,
                projectionDemand = { it },
                applicationObserver = { _, _, _ -> },
            )

        fun ofArgumentRetargeting(
            objectFragment: Fragment,
            retargetArguments: (Value.Key, Value.Arguments) -> Value.Arguments,
            function: FieldResolverFunction,
        ): FieldResolverDefinition =
            FieldResolverDefinition(
                objectFragment = objectFragment,
                objectFragmentFunction = { arguments ->
                    objectFragment.retargetArguments(arguments, retargetArguments)
                },
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

private fun Fragment.retargetArguments(
    resolverArguments: Value.Arguments,
    retargetArguments: (Value.Key, Value.Arguments) -> Value.Arguments,
): Fragment =
    Fragment.of(
        nominalType = nominalType,
        subselections = subselections.retargetArguments(resolverArguments, retargetArguments),
    )

private fun SelectionForest.retargetArguments(
    resolverArguments: Value.Arguments,
    retargetArguments: (Value.Key, Value.Arguments) -> Value.Arguments,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    Value.Key.of(
                        selection.key.field,
                        retargetArguments(selection.key, resolverArguments),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections =
                    selection.subselections.retargetArguments(
                        resolverArguments,
                        retargetArguments,
                    ),
            ),
        )
    }
