package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineResult
import model.EngineResultCell
import model.InclusionCondition
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.ResolverOccurrenceId
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.Selection
import model.SelectionForest
import model.VariableBinding
import model.engineObjectDataOf
import model.guardedBy
import model.invariants.conformsToResolverOutputSchemaType
import model.merge
import model.registry.ProviderFragment
import model.registry.ResolverFragment
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import model.schemaType
import model.selectionForestOf
import model.usedVariables
import model.variableArgumentNames
import semantics.correctresolution.argumentsContainErrorValue
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.fetchGroundedArguments
import viaduct.engine.api.EngineObjectData

/** Invokes and publishes one already-installed field resolver or root-field reference. */
internal class FieldResolverTask(
    private val operationContext: Resolver26OperationContext,
    private val oerOccurrenceContext: OEROccurrenceContext,
    private val resolverOccurrenceContext: ResolverOccurrenceContext,
    private val cell: EngineResultCell,
) {
    private val world: Assumptions = operationContext.world

    init {
        val selection = resolverOccurrenceContext.selection
        require(selection.key.field.containingDef == oerOccurrenceContext.target.type) {
            "Resolver selection does not belong to its target occurrence"
        }

        val objectFieldPublication =
            resolverOccurrenceContext.publicationPath.lastOrNull() is ObjectEngineResult.ObjectKey
        if (objectFieldPublication) {
            require(oerOccurrenceContext.target.getCell(selection.key) === cell) {
                "Resolver cell does not belong to its target occurrence and selection"
            }
        }

        when (resolverOccurrenceContext) {
            is FieldResolverOccurrenceContext -> {
                val resolver = resolverOccurrenceContext.resolver
                val resolverOccurrenceId = resolverOccurrenceContext.resolverOccurrenceId
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
            }
            is RootFieldReferenceOccurrence -> {
                require(resolverOccurrenceContext.reference.targetField in world.resolverRegistry) {
                    "Root-field-reference target has no resolver"
                }
            }
            is PassiveValueOccurrence -> Unit
        }
    }

    suspend fun run() {
        context(operationContext, world, operationContext.cycleChecker) {
            val selection = resolverOccurrenceContext.selection
            val constructionDemand = resolverOccurrenceContext.publicationConstructionDemand
            val invocationDemand: SelectionForest =
                when (resolverOccurrenceContext) {
                    is PassiveValueOccurrence -> resolverOccurrenceContext.invocationDemand
                    else -> constructionDemand.successorDemand()
                }

            val activated = activateResolverOccurrence(resolverOccurrenceContext)
            if (!activated) return

            var fieldValue: ResolverOutputData? =
                when (resolverOccurrenceContext) {
                    is FieldResolverOccurrenceContext ->
                        runFieldResolver(
                            context = resolverOccurrenceContext,
                            selection = selection,
                            invocationDemand = invocationDemand,
                        )
                    is RootFieldReferenceOccurrence -> resolverOccurrenceContext.reference
                    is PassiveValueOccurrence -> resolverOccurrenceContext.value
                }

            while (fieldValue is RootFieldReferenceData) {
                val reference = fieldValue
                require(
                    reference.conformsToResolverOutputSchemaType(
                        resolverOccurrenceContext.publicationExpectedType,
                    ),
                ) {
                    "Root-field-reference target ${reference.type.name} does not conform to " +
                        "the consumer publication type"
                }
                val invocation =
                    createRootFieldResolverOccurrence(
                        reference = reference,
                        constructionDemand = constructionDemand,
                    )
                fieldValue =
                    invokeRootFieldResolver(
                        context = invocation,
                        arguments = reference.arguments,
                        invocationDemand = invocationDemand,
                    )
                operationContext.resolverObserver.onRootFieldReferenceInvocation(
                    RootFieldReferenceInvocationObservation(
                        publicationRoot = oerOccurrenceContext.root,
                        publicationPath = resolverOccurrenceContext.publicationPath,
                        reference = reference,
                        invocationRoot = invocation.invocationRoot,
                        invocationPath = invocation.invocationPath,
                        invocationKey = invocation.selection.key,
                        suppliedDemand = invocationDemand,
                    ),
                )
            }

            val passiveValue: EngineResult? =
                fieldValue.resolvePassiveValues(
                    root = oerOccurrenceContext.root,
                    expectedType = resolverOccurrenceContext.publicationExpectedType,
                    path = resolverOccurrenceContext.publicationPath,
                    invocationDemand = invocationDemand,
                    constructionDemand = constructionDemand,
                    parent = oerOccurrenceContext,
                )

            cell.getValue().complete(passiveValue)
        }
    }

    private suspend fun activateResolverOccurrence(
        context: ResolverOccurrenceContext,
    ): Boolean {
        val publicationCellNeedsActivation =
            context.publicationPath.lastOrNull() is ObjectEngineResult.ObjectKey
        if (!publicationCellNeedsActivation) return true

        val fieldContext = context as? FieldResolverOccurrenceContext
        val fromArgumentVariableIds =
            fieldContext
                ?.variableDefinitions
                ?.filter { definition ->
                    definition.definition is VariableDefinition.FromArgument
                }?.mapTo(linkedSetOf()) { definition ->
                    requireNotNull(definition.variable.instanceId)
                }.orEmpty()
        val activated =
            context.selection.inclusionCondition.include { variable ->
                val variableId = requireNotNull(variable.instanceId)
                if (
                    fieldContext != null &&
                    variableId in fromArgumentVariableIds &&
                    !operationContext.variableBindingsState.isBound(variableId)
                ) {
                    val groundedArguments =
                        context(operationContext) {
                            context.selection.key.fetchGroundedArguments()
                    }
                    completeFromArgumentBindings(fieldContext, groundedArguments)
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
        return activated
    }

    context(world: Assumptions)
    private suspend fun runFieldResolver(
        context: FieldResolverOccurrenceContext,
        selection: ObjectSelection,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val groundedArguments =
            context(operationContext) {
                selection.key.fetchGroundedArguments()
            }
        completeFromArgumentBindings(context, groundedArguments)
        if (groundedArguments.argumentsContainErrorValue()) {
            completeVariablesProviderBindingsWithError(context)
            return EngineErrorData.of()
        }

        val resolverArguments = groundedArguments as Arguments.Resolved
        val providerError =
            completeVariablesProviderBindings(
                context = context,
                arguments = resolverArguments,
            )
        if (providerError != null) return providerError

        val input: EngineObjectData.Sync =
            context(operationContext, operationContext.cycleChecker) {
                oerOccurrenceContext.target.materializeResolverInput(
                    selections = context.inputMaterializeSelections,
                    reader = context.publicationPath,
                    resultPath = oerOccurrenceContext.path,
                )
            }
        val queryValue = operationContext.queryValuesState.fetch(context.resolverOccurrenceId)
        val variableArgumentCount = selection.key.arguments.variableArgumentNames().size
        val variableResolverOccurrenceIds =
            selection.key.arguments
                .usedVariables()
                .mapNotNullTo(linkedSetOf()) { variable ->
                    variable.instanceId?.resolverOccurrenceId
                }

        operationContext.resolverObserver.onResolverApplication(
            Resolver26ApplicationObservation(
                occurrencePath = context.publicationPath,
                field = selection.key.field,
                input = input,
                inputSelections = context.inputMaterializeSelections,
                arguments = resolverArguments,
                suppliedDemand = invocationDemand,
                resolverOccurrenceId = context.resolverOccurrenceId,
                variableArgumentCount = variableArgumentCount,
                variableResolverOccurrenceIds = variableResolverOccurrenceIds,
            ),
        )

        return context.resolver(
            input = input,
            queryValue = queryValue,
            arguments = resolverArguments,
            selections = invocationDemand,
        )
    }

    context(world: Assumptions)
    private fun createRootFieldResolverOccurrence(
        reference: RootFieldReferenceData,
        constructionDemand: SelectionForest,
    ): FieldResolverOccurrenceContext {
        val queryRoot = ObjectEngineResult.of(operationContext.schema.requireQueryTypeDef())
        val prefixKeys =
            reference.path.dropLast(1).map { prefixField ->
                ObjectEngineResult.GroundKey.of(prefixField, emptyMap())
            }
        val targetKey =
            ObjectEngineResult.GroundKey.of(reference.targetField, reference.arguments)
        val invocationPath: List<PathComponent> = prefixKeys + targetKey
        val resolverOccurrenceId = ResolverOccurrenceId.at(queryRoot, invocationPath)
        val resolver = world.resolverRegistry.resolver(reference.targetField)
        val fragments = resolver.instantiateFragments(resolverOccurrenceId)
        require(fragments.objectFragment.materializeSelections.isEmpty()) {
            "Root-field-reference target ${reference.targetField.containingDef.name}/" +
                "${reference.targetField.name} must not declare an object fragment"
        }
        require(
            resolver.variables.values.none { definition ->
                definition is VariableDefinition.FromField &&
                    definition.providerFragment == ProviderFragment.OBJECT
            },
        ) {
            "Root-field-reference target ${reference.targetField.containingDef.name}/" +
                "${reference.targetField.name} must not declare FromObjectField variables"
        }
        val context =
            FieldResolverOccurrenceContext(
                selection =
                    selectionForestOf(
                        Selection.of(
                            key = targetKey,
                            possibleTypes = setOf(reference.targetField.containingDef),
                            subselections = constructionDemand,
                        ),
                    ).merge(reference.targetField.containingDef).byKey().getValue(targetKey),
                invocationRoot = queryRoot,
                invocationPath = invocationPath,
                resolverOccurrenceId = resolverOccurrenceId,
                resolver = resolver,
                inputMaterializeSelections = fragments.objectFragment.materializeSelections,
                variableDefinitions =
                    resolver.instantiatedVariableDefinitions(resolverOccurrenceId),
                fragments = fragments,
            )
        declareRootFieldInvocationBindings(context, reference.arguments)
        operationContext.queryValuesState.declare(resolverOccurrenceId)
        operationContext.requestScope.launch {
            val queryValue =
                context(operationContext) {
                    context.fragments.queryFragment.resolveQueryFragment(
                        coordinate = invocationPath,
                        inclusionCondition = InclusionCondition.Always,
                    )
                }
            operationContext.queryValuesState.complete(resolverOccurrenceId, queryValue)
        }
        return context
    }

    private fun declareRootFieldInvocationBindings(
        context: FieldResolverOccurrenceContext,
        arguments: Arguments.Resolved,
    ) {
        context.variableDefinitions.forEach { variableDefinition ->
            val variableId = requireNotNull(variableDefinition.variable.instanceId)
            when (val definition = variableDefinition.definition) {
                VariableDefinition.FromProvider ->
                    operationContext.variableBindingsState.declareBinding(variableId)
                is VariableDefinition.FromArgument ->
                    operationContext.variableBindingsState.bindVariable(
                        variableId,
                        bindingFor(arguments, definition),
                    )
                is VariableDefinition.FromField -> {
                    require(definition.providerFragment == ProviderFragment.QUERY) {
                        "Root-field-reference targets cannot use object-field variables"
                    }
                    operationContext.variableBindingsState.declareBinding(variableId)
                }
            }
        }
    }

    context(world: Assumptions, cycleChecker: semantics.shared.CycleCheckState)
    private suspend fun invokeRootFieldResolver(
        context: FieldResolverOccurrenceContext,
        arguments: Arguments.Resolved,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        completeVariablesProviderBindings(context, arguments)?.let { return it }
        val input = engineObjectDataOf(context.resolver.field.containingDef)
        val queryValue = operationContext.queryValuesState.fetch(context.resolverOccurrenceId)
        operationContext.resolverObserver.onResolverApplication(
            Resolver26ApplicationObservation(
                occurrencePath = context.invocationPath,
                field = context.selection.key.field,
                input = input,
                inputSelections = context.inputMaterializeSelections,
                arguments = arguments,
                suppliedDemand = invocationDemand,
                resolverOccurrenceId = context.resolverOccurrenceId,
                variableArgumentCount = 0,
                variableResolverOccurrenceIds = emptySet(),
            ),
        )
        return context.resolver(input, queryValue, arguments, invocationDemand)
    }

    // Calls the tenant provider once for this occurrence and publishes its complete binding set.
    // A provider failure becomes the owning field's error while also unblocking fragment work.
    private suspend fun completeVariablesProviderBindings(
        context: FieldResolverOccurrenceContext,
        arguments: Arguments.Resolved,
    ): EngineErrorData? {
        val resolver = context.resolver
        val provider = resolver.variablesProvider ?: return null
        val providerDefinitions =
            context.variableDefinitions.filter { definition ->
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
                completeVariablesProviderBindingsWithError(context)
                return EngineErrorData.of(exception)
            }
        if (values.keys != expectedNames) {
            val extra = values.keys - expectedNames
            val missing = expectedNames - values.keys
            completeVariablesProviderBindingsWithError(context)
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

    private fun completeVariablesProviderBindingsWithError(
        context: FieldResolverOccurrenceContext,
    ) {
        context.variableDefinitions.forEach { definition ->
            if (definition.definition != VariableDefinition.FromProvider) return@forEach
            operationContext.variableBindingsState.completeBinding(
                requireNotNull(definition.variable.instanceId),
                VariableBinding.Error,
            )
        }
    }

    // Fills FromArgument bindings that were declared while their owning resolver key was symbolic.
    // Bindings for already-ground owners received their values during binding declaration.
    private fun completeFromArgumentBindings(
        context: FieldResolverOccurrenceContext,
        groundedArguments: Arguments.Ground,
    ) {
        if (context.selection.key is ObjectEngineResult.GroundKey) return
        context.variableDefinitions.forEach { variableDefinition ->
            if (variableDefinition.definition !is VariableDefinition.FromArgument) {
                return@forEach
            }
            val definition = variableDefinition.definition as VariableDefinition.FromArgument
            val variableId = requireNotNull(variableDefinition.variable.instanceId)
            if (operationContext.variableBindingsState.isBound(variableId)) return@forEach
            operationContext.variableBindingsState.completeBinding(
                variableId,
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
