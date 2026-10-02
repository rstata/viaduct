package execution.testing

import graphql.language.AstPrinter
import model.Arguments
import model.Fragment
import model.fragmentFrom
import model.testing.VariableDeclaration
import model.testing.fromArgument
import model.testing.fromObjectField
import model.testing.fromQueryField
import model.usedVariables
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.Validated
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.resolve
import viaduct.engine.runtime.tenantloading.InvalidVariableException
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.ParsedSelections

/**
 * Recovers qplan registry variable declarations from a field executor's object and Query RSSes.
 *
 * This reverses the `FromArgumentVariable` and `FromObjectFieldVariable` recipes compiled by:
 *
 * - `core/engine/api/.../bootstrap/executionregistry/RequiredSelectionSetSupport.kt`
 * - `core/engine/api/.../VariablesResolver.kt`, especially `Builder.buildOne` and
 *   `createFromArgument`
 * - `core/tenant/runtime/.../bootstrap/RequiredSelectionSetFactory.kt`, which wraps the resulting
 *   resolvers in [Validated] and installs them on the field executor's [RequiredSelectionSet]
 *
 * A [FromFieldVariablesResolver] does not retain whether it originated as `fromObjectField` or
 * `fromQueryField`. Recovery reconstructs that distinction by checking whether its nested RSS is
 * exactly the executor's object or Query RSS filtered to the retained response-key path. The
 * nested RSS is recovered recursively so providers used by argument-bearing path selections are
 * validated too.
 *
 * Qplan's semantic registry supports nested input-object paths, so this adapter retains every
 * segment in the production Engine API argument recipe. Callbacks with their own required
 * selections are rejected here rather than approximated. All disjoint no-RSS callbacks are
 * composed as the field resolver's variables provider.
 */
