package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult

import model.Assumptions
import model.EngineErrorDataReadException
import model.EngineOutputData
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.ObjectSelectionForest
import model.Arguments
import model.PathComponent
import model.Selection
import model.SelectionForest
import model.applicableGroundSelections
import model.arg
import model.concatenateSelectionForests
import model.engineObjectDataOf
import model.materializeSelectionForestOf
import model.outputValue
import model.schemaType
import model.selectionForestOf
import model.stampVars
import model.toCanonicalMaterializeSelectionForest
import viaduct.engine.api.EngineObjectData

/** A deterministic partial map from resolved object and Query fragments plus arguments to an output value. */
typealias FieldResolverFunction =
    (EngineObjectData.Sync, EngineObjectData.Sync, Arguments.Resolved) -> EngineOutputData?

/** Observes one complete (null demand) or selective field-resolver application boundary. */
typealias FieldResolverApplicationObserver =
    (EngineObjectData.Sync, Arguments.Resolved, SelectionForest?) -> Unit

/** Paired resolver-object-fragment views instantiated from one resolver occurrence. */
sealed interface ResolverObjectFragment {
    val materializeSelections: MaterializeSelectionForest
    val constructionSelections: SelectionForest

    /** Object-path definitions instantiated with this fragment's variable identities. */
    val pathVariableDefinitions: List<StampedObjectPathDefinition>
}

/** Paired resolver-query-fragment views instantiated from one resolver occurrence. */
sealed interface ResolverQueryFragment {
    val materializeSelections: MaterializeSelectionForest
    val constructionSelections: SelectionForest
}

/**
 * A field resolver supplied by the reasoning world's external resolver registry.
 *
 * Equality is undefined. Resolver-demand identity is expressed with canonical object fields
 * instead.
 *
 * [objectFragment] is the direct parent-object input requirement. [queryFragment] is the
 * independently resolved Query-rooted input requirement. In a canonical registry entry,
 * [variables] maps every variable template defined by this resolver and used by either fragment
 * to its argument or nonempty alias-free object-field path definition.
 *
 * ### Invariant: resolver-fixed-object-fragment-shape
 *
 * [objectFragment] is specialized to the resolver field's concrete parent type. [queryFragment]
 * is specialized to the canonical Query type.
 *
 * ### Invariant: field-resolver-variable-definitions
 *
 * Every variable is defined by this resolver's field. A [VariableDefinition.FromArgument]
 * references one schema-valid input path rooted at an argument belonging to that field. A
 * [VariableDefinition.FromObjectField] is a valid selection path relative to that field's
 * containing type and is structurally contained by [objectFragment]; its factory additionally
 * ensures that the path does not cross a list and ends at a simple value.
 */
