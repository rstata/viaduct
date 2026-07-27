package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.Selection
import model.SelectionForest

/**
 * Whether this result contains every cell required by [fragment].
 *
 * Each selection is first guarded by the runtime concrete object type. Applicable selection keys
 * are then specialized to that concrete type and have all variables instantiated before lookup.
 * Null and error values stop recursive requirements. Cells not required by [fragment] are permitted.
 *
 * This predicate trusts the fragment's post-validation schema compatibility and does not duplicate
 * [conformsToSchema]. It observes cell presence and values, but never cell check components.
 *
 * @throws model.MissingVariablesException when an applicable required key contains an unbound
 * variable
 */
context(world: Assumptions)
suspend fun ObjectEngineResult.conformsToFragment(fragment: Fragment): Boolean =
    objectConformsToFragment(fragment.subselections)

context(world: Assumptions)
private suspend fun ObjectEngineResult.objectConformsToFragment(
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
private suspend fun EngineResult?.engineResultConformsToFragment(
    selections: SelectionForest,
): Boolean =
    when (this) {
        null,
        Schema.ErrorValue,
        is Schema.SimpleValue,
        -> true

        is ObjectEngineResult -> objectConformsToFragment(selections)
        is ListEngineResult ->
            all { element -> element.engineResultConformsToFragment(selections) }
    }

context(world: Assumptions)
internal fun Selection.concreteObjectKey(type: Schema.ObjectType): Schema.ObjectKey =
    world.schema.objectKey(
        field = world.schema.field(type.typeName, key.field.fieldName),
        arguments =
            key.arguments.fieldValues.mapValues { (_, value) ->
                value?.let(world.variableValues::instantiateAllVariables)
            },
    )
