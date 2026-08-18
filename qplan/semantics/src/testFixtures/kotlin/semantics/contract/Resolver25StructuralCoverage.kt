package semantics.contract

import model.EngineInputData
import model.EngineInputListData
import model.EngineInputObjectData
import model.ListEngineResult
import model.ObjectEngineResult
import model.OpenArguments
import model.PathComponent
import model.Schema
import model.SelectionForest
import model.Value
import model.VariableBinding
import model.usedVariables
import semantics.resolver25.Resolver25BindingSource
import semantics.resolver25.Resolver25KeyKind
import semantics.resolver25.Resolver25LifecycleEvent

internal enum class Resolver25StructuralSignature {
    OBJECT_PATH_KEY_GROUNDING,
    PRELAUNCH_DEMAND_AGGREGATION,
    LITERAL_VARIABLE_KEY_CONVERGENCE,
    POSTLAUNCH_DEMAND_DEEPENING,
    STAGGERED_DISTINCT_KEYS,
    NESTED_VARIABLE_USE,
    DESCENDANT_VARIABLE_OWNER,
    LIST_ELEMENT_VARIABLE_OWNER,
    MIXED_BINDING_SOURCES_COACTIVATED,
    NESTED_PROVIDER_PATH,
    MULTIPLE_OBJECT_PATH_OWNERS,
    PROVIDER_NULL_SHORT_CIRCUIT,
    PROVIDER_ERROR_SHORT_CIRCUIT,
}