class FieldResolver private constructor(
    val field: ViaductSchema.ObjectField,
    private val objectFragmentTemplate: MaterializeSelectionForest,
    private val queryFragmentTemplate: MaterializeSelectionForest,
    private val queryType: ViaductSchema.Object,
    val variables: Map<Arguments.Variable, VariableDefinition>,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    val objectFragment: SelectionForest =
        objectFragmentTemplate.constructionSelections()

    val queryFragment: SelectionForest =
        queryFragmentTemplate.constructionSelections()

    /**
     * Returns the exact object fragment with every variable template stamped at [path], while
     * retaining ordinary unstamped arguments for compatibility with existing resolvers.
     *
     * A synthetic copy of each path-variable provider path marks the variable definition.
     */
    fun stampVars(
        path: List<PathComponent>,
    ): SelectionForest =
        instantiateObjectFragmentAt(path).constructionSelections

    /** Instantiates response-preserving and construction views for one resolver occurrence. */
    fun instantiateObjectFragmentAt(
        path: List<PathComponent>,
    ): ResolverObjectFragment {
        val materializeSelections = objectFragmentTemplate.stampVariables(path)
        val stampedFragment: SelectionForest =
            materializeSelections.constructionSelections()
        val pathVariableDefinitions = stampedPathVarDefinitions(path)
        val pathVarSelections: SelectionForest =
            pathVariableDefinitions
                .map { definition ->
                    stampedFragment.markProviderPath(
                        path = definition.path,
                        variable = definition.variable,
                    )
                }.concatenateSelectionForests()
        return ResolverObjectFragmentImpl(
            materializeSelections = materializeSelections,
            constructionSelections = stampedFragment + pathVarSelections,
            pathVariableDefinitions = pathVariableDefinitions,
        )
    }

    /** Instantiates response-preserving and construction query views at one resolver path. */
    fun instantiateQueryFragmentAt(
        path: List<PathComponent>,
    ): ResolverQueryFragment {
        val materializeSelections = queryFragmentTemplate.stampVariables(path)
        return ResolverQueryFragmentImpl(
            materializeSelections = materializeSelections,
            constructionSelections = materializeSelections.constructionSelections(),
        )
    }

    /** Returns the object fragment with every variable template instantiated at [path]. */
    fun stamp(
        path: List<PathComponent>,
    ): SelectionForest = instantiateObjectFragmentAt(path).constructionSelections

    /** Returns this resolver's path-variable definitions stamped at exact [sitePath]. */
    fun stampedPathVarDefinitions(
        sitePath: List<PathComponent>,
    ): List<StampedObjectPathDefinition> =
        variables.mapNotNull { (variable, definition) ->
            (definition as? VariableDefinition.FromObjectField)?.let {
                StampedObjectPathDefinition.of(
                    variable = variable.stamp(sitePath),
                    path =
                        it.path.map { key ->
                            ObjectEngineResult.Key.of(
                                field = key.field,
                                arguments =
                                    key.arguments.stampVars(
                                        key.field,
                                        sitePath,
                                    ),
                            )
                        },
                )
            }
        }

    /** Returns this resolver's object fragment grounded at exact occurrence [path]. */
    context(world: Assumptions)
    fun objectFragmentAt(
        path: List<PathComponent>,
    ): ObjectSelectionForest =
        stampVars(path)
            .applicableGroundSelections(field.containingDef)

    /** Applies this field resolver, projecting its result only for selective resolver worlds. */
    context(world: Assumptions)
    internal operator fun invoke(
        input: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
        selections: SelectionForest = selectionForestOf(),
    ): EngineOutputData? =
        invoke(
            input = input,
            queryValue = engineObjectDataOf(queryType),
            arguments = arguments,
            selections = selections,
        )

    /** Applies this field resolver, projecting its result only for selective resolver worlds. */
    context(world: Assumptions)
    operator fun invoke(
        input: EngineObjectData.Sync,
        queryValue: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
        selections: SelectionForest = selectionForestOf(),
    ): EngineOutputData? {
        applicationObserver(
            input,
            arguments,
            selections.takeIf { world.selectiveResolvers },
        )
        val output = evaluateRelation(input, queryValue, arguments)
        return if (world.selectiveResolvers) {
            output.snipToDemand(projectionDemand(selections))
        } else {
            output
        }
    }

    /**
     * Evaluates the deterministic function relation for a semantic judgment.
     *
     * This is not an observed resolver application and establishes no execution-count property.
     */
    fun evaluateRelation(
        input: EngineObjectData.Sync,
        queryValue: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
    ): EngineOutputData? {
        require(queryValue.schemaType == queryType) {
            "Query value type ${queryValue.schemaType.name} does not match ${queryType.name}"
        }
        val output =
            try {
                function(input, queryValue, arguments)
            } catch (exception: EngineErrorDataReadException) {
                exception.errorData
            }
        output.requireArgumentlessObjectFields()
        return output
    }

    companion object {
        /**
         * Constructs one fully assembled canonical registry entry.
         *
         * External composition is responsible for lowering coordinates and attaching variables and
         * observers before calling this factory.
         */
        fun of(
            field: ViaductSchema.ObjectField,
            objectFragment: MaterializeSelectionForest,
            queryFragment: MaterializeSelectionForest,
            queryType: ViaductSchema.Object,
            variables: Map<Arguments.Variable, VariableDefinition>,
            function: FieldResolverFunction,
            projectionDemand: (SelectionForest) -> SelectionForest = { it },
            applicationObserver: FieldResolverApplicationObserver = { _, _, _ -> },
        ): FieldResolver {
            require(
                objectFragment.all { selection ->
                    selection.key.field.containingDef == field.containingDef &&
                        selection.possibleTypes == setOf(field.containingDef)
                },
            ) {
                "Object fragment must be specialized to ${field.containingDef.name}"
            }
            require(
                queryFragment.all { selection ->
                    selection.key.field.containingDef == queryType &&
                        selection.possibleTypes == setOf(queryType)
                },
            ) {
                "Query fragment must be specialized to ${queryType.name}"
            }
            require(queryType.name == "Query") {
                "Query fragment type must be Query"
            }
            objectFragment.collect(field.containingDef)
            queryFragment.collect(queryType)
            variables.forEach { (variable, definition) ->
                require(variable.isTemplate) {
                    "Resolver registry variables must be templates"
                }
                require(variable.field == field) {
                    "Variable ${variable.variableName} is not defined by a resolver on " +
                        "${field.containingDef.name}/${field.name}"
                }
                when (definition) {
                    is VariableDefinition.FromArgument -> {
                        val argument = definition.argument
                        require(
                            argument.containingDef == variable.field &&
                                variable.field.arg(argument.name) == argument,
                        ) {
                            "Variable ${variable.variableName} argument ${argument.name} " +
                                "does not belong to ${variable.field.containingDef.name}/" +
                                variable.field.name
                        }
                    }
                    is VariableDefinition.FromObjectField -> {
                        require(objectFragment.constructionSelections().containsPath(definition.path)) {
                            "Variable ${variable.variableName} object-field path is not contained " +
                                "by ${variable.field.containingDef.name}/" +
                                "${variable.field.name} object fragment"
                        }
                    }
                }
            }
            return FieldResolver(
                field = field,
                objectFragmentTemplate = objectFragment,
                queryFragmentTemplate = queryFragment,
                queryType = queryType,
                variables = variables,
                function = function,
                projectionDemand = projectionDemand,
                applicationObserver = applicationObserver,
            )
        }

        fun of(
            field: ViaductSchema.ObjectField,
            objectFragment: SelectionForest,
            queryFragment: SelectionForest,
            queryType: ViaductSchema.Object,
            variables: Map<Arguments.Variable, VariableDefinition>,
            function: FieldResolverFunction,
            projectionDemand: (SelectionForest) -> SelectionForest = { it },
            applicationObserver: FieldResolverApplicationObserver = { _, _, _ -> },
        ): FieldResolver =
            of(
                field = field,
                objectFragment = objectFragment.toCanonicalMaterializeSelectionForest(),
                queryFragment = queryFragment.toCanonicalMaterializeSelectionForest(),
                queryType = queryType,
                variables = variables,
                function = function,
                projectionDemand = projectionDemand,
                applicationObserver = applicationObserver,
            )
    }
}

