package semantics.contract

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.Fragment
import model.fragmentFrom
import model.objectOf
import org.junit.jupiter.api.AfterEach
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToSelections
import semantics.correctresolution.conformsToTypename
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
    val fragment: Fragment,
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
                    validation.result.correctResolution(validation.fragment)
                },
                context(validation.world) {
                    "rooted=${validation.result.rootedAndWellTyped()}, " +
                        "selections=" +
                        validation.result.conformsToSelections(validation.fragment.subselections) +
                        ", closed=${validation.result.isClosedUnderResolverDemand()}, " +
                        "resolvers=${validation.result.conformsToResolvers()}, " +
                        "typename=${validation.result.conformsToTypename()}"
                },
            )
        }
    }
}

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    root: EngineObjectData.Sync,
    fragment: Fragment,
): ObjectEngineResult {
    val result = resolve(world, root, fragment.subselections)
    if (this is CorrectResolutionPostTestPolicy) {
        ContractPostTestState.record(
            PendingResolutionValidation(
                world = world,
                fragment = fragment,
                result = result,
            ),
        )
    }
    return result
}

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    fragment: Fragment,
): ObjectEngineResult =
    resolveAndValidate(
        world = world,
        root = world.objectOf("Query"),
        fragment = fragment,
    )

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    fragmentSource: String,
): ObjectEngineResult =
    resolveAndValidate(
        world = world,
        fragment = world.fragmentFrom(fragmentSource),
    )
