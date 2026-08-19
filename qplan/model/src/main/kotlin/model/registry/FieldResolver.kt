package model.registry

import model.ObjectEngineResult

import java.util.IdentityHashMap
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineOutputListData
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.ObjectSelectionForest
import model.Arguments
import model.PathComponent
import model.Schema
import model.Selection
import model.SelectionForest
import model.SelectionOccurrenceId
import model.Stamp
import model.applicableGroundSelections
import model.concatenateSelectionForests
import model.engineObjectDataOf
import model.materializeSelectionForestOf
import model.schemaType
import model.selectionForestOf
import model.stampVars
import model.toCanonicalMaterializeSelectionForest
import model.variableTemplates
import viaduct.engine.api.EngineObjectData

/** A deterministic partial map from a resolved object fragment and arguments to an output value. */
typealias FieldResolverFunction =
    (EngineObjectData.Sync, Arguments.Resolved) -> EngineOutputData?

/** Observes one complete (null demand) or selective field-resolver application boundary. */
typealias FieldResolverApplicationObserver =
    (EngineObjectData.Sync, Arguments.Resolved, SelectionForest?) -> Unit

/** Paired resolver-object-fragment views instantiated from one resolver occurrence. */
sealed interface ResolverObjectFragment {
    val materializeSelections: MaterializeSelectionForest
    val constructionSelections: SelectionForest
}

/**
 * A field resolver supplied by the reasoning world's external resolver registry.
 *
 * Equality is undefined. Resolver-demand identity is expressed with canonical object fields
 * instead.
 *
 * [objectFragment] is the direct object-valued input requirement. In a canonical registry entry,
 * [variables] maps every variable template defined by this resolver to its argument or nonempty
 * alias-free object-field path definition.
 *
 * ### Invariant: resolver-fixed-object-fragment-shape
 *
 * [objectFragment] is specialized to the resolver field's concrete parent type.
 *
 * ### Invariant: field-resolver-variable-definitions
 *
 * Every variable is defined by this resolver's field. A [VariableDefinition.FromArgument]
 * references one argument belonging to that field. A [VariableDefinition.FromObjectField] is a
 * valid selection path relative to that field's containing type and is structurally contained by
 * [objectFragment]; its factory additionally ensures that the path does not cross a list and ends
 * at a simple value.
 */
