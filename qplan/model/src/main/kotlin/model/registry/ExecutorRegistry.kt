package model.registry

import model.Assumptions
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.VariableCoordinate
import model.selectionForestOf

sealed interface Executor

/** A deterministic partial map from a resolved object fragment and arguments to an output value. */
typealias FieldResolverFunction =
    (Value.Object, Value.Arguments) -> Value.Output?

/**
 * A resolver supplied by the reasoning world's external executor registry.
 *
 * Resolver equality is undefined. Resolver-demand identity is expressed with canonical output
 * fields instead.
 */
sealed interface Resolver : Executor {
    data class OutputProjection(
        val source: Value.Output?,
        val projected: Value.Output?,
    )

    /**
     * A field resolver and the guarded closure of its resolver dependencies.
     *
     * [objectFragment] is the representative direct object-valued input requirement. For an exact
     * resolver occurrence, its predecessor demand is the guarded, path-rooted transitive closure of
     * its exact object fragment under resolver-dependency expansion. It therefore supplies the
     * current resolver's complete input prerequisites. [successorDemand] separately uses these
     * closures to extend a producer's output demand. The argument-taking forms preserve exact
     * argument-dependent coordinates.
     *
     * ### Invariant: resolver-fixed-object-fragment-shape
     *
         * [objectFragment] and every `objectFragment(arguments)` have the same nominal type,
         * field-coordinate occurrences, type guards, nesting, and occurrence multiplicity. Exact
         * fragments may differ only in the values occupying those fixed argument positions.
     */
    class Field private constructor(
        val objectFragment: Fragment,
        val predecessorDemand: Fragment,
        private val objectFragmentFunction: (Value.Arguments) -> Fragment,
        private val predecessorDemandFunction: (Value.Arguments) -> Fragment,
        private val function: FieldResolverFunction,
        private val projectionDemand: (SelectionForest) -> SelectionForest,
        private val validateObjectFragment: (Fragment) -> Unit,
    ) : Resolver {
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
        fun tenantResolve(
            input: Value.Object,
            arguments: Value.Arguments,
            selections: SelectionForest,
        ): Value.Output? =
            function(input, arguments).snipToDemand(projectionDemand(selections))

        /**
         * Applies this field resolver and returns its complete finite selection-independent output.
         */
        fun tenantResolve(
            input: Value.Object,
            arguments: Value.Arguments,
        ): Value.Output? = function(input, arguments)

        /**
         * Applies this field resolver once, strictly projects [selections], and additionally
         * projects the recursively available portion of [speculativeDemand].
         */
        context(world: Assumptions)
        fun tenantResolve(
            input: Value.Object,
            arguments: Value.Arguments,
            selections: SelectionForest,
            speculativeDemand: SelectionForest,
        ): Value.Output? =
            resolveWithSource(
                input,
                arguments,
                selections,
                speculativeDemand,
            ).projected

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
            val output = function(input, arguments)
            val required = projectionDemand(selections)
            val speculative =
                output.availableDemand(projectionDemand(speculativeDemand))
            return OutputProjection(
                source = output,
                projected = output.snipToDemand(required + speculative),
            )
        }

        operator fun component1(): Fragment = objectFragment

        /**
         * Returns a resolver with the same input requirement and a transformed raw output.
         *
         * This is a pre-reasoning composition operation used to adapt externally supplied
         * functions before the canonical registry is exposed to semantic reasoning.
         */
        fun mapOutput(transform: (Value.Output?) -> Value.Output?): Field =
            Field(
                objectFragment = objectFragment,
                predecessorDemand = predecessorDemand,
                objectFragmentFunction = objectFragmentFunction,
                predecessorDemandFunction = predecessorDemandFunction,
                function = { input, arguments -> transform(function(input, arguments)) },
                projectionDemand = projectionDemand,
                validateObjectFragment = validateObjectFragment,
            )