private fun EngineOutputData?.requireArgumentlessObjectFields() {
    when (this) {
        is EngineObjectData.Sync -> {
            getSelections().forEach { selection ->
                val outputField = schemaType.field(selection)
                require(outputField is ViaductSchema.ObjectField) {
                    "Resolver output selection ${schemaType.name}/$selection is not a canonical " +
                        "object field"
                }
                require(outputField.args.isEmpty()) {
                    "Resolver output must not supply argument-bearing field " +
                        "${schemaType.name}/$selection"
                }
                outputValue(selection).requireArgumentlessObjectFields()
            }
        }

        is List<*> -> forEach { value -> value.requireArgumentlessObjectFields() }
    }
}

private class ResolverObjectFragmentImpl(
    override val materializeSelections: MaterializeSelectionForest,
    override val constructionSelections: SelectionForest,
    override val pathVariableDefinitions: List<StampedObjectPathDefinition>,
) : ResolverObjectFragment

private class ResolverQueryFragmentImpl(
    override val materializeSelections: MaterializeSelectionForest,
    override val constructionSelections: SelectionForest,
) : ResolverQueryFragment

private fun MaterializeSelectionForest.stampVariables(
    path: List<PathComponent>,
): MaterializeSelectionForest =
    flatMap { selection ->
        materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.stampVars(
                                selection.key.field,
                                path,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.stampVariables(path),
            ),
        )
    }

private fun SelectionForest.markProviderPath(
    path: List<ObjectEngineResult.Key>,
    variable: Arguments.Variable,
): SelectionForest {
    val key = path.first()
    val remaining = path.drop(1)
    return flatMap { selection ->
        if (selection.key != key) {
            selectionForestOf()
        } else {
            val markedSubselections =
                if (remaining.isEmpty()) {
                    selectionForestOf()
                } else {
                    selection.subselections.markProviderPath(remaining, variable)
                }
            if (remaining.isNotEmpty() && markedSubselections.isEmpty()) {
                selectionForestOf()
            } else {
                selectionForestOf(
                    Selection.of(
                        key = ObjectEngineResult.VariableKey.of(selection.key, variable),
                        possibleTypes = selection.possibleTypes,
                        subselections = markedSubselections,
                    ),
                )
            }
        }
    }
}

private fun SelectionForest.containsPath(path: List<ObjectEngineResult.Key>): Boolean {
    if (path.isEmpty()) return false
    val key = path.first()
    val remaining = path.drop(1)
    return !filter { selection ->
        selection.key == key &&
            (remaining.isEmpty() || selection.subselections.containsPath(remaining))
    }.isEmpty()
}
