package model.registry

import model.Assumptions
import model.Fragment
import model.Schema
import model.SelectionForest
import model.Value

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
 * coordinates. In a canonical registry entry, [variables] maps every variable defined by this
 * resolver to its nonempty alias-free provider path.
 *
 * ### Invariant: resolver-fixed-object-fragment-shape
 *
 * [objectFragment] and every `objectFragment(arguments)` have the same nominal type,
 * field-coordinate occurrences, type guards, nesting, and occurrence multiplicity. Exact
 * fragments may differ only in the values occupying those fixed argument positions.
 */
class FieldResolver private constructor(
    val objectFragment: Fragment,
    val predecessorDemand: Fragment,
    val variables: Map<Value.Variable, List<Value.Key>>,
    private val objectFragmentFunction: (Value.Arguments) -> Fragment,
    private val predecessorDemandFunction: (Fragment) -> Fragment,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val validateObjectFragment: (Fragment) -> Unit,
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
    fun objectFragment(arguments: Value.Arguments): Fragment =
        objectFragmentFunction(arguments).also { exact ->
            validateObjectFragment(exact)
        }

    /** Returns the guarded, path-rooted predecessor demand for this exact argument tuple. */
    fun predecessorDemand(arguments: Value.Arguments): Fragment =
        predecessorDemandFunction(objectFragment(arguments))

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

    /**
     * Applies this resolver once and returns its raw output paired with its selective projection.
     *
     * [additionalProjectionDemand] is evaluated with the raw output as its receiver and receives a
     * function that translates canonical demand into this resolver's projection coordinates. It
     * returns any additional projection-coordinate demand to retain alongside [selections].
     */
    @Deprecated(
        message = "Retained only for Resolver04, which is a dead-end design",
    )
    context(world: Assumptions)
    fun resolveWithSource(
        input: Value.Object,
        arguments: Value.Arguments,
        selections: SelectionForest,
        additionalProjectionDemand:
            Value.Output?.(
                projectDemand: (SelectionForest) -> SelectionForest,
            ) -> SelectionForest,
    ): Pair<Value.Output?, Value.Output?> {
        applicationObserver(input, arguments, selections)
        val output = function(input, arguments)
        val required = projectionDemand(selections)
        val additional =
            output.additionalProjectionDemand { demand ->
                projectionDemand(demand)
            }
        return output to output.snipToDemand(required + additional)
    }

    companion object {
        /**
         * Constructs one fully assembled canonical registry entry.
         *
         * External composition is responsible for lowering coordinates, attaching variables and
         * observers, and computing predecessor demand before calling this factory.
         */
        fun of(
            objectFragment: Fragment,
            variables: Map<Value.Variable, List<Value.Key>>,
            predecessorDemand: Fragment,
            objectFragmentFunction: (Value.Arguments) -> Fragment,
            predecessorDemandFunction: (Fragment) -> Fragment,
            function: FieldResolverFunction,
            projectionDemand: (SelectionForest) -> SelectionForest = { it },
            validateObjectFragment: (Fragment) -> Unit = {},
            applicationObserver: FieldResolverApplicationObserver = { _, _, _ -> },
        ): FieldResolver {
            require(predecessorDemand.nominalType == objectFragment.nominalType) {
                "Predecessor demand type must match object fragment type"
            }
            return FieldResolver(
                objectFragment = objectFragment,
                predecessorDemand = predecessorDemand,
                variables = variables,
                objectFragmentFunction = objectFragmentFunction,
                predecessorDemandFunction = predecessorDemandFunction,
                function = function,
                projectionDemand = projectionDemand,
                validateObjectFragment = validateObjectFragment,
                applicationObserver = applicationObserver,
            )
        }
    }
}

/**
 * The externally supplied field resolvers and field-relative variable providers fixed for one
 * reasoning world.
 *
 * A canonical object field is an actual resolver coordinate exactly when [contains] returns true.
 * The registry satisfies canonical schema ownership, special-field exclusions, query coverage,
 * resolver-local variable names, and acyclicity across object fields and [Value.Variable] values.
 * Acyclicity is intentionally checked over a conservative coordinate-level possibility relation
 * derived from representative fragment shapes. The relation may therefore contain an edge whose
 * exact occurrence is inactive because of a runtime type guard or [Value.Error] argument, and the
 * registry may reject a world whose exact active occurrences would be acyclic.
 *
 * Every variable provider is one nonempty canonical [Value.Key] path relative to its coordinate's
 * containing object and is structurally contained by the defining field resolver's fixed
 * [FieldResolver.objectFragment] envelope. Variables referenced by a field resolver's object
 * fragment or one of its providers belong to that same field. A provider path must terminate at an
 * input-compatible value whose effective nullability and list shape can be coerced at every
 * argument position consuming the variable.
 *
 * ### Invariant: resolver-registry-depth-first-variable-stratification
 *
 * For every concrete object type, form one graph whose vertices are its canonical object fields,
 * interpreted as argument-insensitive structural branches. The graph contains each ordinary
 * resolver-input edge from a required sibling branch to its consuming resolver branch. For each
 * variable, its production branches are the provider's root branch and every transitive branch
 * prerequisite of that root; every production branch has an edge to each branch of the defining
 * resolver's fixed object-fragment envelope whose subtree contains a use of that variable. The
 * least graph closed under these variable edges is acyclic. Consequently, one topological branch
 * order binds every variable used in a branch before resolution enters that branch.
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
