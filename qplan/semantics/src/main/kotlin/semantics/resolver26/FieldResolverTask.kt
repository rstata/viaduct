package semantics.resolver26

import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResultCell
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.SelectionForest
import model.Stamp
import model.groundKey
import model.outputType
import model.registry.FieldResolver
import semantics.PassiveObjectOccurrence
import semantics.ResolvePassiveValuesResult
import semantics.correctresolution.argumentsContainErrorValue
import semantics.resolvePassiveValues
import viaduct.engine.api.EngineObjectData

/** Invokes and publishes one already-installed field resolver instance. */
internal class FieldResolverTask(
    private val world: Assumptions,
    private val support: Resolver26Support,
    private val path: List<PathComponent>,
    private val groundedSelection: ObjectSelection,
    private val resolver: FieldResolver,
    private val inputMaterializeSelections: MaterializeSelectionForest,
    private val target: ObjectEngineResult,
    private val cell: EngineResultCell,

    // The following arguments are passed for instrumentation-purposes only
    private val variableArgumentCount: Int,
    private val variableSourceSelectionStamps: Set<Stamp.Occurrence>,
) {
    suspend fun run() {
        context(world, support) {
            val groundKey = groundedSelection.groundKey()
            if (groundKey.arguments.argumentsContainErrorValue()) {
                val errorResult = ErrorEngineResult.of(EngineErrorData.of())
                cell.getValue().complete(errorResult)
                return
            }

            val coordinate = path + groundKey
            val input: EngineObjectData.Sync =
                target.materializeResolverInput(
                    selections = inputMaterializeSelections,
                    reader = coordinate,
                    resultPath = path,
                )

            val resolverArguments = groundKey.arguments as Arguments.Resolved
            val constructionDemand: SelectionForest = groundedSelection.subselections
            val invocationDemand: SelectionForest = constructionDemand.successorDemand()

            support.observeApplication(
                Resolver26ApplicationObservation(
                    occurrencePath = coordinate,
                    field = groundKey.field,
                    input = input,
                    arguments = resolverArguments,
                    suppliedDemand = invocationDemand,
                    variableArgumentCount = variableArgumentCount,
                    occurrenceStamp = groundKey.stamp as? Stamp.Occurrence,
                    variableSourceSelectionStamps = variableSourceSelectionStamps,
                ),
            )

            val fieldValue: EngineOutputData? =
                resolver(
                    input = input,
                    arguments = resolverArguments,
                    selections = invocationDemand,
                )

            val passiveValuesResult: ResolvePassiveValuesResult =
                fieldValue.resolvePassiveValues(
                    expectedType = groundKey.field.outputType,
                    path = coordinate,
                    resolverDemand = invocationDemand,
                )

            launchOutputObjectOrchestrations(
                passiveValuesResult = passiveValuesResult,
                coordinate = coordinate,
                constructionDemand = constructionDemand,
            )
            cell.getValue().complete(passiveValuesResult.engineResult)
        }
    }

    private fun launchOutputObjectOrchestrations(
        passiveValuesResult: ResolvePassiveValuesResult,
        coordinate: List<PathComponent>,
        constructionDemand: SelectionForest,
    ) {
        passiveValuesResult.objectOccurrences
            .filter { occurrence -> occurrence.isRootOfResolverOutput(coordinate) }
            .forEach { child ->
                support.launchObjectOrchestrationTask(
                    ObjectOrchestrationTask(
                        world = world,
                        support = support,
                        path = child.path,
                        source = child.source,
                        target = child.target,
                        initialDemand = constructionDemand,
                    ),
                )
            }
    }

    private fun PassiveObjectOccurrence.isRootOfResolverOutput(
        coordinate: List<PathComponent>,
    ): Boolean =
        path.size >= coordinate.size &&
            path.take(coordinate.size) == coordinate &&
            path.drop(coordinate.size).all { component -> component is ListEngineResult.Index }
}