class FieldResolver private constructor(
    val field: Schema.ObjectField,
    private val objectFragmentTemplate: MaterializeSelectionForest,
    val variables: Map<Arguments.Variable, VariableDefinition>,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    val objectFragment: SelectionForest =
        objectFragmentTemplate.constructionSelections()

    private val selectionOccurrenceIds: Map<MaterializeSelection, SelectionOccurrenceId> =
        objectFragmentTemplate.selectionOccurrenceIds()

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

    /**
     * Instantiates response-preserving and construction views using the legacy path-stamped
     * variable identity used by the shared resolvers and Resolver25.
     */
    fun instantiateObjectFragmentAt(
        path: List<PathComponent>,
    ): ResolverObjectFragment {
        val materializeSelections = objectFragmentTemplate.stampVariables(path)
        val stampedFragment: SelectionForest =
            materializeSelections.constructionSelections()
        val pathVarSelections: SelectionForest =
            variables.entries
                .mapNotNull { (variable, definition) ->
                    (definition as? VariableDefinition.FromObjectField)?.let {
                        stampedFragment.markProviderPath(
                            path =
                                it.path.map { key ->
                                    ObjectEngineResult.Key.of(
                                        field = key.field,
                                        arguments =
                                            key.arguments.stampVars(
                                                key.field.arguments,
                                                path,
                                            ),
                                    )
                                },
                            variable = variable.stamp(path),
                        )
                    }
                }.concatenateSelectionForests()
        return ResolverObjectFragmentImpl(
            materializeSelections = materializeSelections,
            constructionSelections = stampedFragment + pathVarSelections,
        )
    }

    /**
     * Returns the object fragment with each variable-bearing source selection stamped at [path].
     *
     * Already-ground argument tuples remain ordinary and can coalesce. Each selection containing a
     * variable receives its own [Stamp.Occurrence], which survives grounding and prevents that
     * selection from coalescing with any other occurrence. Synthetic provider-path copies mark
     * every selection-specific path-variable definition.
     */
    fun stamp(
        path: List<PathComponent>,
    ): SelectionForest =
        stamp(
            resolverPath = path,
            occurrencePrefix = emptyList(),
        )

    /**
     * Returns this object fragment stamped as demand contributed by an ungrounded resolver
     * selection carrying [ownerStamp].
     */
    fun stampFrom(
        ownerStamp: Stamp.Occurrence,
    ): SelectionForest =
        instantiateObjectFragment(ownerStamp).constructionSelections

    /**
     * Instantiates response-preserving and construction views from one resolver occurrence.
     *
     * Variable-bearing response groups extend [resolverStamp] with one registry-owned occurrence
     * identity. Provider markers are appended only to [ResolverObjectFragment.constructionSelections].
     */
    fun instantiateObjectFragment(
        resolverStamp: Stamp.Occurrence,
    ): ResolverObjectFragment {
        val materializeSelections =
            objectFragmentTemplate.stampVariableSelections(
                resolverPath = resolverStamp.resolverPath,
                occurrencePrefix = resolverStamp.occurrenceLineage,
                occurrenceIds = selectionOccurrenceIds,
            )
        val constructionBase = materializeSelections.constructionSelections()
        val pathVarSelections: SelectionForest =
            selectionStampedVariableDefinitions(resolverStamp)
                .mapNotNull { stampedDefinition ->
                    (stampedDefinition.definition as? VariableDefinition.FromObjectField)?.let {
                        constructionBase.markProviderSourcePath(
                            sourcePath = it.path,
                            variable = stampedDefinition.variable,
                        )
                    }
                }.concatenateSelectionForests()
        return ResolverObjectFragmentImpl(
            materializeSelections = materializeSelections,
            constructionSelections = constructionBase + pathVarSelections,
        )
    }

    private fun stamp(
        resolverPath: List<PathComponent>,
        occurrencePrefix: List<SelectionOccurrenceId>,
    ): SelectionForest =
        instantiateObjectFragment(
            Stamp.Occurrence.of(
                resolverPath = resolverPath,
                occurrenceLineage = occurrencePrefix,
            ),
        ).constructionSelections

    /** Returns every variable definition instantiated once per source selection that uses it. */
    fun selectionStampedVariableDefinitions(
        resolverPath: List<PathComponent>,
    ): List<SelectionStampedVariableDefinition> =
        selectionStampedVariableDefinitions(
            resolverPath = resolverPath,
            occurrencePrefix = emptyList(),
        )

    /** Returns variable definitions contributed through one ungrounded resolver occurrence. */
    fun selectionStampedVariableDefinitionsFrom(
        ownerStamp: Stamp.Occurrence,
    ): List<SelectionStampedVariableDefinition> =
        selectionStampedVariableDefinitions(ownerStamp)

    private fun selectionStampedVariableDefinitions(
        resolverStamp: Stamp.Occurrence,
    ): List<SelectionStampedVariableDefinition> =
        selectionStampedVariableDefinitions(
            resolverPath = resolverStamp.resolverPath,
            occurrencePrefix = resolverStamp.occurrenceLineage,
        )

    private fun selectionStampedVariableDefinitions(
        resolverPath: List<PathComponent>,
        occurrencePrefix: List<SelectionOccurrenceId>,
    ): List<SelectionStampedVariableDefinition> =
        objectFragmentTemplate.selectionStampedVariableDefinitions(
            resolverPath = resolverPath,
            occurrencePrefix = occurrencePrefix,
            occurrenceIds = selectionOccurrenceIds,
            definitions = variables,
        )

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
                                        key.field.arguments,
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
            .applicableGroundSelections(field.containingType)

    /**
     * Applies this field resolver and projects its selection-independent result to
     * [selections].
     */
    context(world: Assumptions)
    operator fun invoke(
        input: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
        selections: SelectionForest,
    ): EngineOutputData? {
        applicationObserver(input, arguments, selections)
        return function(input, arguments)
            .synthesizeTypenames()
            .snipToDemand(projectionDemand(selections))
    }

    /**
     * Applies this field resolver and returns its complete finite selection-independent output.
     */
    operator fun invoke(
        input: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
    ): EngineOutputData? {
        applicationObserver(input, arguments, null)
        return function(input, arguments).synthesizeTypenames()
    }

    companion object {
        /**
         * Constructs one fully assembled canonical registry entry.
         *
         * External composition is responsible for lowering coordinates and attaching variables and
         * observers before calling this factory.
         */
        fun of(
            field: Schema.ObjectField,
            objectFragment: MaterializeSelectionForest,
            variables: Map<Arguments.Variable, VariableDefinition>,
            function: FieldResolverFunction,
            projectionDemand: (SelectionForest) -> SelectionForest = { it },
            applicationObserver: FieldResolverApplicationObserver = { _, _, _ -> },
        ): FieldResolver {
            require(
                objectFragment.all { selection ->
                    selection.key.field.containingType == field.containingType &&
                        selection.possibleTypes == setOf(field.containingType)
                },
            ) {
                "Object fragment must be specialized to ${field.containingType.typeName}"
            }
            objectFragment.collect(field.containingType)
            variables.forEach { (variable, definition) ->
                require(variable.isTemplate) {
                    "Resolver registry variables must be templates"
                }
                require(variable.field == field) {
                    "Variable ${variable.variableName} is not defined by a resolver on " +
                        "${field.containingType.typeName}/${field.fieldName}"
                }
                when (definition) {
                    is VariableDefinition.FromArgument -> {
                        val argument = definition.argument
                        require(
                            argument.containingType == variable.field.arguments &&
                                variable.field.arguments.fields[argument.argumentName] == argument,
                        ) {
                            "Variable ${variable.variableName} argument ${argument.argumentName} " +
                                "does not belong to ${variable.field.containingType.typeName}/" +
                                variable.field.fieldName
                        }
                    }
                    is VariableDefinition.FromObjectField -> {
                        require(objectFragment.constructionSelections().containsPath(definition.path)) {
                            "Variable ${variable.variableName} object-field path is not contained " +
                                "by ${variable.field.containingType.typeName}/" +
                                "${variable.field.fieldName} object fragment"
                        }
                    }
                }
            }
            return FieldResolver(
                field = field,
                objectFragmentTemplate = objectFragment,
                variables = variables,
                function = function,
                projectionDemand = projectionDemand,
                applicationObserver = applicationObserver,
            )
        }

        fun of(
            field: Schema.ObjectField,
            objectFragment: SelectionForest,
            variables: Map<Arguments.Variable, VariableDefinition>,
            function: FieldResolverFunction,
            projectionDemand: (SelectionForest) -> SelectionForest = { it },
            applicationObserver: FieldResolverApplicationObserver = { _, _, _ -> },
        ): FieldResolver =
            of(
                field = field,
                objectFragment = objectFragment.toCanonicalMaterializeSelectionForest(),
                variables = variables,
                function = function,
                projectionDemand = projectionDemand,
                applicationObserver = applicationObserver,
            )
    }
}