internal class RequiredSelectionSetVariableRecovery(
    private val schema: ViaductSchema,
) {
    /**
     * One recovered source configuration before it is compiled into a qplan declaration.
     *
     * [variable] is the exact template decoded from the resolver object fragment. [argumentPath]
     * is the path retained by Engine API [FromArgument].
     */
    data class RecoveredFromArgument(
        override val variable: Arguments.Variable,
        val argumentPath: List<String>,
    ) : RecoveredConfiguration

    /**
     * One recovered object response path before it is compiled into a qplan declaration.
     */
    data class RecoveredFromObjectField(
        override val variable: Arguments.Variable,
        val responsePath: List<String>,
    ) : RecoveredConfiguration

    /**
     * One recovered Query response path before it is compiled into a qplan declaration.
     */
    data class RecoveredFromQueryField(
        override val variable: Arguments.Variable,
        val responsePath: List<String>,
    ) : RecoveredConfiguration

    /** One variable supplied by the field resolver's shared tenant callback. */
    data class RecoveredFromProvider(
        override val variable: Arguments.Variable,
        val resolver: VariablesResolver,
    ) : RecoveredConfiguration

    sealed interface RecoveredConfiguration {
        val variable: Arguments.Variable
    }

    data class RecoveredVariablesProvider(
        val variableNames: Set<String>,
        val resolvers: List<VariablesResolver>,
    ) {
        suspend fun resolve(
            ctx: VariablesResolver.ResolveCtx,
            context: viaduct.engine.api.EngineExecutionContext,
        ): Map<String, Any?> = resolvers.resolve(ctx, context)
    }

    data class RecoveryResult(
        val declarations: Map<Arguments.Variable, VariableDeclaration>,
        val variablesProvider: RecoveredVariablesProvider?,
    )

    /**
     * Returns declarations ready for `TestWorld.fromSDL(variableProviders = ...)`.
     */
    fun recover(
        field: ViaductSchema.ObjectField,
        objectFragment: Fragment,
        requiredSelectionSet: RequiredSelectionSet?,
    ): RecoveryResult =
        recover(
            field = field,
            objectFragment = objectFragment,
            objectRequiredSelectionSet = requiredSelectionSet,
            queryFragment = null,
            queryRequiredSelectionSet = null,
        )

    /**
     * Returns declarations from both resolver fragments ready for
     * `TestWorld.fromSDL(variableProviders = ...)`.
     */
    fun recover(
        field: ViaductSchema.ObjectField,
        objectFragment: Fragment,
        objectRequiredSelectionSet: RequiredSelectionSet?,
        queryFragment: Fragment?,
        queryRequiredSelectionSet: RequiredSelectionSet?,
    ): RecoveryResult {
        val objectFragmentSource = objectRequiredSelectionSet?.fragmentSource()
        val queryFragmentSource = queryRequiredSelectionSet?.fragmentSource()
        val configurations = recoverConfigurations(
            field = field,
            fragments = listOfNotNull(objectFragment, queryFragment),
            variableResolvers =
                listOfNotNull(objectRequiredSelectionSet, queryRequiredSelectionSet)
                    .flatMap(RequiredSelectionSet::variablesResolvers)
                    .distinct(),
            objectSelections = objectRequiredSelectionSet?.selections,
            querySelections = queryRequiredSelectionSet?.selections,
            observedSources = linkedMapOf(),
        )
        val providerConfigurations = configurations.filterIsInstance<RecoveredFromProvider>()
        val providerResolvers = providerConfigurations.map { it.resolver }.distinct()
        return RecoveryResult(
            declarations =
                configurations.filterNot { it is RecoveredFromProvider }.associate { configuration ->
                    configuration.variable to
                        try {
                            when (configuration) {
                                is RecoveredFromArgument ->
                                    schema.fromArgument(field, configuration.argumentPath)
                                is RecoveredFromObjectField ->
                                    schema.fromObjectField(
                                        objectFragmentSource =
                                            checkNotNull(objectFragmentSource) {
                                                "FromObjectField recovery requires an object RSS"
                                            },
                                        responsePath = configuration.responsePath,
                                        variableField = field,
                                    )
                                is RecoveredFromQueryField ->
                                    schema.fromQueryField(
                                        queryFragmentSource =
                                            checkNotNull(queryFragmentSource) {
                                                "FromQueryField recovery requires a Query RSS"
                                            },
                                        responsePath = configuration.responsePath,
                                        variableField = field,
                                    )
                                is RecoveredFromProvider -> error("Provider configurations are separate")
                            }
                        } catch (failure: IllegalArgumentException) {
                            if (failure.message.orEmpty().contains("lossy type condition")) {
                                throw InvalidVariableException(
                                    field.containingDef.name to field.name,
                                    configuration.variable.variableName,
                                    failure.message ?: "Invalid variable source",
                                )
                            }
                            throw failure
                        }
                },
            variablesProvider =
                providerResolvers.takeIf { it.isNotEmpty() }?.let { resolvers ->
                    RecoveredVariablesProvider(
                        variableNames = providerConfigurations.mapTo(linkedSetOf()) {
                            it.variable.variableName
                        },
                        resolvers = resolvers,
                    )
                },
        )
    }

    /**
     * Inverts supported Engine API resolvers and associates them with exact fragment templates.
     */
    fun recoverConfigurations(
        field: ViaductSchema.ObjectField,
        objectFragment: Fragment,
        requiredSelectionSet: RequiredSelectionSet?,
    ): List<RecoveredConfiguration> {
        require(objectFragment.nominalType == field.containingDef) {
            "Object required selection type ${objectFragment.nominalType.name} does not match " +
                "${field.containingDef.name}.${field.name}"
        }
        return recoverConfigurations(
            field = field,
            fragments = listOf(objectFragment),
            variableResolvers = requiredSelectionSet?.variablesResolvers.orEmpty(),
            objectSelections = requiredSelectionSet?.selections,
            querySelections = null,
            observedSources = linkedMapOf(),
        )
    }

    private fun recoverConfigurations(
        field: ViaductSchema.ObjectField,
        fragments: List<Fragment>,
        variableResolvers: List<VariablesResolver>,
        objectSelections: ParsedSelections?,
        querySelections: ParsedSelections?,
        observedSources: MutableMap<String, RecoveredSource>,
    ): List<RecoveredConfiguration> {
        val coordinate = "${field.containingDef.name}.${field.name}"

        val variablesByName =
            fragments
                .flatMap { fragment -> fragment.subselections.usedVariables() }
                .toSet()
                .also { variables ->
                    require(variables.all { it.isTemplate && it.field == field }) {
                        "Required selections for $coordinate contain a variable owned by another field"
                    }
                }.groupBy(Arguments.Variable::variableName)
        require(variablesByName.values.all { it.size == 1 }) {
            "Required selections for $coordinate contain ambiguous variable templates"
        }

        val recoveredByName =
            variableResolvers
                .map(::unwrapValidated)
                .distinct()
                .filter { resolver -> resolver.variableNames.isNotEmpty() }
                .flatMap { resolver ->
                    when (resolver) {
                        is FromArgument -> {
                            require(resolver.variableNames == setOf(resolver.name)) {
                                "FromArgument ${resolver.name} on $coordinate reports inconsistent variable names"
                            }
                            require(resolver.path.isNotEmpty()) {
                                "FromArgument ${resolver.name} on $coordinate has an empty path"
                            }
                            val source = RecoveredSource.FromArgument(resolver.path)
                            observedSources.record(resolver.name, source, coordinate)
                            listOf(resolver.name to source)
                        }
                        is FromFieldVariablesResolver -> {
                            require(resolver.variableNames == setOf(resolver.name)) {
                                "FromFieldVariablesResolver ${resolver.name} on $coordinate " +
                                    "reports inconsistent variable names"
                            }
                            val selectedSources =
                                listOfNotNull(
                                    objectSelections?.filterToPath(resolver.path),
                                    querySelections?.filterToPath(resolver.path),
                                )
                            require(selectedSources.isNotEmpty()) {
                                "FromFieldVariablesResolver ${resolver.name} on $coordinate does not " +
                                    "select path ${resolver.path.joinToString(".")} in its object or Query RSS"
                            }
                            val matchingSources =
                                listOfNotNull(
                                    objectSelections.matchingSource(
                                        resolver.path,
                                        resolver.requiredSelectionSet.selections,
                                        RecoveredSource.FromObjectField(resolver.path),
                                    ),
                                    querySelections.matchingSource(
                                        resolver.path,
                                        resolver.requiredSelectionSet.selections,
                                        RecoveredSource.FromQueryField(resolver.path),
                                    ),
                                )
                            require(matchingSources.isNotEmpty()) {
                                "FromFieldVariablesResolver ${resolver.name} on $coordinate has a " +
                                    "nested RSS that does not match path " +
                                    resolver.path.joinToString(".")
                            }
                            require(matchingSources.size == 1) {
                                "FromFieldVariablesResolver ${resolver.name} on $coordinate ambiguously " +
                                    "matches path ${resolver.path.joinToString(".")} in both its object and Query RSS"
                            }
                            val nestedFragment =
                                schema.fragmentFrom(
                                    resolver.requiredSelectionSet.fragmentSource(),
                                    variableField = field,
                                )
                            recoverConfigurations(
                                field = field,
                                fragments = listOf(nestedFragment),
                                variableResolvers = resolver.requiredSelectionSet.variablesResolvers,
                                objectSelections = objectSelections,
                                querySelections = querySelections,
                                observedSources = observedSources,
                            )
                            val source = matchingSources.single()
                            observedSources.record(resolver.name, source, coordinate)
                            listOf(resolver.name to source)
                        }
                        else -> {
                            require(resolver.requiredSelectionSet == null) {
                                "Qplan variables-provider callbacks may not have required selections; " +
                                    "$coordinate uses ${resolver::class.qualifiedName}"
                            }
                            resolver.variableNames.map { name ->
                                val source = RecoveredSource.FromProvider(resolver)
                                observedSources.record(name, source, coordinate)
                                name to source
                            }
                        }
                    }
                }.groupBy({ (name, _) -> name }, { (_, source) -> source })

        require(recoveredByName.values.all { it.size == 1 }) {
            "Required selections for $coordinate contain duplicate variable providers"
        }

        val missing = variablesByName.keys - recoveredByName.keys
        require(missing.isEmpty()) {
            "Required selections for $coordinate have variables without providers: " +
                missing.sorted().joinToString { "\$$it" }
        }
        val unused = recoveredByName.keys - variablesByName.keys
        require(unused.isEmpty()) {
            "Required selections for $coordinate have unused variable providers: " +
                unused.sorted().joinToString { "\$$it" }
        }

        return variablesByName.map { (name, variables) ->
            when (val source = recoveredByName.getValue(name).single()) {
                is RecoveredSource.FromArgument ->
                    RecoveredFromArgument(
                        variable = variables.single(),
                        argumentPath = source.argumentPath,
                    )
                is RecoveredSource.FromObjectField ->
                    RecoveredFromObjectField(
                        variable = variables.single(),
                        responsePath = source.responsePath,
                    )
                is RecoveredSource.FromQueryField ->
                    RecoveredFromQueryField(
                        variable = variables.single(),
                        responsePath = source.responsePath,
                    )
                is RecoveredSource.FromProvider ->
                    RecoveredFromProvider(
                        variable = variables.single(),
                        resolver = source.resolver,
                    )
            }
        }
    }
}

