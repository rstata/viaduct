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
     * resolver occurrence, its extended fragment is the guarded, path-rooted transitive closure of
     * its exact object fragment under resolver-dependency expansion. The extension can therefore
     * supply the current resolver's complete input prerequisites or extend a producer's output
     * demand with the prerequisites of successor resolver occurrences. The argument-taking forms
     * preserve exact argument-dependent coordinates.
     *
     * ### Invariant: resolver-fixed-object-fragment-shape
     *
         * [objectFragment] and every `objectFragment(arguments)` have the same nominal type,
         * field-coordinate occurrences, type guards, nesting, and occurrence multiplicity. Exact
         * fragments may differ only in the values occupying those fixed argument positions.
     */
    class Field private constructor(
        val objectFragment: Fragment,
        val extendedFragment: Fragment,
        private val objectFragmentFunction: (Value.Arguments) -> Fragment,
        private val extendedFragmentFunction: (Value.Arguments) -> Fragment,
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

        /** Returns the guarded, path-rooted dependency closure for this exact argument tuple. */
        fun extendedFragment(arguments: Value.Arguments): Fragment {
            objectFragment(arguments)
            return extendedFragmentFunction(arguments)
        }

        /**
         * Applies this field resolver and projects its selection-independent result to
         * [transitiveDemand].
         */
        context(world: Assumptions)
        fun resolve(
            input: Value.Object,
            arguments: Value.Arguments,
            transitiveDemand: SelectionForest,
        ): Value.Output? =
            function(input, arguments).snipToDemand(projectionDemand(transitiveDemand))

        /**
         * Applies this field resolver once, strictly projects [transitiveDemand], and additionally
         * projects the recursively available portion of [speculativeDemand].
         */
        context(world: Assumptions)
        fun resolve(
            input: Value.Object,
            arguments: Value.Arguments,
            transitiveDemand: SelectionForest,
            speculativeDemand: SelectionForest,
        ): Value.Output? =
            resolveWithSource(
                input,
                arguments,
                transitiveDemand,
                speculativeDemand,
            ).projected

        /**
         * Applies this resolver once and returns both its raw output and selective projection.
         */
        context(world: Assumptions)
        fun resolveWithSource(
            input: Value.Object,
            arguments: Value.Arguments,
            transitiveDemand: SelectionForest,
            speculativeDemand: SelectionForest,
        ): OutputProjection {
            val output = function(input, arguments)
            val required = projectionDemand(transitiveDemand)
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
                extendedFragment = extendedFragment,
                objectFragmentFunction = objectFragmentFunction,
                extendedFragmentFunction = extendedFragmentFunction,
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
                extendedFragment = extendedFragment,
                objectFragmentFunction = objectFragmentFunction,
                extendedFragmentFunction = extendedFragmentFunction,
                function = function,
                projectionDemand = { demand -> transform(projectionDemand(demand)) },
                validateObjectFragment = validateObjectFragment,
            )

        /**
         * Returns this resolver with the precomputed transitive extension of [objectFragment].
         *
         * Registry assembly applies this pre-reasoning operation after resolver lowering and
         * dependency analysis. The extension is rooted at the same object type and is the guarded,
         * path-rooted transitive closure of [objectFragment] under resolver-dependency expansion.
         */
        fun withExtendedFragment(
            extendedFragment: Fragment,
            extendedFragmentFunction: (Value.Arguments) -> Fragment,
            validateObjectFragment: (Fragment) -> Unit = {},
        ): Field {
            require(extendedFragment.nominalType == objectFragment.nominalType) {
                "Extended fragment type must match object fragment type"
            }
            return Field(
                objectFragment = objectFragment,
                extendedFragment = extendedFragment,
                objectFragmentFunction = objectFragmentFunction,
                extendedFragmentFunction = extendedFragmentFunction,
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
                    extendedFragment = objectFragment,
                    objectFragmentFunction = { objectFragment },
                    extendedFragmentFunction = { objectFragment },
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
                    extendedFragment = objectFragment,
                    objectFragmentFunction = objectFragmentFunction,
                    extendedFragmentFunction = objectFragmentFunction,
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
                nominalType = selection.nominalType,
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
 * [VariableCoordinate] values. Every variable provider is one path selection relative to its
 * coordinate's containing object and is structurally contained by the defining field resolver's
 * fixed [Resolver.Field.objectFragment] envelope. Variables referenced by a field resolver's
 * object fragment or one of its providers belong to that same field. A provider path must terminate
 * at an input-compatible value, but compatibility between that value's precise type and every
 * argument position consuming the variable is externally stipulated rather than validated by this
 * registry.
 */
interface ExecutorRegistry {
    operator fun contains(field: Schema.OutputField): Boolean

    /** Defined only when [field] is registered. */
    fun resolver(field: Schema.OutputField): Resolver.Field

    /** The provider selection for the globally registered [variable]. */
    fun variable(variable: Value.Variable): Selection

    /** The unique resolver-relative coordinate of the globally registered [variable]. */
    fun variableCoordinate(variable: Value.Variable): VariableCoordinate

    /** The resolver sites directly demanded by [site]. */
    fun mayDemandFrom(site: Schema.ResolverSite): Set<Schema.ResolverSite>

    /** The resolver sites that may directly demand [site]. */
    fun mayBeDemandedBy(site: Schema.ResolverSite): Set<Schema.ResolverSite>
}

/** Indicates that no executor is defined at a valid schema coordinate. */
class MissingExecutorException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field resolver: $typeName/$fieldName")