        /**
         * Returns a resolver that translates external demand before projecting its raw output.
         *
         * This is a pre-reasoning composition operation used when external output coordinates are
         * lowered to different canonical coordinates.
         */
        fun mapDemand(transform: (SelectionForest) -> SelectionForest): Field =
            Field(
                objectFragment = objectFragment,
                predecessorDemand = predecessorDemand,
                objectFragmentFunction = objectFragmentFunction,
                predecessorDemandFunction = predecessorDemandFunction,
                function = function,
                projectionDemand = { demand -> transform(projectionDemand(demand)) },
                validateObjectFragment = validateObjectFragment,
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
        ): Field {
            require(predecessorDemand.nominalType == objectFragment.nominalType) {
                "Predecessor demand type must match object fragment type"
            }
            return Field(
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
            )
        }

        companion object {
            fun of(
                objectFragment: Fragment,
                function: FieldResolverFunction,
            ): Field =
                Field(
                    objectFragment = objectFragment,
                    predecessorDemand = objectFragment,
                    objectFragmentFunction = { objectFragment },
                    predecessorDemandFunction = { objectFragment },
                    function = function,
                    projectionDemand = { it },
                    validateObjectFragment = {},
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
            ): Field {
                val objectFragmentFunction = { arguments: Value.Arguments ->
                    objectFragment.retargetArguments(arguments, retargetArguments)
                }
                return Field(
                    objectFragment = objectFragment,
                    predecessorDemand = objectFragment,
                    objectFragmentFunction = objectFragmentFunction,
                    predecessorDemandFunction = objectFragmentFunction,
                    function = function,
                    projectionDemand = { it },
                    validateObjectFragment = {},
                )
            }
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
 * An output field is an actual resolver coordinate exactly when [contains] returns true. The
 * registry satisfies canonical schema ownership, special-field exclusions, query coverage,
 * globally unique variable names, exact transpose, and acyclicity across output fields and
 * [VariableCoordinate] values. Acyclicity is intentionally checked over a conservative
 * coordinate-level possibility relation derived from representative fragment shapes. The relation
 * may therefore contain an edge whose exact occurrence is inactive because of a runtime type guard
 * or [Value.Error] argument, and the registry may reject a world whose exact active occurrences
 * would be acyclic.
 *
 * Every variable provider is one path selection relative to its coordinate's containing object and
 * is structurally contained by the defining field resolver's fixed
 * [Resolver.Field.objectFragment] envelope. Variables referenced by a field resolver's object
 * fragment or one of its providers belong to that same field. A provider path must terminate at an
 * input-compatible value, but compatibility between that value's precise type and every argument
 * position consuming the variable is externally stipulated rather than validated by this registry.
 *
 * ### Invariant: executor-registry-depth-first-variable-stratification
 *
 * For every concrete object type, form one graph whose vertices are its canonical output fields,
 * interpreted as argument-insensitive structural branches. The graph contains each ordinary
 * resolver-input edge from a required sibling branch to its consuming resolver branch. For each
 * variable, its production branches are the provider's root branch and every transitive branch
 * prerequisite of that root; every production branch has an edge to each branch of the defining
 * resolver's fixed object-fragment envelope whose subtree contains a use of that variable. The
 * least graph closed under these variable edges is acyclic. Consequently, one topological branch
 * order binds every variable used in a branch before resolution enters that branch.
 */
interface ExecutorRegistry {
    operator fun contains(field: Schema.OutputField): Boolean

    /** Defined only when [field] is registered. */
    fun resolver(field: Schema.OutputField): Resolver.Field

    /** The provider selection for the globally registered [variable]. */
    fun variable(variable: Value.Variable): Selection

    /** The unique resolver-relative coordinate of the globally registered [variable]. */
    fun variableCoordinate(variable: Value.Variable): VariableCoordinate

    /**
     * The resolver sites that may be directly demanded by [site].
     *
     * This conservative coordinate relation is not specialized to one exact argument tuple or
     * runtime type assignment.
     */
    fun mayDemandFrom(site: Schema.ResolverSite): Set<Schema.ResolverSite>

    /** The resolver sites that may directly demand [site]. */
    fun mayBeDemandedBy(site: Schema.ResolverSite): Set<Schema.ResolverSite>
}

/** Indicates that no executor is defined at a valid schema coordinate. */
class MissingExecutorException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field resolver: $typeName/$fieldName")