private sealed interface RecoveredSource {
    data class FromArgument(
        val argumentPath: List<String>,
    ) : RecoveredSource

    data class FromObjectField(
        val responsePath: List<String>,
    ) : RecoveredSource

    data class FromQueryField(
        val responsePath: List<String>,
    ) : RecoveredSource

    data class FromProvider(
        val resolver: VariablesResolver,
    ) : RecoveredSource
}

private fun <T : RecoveredSource> ParsedSelections?.matchingSource(
    path: List<String>,
    nestedSelections: ParsedSelections,
    source: T,
): T? {
    val selected = this?.filterToPath(path) ?: return null
    return source.takeIf { ParsedSelections.equals(selected, nestedSelections) }
}

private fun MutableMap<String, RecoveredSource>.record(
    variableName: String,
    source: RecoveredSource,
    coordinate: String,
) {
    val previous = putIfAbsent(variableName, source)
    require(previous == null || previous == source) {
        "Variable \$$variableName on $coordinate has inconsistent providers across nested RSSes"
    }
}

private fun RequiredSelectionSet.fragmentSource(): String =
    "fragment _ on ${selections.typeName} ${AstPrinter.printAst(selections.selections)}"

private tailrec fun unwrapValidated(resolver: VariablesResolver): VariablesResolver =
    if (resolver is Validated) {
        unwrapValidated(resolver.delegate)
    } else {
        resolver
    }
