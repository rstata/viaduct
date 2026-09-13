package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import model.EngineResultCell
import model.ObjectEngineResult
import model.VariableBinding
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import model.outputValue
import semantics.correctresolution.argumentsContainErrorValue

/**
 * Reads object-path providers and installs every local field resolver.
 *
 * Each installation synchronously claims the original symbolic target cell and registers its writer
 * for cycle detection before launching the field-resolver task. Provider and Query-fragment reads
 * remain request-owned coroutines. The enclosing orchestration may therefore freeze the target as
 * soon as this function returns without waiting for any values to complete.
 */
internal fun ObjectOrchestrationTask.launchBindingsAndResolvers(
    closed: CloseInputDemandResult,
) {
    context(operation) {
        closed.fieldResolverOccurrenceContexts.values.forEach { fieldResolverOccurrenceContext ->
            operation.queryValuesState.declare(
                fieldResolverOccurrenceContext.resolverOccurrenceId,
            )
        }
        operation.requestScope
            .launchProviderBindings(occurrence.target, closed.objectProviderReads)
        closed.fieldResolverOccurrenceContexts.forEach {
                (objectKey, fieldResolverOccurrenceContext) ->
            operation.queryValuesState
                .launchProducer(
                    scope = operation.requestScope,
                    resolverOccurrenceId = fieldResolverOccurrenceContext.resolverOccurrenceId,
                ) {
                    try {
                        if (
                            objectKey is ObjectEngineResult.GroundKey &&
                            objectKey.arguments.argumentsContainErrorValue()
                        ) {
                            model.engineObjectDataOf(operation.schema.requireQueryTypeDef())
                        } else {
                            fieldResolverOccurrenceContext.fragments.queryFragment
                                .resolveQueryFragment(
                                    coordinate = occurrence.coordinate(objectKey),
                                    inclusionCondition =
                                        fieldResolverOccurrenceContext.selection.inclusionCondition,
                                )
                        }
                    } catch (cause: Exception) {
                        currentCoroutineContext().ensureActive()
                        operation.completeQueryPathBindingsWithError(
                            fieldResolverOccurrenceContext,
                        )
                        throw cause
                    }
                }.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        fieldResolverOccurrenceContext.fragments.queryFragment
                            .pathVariableDefinitions
                            .forEach { definition ->
                                operation.variableBindingsState.cancelBinding(
                                    requireNotNull(definition.variable.instanceId),
                                    cause,
                                )
                            }
                    }
                }
            check(objectKey.field in operation.resolverRegistry) {
                "Resolver26 attempted to install passive key $objectKey"
            }
            check(!source.isPresent(objectKey.field.name)) {
                "Resolver26 attempted to install source-provided key $objectKey"
            }
            occurrence.installAndLaunchResolver(fieldResolverOccurrenceContext)
        }
        closed.rootFieldReferenceOccurrences.values.forEach { referenceOccurrence ->
            val objectKey = referenceOccurrence.selection.key
            check(source.outputValue(objectKey.field.name) === referenceOccurrence.reference) {
                "Resolver26 root reference does not match its source value"
            }
            occurrence.installAndLaunchResolver(referenceOccurrence)
        }
    }
}

// Installs one field-resolution task while retaining its symbolic cell key.
context(operation: Resolver26OperationContext)
internal fun OEROccurrenceContext.installAndLaunchResolver(
    resolverOccurrenceContext: ResolverOccurrenceContext,
) {
    val objectKey = resolverOccurrenceContext.selection.key
    val cell = target.reserveCell(objectKey)
    cell.createValuePromise()
    operation.cycleChecker.registerWriter(
        cell = cell,
        writer = coordinate(objectKey),
    )
    launchFieldResolverTask(
        operationContext = operation,
        oerOccurrenceContext = this,
        resolverOccurrenceContext = resolverOccurrenceContext,
        cell = cell,
    )
}

internal fun Resolver26OperationContext.completeQueryPathBindingsWithError(
    context: FieldResolverOccurrenceContext,
) {
    context.fragments.queryFragment.pathVariableDefinitions.forEach { definition ->
        variableBindingsState.completeBinding(
            requireNotNull(definition.variable.instanceId),
            VariableBinding.Error,
        )
    }
}

internal fun launchFieldResolverTask(
    operationContext: Resolver26OperationContext,
    oerOccurrenceContext: OEROccurrenceContext,
    resolverOccurrenceContext: ResolverOccurrenceContext,
    cell: EngineResultCell,
) {
    operationContext.requestScope
        .launch {
            FieldResolverTask(
                operationContext = operationContext,
                oerOccurrenceContext = oerOccurrenceContext,
                resolverOccurrenceContext = resolverOccurrenceContext,
                cell = cell,
                fieldTaskScope = this,
            ).run()
        }.invokeOnCompletion { cause ->
            if (cause !is CancellationException) return@invokeOnCompletion

            cell.cancelValue(cause)
            val context = resolverOccurrenceContext as? FieldResolverOccurrenceContext
            context?.variableDefinitions?.forEach { definition ->
                if (
                    definition.definition == VariableDefinition.FromProvider ||
                    definition.definition is VariableDefinition.FromArgument
                ) {
                    operationContext.variableBindingsState.cancelBinding(
                        requireNotNull(definition.variable.instanceId),
                        cause,
                    )
                }
            }
        }
}
