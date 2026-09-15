package semantics.resolvers

import model.Arguments
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.engineObjectDataOf
import model.registry.FieldResolver
import model.registry.ResolverFragments
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import semantics.shared.SharedOperationContext

/** One independently rooted invocation prepared from a symbolic root-field reference. */
internal data class PreparedRootFieldReferenceInvocation(
    val root: ObjectEngineResult,
    val path: List<PathComponent>,
    val key: ObjectEngineResult.GroundKey,
    val resolver: FieldResolver,
    val fragments: ResolverFragments,
)

/**
 * Creates the empty Query-rooted identity and binds the grounded arguments for one reference hop.
 *
 * The maintained pre-Resolver26 algorithms support only `FromArgument` variables. Reference
 * targets inherit that boundary instead of acquiring Resolver26's runtime binding protocols.
 */
context(operation: SharedOperationContext<*>)
internal fun RootFieldReferenceData.prepareInvocation(): PreparedRootFieldReferenceInvocation {
    require(targetField in operation.resolverRegistry) {
        "Root-field-reference target has no registered resolver: " +
            "${targetField.containingDef.name}/${targetField.name}"
    }
    val root = ObjectEngineResult.of(operation.schema.requireQueryTypeDef())
    val prefixKeys =
        path.dropLast(1).map { field ->
            ObjectEngineResult.GroundKey.of(field, emptyMap())
        }
    val key = ObjectEngineResult.GroundKey.of(targetField, arguments)
    val invocationPath: List<PathComponent> = prefixKeys + key
    val resolverOccurrenceId = ResolverOccurrenceId.at(root, invocationPath)
    val resolver = operation.resolverRegistry.resolver(targetField)
    val fragments = resolver.instantiateFragments(resolverOccurrenceId)
    require(fragments.objectFragment.materializeSelections.isEmpty()) {
        "Root-field-reference target ${targetField.containingDef.name}/${targetField.name} " +
            "must not declare an object fragment"
    }
    require(resolver.variables.values.all { definition -> definition is VariableDefinition.FromArgument }) {
        "Root-field-reference target ${targetField.containingDef.name}/${targetField.name} " +
            "uses a variable source unsupported before Resolver26"
    }
    setOf(key).bindFromArguments(root, prefixKeys)
    return PreparedRootFieldReferenceInvocation(
        root = root,
        path = invocationPath,
        key = key,
        resolver = resolver,
        fragments = fragments,
    )
}

/** Empty resolver object input required by every root-field-reference target. */
internal fun PreparedRootFieldReferenceInvocation.emptyObjectInput() =
    engineObjectDataOf(key.field.containingDef)
