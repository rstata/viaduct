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
import model.ResolverOccurrenceId
import model.SelectionForest
import model.engineObjectDataOf
import model.outputType
import model.requireQueryTypeDef
import model.registry.FieldResolver
import model.schemaType
import model.toMaterializeSelectionForest
import model.usedVariables
import semantics.correctresolution.argumentsContainErrorValue
import model.registry.VariableDefinition
import viaduct.engine.api.EngineObjectData

/** Invokes and publishes one already-installed field resolver instance. */
internal class FieldResolverTask(
    private val world: Assumptions,
    private val support: Resolver26Support,
    private val root: ObjectEngineResult,
    private val path: List<PathComponent>,
    private val selection: ObjectSelection,
    private val groundedArguments: Arguments.Ground,
    private val resolver: FieldResolver,
    private val resolverOccurrenceId: ResolverOccurrenceId,
    private val inputMaterializeSelections: MaterializeSelectionForest,
    private val target: ObjectEngineResult,
    private val cell: EngineResultCell,

    // The following arguments are passed for instrumentation-purposes only
    private val variableArgumentCount: Int,
    private val variableResolverOccurrenceIds: Set<ResolverOccurrenceId>,
) {
    suspend fun run() {
        context(world, support) {
            val objectKey = selection.key
            if (groundedArguments.argumentsContainErrorValue()) {
                val errorResult = ErrorEngineResult.of(EngineErrorData.of())
                cell.getValue().complete(errorResult)
                return
            }

            val coordinate = path + objectKey
            val input: EngineObjectData.Sync =
                target.materializeResolverInput(
                    selections = inputMaterializeSelections,
                    reader = coordinate,
                    resultPath = path,
                )

            val resolverArguments = groundedArguments as Arguments.Resolved
            val constructionDemand: SelectionForest = selection.subselections
            val invocationDemand: SelectionForest = constructionDemand.successorDemand()
            val queryValue =
                resolver.resolveQueryFragment(
                    coordinate = coordinate,
                    resolverOccurrenceId = resolverOccurrenceId,
                    arguments = resolverArguments,
                )

            support.observeApplication(
                Resolver26ApplicationObservation(
                    occurrencePath = coordinate,
                    field = objectKey.field,
                    input = input,
                    arguments = resolverArguments,
                    suppliedDemand = invocationDemand,
                    resolverOccurrenceId = resolverOccurrenceId,
                    variableArgumentCount = variableArgumentCount,
                    variableResolverOccurrenceIds = variableResolverOccurrenceIds,
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
                    root = root,
                    expectedType = objectKey.field.outputType,
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
    resolverOccurrenceId: ResolverOccurrenceId,
    arguments: Arguments.Resolved,
): EngineObjectData.Sync {
    val queryFragment = instantiateQueryFragment(resolverOccurrenceId)
    if (queryFragment.constructionSelections.isEmpty()) {
        return engineObjectDataOf(world.schema.requireQueryTypeDef())
    }

    queryFragment.constructionSelections.usedVariables().forEach { variable ->
        val template = Arguments.Variable.of(variable.field, variable.variableName)
        val definition = variables.getValue(template)
        val instanceId = requireNotNull(variable.instanceId)
        when (definition) {
            is VariableDefinition.FromArgument -> {
                if (!world.isBound(instanceId)) {
                    world.bindVariable(instanceId, definition.read(arguments))
                }
            }

            is VariableDefinition.FromObjectField -> {
                world.fetchBinding(instanceId)
            }

            is VariableDefinition.FromQueryField -> {
                world.fetchBinding(instanceId)
            }
        }
    }

    val symbolicSelections = queryFragment.materializeSelections
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
            root = queryResult,
            path = emptyList(),
            source = source,
            target = queryResult,
            initialDemand = symbolicSelections.constructionSelections(),
        )
    orchestration.prepare()
    orchestration.launch()
    world.queryValues[resolverOccurrenceId] = queryResult
    return queryResult.materializeResolverInput(
        selections = symbolicSelections,
        reader = coordinate,
        resultPath = emptyList(),
    )
}
