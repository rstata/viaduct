package model.registry

import model.ObjectEngineResult

import java.util.IdentityHashMap
import model.Assumptions
import model.ObjectSelectionForest
import model.OpenArguments
import model.PathComponent
import model.Schema
import model.Selection
import model.SelectionForest
import model.SelectionOccurrenceId
import model.SelectionStamp
import model.Value
import model.applicableGroundSelections
import model.concatenateSelectionForests
import model.selectionForestOf
import model.stampVars
import model.variableTemplates

/** One occurrence-specific variable and its occurrence-specific object provider path. */
data class StampedObjectPathDefinition(
    val variable: Value.Variable,
    val path: List<ObjectEngineResult.Key>,
) {
    init {
        require(variable.isStamped) { "An object-path definition requires a stamped variable" }
    }
}

/** One selection-specific variable use and the resolver definition that supplies its value. */
data class SelectionStampedVariableDefinition(
    val variable: Value.Variable,
    val definition: VariableDefinition,
) {
    init {
        require(variable.selectionStamp != null) {
            "A selection-stamped definition requires a selection-stamped variable"
        }
    }
}

/** A deterministic partial map from a resolved object fragment and arguments to an output value. */
typealias FieldResolverFunction =
    (Value.Object, Value.Arguments) -> Value.Output?

/** Observes one complete (null demand) or selective field-resolver application boundary. */
typealias FieldResolverApplicationObserver =
    (Value.Object, Value.Arguments, SelectionForest?) -> Unit

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
    val objectFragment: SelectionForest,
    val variables: Map<Value.Variable, VariableDefinition>,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    private val selectionOccurrenceIds: Map<Selection, SelectionOccurrenceId> =
        objectFragment.selectionOccurrenceIds()

    /**
     * Returns the exact object fragment with every variable template stamped at [path], while
     * retaining ordinary unstamped arguments for compatibility with existing resolvers.
     *
     * A synthetic copy of each path-variable provider path marks the variable definition.
     */
    fun stampVars(
        path: List<PathComponent>,
    ): SelectionForest {
        val stampedFragment: SelectionForest = objectFragment.stampVariables(path)
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
        return stampedFragment + pathVarSelections
    }

    /**
     * Returns the object fragment with each variable-bearing source selection stamped at [path].
     *
     * Already-ground argument tuples remain ordinary and can coalesce. Each selection containing a
     * variable receives its own [SelectionStamp], which survives grounding and prevents that
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
        ownerStamp: SelectionStamp,
    ): SelectionForest =
        stamp(
            resolverPath = ownerStamp.resolverPath,
            occurrencePrefix = ownerStamp.occurrenceLineage,
        )

    private fun stamp(
        resolverPath: List<PathComponent>,
        occurrencePrefix: List<SelectionOccurrenceId>,
    ): SelectionForest {
        val stampedFragment =
            objectFragment.stampVariableSelections(
                resolverPath = resolverPath,
                occurrencePrefix = occurrencePrefix,
                occurrenceIds = selectionOccurrenceIds,
            )
        val pathVarSelections: SelectionForest =
            selectionStampedVariableDefinitions(
                resolverPath = resolverPath,
                occurrencePrefix = occurrencePrefix,
            )
                .mapNotNull { stampedDefinition ->
                    (stampedDefinition.definition as? VariableDefinition.FromObjectField)?.let {
                        stampedFragment.markProviderSourcePath(
                            sourcePath = it.path,
                            variable = stampedDefinition.variable,
                        )
                    }
                }.concatenateSelectionForests()
        return stampedFragment + pathVarSelections
    }

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
        ownerStamp: SelectionStamp,
    ): List<SelectionStampedVariableDefinition> =
        selectionStampedVariableDefinitions(
            resolverPath = ownerStamp.resolverPath,
            occurrencePrefix = ownerStamp.occurrenceLineage,
        )

    private fun selectionStampedVariableDefinitions(
        resolverPath: List<PathComponent>,
        occurrencePrefix: List<SelectionOccurrenceId>,
    ): List<SelectionStampedVariableDefinition> =
        objectFragment.selectionStampedVariableDefinitions(
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
                StampedObjectPathDefinition(
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
        input: Value.Object,
        arguments: Value.Arguments,
        selections: SelectionForest,
    ): Value.Output? {
        applicationObserver(input, arguments, selections)
        return function(input, arguments)
            .synthesizeTypenames()
            .snipToDemand(projectionDemand(selections))
    }

    /**
     * Applies this field resolver and returns its complete finite selection-independent output.
     */
    operator fun invoke(
        input: Value.Object,
        arguments: Value.Arguments,
    ): Value.Output? {
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
            objectFragment: SelectionForest,
            variables: Map<Value.Variable, VariableDefinition>,
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
                        require(objectFragment.containsPath(definition.path)) {
                            "Variable ${variable.variableName} object-field path is not contained " +
                                "by ${variable.field.containingType.typeName}/" +
                                "${variable.field.fieldName} object fragment"
                        }
                    }
                }
            }
            return FieldResolver(
                field = field,
                objectFragment = objectFragment,
                variables = variables,
                function = function,
                projectionDemand = projectionDemand,
                applicationObserver = applicationObserver,
            )
        }
    }
}

