package semantics.contract

import model.PathComponent
import model.Value
import model.usedVariables
import semantics.resolver25.DemandContributionId
import semantics.resolver25.Resolver25KeyKind
import semantics.resolver25.Resolver25LifecycleEvent
import kotlin.test.assertTrue

internal object Resolver25LifecycleTraceValidators {
    val sequenceClock =
        TraceValidator<Resolver25LifecycleEvent> { trace ->
            trace.mapIndexedNotNull { index, event ->
                if (event.sequence == index.toLong()) {
                    null
                } else {
                    violation(
                        code = "sequence.non-contiguous",
                        event = event,
                        message =
                            "Expected sequence $index but observed ${event.sequence}",
                    )
                }
            }
        }

    val referencesAndOrder =
        TraceValidator<Resolver25LifecycleEvent> { trace ->
            val violations = mutableListOf<TraceViolation>()
            val orchestrators = mutableSetOf<List<PathComponent>>()
            val readyOrchestrators = mutableSetOf<List<PathComponent>>()
            val contributions =
                mutableMapOf<
                    DemandContributionId,
                    Resolver25LifecycleEvent.DemandSubmitted,
                >()
            val grounded =
                mutableSetOf<Pair<DemandContributionId, List<PathComponent>>>()
            val interned =
                mutableMapOf<List<PathComponent>, Resolver25KeyKind>()
            val promises = mutableSetOf<List<PathComponent>>()
            val sealed = mutableSetOf<List<PathComponent>>()
            val declaredBindings =
                mutableSetOf<Pair<List<PathComponent>, Value.Variable.Stamped>>()
            val completedBindingVariables = mutableSetOf<Value.Variable.Stamped>()
            val contributionsByConsumer =
                mutableMapOf<List<PathComponent>, MutableSet<DemandContributionId>>()
            val installedContributionIds = mutableSetOf<DemandContributionId>()
            val startedResolvers = mutableSetOf<List<PathComponent>>()
            val finishedResolvers = mutableSetOf<List<PathComponent>>()
            val availableOutputs = mutableSetOf<List<PathComponent>>()
            val requiredChildren =
                mutableMapOf<List<PathComponent>, MutableSet<List<PathComponent>>>()

            trace.forEach { event ->
                when (event) {
                    is Resolver25LifecycleEvent.OrchestratorCreated -> {
                        if (!orchestrators.add(event.path)) {
                            violations +=
                                violation(
                                    "orchestrator.duplicate-create",
                                    event,
                                    "Orchestrator created twice at ${event.path}",
                                )
                        }
                    }
                    is Resolver25LifecycleEvent.OrchestratorReady -> {
                        if (event.path !in orchestrators) {
                            violations +=
                                violation(
                                    "orchestrator.ready-before-create",
                                    event,
                                    "Orchestrator became ready before creation at ${event.path}",
                                )
                        }
                        readyOrchestrators += event.path
                    }
                    is Resolver25LifecycleEvent.DemandSubmitted -> {
                        if (event.path !in orchestrators) {
                            violations +=
                                violation(
                                    "contribution.submitted-before-orchestrator",
                                    event,
                                    "Demand submitted before orchestrator creation at ${event.path}",
                                )
                        }
                        contributions[event.contributionId] = event
                        event.consumerCoordinate?.let { consumer ->
                            contributionsByConsumer
                                .getOrPut(consumer, ::linkedSetOf)
                                .add(event.contributionId)
                        }
                    }
                    is Resolver25LifecycleEvent.DemandGrounded -> {
                        val submission = contributions[event.contributionId]
                        if (submission == null) {
                            violations +=
                                violation(
                                    "contribution.grounded-before-submit",
                                    event,
                                    "Unknown contribution ${event.contributionId} was grounded",
                                )
                        } else {
                            if (event.coordinate.dropLast(1) != submission.path) {
                                violations +=
                                    violation(
                                        "contribution.grounded-outside-orchestrator",
                                        event,
                                        "Contribution grounded outside ${submission.path}: " +
                                            event.coordinate,
                                    )
                            }
                            val unboundVariables =
                                submission.selection.key.arguments
                                    .usedVariables()
                                    .filterIsInstance<Value.Variable.Stamped>()
                                    .filterNot(completedBindingVariables::contains)
                            if (unboundVariables.isNotEmpty()) {
                                violations +=
                                    violation(
                                        "binding.grounded-before-completion",
                                        event,
                                        "Contribution grounded before bindings completed: " +
                                            unboundVariables,
                                    )
                            }
                        }
                        grounded += event.contributionId to event.coordinate
                    }
                    is Resolver25LifecycleEvent.GroundedKeyInterned -> {
                        val grounding = event.contributionId to event.coordinate
                        if (grounding !in grounded) {
                            violations +=
                                violation(
                                    "key.interned-before-grounding",
                                    event,
                                    "Key ${event.coordinate} was interned before grounding",
                                )
                        }
                        interned[event.coordinate] = event.kind
                    }
                    is Resolver25LifecycleEvent.GroundedDemandMerged -> {
                        val grounding = event.contributionId to event.coordinate
                        if (grounding !in grounded) {
                            violations +=
                                violation(
                                    "contribution.merged-before-grounding",
                                    event,
                                    "Contribution ${event.contributionId} merged before grounding",
                                )
                        }
                        if (event.coordinate !in interned) {
                            violations +=
                                violation(
                                    "contribution.merged-before-intern",
                                    event,
                                    "Contribution merged before key interning at ${event.coordinate}",
                                )
                        }
                        if (event.beforeLaunch && event.coordinate in sealed) {
                            violations +=
                                violation(
                                    "contribution.prelaunch-merge-after-seal",
                                    event,
                                    "Pre-launch merge was reported after demand sealing",
                                )
                        }
                        if (!event.beforeLaunch && event.coordinate !in sealed) {
                            violations +=
                                violation(
                                    "contribution.postlaunch-merge-before-seal",
                                    event,
                                    "Post-launch merge was reported before demand sealing",
                                )
                        }
                    }
                    is Resolver25LifecycleEvent.ValuePromiseInstalled -> {
                        if (event.coordinate !in interned) {
                            violations +=
                                violation(
                                    "promise.installed-before-intern",
                                    event,
                                    "Promise installed before key interning at ${event.coordinate}",
                                )
                        }
                        promises += event.coordinate
                    }
                    is Resolver25LifecycleEvent.DemandSealed -> {
                        if (event.coordinate !in promises) {
                            violations +=
                                violation(
                                    "demand.sealed-before-promise",
                                    event,
                                    "Demand sealed before promise installation at ${event.coordinate}",
                                )
                        }
                        sealed += event.coordinate
                    }
                    is Resolver25LifecycleEvent.BindingDeclared -> {
                        if (event.ownerCoordinate !in interned) {
                            violations +=
                                violation(
                                    "binding.declared-before-intern",
                                    event,
                                    "Binding declared before owner interning at " +
                                        event.ownerCoordinate,
                                )
                        }
                        declaredBindings += event.ownerCoordinate to event.variable
                    }
                    is Resolver25LifecycleEvent.BindingCompleted -> {
                        val binding = event.ownerCoordinate to event.variable
                        if (binding !in declaredBindings) {
                            violations +=
                                violation(
                                    "binding.completed-before-declare",
                                    event,
                                    "Binding completed before declaration: ${event.variable}",
                                )
                        }
                        completedBindingVariables += event.variable
                    }
                    is Resolver25LifecycleEvent.ResolverStarted -> {
                        if (event.coordinate !in sealed) {
                            violations +=
                                violation(
                                    "resolver.started-before-seal",
                                    event,
                                    "Resolver started before demand sealing at ${event.coordinate}",
                                )
                        }
                        if (interned[event.coordinate] != Resolver25KeyKind.FIELD_RESOLVER) {
                            violations +=
                                violation(
                                    "resolver.started-for-non-resolver-key",
                                    event,
                                    "Resolver started for ${interned[event.coordinate]} at " +
                                        event.coordinate,
                                )
                        }
                        val uninstalledInputs =
                            contributionsByConsumer[event.coordinate]
                                .orEmpty()
                                .filterNot(installedContributionIds::contains)
                        if (uninstalledInputs.isNotEmpty()) {
                            violations +=
                                violation(
                                    "resolver.started-before-input-installation",
                                    event,
                                    "Resolver started before input contributions installed: " +
                                        uninstalledInputs,
                                )
                        }
                        startedResolvers += event.coordinate
                    }
                    is Resolver25LifecycleEvent.ResolverFinished -> {
                        if (event.coordinate !in startedResolvers) {
                            violations +=
                                violation(
                                    "resolver.finished-before-start",
                                    event,
                                    "Resolver finished before start at ${event.coordinate}",
                                )
                        }
                        finishedResolvers += event.coordinate
                    }
                    is Resolver25LifecycleEvent.OutputAvailable -> {
                        if (event.coordinate !in sealed) {
                            violations +=
                                violation(
                                    "output.available-before-seal",
                                    event,
                                    "Output became available before demand sealing at " +
                                        event.coordinate,
                                )
                        }
                        if (
                            event.coordinate in startedResolvers &&
                            event.coordinate !in finishedResolvers
                        ) {
                            violations +=
                                violation(
                                    "output.available-before-resolver-finish",
                                    event,
                                    "Output became available before resolver finish at " +
                                        event.coordinate,
                                )
                        }
                        availableOutputs += event.coordinate
                    }
                    is Resolver25LifecycleEvent.ChildOrchestratorRequired -> {
                        if (event.parentCoordinate !in availableOutputs) {
                            violations +=
                                violation(
                                    "child.required-before-output",
                                    event,
                                    "Child orchestrator required before parent output availability",
                                )
                        }
                        requiredChildren
                            .getOrPut(event.parentCoordinate, ::linkedSetOf)
                            .add(event.childPath)
                    }
                    is Resolver25LifecycleEvent.KeyActivationReady -> {
                        if (event.coordinate !in interned) {
                            violations +=
                                violation(
                                    "key.ready-before-intern",
                                    event,
                                    "Key became activation-ready before interning at " +
                                        event.coordinate,
                                )
                        }
                    }
                    is Resolver25LifecycleEvent.ContributionInstalled -> {
                        val contribution = event.contributionId to event.coordinate
                        if (contribution !in grounded) {
                            violations +=
                                violation(
                                    "contribution.installed-before-grounding",
                                    event,
                                    "Contribution installed before grounding: $contribution",
                                )
                        }
                        installedContributionIds += event.contributionId
                    }
                    is Resolver25LifecycleEvent.ValuePublished -> {
                        if (event.coordinate !in availableOutputs) {
                            violations +=
                                violation(
                                    "value.published-before-output",
                                    event,
                                    "Value published before output availability at " +
                                        event.coordinate,
                                )
                        }
                        val unreadyDescendants =
                            requiredChildren[event.coordinate]
                                .orEmpty()
                                .filterNot(readyOrchestrators::contains)
                        if (unreadyDescendants.isNotEmpty()) {
                            violations +=
                                violation(
                                    "value.published-before-descendant-ready",
                                    event,
                                    "Value published before descendant orchestrators were ready: " +
                                        unreadyDescendants,
                                )
                        }
                    }
                }
            }
            violations
        }

