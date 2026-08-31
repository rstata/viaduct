package semantics.resolver26

import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.SelectionForest
import model.Stamp
import model.engineObjectDataOf
import model.fetchBindings
import model.groundKey
import model.outputType
import model.requireQueryTypeDef
import model.registry.FieldResolver
import model.registry.VariableDefinition
import model.schemaType
import model.toMaterializeSelectionForest
import model.usedVariables
import semantics.correctresolution.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData

/** Invokes and publishes one already-installed field resolver instance. */
internal class FieldResolverTask(
    private val world: Assumptions,
    private val support: Resolver26Support,
    private val path: List<PathComponent>,
    private val groundedSelection: ObjectSelection,
    private val resolver: FieldResolver,
    private val inputMaterializeSelections: MaterializeSelectionForest,
    private val target: ObjectEngineResult,
    private val cell: EngineResultCell,

    // The following arguments are passed for instrumentation-purposes only
    private val variableArgumentCount: Int,
    private val variableSourceSelectionStamps: Set<Stamp.Occurrence>,
) {
    suspend fun run() {
        context(world, support) {
            val groundKey = groundedSelection.groundKey()
            if (groundKey.arguments.argumentsContainErrorValue()) {
                val errorResult = ErrorEngineResult.of(EngineErrorData.of())
                cell.getValue().complete(errorResult)
                return
            }

            val coordinate = path + groundKey
            val input: EngineObjectData.Sync =
                target.materializeResolverInput(
                    selections = inputMaterializeSelections,
                    reader = coordinate,
                    resultPath = path,
                )

            val resolverArguments = groundKey.arguments as Arguments.Resolved
            val constructionDemand: SelectionForest = groundedSelection.subselections
            val invocationDemand: SelectionForest = constructionDemand.successorDemand()
            val queryValue =
                resolver.resolveQueryFragment(
                    coordinate = coordinate,
                    arguments = resolverArguments,
                )

            support.observeApplication(
                Resolver26ApplicationObservation(
                    occurrencePath = coordinate,
                    field = groundKey.field,
                    input = input,
                    arguments = resolverArguments,
                    suppliedDemand = invocationDemand,
                    variableArgumentCount = variableArgumentCount,
                    occurrenceStamp = groundKey.stamp as? Stamp.Occurrence,
                    variableSourceSelectionStamps = variableSourceSelectionStamps,
                ),
            )

            val fieldValue: EngineOutputData? =
                resolver(
                    input = input,
                    queryValue = queryValue,
                    arguments = resolverArguments,
                    selections = invocationDemand,
                )

            val passiveValue: EngineResult? =
                fieldValue.resolvePassiveValues(
                    expectedType = groundKey.field.outputType,
                    path = coordinate,
                    invocationDemand = invocationDemand,
                    constructionDemand = constructionDemand,
                )

            cell.getValue().complete(passiveValue)
        }
    }
}

context(world: Assumptions, support: Resolver26Support)
private suspend fun FieldResolver.resolveQueryFragment(
    coordinate: List<PathComponent>,
    arguments: Arguments.Resolved,
): EngineObjectData.Sync {
    val queryFragment = instantiateQueryFragmentAt(coordinate)
    if (queryFragment.constructionSelections.isEmpty()) {
        return engineObjectDataOf(world.schema.requireQueryTypeDef())
    }

    queryFragment.constructionSelections.usedVariables().forEach { variable ->
        val template = Arguments.Variable.of(variable.field, variable.variableName)
        val definition = variables.getValue(template)
        require(definition is VariableDefinition.FromArgument) {
            "Resolver26 query fragments do not support FromObjectField variables"
        }
        world.bindVariable(variable, definition.read(arguments))
    }

    val groundedSelections = queryFragment.materializeSelections.fetchAllBindings()
    val source = world.resolverRegistry.createRootQueryInput()
    val queryResult =
        ObjectEngineResult.of(
            type = source.schemaType,
            mutable = true,
        )
    val orchestration =
        ObjectOrchestrationTask(
            world = world,
            support = support,
            path = emptyList(),
            source = source,
            target = queryResult,
            initialDemand = groundedSelections.constructionSelections(),
        )
    orchestration.prepare()
    orchestration.launch()
    world.queryValues[coordinate.toList()] = queryResult
    return queryResult.materializeResolverInput(
        selections = groundedSelections,
        reader = coordinate,
        resultPath = emptyList(),
    )
}

context(world: Assumptions)
private suspend fun MaterializeSelectionForest.fetchAllBindings(): MaterializeSelectionForest {
    val selections =
        buildList<MaterializeSelection> {
            this@fetchAllBindings.forEach(::add)
        }
    return selections
        .map { selection ->
            val groundedKey =
                ObjectEngineResult.Key.of(
                    field = selection.key.field,
                    arguments = selection.key.arguments.fetchBindings(selection.key.field),
                )
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key = groundedKey,
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.fetchAllBindings(),
            )
        }.toMaterializeSelectionForest()
}
