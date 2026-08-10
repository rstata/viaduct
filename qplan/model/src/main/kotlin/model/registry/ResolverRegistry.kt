package model.registry

import model.Assumptions
import model.ObjectSelectionForest
import model.PathComponent
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.applicableGroundSelections
import model.selectionForestOf
import model.stamp

/** One occurrence-specific variable and its occurrence-specific object provider path. */
data class StampedObjectPathDefinition(
    val variable: Value.Variable.Stamped,
    val path: List<Value.Key>,
)

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
    val variables: Map<Value.Variable.Template, VariableDefinition>,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    /**
     * Returns the exact object fragment with every variable template stamped at [path].
     *
     * Stamping preserves the selection fields, applicability guards, occurrence shape, and
     * non-variable argument values.
     */
    fun stampedObjectFragment(
        path: List<PathComponent>,
    ): SelectionForest =
        objectFragment.stampVariables(path)

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
                            Value.Key.of(
                                field = key.field,
                                arguments = key.arguments.stamp(sitePath),
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
        stampedObjectFragment(path)
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
        return function(input, arguments).snipToDemand(projectionDemand(selections))
    }

    /**
     * Applies this field resolver with an observed demand while retaining its complete output.
     */
    fun completeOutput(
        input: Value.Object,
        arguments: Value.Arguments,
        selections: SelectionForest,
    ): Value.Output? {
        applicationObserver(input, arguments, selections)
        return function(input, arguments)
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
         * External composition is responsible for lowering coordinates and attaching variables and
         * observers before calling this factory.
         */
        fun of(
            field: Schema.ObjectField,
            objectFragment: SelectionForest,
            variables: Map<Value.Variable.Template, VariableDefinition>,
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
 * coordinate-level possibility relation derived from fixed open fragment shapes. The relation
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
