package semantics.resolver26

import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
import model.InclusionCondition
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.SelectionForest
import model.engineObjectDataOf
import model.guardedBy
import model.outputType
import model.requireQueryTypeDef
import model.registry.ResolverFragment
import model.registry.VariableDefinition
import model.schemaType
import model.usedVariables
import model.variableArgumentNames
import semantics.correctresolution.argumentsContainErrorValue
import semantics.shared.fetchGroundedArguments
import semantics.shared.fetchIncluded
import viaduct.engine.api.EngineObjectData

/** Invokes and publishes one already-installed field resolver instance. */
internal class FieldResolverTask(
    private val operationContext: Resolver26OperationContext,
    private val oerOccurrenceContext: OEROccurrenceContext,
    private val fieldResolverOccurrenceContext: FieldResolverOccurrenceContext,
    private val cell: EngineResultCell,
) {
    private val world: Assumptions = operationContext.world

    init {
        val selection = fieldResolverOccurrenceContext.selection
        val resolver = fieldResolverOccurrenceContext.resolver
        val resolverOccurrenceId = fieldResolverOccurrenceContext.resolverOccurrenceId
        require(selection.key.field.containingDef == oerOccurrenceContext.target.type) {
            "Resolver selection does not belong to its target occurrence"
        }
        require(resolver.field == selection.key.field) {
            "Resolver field ${resolver.field.name} does not match ${selection.key.field.name}"
        }
        require(
            resolverOccurrenceId ==
                ResolverOccurrenceId.at(
                    oerOccurrenceContext.root,
                    oerOccurrenceContext.coordinate(selection.key),
                ),
        ) {
            "Resolver occurrence ID does not match its target occurrence and selection"
        }
        require(oerOccurrenceContext.target.getCell(selection.key) === cell) {
            "Resolver cell does not belong to its target occurrence and selection"
        }
    }

    suspend fun run() {
        context(operationContext, world, operationContext.cycleChecker) {
            val selection = fieldResolverOccurrenceContext.selection
            val objectKey = selection.key
            val groundedArguments = objectKey.fetchGroundedArguments()
            completeFromArgumentBindings(groundedArguments)
            val activated = selection.inclusionCondition.fetchIncluded()
            cell.setActivated(activated)
            if (!activated) return
            if (groundedArguments.argumentsContainErrorValue()) {
                val errorResult = ErrorEngineResult.of(EngineErrorData.of())
                cell.getValue().complete(errorResult)
                return
            }

            val coordinate = oerOccurrenceContext.coordinate(objectKey)
            val input: EngineObjectData.Sync =
                oerOccurrenceContext.target.materializeResolverInput(
                    selections = fieldResolverOccurrenceContext.inputMaterializeSelections,
                    reader = coordinate,
                    resultPath = oerOccurrenceContext.path,
                )

            val resolverArguments = groundedArguments as Arguments.Resolved
            val constructionDemand: SelectionForest = selection.subselections
            val invocationDemand: SelectionForest = constructionDemand.successorDemand()
            val queryValue =
                operationContext.queryValuesState.fetch(
                    fieldResolverOccurrenceContext.resolverOccurrenceId,
                )
            val variableArgumentCount =
                selection.key.arguments.variableArgumentNames().size
            val variableResolverOccurrenceIds =
                selection.key.arguments
                    .usedVariables()
                    .mapNotNullTo(linkedSetOf()) { variable ->
                        variable.instanceId?.resolverOccurrenceId
                    }

            operationContext.resolverObserver.onResolverApplication(
                Resolver26ApplicationObservation(
                    occurrencePath = coordinate,
                    field = objectKey.field,
                    input = input,
                    inputSelections = fieldResolverOccurrenceContext.inputMaterializeSelections,
                    arguments = resolverArguments,
                    suppliedDemand = invocationDemand,
                    resolverOccurrenceId =
                        fieldResolverOccurrenceContext.resolverOccurrenceId,
                    variableArgumentCount = variableArgumentCount,
                    variableResolverOccurrenceIds = variableResolverOccurrenceIds,
                ),
            )

            val fieldValue: EngineOutputData? =
                fieldResolverOccurrenceContext.resolver(
                    input = input,
                    queryValue = queryValue,
                    arguments = resolverArguments,
                    selections = invocationDemand,
                )

            val passiveValue: EngineResult? =
                fieldValue.resolvePassiveValues(
                    root = oerOccurrenceContext.root,
                    expectedType = objectKey.field.outputType,
                    path = coordinate,
                    invocationDemand = invocationDemand,
                    constructionDemand = constructionDemand,
                    parent = oerOccurrenceContext,
                )

            cell.getValue().complete(passiveValue)
        }
    }

    // Fills FromArgument bindings that were declared while their owning resolver key was symbolic.
    // Bindings for already-ground owners received their values during binding declaration.
    private fun completeFromArgumentBindings(groundedArguments: Arguments.Ground) {
        if (fieldResolverOccurrenceContext.selection.key is ObjectEngineResult.GroundKey) return
        fieldResolverOccurrenceContext.variableDefinitions.forEach { variableDefinition ->
            if (variableDefinition.definition !is VariableDefinition.FromArgument) {
                return@forEach
            }
            val definition = variableDefinition.definition as VariableDefinition.FromArgument
            operationContext.variableBindingsState.completeBinding(
                requireNotNull(variableDefinition.variable.instanceId),
                bindingFor(groundedArguments, definition),
            )
        }
    }
}

context(operation: Resolver26OperationContext)
internal suspend fun ResolverFragment.resolveQueryFragment(
    coordinate: List<PathComponent>,
    inclusionCondition: InclusionCondition,
): EngineObjectData.Sync {
    if (constructionSelections.isEmpty()) {
        return engineObjectDataOf(operation.schema.requireQueryTypeDef())
    }

    val symbolicSelections = materializeSelections.guardedBy(inclusionCondition)
    val providerDemand =
        constructionSelections.providerDemand(
            definitions = pathVariableDefinitions,
            inclusionCondition = inclusionCondition,
        )
    val source = operation.resolverRegistry.createRootQueryInput()
    val queryResult =
        ObjectEngineResult.of(
            type = source.schemaType,
            mutable = true,
        )
    val orchestration =
        ObjectOrchestrationTask(
            operation = operation,
            occurrence =
                OEROccurrenceContext(
                    root = queryResult,
                    path = emptyList(),
                    target = queryResult,
                ),
            source = source,
            initialDemand = symbolicSelections.constructionSelections() + providerDemand,
    )
    orchestration.prepare()
    operation.resolverObserver.onQueryFragmentResult(resolverOccurrenceId, queryResult)
    orchestration.launch()
    queryResult.completeProviderBindings(
        reads =
            pathVariableDefinitions.map { definition ->
                ProviderDefinitionRead(
                    definition = definition,
                    readerPath = coordinate,
                    inclusionCondition = inclusionCondition,
                )
            },
    )
    return context(operation.cycleChecker) {
        queryResult.materializeResolverInput(
            selections = symbolicSelections,
            reader = coordinate,
            resultPath = emptyList(),
        )
    }
}
