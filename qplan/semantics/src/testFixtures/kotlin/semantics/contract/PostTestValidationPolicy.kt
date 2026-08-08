package semantics.contract

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.Value
import org.junit.jupiter.api.AfterEach
import semantics.correctresolution.correctResolution
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
    val result: EngineResult.Object,
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
            )
        }
    }
}

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    root: Value.Object,
    fragment: Fragment,
): EngineResult.Object {
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
