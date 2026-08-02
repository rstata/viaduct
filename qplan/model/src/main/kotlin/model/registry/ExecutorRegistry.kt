package model.registry

import model.Assumptions
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.VariableCoordinate

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
    /**
     * A field resolver and its object-valued input requirements.
     *
     * [objectFragment] is the representative direct requirement. [extendedFragment] starts with
     * that requirement and additionally roots the transitive requirements of resolver occurrences
     * reached within it. The argument-taking forms preserve exact argument-dependent coordinates.
     */
    class Field private constructor(
        val objectFragment: Fragment,
        val extendedFragment: Fragment,
        private val objectFragmentFunction: (Value.Arguments) -> Fragment,
        private val extendedFragmentFunction: (Value.Arguments) -> Fragment,
        private val function: FieldResolverFunction,
        private val projectionDemand: (SelectionForest) -> SelectionForest,
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
            objectFragmentFunction(arguments)

        /** Returns the transitive extension of the object fragment for this exact argument tuple. */
        fun extendedFragment(arguments: Value.Arguments): Fragment =
            extendedFragmentFunction(arguments)

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
            )

        /**
         * Returns this resolver with the precomputed transitive extension of [objectFragment].
         *
         * Registry assembly applies this pre-reasoning operation after resolver lowering and
         * dependency analysis. The extension is rooted at the same object type and contains the
         * requirements of resolver fields reached within [objectFragment].
         */
        fun withExtendedFragment(
            extendedFragment: Fragment,
            extendedFragmentFunction: (Value.Arguments) -> Fragment,
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
                )

            /**
             * Constructs a resolver whose exact object fragment depends on its arguments.
             *
             * [objectFragment] is a representative fragment used only by pre-reasoning registry
             * analysis. Registry assembly must account separately for dependencies omitted from
             * that representative, while semantic operations use [objectFragmentFunction].
             */
            fun ofArgumentDependent(
                objectFragment: Fragment,
                objectFragmentFunction: (Value.Arguments) -> Fragment,
                function: FieldResolverFunction,
            ): Field =
                Field(
                    objectFragment = objectFragment,
                    extendedFragment = objectFragment,
                    objectFragmentFunction = objectFragmentFunction,
                    extendedFragmentFunction = objectFragmentFunction,
                    function = function,
                    projectionDemand = { it },
                )
        }
    }
}

/**
 * The externally supplied field resolvers and field-relative variable providers fixed for one
 * reasoning world.
 *
 * An output field is an actual resolver coordinate exactly when [contains] returns true. The
 * registry satisfies canonical schema ownership, special-field exclusions, query coverage,
 * globally unique variable names, exact transpose, and acyclicity across output fields and
 * [VariableCoordinate] values. Every variable provider is one path selection relative to its
 * coordinate's containing object, and variables referenced by a field resolver's object fragment
 * or one of its providers belong to that same field. An argument-dependent fragment may
 * transparently forward a caller's variables through its argument tuple without redefining them.
 * A provider path must terminate at an input-compatible value, but compatibility between that
 * value's precise type and every argument position consuming the variable is externally stipulated
 * rather than validated by this registry.
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