private class ResolverObjectFragmentImpl(
    override val materializeSelections: MaterializeSelectionForest,
    override val constructionSelections: SelectionForest,
) : ResolverObjectFragment

/**
 * Recursively supplies the canonical passive `__typename` field of every resolver-produced object.
 */
private fun EngineOutputData?.synthesizeTypenames(): EngineOutputData? =
    when (this) {
        null,
        EngineErrorData,
        is Int,
        is Double,
        is String,
        is Boolean,
        -> this

        is List<*> -> {
            val values: EngineOutputListData =
                map { value -> value.synthesizeTypenames() }
            values
        }

        is EngineObjectData.Sync -> {
            val schemaType = this.schemaType
            val typenameKey = "__typename"
            val typenameValue = schemaType.typeName
            if (isPresent(typenameKey)) {
                val supplied = get(typenameKey)
                require(supplied == typenameValue) {
                    "Resolver supplied invalid ${schemaType.typeName}/__typename: $supplied"
                }
            }
            engineObjectDataOf(
                schemaType = schemaType,
                fields =
                    getSelections().associateWith { selection ->
                        get(selection).synthesizeTypenames()
                    } + (typenameKey to typenameValue),
            )
        }
        else -> throw ClassCastException("Unsupported engine output data: $this")
    }

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
                                selection.key.field.arguments,
                                path,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.stampVariables(path),
            ),
        )
    }

