package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Value
import semantics.instantiateVariables
import semantics.readVariable

/** Whether every stored variable equals its registered field-relative provider value. */
context(world: Assumptions)
fun EngineResult.Object.conformsToVariables(): Boolean =
    variableValues.all { (variable, value) ->
        val provider =
            world.resolverRegistry
                .variable(variable)
                .instantiateVariables(variableValues)
        readVariable(provider) == value
    } &&
        cells.values.all { cell -> cell.value.conformsToVariables() }

context(world: Assumptions)
private fun EngineResult?.conformsToVariables(): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true
        is EngineResult.Object -> conformsToVariables()
        is EngineResult.List -> all { cell -> cell.value.conformsToVariables() }
    }
