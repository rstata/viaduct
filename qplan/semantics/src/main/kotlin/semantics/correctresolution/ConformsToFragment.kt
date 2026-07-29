package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value

/**
 * Whether this result contains every cell required by [fragment].
 *
 * Each selection is first guarded by the runtime concrete object type. Applicable selection keys
 * are then specialized to that concrete type and have all variables instantiated before lookup.
 * Null and error values stop recursive requirements. Cells not required by [fragment] are permitted.
 *
 * This predicate trusts the fragment's post-validation schema compatibility and the engine-result
 * carrier invariants established by its factories. It observes values, but not check components.
 *
 * @throws model.MissingVariablesException when an applicable required key contains an unbound
 * variable
 */
context(world: Assumptions)
fun EngineResult.Object.conformsToFragment(fragment: Fragment): Boolean =
    objectConformsToFragment(fragment.subselections)

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToFragment(
    selections: SelectionForest,
): Boolean =
    selections.all { selection ->
        if (type !in selection.possibleTypes) {
            true
        } else {
            val key = selection.concreteObjectKey(type)
            key in keys &&
                fetch(key).value.engineResultConformsToFragment(selection.subselections)
        }
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToFragment(
    selections: SelectionForest,
): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectConformsToFragment(selections)
        is EngineResult.List ->
            all { cell -> cell.value.engineResultConformsToFragment(selections) }
    }

context(world: Assumptions)
internal fun Selection.concreteObjectKey(type: Schema.ObjectType): Value.Key =
    Value.Key.of(
        field = world.schema.field(type.typeName, key.field.fieldName),
        arguments =
            key.arguments.fieldValues.mapValues { (_, value) ->
                value?.let(world.variableValues::instantiateAllVariables)
            },
    )
