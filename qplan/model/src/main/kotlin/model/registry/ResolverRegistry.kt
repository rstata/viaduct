package model.registry

import model.Assumptions
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.selectionForestOf

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
 * [objectFragment] is the representative direct object-valued input requirement. For an exact
 * resolver occurrence, its predecessor demand is the guarded, path-rooted transitive closure of
 * its exact object fragment under resolver-dependency expansion. It therefore supplies the current
 * resolver's complete input prerequisites. [successorDemand] separately uses these closures to
 * extend a producer's output demand. The argument-taking forms preserve exact argument-dependent
 * coordinates. In a canonical registry entry, [variables] maps every variable template defined by
 * this resolver to its argument or nonempty alias-free object-field path definition.
 *
 * ### Invariant: resolver-fixed-object-fragment-shape
 *
 * [objectFragment] and every `objectFragment(arguments)` have the same concrete parent type and
 * normalized field-coordinate shape. Exact fragments may differ only in the values occupying
 * fixed argument positions.
 *
 * ### Invariant: field-resolver-variable-definitions
 *
 * Every variable is defined by this resolver's field. A [VariableDefinition.FromArgument]
 * references one argument belonging to that field. A [VariableDefinition.FromObjectField] is a
 * valid selection path relative to that field's containing type and is structurally contained by
 * [objectFragment]; its factory additionally ensures that the path does not cross a list and ends
 * at a simple value. External composition preserves containment in every exact object fragment.
 */
class FieldResolver private constructor(
    val objectFragment: ObjectSelectionForest,
    val predecessorDemand: ObjectSelectionForest,
    val variables: Map<Value.Variable.Template, VariableDefinition>,
    private val objectFragmentFunction: (Value.Arguments) -> ObjectSelectionForest,
    private val predecessorDemandFunction: (Value.Arguments) -> ObjectSelectionForest,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    /**
     * Returns the object fragment required for this exact argument tuple.
     *
     * Ordinary field resolvers return [objectFragment]. Pre-reasoning lowering may construct a
     * resolver whose required synthetic sibling coordinates carry the same arguments as the
     * resolved field. Semantic operations use this function rather than assuming the
     * representative [objectFragment] is exact.
     */
    fun objectFragment(arguments: Value.Arguments): ObjectSelectionForest =
        objectFragmentFunction(arguments)

    /**
     * Returns the exact object fragment with every variable template stamped at [path].
     *
     * Stamping preserves the selection fields, applicability guards, occurrence shape, and
     * non-variable argument values.
     */
    fun stampedObjectFragment(
        arguments: Value.Arguments,
        path: List<PathComponent>,
    ): ObjectSelectionForest =
        objectFragment(arguments).stampVariables(path)

    /** Returns the guarded, path-rooted predecessor demand for this exact argument tuple. */
    fun predecessorDemand(arguments: Value.Arguments): ObjectSelectionForest =
        predecessorDemandFunction(arguments)

    /**
     * Returns the exact predecessor demand with every variable template stamped at [path].
     *
     * Stamping preserves the selection fields, applicability guards, occurrence shape, and
     * non-variable argument values.
     */
    fun infusedPredecessorDemand(
        arguments: Value.Arguments,
        path: List<PathComponent>,
    ): ObjectSelectionForest =
        predecessorDemand(arguments).stampVariables(path)

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
        return function(input, arguments).snipToDemand(projectionDemand(selections))
    }

    /**
     * Applies this field resolver and returns its complete finite selection-independent output.
     */
    operator fun invoke(
        input: Value.Object,
        arguments: Value.Arguments,
    ): Value.Output? {
        applicationObserver(input, arguments, null)
        return function(input, arguments)
    }

    companion object {
        /**
         * Constructs one fully assembled canonical registry entry.
         *
         * External composition is responsible for lowering coordinates, attaching variables and
         * observers, and computing predecessor demand before calling this factory.
         */
        fun of(
            objectFragment: ObjectSelectionForest,
            variables: Map<Value.Variable.Template, VariableDefinition>,
            predecessorDemand: ObjectSelectionForest,
            objectFragmentFunction: (Value.Arguments) -> ObjectSelectionForest,
            predecessorDemandFunction: (Value.Arguments) -> ObjectSelectionForest,
            function: FieldResolverFunction,
            projectionDemand: (SelectionForest) -> SelectionForest = { it },
            applicationObserver: FieldResolverApplicationObserver = { _, _, _ -> },
        ): FieldResolver {
            require(predecessorDemand.type == objectFragment.type) {
                "Predecessor demand type must match object fragment type"
            }
            variables.forEach { (variable, definition) ->
                require(variable.field.containingType == objectFragment.type) {
                    "Variable ${variable.variableName} is not defined by a resolver on " +
                        objectFragment.type.typeName
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
                objectFragment = objectFragment,
                predecessorDemand = predecessorDemand,
                variables = variables,
                objectFragmentFunction = objectFragmentFunction,
                predecessorDemandFunction = predecessorDemandFunction,
                function = function,
                projectionDemand = projectionDemand,
                applicationObserver = applicationObserver,
            )
        }
    }
}

private fun ObjectSelectionForest.stampVariables(
    path: List<PathComponent>,
): ObjectSelectionForest =
    ObjectSelectionForest.of(
        type = type,
        selections =
            byKey().values.map { selection ->
                ObjectSelection.of(
                    key =
                        Value.ObjectKey.of(
                            field = selection.key.field,
                            arguments = selection.key.arguments.stamp(path),
                        ),
                    possibleTypes = selection.possibleTypes,
                    subselections = selection.subselections.stampVariables(path),
                )
            },
    )

private fun SelectionForest.stampVariables(
    path: List<PathComponent>,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    Value.Key.of(
                        field = selection.key.field,
                        arguments = selection.key.arguments.stamp(path),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.stampVariables(path),
            ),
        )
    }

private fun SelectionForest.containsPath(path: List<Value.Key>): Boolean {
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
 * [Value.Variable.Template] values. Acyclicity is intentionally checked over a conservative
 * coordinate-level possibility relation derived from representative fragment shapes. The relation
 * may therefore contain an edge whose exact occurrence is inactive because of a runtime type guard
 * or [Value.Error] argument, and the registry may reject a world whose exact active occurrences
 * would be acyclic.
 *
 * Every variable is defined from one argument of its resolver field or from one nonempty canonical
 * [Value.Key] path relative to that field's containing object. Object-field paths are structurally
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
