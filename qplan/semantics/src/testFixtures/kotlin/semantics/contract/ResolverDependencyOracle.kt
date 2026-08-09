package semantics.contract

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.Value
import semantics.arbitrary.registeredResolverOccurrences
import semantics.correctresolution.argumentsContainErrorValue
import semantics.materialize

/**
 * Independently reconstructs each resolver application's exact input dependencies.
 *
 * The oracle materializes each resolver object fragment from the completed result, then walks only
 * that materialized value. It does not use runtime readiness selections or dependency analysis.
 */
context(world: Assumptions)
fun EngineResult.Object.expectedResolverDependencies():
    Map<List<PathComponent>, Set<List<PathComponent>>> =
    registeredResolverOccurrences(world.resolverRegistry)
        .associate { cell ->
            val resolver =
                world.resolverRegistry.resolver(
                    world.schema.objectField(
                        cell.canonicalField.typeName,
                        cell.canonicalField.fieldName,
                    ),
                )
            val objectFragment =
                resolver
                    .objectFragmentAt(cell.occurrencePath)
            val input =
                runBlocking {
                    cell.containingObject.materialize(objectFragment)
                }
            cell.occurrencePath to
                input.registeredResolverCoordinates(
                    path = cell.occurrencePath.dropLast(1),
                )
        }

context(world: Assumptions)
private fun Value.Object.registeredResolverCoordinates(
    path: List<PathComponent>,
): Set<List<PathComponent>> {
    val coordinates = linkedSetOf<List<PathComponent>>()

    fun visit(
        value: Value.Output?,
        valuePath: List<PathComponent>,
    ) {
        when {
            value == null ||
                value == Value.Error ||
                value is Value.Simple ->
                Unit

            value is Value.Object ->
                value.fieldValues.forEach { (key, fieldValue) ->
                    val coordinate = valuePath + key
                    if (
                        key.field in world.resolverRegistry &&
                        !key.arguments.argumentsContainErrorValue()
                    ) {
                        coordinates += coordinate
                    }
                    visit(fieldValue, coordinate)
                }

            value is Value.OutputList ->
                value.values.forEachIndexed { index, element ->
                    visit(
                        value = element,
                        valuePath = valuePath + Value.ListIndex.of(index),
                    )
                }
        }
    }

    visit(this, path)
    return coordinates
}