internal fun List<Resolver25LifecycleEvent>.resolver25StructuralSignatures():
    Set<Resolver25StructuralSignature> {
    val signatures = linkedSetOf<Resolver25StructuralSignature>()
    val submissions =
        filterIsInstance<Resolver25LifecycleEvent.DemandSubmitted>()
            .associateBy(Resolver25LifecycleEvent.DemandSubmitted::contributionId)
    val declarations =
        filterIsInstance<Resolver25LifecycleEvent.BindingDeclared>()
            .associateBy(Resolver25LifecycleEvent.BindingDeclared::variable)
    val completions =
        filterIsInstance<Resolver25LifecycleEvent.BindingCompleted>()
            .associateBy(Resolver25LifecycleEvent.BindingCompleted::variable)
    val grounding =
        filterIsInstance<Resolver25LifecycleEvent.DemandGrounded>()
    val installed =
        filterIsInstance<Resolver25LifecycleEvent.ContributionInstalled>()
            .associateBy { event -> event.contributionId to event.coordinate }
    val resolverStarts =
        filterIsInstance<Resolver25LifecycleEvent.ResolverStarted>()
    val resolverStartCountByCoordinate =
        resolverStarts
            .groupingBy(Resolver25LifecycleEvent.ResolverStarted::coordinate)
            .eachCount()
    val earliestResolverStartByCoordinate =
        resolverStarts
            .groupBy(Resolver25LifecycleEvent.ResolverStarted::coordinate)
            .mapValues { (_, starts) ->
                starts.minOf(Resolver25LifecycleEvent.ResolverStarted::sequence)
            }
    val sealed =
        filterIsInstance<Resolver25LifecycleEvent.DemandSealed>()
            .associateBy(Resolver25LifecycleEvent.DemandSealed::coordinate)

    if (
        grounding.any { event ->
            val submission = submissions[event.contributionId] ?: return@any false
            submission.stampedVariables().any { variable ->
                declarations[variable]?.source is Resolver25BindingSource.FromObjectField &&
                    completions[variable]
                        ?.sequence
                        ?.let { completedAt -> completedAt < event.sequence } == true
            }
        }
    ) {
        signatures += Resolver25StructuralSignature.OBJECT_PATH_KEY_GROUNDING
    }

    if (
        any { event ->
            event is Resolver25LifecycleEvent.GroundedDemandMerged &&
                event.beforeLaunch
        }
    ) {
        signatures += Resolver25StructuralSignature.PRELAUNCH_DEMAND_AGGREGATION
    }

    if (
        grounding.groupBy(Resolver25LifecycleEvent.DemandGrounded::coordinate)
            .any { (coordinate, events) ->
                val groupedSubmissions =
                    events.mapNotNull { event -> submissions[event.contributionId] }
                val literal =
                    groupedSubmissions.filter { submission ->
                        submission.stampedVariables().isEmpty()
                    }
                val symbolic =
                    groupedSubmissions.filter { submission ->
                        submission.stampedVariables().isNotEmpty()
                    }
                literal.any { literalSubmission ->
                    symbolic.any { symbolicSubmission ->
                        val literalFields = literalSubmission.subselectionFields()
                        val symbolicFields = symbolicSubmission.subselectionFields()
                        val sealedFields =
                            sealed[coordinate]?.demand?.subselections
                                ?.fields()
                                .orEmpty()
                        literalFields.isNotEmpty() &&
                            symbolicFields.isNotEmpty() &&
                            literalFields.intersect(symbolicFields).isEmpty() &&
                            sealedFields.containsAll(literalFields + symbolicFields) &&
                            resolverStartCountByCoordinate[coordinate] == 1
                    }
                }
            }
    ) {
        signatures += Resolver25StructuralSignature.LITERAL_VARIABLE_KEY_CONVERGENCE
    }

    if (
        filterIsInstance<Resolver25LifecycleEvent.GroundedDemandMerged>()
            .filterNot(Resolver25LifecycleEvent.GroundedDemandMerged::beforeLaunch)
            .any { event ->
                installed[event.contributionId to event.coordinate]?.sequence
                    ?.let { installedAt -> installedAt > event.sequence } == true
            }
    ) {
        signatures += Resolver25StructuralSignature.POSTLAUNCH_DEMAND_DEEPENING
    }

    val internedResolverKeys =
        filterIsInstance<Resolver25LifecycleEvent.GroundedKeyInterned>()
            .filter { event -> event.kind == Resolver25KeyKind.FIELD_RESOLVER }
    if (
        internedResolverKeys.groupBy { event -> event.coordinate.fieldOccurrence() }
            .values
            .any { occurrences ->
                val earliestStarts =
                    occurrences
                        .mapNotNull { event ->
                            earliestResolverStartByCoordinate[event.coordinate]
                                ?.let { sequence -> event.coordinate to sequence }
                        }.distinctBy { (coordinate, _) -> coordinate }
                        .sortedBy { (_, sequence) -> sequence }
                val firstStart = earliestStarts.getOrNull(0)
                val secondStart = earliestStarts.getOrNull(1)
                occurrences.any { later ->
                    val otherStart =
                        if (firstStart?.first != later.coordinate) {
                            firstStart
                        } else {
                            secondStart
                        }
                    otherStart?.second?.let { sequence -> sequence < later.sequence } == true
                }
            }
    ) {
        signatures += Resolver25StructuralSignature.STAGGERED_DISTINCT_KEYS
    }

    val nestedStampedVariableUse =
        submissions.values.any { submission ->
            submission.stampedVariables().any { variable ->
                val ownerPath =
                    declarations[variable]
                        ?.ownerCoordinate
                        ?.dropLast(1)
                        ?: return@any false
                submission.path.size > ownerPath.size &&
                    submission.path.hasPrefix(ownerPath)
            }
        }
    // Successor closure may eagerly ground a nested key before its demand is submitted.
    val nestedEagerGroundedVariableUse =
        grounding.any { event ->
            val submission = submissions[event.contributionId] ?: return@any false
            val groundedKey =
                event.coordinate.lastOrNull() as? ObjectEngineResult.GroundKey
                    ?: return@any false
            declarations.values.any { declaration ->
                val completion =
                    completions[declaration.variable]
                        ?: return@any false
                val ownerPath = declaration.ownerCoordinate.dropLast(1)
                completion.sequence < submission.sequence &&
                    submission.sequence < event.sequence &&
                    submission.path.size > ownerPath.size &&
                    submission.path.hasPrefix(ownerPath) &&
                    groundedKey.arguments.containsBinding(completion.binding)
            }
        }
    if (nestedStampedVariableUse || nestedEagerGroundedVariableUse) {
        signatures += Resolver25StructuralSignature.NESTED_VARIABLE_USE
    }

    if (declarations.values.any { event -> event.ownerCoordinate.size > 1 }) {
        signatures += Resolver25StructuralSignature.DESCENDANT_VARIABLE_OWNER
    }
    if (
        declarations.values.any { event ->
            event.ownerCoordinate.any { component -> component is ListEngineResult.Index }
        }
    ) {
        signatures += Resolver25StructuralSignature.LIST_ELEMENT_VARIABLE_OWNER
    }

    val bindingSourceKinds =
        declarations.values.mapTo(linkedSetOf()) { event -> event.source::class }
    if (
        Resolver25BindingSource.FromArgument::class in bindingSourceKinds &&
        Resolver25BindingSource.FromObjectField::class in bindingSourceKinds
    ) {
        signatures += Resolver25StructuralSignature.MIXED_BINDING_SOURCES_COACTIVATED
    }

    if (
        declarations.values.any { event ->
            (event.source as? Resolver25BindingSource.FromObjectField)
                ?.providerPath
                ?.size
                ?.let { size -> size > 1 } == true
        }
    ) {
        signatures += Resolver25StructuralSignature.NESTED_PROVIDER_PATH
    }

    val objectPathOwners =
        declarations.values
            .filter { event -> event.source is Resolver25BindingSource.FromObjectField }
            .mapTo(linkedSetOf(), Resolver25LifecycleEvent.BindingDeclared::ownerCoordinate)
    if (objectPathOwners.size > 1) {
        signatures += Resolver25StructuralSignature.MULTIPLE_OBJECT_PATH_OWNERS
    }

    declarations.values
        .filter { event -> event.source is Resolver25BindingSource.FromObjectField }
        .mapNotNull { declaration -> completions[declaration.variable] }
        .forEach { completion ->
            when (val binding = completion.binding) {
                is VariableBinding.Input ->
                    if (binding.value == null) {
                        signatures += Resolver25StructuralSignature.PROVIDER_NULL_SHORT_CIRCUIT
                    }
                VariableBinding.Error ->
                    signatures += Resolver25StructuralSignature.PROVIDER_ERROR_SHORT_CIRCUIT
            }
        }
    return signatures
}