private fun MaterializeSelectionForest.selectionOccurrenceIds():
    Map<MaterializeSelection, SelectionOccurrenceId> {
    val result = IdentityHashMap<MaterializeSelection, SelectionOccurrenceId>()

    fun register(forest: MaterializeSelectionForest) {
        val selectionsByResponseKey =
            linkedMapOf<String, MutableList<MaterializeSelection>>()
        forest.forEach { selection ->
            selectionsByResponseKey
                .getOrPut(selection.responseKey, ::mutableListOf)
                .add(selection)
        }
        selectionsByResponseKey.values.forEach { selections ->
            val occurrenceId =
                SelectionOccurrenceId.forResponseGroup(
                    selections.mapTo(linkedSetOf()) { selection -> selection.key },
                )
            selections.forEach { selection ->
                check(result.put(selection, occurrenceId) == null)
            }
            val children =
                selections.fold(materializeSelectionForestOf()) { forestSoFar, selection ->
                    forestSoFar + selection.subselections
                }
            register(children)
        }
    }

    register(this)
    return result
}

private fun MaterializeSelectionForest.stampVariableSelections(
    resolverPath: List<PathComponent>,
    occurrencePrefix: List<SelectionOccurrenceId>,
    occurrenceIds: Map<MaterializeSelection, SelectionOccurrenceId>,
): MaterializeSelectionForest =
    flatMap { selection ->
        val variableTemplates = selection.key.arguments.variableTemplates()
        val key =
            if (variableTemplates.isEmpty()) {
                selection.key
            } else {
                val selectionStamp =
                    Stamp.Occurrence.of(
                        resolverPath = resolverPath,
                        occurrenceLineage =
                            occurrencePrefix + occurrenceIds.getValue(selection),
                    )
                val arguments =
                    Arguments.Template
                        .of(selection.key.field.arguments, selection.key.arguments)
                        .stamp(selection.key.field.arguments, selectionStamp)
                ObjectEngineResult.Key.of(
                    stamp = selectionStamp,
                    field = selection.key.field,
                    arguments = arguments,
                )
            }
        materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key = key,
                possibleTypes = selection.possibleTypes,
                subselections =
                    selection.subselections.stampVariableSelections(
                        resolverPath = resolverPath,
                        occurrencePrefix = occurrencePrefix,
                        occurrenceIds = occurrenceIds,
                    ),
            ),
        )
    }

private fun MaterializeSelectionForest.selectionStampedVariableDefinitions(
    resolverPath: List<PathComponent>,
    occurrencePrefix: List<SelectionOccurrenceId>,
    occurrenceIds: Map<MaterializeSelection, SelectionOccurrenceId>,
    definitions: Map<Arguments.Variable, VariableDefinition>,
): List<SelectionStampedVariableDefinition> {
    val stampedDefinitions =
        linkedMapOf<Pair<SelectionOccurrenceId, Arguments.Variable>, SelectionStampedVariableDefinition>()
    forEach { selection ->
        val occurrenceId = occurrenceIds.getValue(selection)
        val selectionStamp =
            Stamp.Occurrence.of(
                resolverPath = resolverPath,
                occurrenceLineage =
                    occurrencePrefix + occurrenceId,
            )
        selection.key.arguments.variableTemplates().forEach { variable ->
            stampedDefinitions[occurrenceId to variable] =
                SelectionStampedVariableDefinition.of(
                    variable = variable.stamp(selectionStamp),
                    definition = definitions.getValue(variable),
                )
        }
        selection.subselections
            .selectionStampedVariableDefinitions(
                resolverPath = resolverPath,
                occurrencePrefix = occurrencePrefix,
                occurrenceIds = occurrenceIds,
                definitions = definitions,
            ).forEach { definition ->
                val id = definition.variable.stamp!!.occurrenceLineage.last()
                stampedDefinitions[id to Arguments.Variable.of(
                    definition.variable.field,
                    definition.variable.variableName,
                )] = definition
            }
    }
    return stampedDefinitions.values.toList()
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

private fun SelectionForest.markProviderSourcePath(
    sourcePath: List<ObjectEngineResult.Key>,
    variable: Arguments.Variable,
): SelectionForest {
    val sourceKey = sourcePath.first()
    val remaining = sourcePath.drop(1)
    return flatMap { selection ->
        if (!selection.hasSourceKey(sourceKey)) {
            selectionForestOf()
        } else {
            val markedSubselections =
                if (remaining.isEmpty()) {
                    selectionForestOf()
                } else {
                    selection.subselections.markProviderSourcePath(
                        sourcePath = remaining,
                        variable = variable,
                    )
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

private fun Selection.hasSourceKey(sourceKey: ObjectEngineResult.Key): Boolean {
    val occurrenceId =
        (key.stamp as? Stamp.Occurrence)
            ?.occurrenceLineage
            ?.last()
    return occurrenceId?.represents(sourceKey) ?: (key == sourceKey)
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
