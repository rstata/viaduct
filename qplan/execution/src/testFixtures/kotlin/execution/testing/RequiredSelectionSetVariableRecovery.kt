package execution.testing

import graphql.language.AstPrinter
import model.Arguments
import model.Fragment
import model.fragmentFrom
import model.testing.VariableDeclaration
import model.testing.fromArgument
import model.testing.fromObjectField
import model.usedVariables
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.Validated
import viaduct.engine.api.VariablesResolver
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.ParsedSelections

/**
 * Recovers qplan registry variable declarations from a field executor's object RSS.
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
 * `fromQueryField`. Recovery proves the former by checking that its nested RSS is exactly the
 * executor's object RSS filtered to the retained response-key path. The nested RSS is recovered
 * recursively so providers used by argument-bearing path selections are validated too.
 *
 * Qplan's semantic registry supports nested input-object paths, but this adapter can recover only
 * the one-segment argument recipes retained by the production Engine API. Nested input-object
 * paths, query-field paths, and arbitrary [VariablesResolver] callbacks are rejected here rather
 * than approximated.
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
    ): Map<Arguments.Variable, VariableDeclaration> {
        val objectFragmentSource = requiredSelectionSet?.fragmentSource()
        return recoverConfigurations(field, objectFragment, requiredSelectionSet)
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
    ): List<RecoveredConfiguration> =
        recoverConfigurations(
            field = field,
            objectFragment = objectFragment,
            requiredSelectionSet = requiredSelectionSet,
            objectSelections = requiredSelectionSet?.selections,
            observedSources = linkedMapOf(),
        )

    private fun recoverConfigurations(
        field: ViaductSchema.ObjectField,
        objectFragment: Fragment,
        requiredSelectionSet: RequiredSelectionSet?,
        objectSelections: ParsedSelections?,
        observedSources: MutableMap<String, RecoveredSource>,
    ): List<RecoveredConfiguration> {
        val coordinate = "${field.containingDef.name}.${field.name}"
        require(objectFragment.nominalType == field.containingDef) {
            "Object required selection type ${objectFragment.nominalType.name} does not match $coordinate"
        }

        val variablesByName =
            objectFragment.subselections
                .usedVariables()
                .also { variables ->
                    require(variables.all { it.isTemplate && it.field == field }) {
                        "Object required selections for $coordinate contain a variable owned by another field"
                    }
                }.groupBy(Arguments.Variable::variableName)
        require(variablesByName.values.all { it.size == 1 }) {
            "Object required selections for $coordinate contain ambiguous variable templates"
        }

        val recoveredByName =
            requiredSelectionSet
                ?.variablesResolvers
                .orEmpty()
                .map(::unwrapValidated)
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
                            val rootObjectSelections =
                                checkNotNull(objectSelections) {
                                    "FromFieldVariablesResolver ${resolver.name} on $coordinate " +
                                        "has no object RSS"
                                }
                            val expectedSource =
                                checkNotNull(rootObjectSelections.filterToPath(resolver.path)) {
                                    "FromFieldVariablesResolver ${resolver.name} on $coordinate " +
                                        "does not select object path ${resolver.path.joinToString(".")}"
                                }
                            require(
                                ParsedSelections.equals(
                                    expectedSource,
                                    resolver.requiredSelectionSet.selections,
                                ),
                            ) {
                                "FromFieldVariablesResolver ${resolver.name} on $coordinate has a " +
                                    "nested RSS that does not match object path " +
                                    resolver.path.joinToString(".")
                            }
                            val nestedFragment =
                                schema.fragmentFrom(
                                    resolver.requiredSelectionSet.fragmentSource(),
                                    variableField = field,
                                )
                            recoverConfigurations(
                                field,
                                nestedFragment,
                                resolver.requiredSelectionSet,
                                rootObjectSelections,
                                observedSources,
                            )
                            val source = RecoveredSource.FromObjectField(resolver.path)
                            observedSources.record(resolver.name, source, coordinate)
                            listOf(resolver.name to source)
                        }
                        else ->
                            throw IllegalArgumentException(
                                "Qplan feature tests support only FromArgument and object-path " +
                                    "variable providers; $coordinate uses " +
                                    resolver::class.qualifiedName,
                            )
                    }
                }.groupBy({ (name, _) -> name }, { (_, source) -> source })

        require(recoveredByName.values.all { it.size == 1 }) {
            "Object required selections for $coordinate contain duplicate variable providers"
        }

        val missing = variablesByName.keys - recoveredByName.keys
        require(missing.isEmpty()) {
            "Object required selections for $coordinate have variables without providers: " +
                missing.sorted().joinToString { "\$$it" }
        }
        val unused = recoveredByName.keys - variablesByName.keys
        require(unused.isEmpty()) {
            "Object required selections for $coordinate have unused variable providers: " +
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
