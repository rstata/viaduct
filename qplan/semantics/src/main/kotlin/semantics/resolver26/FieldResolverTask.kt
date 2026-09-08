package semantics.resolver26

import kotlinx.coroutines.CancellationException
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
import model.VariableBinding
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
            var cachedGroundedArguments: Arguments.Ground? = null
            suspend fun fetchArguments(): Arguments.Ground =
                cachedGroundedArguments
                    ?: objectKey.fetchGroundedArguments().also { cachedGroundedArguments = it }
            var fromArgumentBindingsCompleted = false
            suspend fun completeFromArgumentBindingsIfNeeded() {
                if (fromArgumentBindingsCompleted) return
                completeFromArgumentBindings(fetchArguments())
                fromArgumentBindingsCompleted = true
            }
            val fromArgumentVariableIds =
                fieldResolverOccurrenceContext.variableDefinitions
                    .filter { definition ->
                        definition.definition is VariableDefinition.FromArgument
                    }.mapTo(linkedSetOf()) { definition ->
                        requireNotNull(definition.variable.instanceId)
                    }
            val activated =
                selection.inclusionCondition.include { variable ->
                    val variableId = requireNotNull(variable.instanceId)
                    if (
                        variableId in fromArgumentVariableIds &&
                        !operationContext.variableBindingsState.isBound(variableId)
                    ) {
                        completeFromArgumentBindingsIfNeeded()
                    }
                    when (
                        val binding =
                            operationContext.variableBindingsState.fetchBinding(variableId)
                    ) {
                        VariableBinding.Error -> error("Inclusion-condition variable failed")
                        is VariableBinding.Input ->
                            binding.value as? Boolean
                                ?: error("Inclusion-condition variable must contain a Boolean")
                    }
                }
            cell.setActivated(activated)
            if (!activated) return
            val groundedArguments = fetchArguments()
            completeFromArgumentBindingsIfNeeded()
            if (groundedArguments.argumentsContainErrorValue()) {
                completeVariablesProviderBindingsWithError()
                val errorResult = ErrorEngineResult.of(EngineErrorData.of())
                cell.getValue().complete(errorResult)
                return
            }

            val coordinate = oerOccurrenceContext.coordinate(objectKey)
            val resolverArguments = groundedArguments as Arguments.Resolved
            completeVariablesProviderBindings(resolverArguments)?.let { errorData ->
                cell.getValue().complete(ErrorEngineResult.of(errorData))
                return
            }
            val input: EngineObjectData.Sync =
                oerOccurrenceContext.target.materializeResolverInput(
                    selections = fieldResolverOccurrenceContext.inputMaterializeSelections,
                    reader = coordinate,
                    resultPath = oerOccurrenceContext.path,
                )

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

    // Calls the tenant provider once for this occurrence and publishes its complete binding set.
    // A provider failure becomes the owning field's error while also unblocking fragment work.
    private suspend fun completeVariablesProviderBindings(
        arguments: Arguments.Resolved,
    ): EngineErrorData? {
        val resolver = fieldResolverOccurrenceContext.resolver
        val provider = resolver.variablesProvider ?: return null
        val providerDefinitions =
            fieldResolverOccurrenceContext.variableDefinitions.filter { definition ->
                definition.definition == VariableDefinition.FromProvider
            }
        val expectedNames =
            providerDefinitions.mapTo(linkedSetOf()) { definition ->
                definition.variable.variableName
            }
        val values =
            try {
                provider(arguments)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                completeVariablesProviderBindingsWithError()
                return EngineErrorData.of(exception)
            }
        if (values.keys != expectedNames) {
            val extra = values.keys - expectedNames
            val missing = expectedNames - values.keys
            completeVariablesProviderBindingsWithError()
            error(
                buildString {
                    append("VariablesProvider returned invalid variables.")
                    if (extra.isNotEmpty()) append(" Extra keys: ${extra.joinToString(",")}")
                    if (missing.isNotEmpty()) append(" Missing keys: ${missing.joinToString(",")}")
                },
            )
        }
        providerDefinitions.forEach { definition ->
            operationContext.variableBindingsState.completeBinding(
                requireNotNull(definition.variable.instanceId),
                values.getValue(definition.variable.variableName),
            )
        }
        return null
    }

    private fun completeVariablesProviderBindingsWithError() {
        fieldResolverOccurrenceContext.variableDefinitions.forEach { definition ->
            if (definition.definition != VariableDefinition.FromProvider) return@forEach
            operationContext.variableBindingsState.completeBinding(
                requireNotNull(definition.variable.instanceId),
                model.VariableBinding.Error,
            )
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
