package model.registry

import model.Fragment
import model.ResolverSite
import model.Schema

sealed interface Executor

/**
 * A resolver supplied by the reasoning world's external executor registry.
 *
 * Resolver equality is undefined. Resolver-demand identity is expressed with canonical
 * [ResolverSite] schema elements instead.
 */
sealed interface Resolver : Executor

/** A deterministic partial map from a node ID to its selection-independent object value. */
typealias NodeResolverFunction = (Schema.IDValue) -> Schema.ObjectValue

/** A deterministic partial map from a resolved object fragment and arguments to an output value. */
typealias FieldResolverFunction =
    (Schema.ObjectValue, Schema.ArgumentsValue) -> Schema.OutputValue?

interface NodeResolver : Resolver {
    val function: NodeResolverFunction
}

interface FieldResolver : Resolver {
    val objectFragment: Fragment
    val function: FieldResolverFunction

    operator fun component1(): Fragment = objectFragment

    operator fun component2(): FieldResolverFunction = function
}

/**
 * The externally supplied node and field resolvers fixed for one reasoning world.
 *
 * Object types and output fields are only resolver-site candidates. A site is an actual coordinate
 * exactly when [contains] returns true. The registry's maps satisfy canonical schema ownership,
 * node eligibility, special-field exclusions, query coverage, exact transpose, and acyclicity.
 */
interface ExecutorRegistry {
    operator fun contains(site: ResolverSite): Boolean

    /** Defined only when [type] is registered. */
    fun resolver(type: Schema.ObjectType): NodeResolver

    /** Defined only when [field] is registered. */
    fun resolver(field: Schema.OutputField): FieldResolver

    /** The registered sites directly demanded by this registered field site. */
    fun mayDemandFrom(field: Schema.OutputField): Set<ResolverSite>

    /** The registered field sites that may directly demand this registered site. */
    fun mayBeDemandedBy(site: ResolverSite): Set<Schema.OutputField>
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
