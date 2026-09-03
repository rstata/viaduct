package semantics.resolver26

import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
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
import model.registry.ResolverFragment
import model.schemaType
import semantics.correctresolution.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData

/** Invokes and publishes one already-installed field resolver instance. */
internal class FieldResolverTask(
    private val operation: Resolver26OperationContext,
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
    private val world: Assumptions = operation.world

    suspend fun run() {
        context(operation, world) {
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
            val queryValue = operation.queryValuesState.fetch(resolverOccurrenceId)

            operation.resolverObserver.onResolverApplication(
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

context(operation: Resolver26OperationContext)
internal suspend fun ResolverFragment.resolveQueryFragment(
    coordinate: List<PathComponent>,
): EngineObjectData.Sync {
    if (constructionSelections.isEmpty()) {
        return engineObjectDataOf(operation.schema.requireQueryTypeDef())
    }

    val symbolicSelections = materializeSelections
    val source = operation.resolverRegistry.createRootQueryInput()
    val queryResult =
        ObjectEngineResult.of(
            type = source.schemaType,
            mutable = true,
        )
    val orchestration =
        ObjectOrchestrationTask(
            operation = operation,
            root = queryResult,
            path = emptyList(),
            source = source,
            target = queryResult,
            initialDemand = symbolicSelections.constructionSelections(),
    )
    orchestration.prepare()
    operation.resolverObserver.onQueryFragmentResult(resolverOccurrenceId, queryResult)
    orchestration.launch()
    queryResult.completeProviderBindings(
        reads =
            pathVariableDefinitions.map { definition ->
                ProviderDefinitionRead(definition, coordinate)
            },
    )
    return queryResult.materializeResolverInput(
        selections = symbolicSelections,
        reader = coordinate,
        resultPath = emptyList(),
    )
}