/**
 * Recursively supplies the canonical passive `__typename` field of every resolver-produced object.
 */
private fun Value.Output?.synthesizeTypenames(): Value.Output? =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> this

        is Value.OutputList ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values = values.map { value -> value.synthesizeTypenames() },
            )

        is Value.Object -> {
            val typenameKey =
                ObjectEngineResult.GroundKey.of(
                    field = type.fields.getValue("__typename"),
                    arguments = emptyMap(),
                )
            val typenameValue = Value.String.of(type.typeName)
            if (typenameKey in fieldValues) {
                val supplied = fieldValues.getValue(typenameKey)
                require(supplied == typenameValue) {
                    "Resolver supplied invalid ${type.typeName}/__typename: $supplied"
                }
            }
            Value.Object.of(
                type = type,
                fields =
                    fieldValues.mapValues { (_, value) ->
                        value.synthesizeTypenames()
                    } + (typenameKey to typenameValue),
            )
        }
    }

private fun SelectionForest.stampVariables(
    path: List<PathComponent>,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
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

private fun SelectionForest.selectionOccurrenceIds(): Map<Selection, SelectionOccurrenceId> {
    val result = IdentityHashMap<Selection, SelectionOccurrenceId>()

    fun register(forest: SelectionForest) {
        forest.forEach { selection ->
            check(result.put(selection, SelectionOccurrenceId(selection.key)) == null)
            register(selection.subselections)
        }
    }

    register(this)
    return result
}

private fun SelectionForest.stampVariableSelections(
    resolverPath: List<PathComponent>,
    occurrencePrefix: List<SelectionOccurrenceId>,
    occurrenceIds: Map<Selection, SelectionOccurrenceId>,
): SelectionForest =
    flatMap { selection ->
        val variableTemplates = selection.key.arguments.variableTemplates()
        val key =
            if (variableTemplates.isEmpty()) {
                selection.key
            } else {
                val selectionStamp =
                    SelectionStamp(
                        resolverPath = resolverPath,
                        occurrenceLineage =
                            occurrencePrefix + occurrenceIds.getValue(selection),
                    )
                val arguments =
                    OpenArguments.Template
                        .of(selection.key.field.arguments, selection.key.arguments)
                        .stamp(selection.key.field.arguments, selectionStamp)
                ObjectEngineResult.Key.of(
                    selectionStamp = selectionStamp,
                    field = selection.key.field,
                    arguments = arguments,
                )
            }
        selectionForestOf(
            Selection.of(
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

private fun SelectionForest.selectionStampedVariableDefinitions(
    resolverPath: List<PathComponent>,
    occurrencePrefix: List<SelectionOccurrenceId>,
    occurrenceIds: Map<Selection, SelectionOccurrenceId>,
    definitions: Map<Value.Variable, VariableDefinition>,
): List<SelectionStampedVariableDefinition> {
    val stampedDefinitions = mutableListOf<SelectionStampedVariableDefinition>()
    forEach { selection ->
        val selectionStamp =
            SelectionStamp(
                resolverPath = resolverPath,
                occurrenceLineage =
                    occurrencePrefix + occurrenceIds.getValue(selection),
            )
        selection.key.arguments.variableTemplates().forEach { variable ->
            stampedDefinitions +=
                SelectionStampedVariableDefinition(
                    variable = variable.stamp(selectionStamp),
                    definition = definitions.getValue(variable),
                )
        }
        stampedDefinitions +=
            selection.subselections.selectionStampedVariableDefinitions(
                resolverPath = resolverPath,
                occurrencePrefix = occurrencePrefix,
                occurrenceIds = occurrenceIds,
                definitions = definitions,
            )
    }
    return stampedDefinitions
}

private fun SelectionForest.markProviderPath(
    path: List<ObjectEngineResult.Key>,
    variable: Value.Variable,
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
    variable: Value.Variable,
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
    val actualSourceKey =
        key.selectionStamp
            ?.occurrenceLineage
            ?.last()
            ?.sourceKey
            ?: key
    return actualSourceKey == sourceKey
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

/**
 * The externally supplied field resolvers and field-relative variable definitions fixed for one
 * reasoning world.
 *
 * A canonical object field is an actual resolver coordinate exactly when [contains] returns true.
 * The registry satisfies canonical schema ownership, special-field exclusions, query coverage,
 * resolver-local variable-template names, and acyclicity across object fields and
 * variable-template values. Acyclicity is intentionally checked over a conservative
 * coordinate-level possibility relation derived from fixed open fragment shapes. The relation
 * may therefore contain an edge whose exact occurrence is inactive because of a runtime type guard
 * or [Value.Error] argument, and the registry may reject a world whose exact active occurrences
 * would be acyclic.
 *
 * Every variable is defined from one argument of its resolver field or from one nonempty canonical
 * [ObjectEngineResult.Key] path relative to that field's containing object. Object-field paths are structurally
 * contained by the defining field resolver's fixed [FieldResolver.objectFragment] envelope.
 * Variables referenced by a field resolver's object fragment or one of its object-field paths
 * belong to that same field. An object-field path must terminate at an input-compatible value whose
 * effective nullability and list shape can be coerced at every argument position consuming the
 * variable.
 *
 * ### Invariant: resolver-registry-depth-first-variable-stratification
 *
 * For every concrete object type, form one graph whose vertices are its canonical object fields,
 * interpreted as argument-insensitive structural branches. The graph contains each ordinary
 * resolver-input edge from a required sibling branch to its consuming resolver branch. For each
 * object-field variable, its production branches are the provider's root branch and every
 * transitive branch prerequisite of that root; every production branch has an edge to each branch
 * of the defining resolver's fixed object-fragment envelope whose subtree contains a use of that
 * variable. Argument-defined variables add no branch edge because their values are resolver
 * inputs. The least graph closed under the object-field variable edges is acyclic. Consequently,
 * one topological branch order binds every object-field variable used in a branch before resolution
 * enters that branch.
 */
interface ResolverRegistry {
    /**
     * Creates the root resolver input with its canonical passive `Query.__typename`.
     *
     * Every other Query field is active and supplied by a registered field resolver.
     */
    fun resolveRootQuery(): Value.Object

    operator fun contains(field: Schema.ObjectField): Boolean

    /** Defined only when [field] is registered. */
    fun resolver(field: Schema.ObjectField): FieldResolver

    /**
     * The registered fields that may be directly demanded by [field].
     *
     * This conservative coordinate relation is not specialized to one exact argument tuple or
     * runtime type assignment.
     */
    fun mayDemandFrom(field: Schema.ObjectField): Set<Schema.ObjectField>

}

/** Indicates that no field resolver is defined at a valid schema coordinate. */
class MissingResolverException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field resolver: $typeName/$fieldName")
