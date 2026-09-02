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
 * Qplan's semantic registry supports nested input-object paths, but this adapter can recover only
 * the one-segment argument recipes retained by the production Engine API. Nested input-object
 * paths and arbitrary [VariablesResolver] callbacks are rejected here rather than approximated.
 */
internal class RequiredSelectionSetVariableRecovery(
    private val schema: ViaductSchema,
) {
    /**
     * One recovered source configuration before it is compiled into a qplan declaration.
     *
     * [variable] is the exact template decoded from the resolver object fragment. [argumentName]
     * is the sole path segment retained by Engine API [FromArgument].
     */
    data class RecoveredFromArgument(
        override val variable: Arguments.Variable,
        val argumentName: String,
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

    sealed interface RecoveredConfiguration {
        val variable: Arguments.Variable
    }

    /**
     * Returns declarations ready for `TestWorld.fromSDL(variableProviders = ...)`.
     */
    fun recover(
        field: ViaductSchema.ObjectField,
        objectFragment: Fragment,
        requiredSelectionSet: RequiredSelectionSet?,
    ): Map<Arguments.Variable, VariableDeclaration> =
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
    ): Map<Arguments.Variable, VariableDeclaration> {
        val objectFragmentSource = objectRequiredSelectionSet?.fragmentSource()
        val queryFragmentSource = queryRequiredSelectionSet?.fragmentSource()
        return recoverConfigurations(
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
            .associate { configuration ->
                configuration.variable to
                    when (configuration) {
                        is RecoveredFromArgument ->
                            schema.fromArgument(field, configuration.argumentName)
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
                    }
            }
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
                            require(resolver.path.size == 1) {
                                "Qplan feature tests do not support nested FromArgument path " +
                                    "${resolver.path.joinToString(".")} for \$${resolver.name} on $coordinate"
                            }
                            val source = RecoveredSource.FromArgument(resolver.path.single())
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
                        else ->
                            throw IllegalArgumentException(
                                "Qplan feature tests support only FromArgument and from-field " +
                                    "variable providers; $coordinate uses " +
                                    resolver::class.qualifiedName,
                            )
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
                        argumentName = source.argumentName,
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
            }
        }
    }
}

private sealed interface RecoveredSource {
    data class FromArgument(
        val argumentName: String,
    ) : RecoveredSource

    data class FromObjectField(
        val responsePath: List<String>,
    ) : RecoveredSource

    data class FromQueryField(
        val responsePath: List<String>,
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
