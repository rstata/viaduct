package semantics.contract

import model.requireQueryTypeDef
import viaduct.engine.api.EngineObjectData
import model.Assumptions
import model.ObjectEngineResult
import model.SelectionForest
import model.merge
import model.objectOf
import model.operationSelectionsFrom
import org.junit.jupiter.api.AfterEach
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToSelections
import semantics.correctresolution.correctResolution
import semantics.correctresolution.isClosedUnderResolverDemand
import semantics.correctresolution.rootedAndWellTyped
import kotlin.test.assertTrue
import semantics.shared.OperationContext

/**
 * Post-test policy requiring every contract result to satisfy the complete correctness judgment.
 */
interface CorrectResolutionPostTestPolicy : ResolverContract {
    @AfterEach
    fun validateContractResolutions() {
        ContractPostTestState.validateAndClear()
    }
}

private data class PendingResolutionValidation(
    val operation: OperationContext,
    val selections: SelectionForest,
    val result: ObjectEngineResult,
)

private object ContractPostTestState {
    private val pending =
        ThreadLocal.withInitial {
            mutableListOf<PendingResolutionValidation>()
        }

    fun record(validation: PendingResolutionValidation) {
        pending.get() += validation
    }

    fun validateAndClear() {
        val validations = pending.get().toList()
        pending.remove()
        validations.forEach { validation ->
            assertTrue(
                context(validation.operation) {
                    validation.result.correctResolution(
                        validation.selections
                            .merge(validation.operation.schema.requireQueryTypeDef()),
                    )
                },
                context(validation.operation) {
                    "rooted=${context(validation.operation.world) {
                        validation.result.rootedAndWellTyped()
                    }}, " +
                        "selections=" +
                        validation.result.conformsToSelections(
                            validation.selections,
                        ) +
                        ", closed=" +
                        validation.result.isClosedUnderResolverDemand() +
                        ", resolvers=" +
                        validation.result.conformsToResolvers()
                },
            )
        }
    }
}

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
): ObjectEngineResult = resolveAndValidateObserved(world, root, selections).result

internal fun ResolverContract.resolveAndValidateObserved(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
): ResolverResolutionObservation {
    val observation = observeResolution(world, root, selections)
    if (this is CorrectResolutionPostTestPolicy) {
        ContractPostTestState.record(
            PendingResolutionValidation(
                operation = observation.operation,
                selections = selections,
                result = observation.result,
            ),
        )
    }
    return observation
}

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    selections: SelectionForest,
): ObjectEngineResult =
    resolveAndValidate(
        world = world,
        root = world.objectOf("Query"),
        selections = selections,
    )

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
): ObjectEngineResult =
    resolveAndValidate(
        world = world,
        selections =
            world.operationSelectionsFrom(
                documentSource = documentSource,
                variables = variables,
                operationName = operationName,
            ),
    )

internal fun ResolverContract.resolveAndValidateObserved(
    world: Assumptions,
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
): ResolverResolutionObservation =
    resolveAndValidateObserved(
        world = world,
        root = world.objectOf("Query"),
        selections =
            world.operationSelectionsFrom(
                documentSource = documentSource,
                variables = variables,
                operationName = operationName,
            ),
    )