private fun Resolver25LifecycleEvent.DemandSubmitted.stampedVariables():
    Set<Value.Variable> =
    selection.key.arguments
        .usedVariables()
        .filterTo(linkedSetOf(), Value.Variable::isStamped)

private fun Resolver25LifecycleEvent.DemandSubmitted.subselectionFields() =
    selection.subselections.fields()

private fun OpenArguments.Ground.containsBinding(binding: VariableBinding): Boolean =
    when (this) {
        OpenArguments.Ground.Error -> binding == VariableBinding.Error
        is Value.Arguments ->
            binding is VariableBinding.Input && containsValue(binding.value)
    }

private fun Value.Arguments.containsValue(value: EngineInputData?): Boolean =
    fieldValues.values.any { argument -> argument.containsValue(value) }

private fun EngineInputData?.containsValue(value: EngineInputData?): Boolean =
    when (this) {
        null -> value == null
        is List<*> -> {
            val elements = requireType<EngineInputListData>(this)
            this == value || elements.any { element -> element.containsValue(value) }
        }
        is Map<*, *> -> {
            val fields = requireType<EngineInputObjectData>(this)
            this == value ||
                fields.values.any { fieldValue -> fieldValue.containsValue(value) }
        }
        else -> this == value
    }

private inline fun <reified T> requireType(value: EngineInputData): T {
    require(value is T)
    return value
}

private fun SelectionForest.fields(): Set<Schema.OutputField> =
    buildSet {
        this@fields.forEach { selection -> add(selection.key.field) }
    }

private fun List<PathComponent>.fieldOccurrence(): Pair<List<PathComponent>, Schema.ObjectField> {
    val groundedKey = last() as ObjectEngineResult.GroundKey
    return dropLast(1) to groundedKey.field
}

private fun List<PathComponent>.hasPrefix(prefix: List<PathComponent>): Boolean =
    size >= prefix.size && take(prefix.size) == prefix