    val oneShotOccurrences =
        TraceValidator<Resolver25LifecycleEvent> { trace ->
            buildList {
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.OrchestratorReady>(),
                    Resolver25LifecycleEvent.OrchestratorReady::path,
                    "orchestrator.duplicate-ready",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.DemandGrounded>(),
                    { event -> event.contributionId to event.coordinate },
                    "contribution.duplicate-grounding",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.GroundedKeyInterned>(),
                    Resolver25LifecycleEvent.GroundedKeyInterned::coordinate,
                    "key.duplicate-intern",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.ValuePromiseInstalled>(),
                    Resolver25LifecycleEvent.ValuePromiseInstalled::coordinate,
                    "promise.duplicate-install",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.DemandSealed>(),
                    Resolver25LifecycleEvent.DemandSealed::coordinate,
                    "demand.duplicate-seal",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.ResolverStarted>(),
                    Resolver25LifecycleEvent.ResolverStarted::coordinate,
                    "resolver.duplicate-start",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.ResolverFinished>(),
                    Resolver25LifecycleEvent.ResolverFinished::coordinate,
                    "resolver.duplicate-finish",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.OutputAvailable>(),
                    Resolver25LifecycleEvent.OutputAvailable::coordinate,
                    "output.duplicate-availability",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<
                        Resolver25LifecycleEvent.ChildOrchestratorRequired
                    >(),
                    { event -> event.parentCoordinate to event.childPath },
                    "child.duplicate-requirement",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.KeyActivationReady>(),
                    Resolver25LifecycleEvent.KeyActivationReady::coordinate,
                    "key.duplicate-ready",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.ContributionInstalled>(),
                    { event -> event.contributionId to event.coordinate },
                    "contribution.duplicate-install",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.ValuePublished>(),
                    Resolver25LifecycleEvent.ValuePublished::coordinate,
                    "value.duplicate-publication",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.BindingDeclared>(),
                    { event -> event.ownerCoordinate to event.variable },
                    "binding.duplicate-declare",
                )
                addDuplicateViolations(
                    trace.filterIsInstance<Resolver25LifecycleEvent.BindingCompleted>(),
                    { event -> event.ownerCoordinate to event.variable },
                    "binding.duplicate-complete",
                )
            }
        }

    val successfulCompletion =
        TraceValidator<Resolver25LifecycleEvent> { trace ->
            val violations = mutableListOf<TraceViolation>()
            val created =
                trace.filterIsInstance<Resolver25LifecycleEvent.OrchestratorCreated>()
                    .mapTo(linkedSetOf(), Resolver25LifecycleEvent.OrchestratorCreated::path)
            val ready =
                trace.filterIsInstance<Resolver25LifecycleEvent.OrchestratorReady>()
                    .mapTo(linkedSetOf(), Resolver25LifecycleEvent.OrchestratorReady::path)
            (created - ready).forEach { path ->
                violations +=
                    TraceViolation(
                        "orchestrator.not-ready",
                        null,
                        "Created orchestrator never became ready at $path",
                    )
            }

            val submitted =
                trace.filterIsInstance<Resolver25LifecycleEvent.DemandSubmitted>()
                    .associateBy(Resolver25LifecycleEvent.DemandSubmitted::contributionId)
            val grounded =
                trace.filterIsInstance<Resolver25LifecycleEvent.DemandGrounded>()
                    .groupBy(Resolver25LifecycleEvent.DemandGrounded::contributionId)
            submitted.forEach { (contribution, submission) ->
                val count = grounded[contribution]?.size ?: 0
                if (count != 1) {
                    violations +=
                        TraceViolation(
                            "contribution.grounding-count",
                            submission.sequence,
                            "Contribution grounded $count times: $contribution",
                        )
                }
            }

            val transitions =
                buildList {
                    addAll(
                        trace.filterIsInstance<
                            Resolver25LifecycleEvent.GroundedKeyInterned
                        >().map { event -> event.contributionId to event.coordinate },
                    )
                    addAll(
                        trace.filterIsInstance<
                            Resolver25LifecycleEvent.GroundedDemandMerged
                        >().map { event -> event.contributionId to event.coordinate },
                    )
                }.groupingBy { transition -> transition }
                    .eachCount()
            val installations =
                trace.filterIsInstance<Resolver25LifecycleEvent.ContributionInstalled>()
                    .map { event -> event.contributionId to event.coordinate }
                    .groupingBy { installation -> installation }
                    .eachCount()
            trace.filterIsInstance<Resolver25LifecycleEvent.DemandGrounded>()
                .forEach { event ->
                    val grounding = event.contributionId to event.coordinate
                    val transitionCount = transitions[grounding] ?: 0
                    if (transitionCount != 1) {
                        violations +=
                            violation(
                                "contribution.transition-count",
                                event,
                                "Grounded contribution has $transitionCount transitions",
                            )
                    }
                    val installationCount = installations[grounding] ?: 0
                    if (installationCount != 1) {
                        violations +=
                            violation(
                                "contribution.installation-count",
                                event,
                                "Grounded contribution installed $installationCount times",
                            )
                    }
                }

            val bindingDeclarations =
                trace.filterIsInstance<Resolver25LifecycleEvent.BindingDeclared>()
                    .associateBy { event -> event.ownerCoordinate to event.variable }
            val bindingCompletions =
                trace.filterIsInstance<Resolver25LifecycleEvent.BindingCompleted>()
                    .associateBy { event -> event.ownerCoordinate to event.variable }
            (bindingDeclarations.keys - bindingCompletions.keys).forEach { binding ->
                violations +=
                    TraceViolation(
                        "binding.not-completed",
                        bindingDeclarations.getValue(binding).sequence,
                        "Declared binding was never completed: $binding",
                    )
            }

            val resolverStarts =
                trace.filterIsInstance<Resolver25LifecycleEvent.ResolverStarted>()
                    .associateBy(Resolver25LifecycleEvent.ResolverStarted::coordinate)
            val resolverFinishes =
                trace.filterIsInstance<Resolver25LifecycleEvent.ResolverFinished>()
                    .associateBy(Resolver25LifecycleEvent.ResolverFinished::coordinate)
            (resolverStarts.keys - resolverFinishes.keys).forEach { coordinate ->
                violations +=
                    TraceViolation(
                        "resolver.not-finished",
                        resolverStarts.getValue(coordinate).sequence,
                        "Started resolver never finished at $coordinate",
                    )
            }
            val interned =
                trace.filterIsInstance<Resolver25LifecycleEvent.GroundedKeyInterned>()
                    .associateBy(Resolver25LifecycleEvent.GroundedKeyInterned::coordinate)
            val resolverCoordinates =
                interned.filterValues { event ->
                    event.kind == Resolver25KeyKind.FIELD_RESOLVER
                }.keys
            (resolverCoordinates - resolverStarts.keys).forEach { coordinate ->
                violations +=
                    TraceViolation(
                        "resolver.not-started",
                        interned.getValue(coordinate).sequence,
                        "Resolver-backed key never started at $coordinate",
                    )
            }
            val activationReady =
                trace.filterIsInstance<Resolver25LifecycleEvent.KeyActivationReady>()
                    .mapTo(linkedSetOf(), Resolver25LifecycleEvent.KeyActivationReady::coordinate)
            (interned.keys - activationReady).forEach { coordinate ->
                violations +=
                    TraceViolation(
                        "key.not-ready",
                        interned.getValue(coordinate).sequence,
                        "Interned key never became activation-ready at $coordinate",
                    )
            }

            val sealed =
                trace.filterIsInstance<Resolver25LifecycleEvent.DemandSealed>()
                    .associateBy(Resolver25LifecycleEvent.DemandSealed::coordinate)
            val available =
                trace.filterIsInstance<Resolver25LifecycleEvent.OutputAvailable>()
                    .mapTo(linkedSetOf(), Resolver25LifecycleEvent.OutputAvailable::coordinate)
            val published =
                trace.filterIsInstance<Resolver25LifecycleEvent.ValuePublished>()
                    .mapTo(linkedSetOf(), Resolver25LifecycleEvent.ValuePublished::coordinate)
            (sealed.keys - available).forEach { coordinate ->
                violations +=
                    TraceViolation(
                        "output.not-available",
                        sealed.getValue(coordinate).sequence,
                        "Sealed key never exposed output at $coordinate",
                    )
            }
            (sealed.keys - published).forEach { coordinate ->
                violations +=
                    TraceViolation(
                        "value.not-published",
                        sealed.getValue(coordinate).sequence,
                        "Sealed key never published a value at $coordinate",
                    )
            }

            violations
        }

    val successfulTrace =
        listOf(
            sequenceClock,
            referencesAndOrder,
            oneShotOccurrences,
            successfulCompletion,
        )
}

internal fun List<Resolver25LifecycleEvent>.assertValidResolver25LifecycleTrace() {
    val violations = Resolver25LifecycleTraceValidators.successfulTrace.validate(this)
    assertTrue(
        violations.isEmpty(),
        violations.joinToString(
            prefix = "Resolver25 lifecycle trace violations:\n",
            separator = "\n",
        ) { violation ->
            "${violation.code} at ${violation.sequence}: ${violation.message}"
        },
    )
}

private fun violation(
    code: String,
    event: Resolver25LifecycleEvent,
    message: String,
): TraceViolation =
    TraceViolation(code, event.sequence, message)

private fun <E : Resolver25LifecycleEvent, K> MutableList<TraceViolation>.addDuplicateViolations(
    events: List<E>,
    key: (E) -> K,
    code: String,
) {
    events.groupBy(key)
        .filterValues { occurrences -> occurrences.size > 1 }
        .forEach { (identity, occurrences) ->
            occurrences.drop(1).forEach { event ->
                this +=
                    violation(
                        code,
                        event,
                        "Lifecycle event repeated for $identity",
                    )
            }
        }
}
