package semantics.resolver26

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.Assumptions
import model.EngineInputData
import model.EngineInputListData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Selection
import model.VariableBinding
import model.fetchGroundedArguments
import model.objectKey
import model.outputType
import model.registry.InstantiatedFieldPathDefinition
import model.registry.ProviderFragment
import model.selectionForestOf
import model.toEngineSimpleData
import viaduct.graphql.schema.ViaductSchema

// Traverses a provider path through OER promises and returns its terminal input-compatible value.
context(world: Assumptions)
internal suspend fun ObjectEngineResult.readProvider(
    definition: InstantiatedFieldPathDefinition,
    reader: List<PathComponent>,
    support: Resolver26Support,
): VariableBinding = readProvider(definition.path, reader, support)

// Reads and completes all provider bindings rooted in this result.
context(world: Assumptions)
internal suspend fun ObjectEngineResult.completeProviderBindings(
    reads: List<ProviderDefinitionRead>,
    providerFragment: ProviderFragment,
    support: Resolver26Support,
) {
    require(reads.all { read -> read.definition.providerFragment == providerFragment })
    coroutineScope {
        reads.forEach { read ->
            launch {
                val binding =
                    readProvider(
                        definition = read.definition,
                        reader = read.readerPath,
                        support = support,
                    )
                world.completeBinding(
                    requireNotNull(read.definition.variable.instanceId),
                    binding,
                )
            }
        }
    }
}

context(world: Assumptions)
private suspend fun ObjectEngineResult.readProvider(
    path: List<ObjectEngineResult.Key>,
    reader: List<PathComponent>,
    support: Resolver26Support,
): VariableBinding {
    var current = this
    path.forEachIndexed { index, openKey ->
        support.awaitBindingsDeclared(current)
        val specializedKey =
            Selection.of(
                key = openKey,
                possibleTypes = setOf(current.type),
                subselections = selectionForestOf(),
            ).objectKey(current.type)
        val objectKey = specializedKey
        objectKey.fetchGroundedArguments()
        val cell = current.reserveCell(objectKey)
        support.cycleCheck(reader, cell)
        val value = cell.reserveValue().await()
        if (index == path.lastIndex) {
            return value.toProviderBinding(objectKey.field.outputType)
        }
        when (value) {
            null -> return VariableBinding.of(null)
            is ErrorEngineResult -> return VariableBinding.Error
            is ObjectEngineResult -> current = value
            else -> error("Resolver26 provider path crossed a non-object at $objectKey")
        }
    }
    error("Resolver26 provider path must be nonempty")
}

// Converts a provider result to an input value and rejects object-valued terminals.
private fun EngineResult?.toProviderBinding(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
): VariableBinding =
    when (this) {
        null -> VariableBinding.of(null)
        is ErrorEngineResult -> VariableBinding.Error
        is ListEngineResult -> toProviderInputListBinding()
        is ObjectEngineResult ->
            error("A path-variable provider cannot terminate at an object")
        else ->
            VariableBinding.of(
                toEngineSimpleData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef),
            )
    }

// Converts a provider list to an input list after checking its element type.
private fun ListEngineResult.toProviderInputListBinding(): VariableBinding {
    require(typeExpr.baseTypeDef is ViaductSchema.InputTypeDef) {
        "A path-variable provider list must contain input-compatible simple values"
    }
    val values = mutableListOf<EngineInputData?>()
    indices.forEach { index ->
        when (val binding = get(index).getValue().get().toProviderBinding(typeExpr)) {
            VariableBinding.Error -> return VariableBinding.Error
            is VariableBinding.Input -> values += binding.value
        }
    }
    val data: EngineInputListData = values.toList()
    return VariableBinding.of(data)
}
