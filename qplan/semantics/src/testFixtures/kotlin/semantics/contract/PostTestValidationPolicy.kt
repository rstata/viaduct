package semantics.contract

import model.requireQueryTypeDef
import viaduct.engine.api.EngineObjectData
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.instantiateBindings
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
    val world: Assumptions,
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
                context(validation.world) {
                    validation.result.correctResolution(
                        validation.selections
                            .merge(validation.world.schema.requireQueryTypeDef())
                            .instantiateBindings(),
                    )
                },
                context(validation.world) {
                    "rooted=${validation.result.rootedAndWellTyped()}, " +
                        "selections=" +
                        validation.result.conformsToSelections(validation.selections) +
                        ", closed=${validation.result.isClosedUnderResolverDemand()}, " +
                        "resolvers=${validation.result.conformsToResolvers()}"
                },
            )
        }
    }
}

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
): ObjectEngineResult {
    val result = resolve(world, root, selections)
    if (this is CorrectResolutionPostTestPolicy) {
        ContractPostTestState.record(
            PendingResolutionValidation(
                world = world,
                selections = selections,
                result = result,
            ),
        )
    }
    return result
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
