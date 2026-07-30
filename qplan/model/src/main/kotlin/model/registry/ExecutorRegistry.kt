package model.registry

import model.Assumptions
import model.Fragment
import model.Schema
import model.SelectionForest
import model.Value

sealed interface Executor

/** A deterministic partial map from a node ID to its selection-independent object value. */
typealias NodeResolverFunction = (Value.ID) -> Value.Object

/** A deterministic partial map from a resolved object fragment and arguments to an output value. */
typealias FieldResolverFunction =
    (Value.Object, Value.Arguments) -> Value.Output?

/**
 * A resolver supplied by the reasoning world's external executor registry.
 *
 * Resolver equality is undefined. Resolver-demand identity is expressed with canonical
 * [Schema.ResolverSite] elements instead.
 */
sealed interface Resolver : Executor {
    /**
     * A node resolver registered at one concrete object type.
     *
     * ### Invariant: node-resolver-output-id
     *
     * For every [Value.ID] in the construction function's domain, its result contains the
     * registered type's canonical argumentless `id` key mapped to that same input ID.
     */
    class Node private constructor(
        private val function: NodeResolverFunction,
    ) : Resolver {
        /**
         * Applies this node resolver and projects its selection-independent result to
         * [transitiveDemand].
         */
        context(world: Assumptions)
        fun resolve(
            type: Schema.ObjectType,
            id: Value.ID,
            transitiveDemand: SelectionForest,
        ): Value.Object =
            type.snipToDemand(
                result = function(id),
                demand = transitiveDemand,
            )

        companion object {
            fun of(function: NodeResolverFunction): Node = Node(function)
        }
    }

    class Field private constructor(
        val objectFragment: Fragment,
        private val function: FieldResolverFunction,
    ) : Resolver {
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
            function(input, arguments).snipToDemand(transitiveDemand)

        operator fun component1(): Fragment = objectFragment

        companion object {
            fun of(
                objectFragment: Fragment,
                function: FieldResolverFunction,
            ): Field = Field(objectFragment, function)
        }
    }
}

/**
 * The externally supplied node and field resolvers fixed for one reasoning world.
 *
 * Object types and output fields are only resolver-site candidates. A site is an actual coordinate
 * exactly when [contains] returns true. The registry's maps satisfy canonical schema ownership,
 * node eligibility, special-field exclusions, query coverage, exact transpose, and acyclicity.
 */
interface ExecutorRegistry {
    operator fun contains(site: Schema.ResolverSite): Boolean

    /** Defined only when [type] is registered. */
    fun resolver(type: Schema.ObjectType): Resolver.Node

    /** Defined only when [field] is registered. */
    fun resolver(field: Schema.OutputField): Resolver.Field

    /** The registered sites directly demanded by this registered field site. */
    fun mayDemandFrom(field: Schema.OutputField): Set<Schema.ResolverSite>

    /** The registered field sites that may directly demand this registered site. */
    fun mayBeDemandedBy(site: Schema.ResolverSite): Set<Schema.OutputField>
}

/** Indicates that no executor is defined at a valid schema coordinate. */
class MissingExecutorException(
    val typeName: String,
    val fieldName: String? = null,
) : NoSuchElementException(
        if (fieldName == null) {
            "Missing node resolver: $typeName"
        } else {
            "Missing field resolver: $typeName/$fieldName"
        },
    )
