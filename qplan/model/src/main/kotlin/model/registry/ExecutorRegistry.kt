package model.registry

import model.Fragment
import model.Schema
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
    interface Node : Resolver {
        val function: NodeResolverFunction
    }

    interface Field : Resolver {
        val objectFragment: Fragment
        val function: FieldResolverFunction

        operator fun component1(): Fragment = objectFragment

        operator fun component2(): FieldResolverFunction = function
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
