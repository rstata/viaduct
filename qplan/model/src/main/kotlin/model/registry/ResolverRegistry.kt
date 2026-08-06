package model.registry

import model.Assumptions
import model.Fragment
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
 * coordinates.
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
    private val objectFragmentFunction: (Value.Arguments) -> Fragment,
    private val predecessorDemandFunction: (Value.Arguments) -> Fragment,
    private val function: FieldResolverFunction,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    private val validateObjectFragment: (Fragment) -> Unit,
    private val applicationObserver: FieldResolverApplicationObserver,
) {
    data class OutputProjection(
        val source: Value.Output?,
        val projected: Value.Output?,
    )

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
    fun predecessorDemand(arguments: Value.Arguments): Fragment {
        objectFragment(arguments)
        return predecessorDemandFunction(arguments)
    }

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
     * Applies this resolver once and returns both its raw output and selective projection.
     */
    context(world: Assumptions)
    fun resolveWithSource(
        input: Value.Object,
        arguments: Value.Arguments,
        selections: SelectionForest,
        speculativeDemand: SelectionForest,
    ): OutputProjection {
        applicationObserver(input, arguments, selections)
        val output = function(input, arguments)
        val required = projectionDemand(selections)
        val speculative =
            output.availableDemand(projectionDemand(speculativeDemand))
        return OutputProjection(
            source = output,
            projected = output.snipToDemand(required + speculative),
        )
    }

    /**
     * Returns a resolver with the same input requirement and a transformed raw output.
     *
     * This is a pre-reasoning composition operation used to adapt externally supplied
     * functions before the canonical registry is exposed to semantic reasoning.
     */
    fun mapOutput(transform: (Value.Output?) -> Value.Output?): FieldResolver =
        FieldResolver(
            objectFragment = objectFragment,
            predecessorDemand = predecessorDemand,
            objectFragmentFunction = objectFragmentFunction,
            predecessorDemandFunction = predecessorDemandFunction,
            function = { input, arguments -> transform(function(input, arguments)) },
            projectionDemand = projectionDemand,
            validateObjectFragment = validateObjectFragment,
            applicationObserver = applicationObserver,
        )

    /**
     * Returns a resolver that translates external demand before projecting its raw output.
     *
     * This is a pre-reasoning composition operation used when external output coordinates are
     * lowered to different canonical coordinates.
     */
    fun mapDemand(transform: (SelectionForest) -> SelectionForest): FieldResolver =
        FieldResolver(
            objectFragment = objectFragment,
            predecessorDemand = predecessorDemand,
            objectFragmentFunction = objectFragmentFunction,
            predecessorDemandFunction = predecessorDemandFunction,
            function = function,
            projectionDemand = { demand -> transform(projectionDemand(demand)) },
            validateObjectFragment = validateObjectFragment,
            applicationObserver = applicationObserver,
        )

    /**
     * Returns a resolver whose object-fragment values are transformed before canonical registry
     * assembly.
     *
     * This pre-reasoning composition operation is used when lowering changes coordinates
     * carried by symbolic input values.
     */
    fun mapObjectFragment(transform: (Fragment) -> Fragment): FieldResolver =
        FieldResolver(
            objectFragment = transform(objectFragment),
            predecessorDemand = transform(predecessorDemand),
            objectFragmentFunction = { arguments ->
                transform(this.objectFragment(arguments))
            },
            predecessorDemandFunction = { arguments ->
                transform(this.predecessorDemand(arguments))
            },
            function = function,
            projectionDemand = projectionDemand,
            validateObjectFragment = {},
            applicationObserver = applicationObserver,
        )

    /**
     * Returns this resolver with an observer invoked once at each application boundary.
     *
     * Complete applications report null demand. Selective applications report the exact
     * supplied demand before any lowering-specific projection transform.
     */
    fun observeApplications(observer: FieldResolverApplicationObserver): FieldResolver =
        FieldResolver(
            objectFragment = objectFragment,
            predecessorDemand = predecessorDemand,
            objectFragmentFunction = objectFragmentFunction,
            predecessorDemandFunction = predecessorDemandFunction,
            function = function,
            projectionDemand = projectionDemand,
            validateObjectFragment = validateObjectFragment,
            applicationObserver = { input, arguments, selections ->
                applicationObserver(input, arguments, selections)
                observer(input, arguments, selections)
            },
        )

    /**
     * Returns this resolver with the precomputed predecessor demand of [objectFragment].
     *
     * Registry assembly applies this pre-reasoning operation after resolver lowering and
     * dependency analysis. The demand is rooted at the same object type and is the guarded,
     * path-rooted transitive closure of [objectFragment] under resolver-dependency expansion.
     */
    fun withPredecessorDemand(
        predecessorDemand: Fragment,
        predecessorDemandFunction: (Value.Arguments) -> Fragment,
        validateObjectFragment: (Fragment) -> Unit = {},
    ): FieldResolver {
        require(predecessorDemand.nominalType == objectFragment.nominalType) {
            "Predecessor demand type must match object fragment type"
        }
        return FieldResolver(
            objectFragment = objectFragment,
            predecessorDemand = predecessorDemand,
            objectFragmentFunction = objectFragmentFunction,
            predecessorDemandFunction = predecessorDemandFunction,
            function = function,
            projectionDemand = projectionDemand,
            validateObjectFragment = { fragment ->
                this.validateObjectFragment(fragment)
                validateObjectFragment(fragment)
            },
            applicationObserver = applicationObserver,
        )
    }

    companion object {
        fun of(
            objectFragment: Fragment,
            function: FieldResolverFunction,
        ): FieldResolver =
            FieldResolver(
                objectFragment = objectFragment,
                predecessorDemand = objectFragment,
                objectFragmentFunction = { objectFragment },
                predecessorDemandFunction = { objectFragment },
                function = function,
                projectionDemand = { it },
                validateObjectFragment = {},
                applicationObserver = { _, _, _ -> },
            )

        /**
         * Constructs a resolver whose fixed object fragment retargets selection arguments.
         *
         * [retargetArguments] is applied recursively to every selection key in [objectFragment].
         * All fragment and selection structure is preserved by construction.
         */
        fun ofArgumentRetargeting(
            objectFragment: Fragment,
            retargetArguments: (Value.Key, Value.Arguments) -> Value.Arguments,
            function: FieldResolverFunction,
        ): FieldResolver {
            val objectFragmentFunction = { arguments: Value.Arguments ->
                objectFragment.retargetArguments(arguments, retargetArguments)
            }
            return FieldResolver(
                objectFragment = objectFragment,
                predecessorDemand = objectFragment,
                objectFragmentFunction = objectFragmentFunction,
                predecessorDemandFunction = objectFragmentFunction,
                function = function,
                projectionDemand = { it },
                validateObjectFragment = {},
                applicationObserver = { _, _, _ -> },
            )
        }
    }
}

private fun Fragment.retargetArguments(
    resolverArguments: Value.Arguments,
    retargetArguments: (Value.Key, Value.Arguments) -> Value.Arguments,
): Fragment =
    Fragment.of(
        nominalType = nominalType,
        subselections = subselections.retargetArguments(resolverArguments, retargetArguments),
    )

private fun SelectionForest.retargetArguments(
    resolverArguments: Value.Arguments,
    retargetArguments: (Value.Key, Value.Arguments) -> Value.Arguments,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    Value.Key.of(
                        selection.key.field,
                        retargetArguments(selection.key, resolverArguments),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections =
                    selection.subselections.retargetArguments(
                        resolverArguments,
                        retargetArguments,
                    ),
            ),
        )
    }

/**
 * The externally supplied field resolvers and field-relative variable providers fixed for one
 * reasoning world.
 *
 * A canonical object field is an actual resolver coordinate exactly when [contains] returns true. The
 * registry satisfies canonical schema ownership, special-field exclusions, query coverage,
 * globally unique variable names, and acyclicity across object fields and [Value.Variable] values.
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

    /** The nonempty alias-free provider path for the globally registered [variable]. */
    fun variable(variable: Value.Variable): List<Value.Key>

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
