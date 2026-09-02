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
import model.ResolverOccurrenceId
import model.Selection
import model.SelectionForest
import model.applicableGroundSelections
import model.arg
import model.concatenateSelectionForests
import model.engineObjectDataOf
import model.instantiateVariables
import model.materializeSelectionForestOf
import model.outputValue
import model.schemaType
import model.selectionForestOf
import model.toCanonicalMaterializeSelectionForest
import model.usedVariables
import viaduct.engine.api.EngineObjectData

/** A deterministic partial map from resolved object and Query fragments plus arguments to an output value. */
typealias FieldResolverFunction =
    (EngineObjectData.Sync, EngineObjectData.Sync, Arguments.Resolved) -> EngineOutputData?

/** Observes one complete (null demand) or selective field-resolver application boundary. */
typealias FieldResolverApplicationObserver =
    (EngineObjectData.Sync, Arguments.Resolved, SelectionForest?) -> Unit

/** Paired materialization and construction views of one instantiated resolver input fragment. */
sealed interface ResolverFragment {
    val resolverOccurrenceId: ResolverOccurrenceId
    val materializeSelections: MaterializeSelectionForest
    val constructionSelections: SelectionForest
    val variableDefinitions: List<VariableInstanceDefinition>
    val pathVariableDefinitions: List<InstantiatedFieldPathDefinition>
}

data class ResolverFragments(
    val objectFragment: ResolverFragment,
    val queryFragment: ResolverFragment,
)

/**
 * A field resolver supplied by the reasoning world's external resolver registry.
 *
 * Equality is undefined. Resolver-demand identity is expressed with canonical object fields
 * instead.
 *
 * [objectFragment] is the direct parent-object input requirement. [queryFragment] is the
 * independently resolved Query-rooted input requirement. In a canonical registry entry,
 * [variables] maps every variable template defined by this resolver and used by either fragment
 * to its argument or nonempty alias-free object- or Query-field path definition.
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
 * [VariableDefinition.FromField] supplied by [ProviderFragment.OBJECT] is a valid selection path
 * relative to that field's containing type and is structurally contained by [objectFragment]. A
 * definition supplied by [ProviderFragment.QUERY] satisfies the same path invariant relative to
 * Query and is structurally contained by [queryFragment].
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

    /** Instantiates both resolver input fragments at one exact resolver path. */
    fun instantiateFragmentsAt(
        root: ObjectEngineResult,
        path: List<PathComponent>,
    ): ResolverFragments = instantiateFragments(ResolverOccurrenceId.at(root, path))

    /** Instantiates both resolver input fragments from one shared occurrence-variable set. */
    fun instantiateFragments(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): ResolverFragments {
        val variableDefinitions = instantiatedVariableDefinitions(resolverOccurrenceId)
        val pathVariableDefinitions =
            instantiatedFieldPathVariableDefinitions(resolverOccurrenceId)
        return ResolverFragments(
            objectFragment =
                instantiateFragment(
                    resolverOccurrenceId = resolverOccurrenceId,
                    providerFragment = ProviderFragment.OBJECT,
                    variableDefinitions = variableDefinitions,
                    pathVariableDefinitions = pathVariableDefinitions,
                ),
            queryFragment =
                instantiateFragment(
                    resolverOccurrenceId = resolverOccurrenceId,
                    providerFragment = ProviderFragment.QUERY,
                    variableDefinitions = variableDefinitions,
                    pathVariableDefinitions = pathVariableDefinitions,
                ),
        )
    }

    /** Returns each resolver variable definition instantiated once for this application. */
    fun instantiatedVariableDefinitions(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): List<VariableInstanceDefinition> =
        variables.map { (variable, definition) ->
            VariableInstanceDefinition.of(
                variable = variable.instantiate(resolverOccurrenceId),
                definition = definition,
            )
        }

    /** Returns this resolver's from-field path definitions for one application. */
    fun instantiatedFieldPathVariableDefinitions(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): List<InstantiatedFieldPathDefinition> =
        variables.mapNotNull { (variable, definition) ->
            (definition as? VariableDefinition.FromField)?.let {
                InstantiatedFieldPathDefinition.of(
                    variable = variable.instantiate(resolverOccurrenceId),
                    providerFragment = it.providerFragment,
                    path =
                        it.path.map { key ->
                            ObjectEngineResult.Key.of(
                                field = key.field,
                                arguments =
                                    key.arguments.instantiateVariables(
                                        key.field,
                                        resolverOccurrenceId,
                                    ),
                            )
                        },
                )
            }
        }

    private fun instantiateFragment(
        resolverOccurrenceId: ResolverOccurrenceId,
        providerFragment: ProviderFragment,
        variableDefinitions: List<VariableInstanceDefinition>,
        pathVariableDefinitions: List<InstantiatedFieldPathDefinition>,
    ): ResolverFragment {
        val template =
            when (providerFragment) {
                ProviderFragment.OBJECT -> objectFragmentTemplate
                ProviderFragment.QUERY -> queryFragmentTemplate
            }
        val materializeSelections = template.instantiateVariables(resolverOccurrenceId)
        val instantiatedFragment = materializeSelections.constructionSelections()
        val fragmentPathDefinitions =
            pathVariableDefinitions.filter { definition ->
                definition.providerFragment == providerFragment
            }
        val providerSelections =
            fragmentPathDefinitions
                .map { definition ->
                    instantiatedFragment.markProviderPath(
                        path = definition.path,
                        variable = definition.variable,
                    )
                }.concatenateSelectionForests()
        val constructionSelections = instantiatedFragment + providerSelections
        val usedVariables = constructionSelections.usedVariables()
        return ResolverFragmentImpl(
            resolverOccurrenceId = resolverOccurrenceId,
            materializeSelections = materializeSelections,
            constructionSelections = constructionSelections,
            variableDefinitions =
                variableDefinitions.filter { definition ->
                    definition.variable in usedVariables
                },
            pathVariableDefinitions = fragmentPathDefinitions,
        )
    }

    /** Returns this resolver's object fragment grounded at exact occurrence [path]. */
    context(world: Assumptions)
    fun objectFragmentAt(
        root: ObjectEngineResult,
        path: List<PathComponent>,
    ): ObjectSelectionForest =
        instantiateFragmentsAt(root, path)
            .objectFragment
            .constructionSelections
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
                    is VariableDefinition.FromField -> {
                        val fragment =
                            when (definition.providerFragment) {
                                ProviderFragment.OBJECT -> objectFragment
                                ProviderFragment.QUERY -> queryFragment
                            }
                        require(fragment.constructionSelections().containsPath(definition.path)) {
                            "Variable ${variable.variableName} " +
                                "${definition.providerFragment.name.lowercase()}-field path is not " +
                                "contained by ${variable.field.containingDef.name}/" +
                                "${variable.field.name} " +
                                "${definition.providerFragment.name.lowercase()} fragment"
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

private data class ResolverFragmentImpl(
    override val resolverOccurrenceId: ResolverOccurrenceId,
    override val materializeSelections: MaterializeSelectionForest,
    override val constructionSelections: SelectionForest,
    override val variableDefinitions: List<VariableInstanceDefinition>,
    override val pathVariableDefinitions: List<InstantiatedFieldPathDefinition>,
) : ResolverFragment

private fun MaterializeSelectionForest.instantiateVariables(
    resolverOccurrenceId: ResolverOccurrenceId,
): MaterializeSelectionForest =
    flatMap { selection ->
        materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.instantiateVariables(
                                selection.key.field,
                                resolverOccurrenceId,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections =
                    selection.subselections.instantiateVariables(
                        resolverOccurrenceId,
                    ),
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
