package model.registry

import model.Assumptions
import model.Fragment
import model.Schema
import model.SelectionForest
import model.Value

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
    class Field private constructor(
        val objectFragment: Fragment,
        private val objectFragmentFunction: (Value.Arguments) -> Fragment,
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
                objectFragmentFunction = objectFragmentFunction,
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
                objectFragmentFunction = objectFragmentFunction,
                function = function,
                projectionDemand = { demand -> transform(projectionDemand(demand)) },
            )

        companion object {
            fun of(
                objectFragment: Fragment,
                function: FieldResolverFunction,
            ): Field =
                Field(
                    objectFragment = objectFragment,
                    objectFragmentFunction = { objectFragment },
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
                    objectFragmentFunction = objectFragmentFunction,
                    function = function,
                    projectionDemand = { it },
                )
        }
    }
}

/**
 * The externally supplied field resolvers fixed for one reasoning world.
 *
 * An output field is an actual resolver coordinate exactly when [contains] returns true. The
 * registry satisfies canonical schema ownership, special-field exclusions, query coverage, exact
 * transpose, and acyclicity.
 */
interface ExecutorRegistry {
    operator fun contains(field: Schema.OutputField): Boolean

    /** Defined only when [field] is registered. */
    fun resolver(field: Schema.OutputField): Resolver.Field

    /** The registered fields directly demanded by this registered field. */
    fun mayDemandFrom(field: Schema.OutputField): Set<Schema.OutputField>

    /** The registered fields that may directly demand this registered field. */
    fun mayBeDemandedBy(field: Schema.OutputField): Set<Schema.OutputField>
}

/** Indicates that no executor is defined at a valid schema coordinate. */
class MissingExecutorException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field resolver: $typeName/$fieldName")
