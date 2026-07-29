package model

/**
 * A canonical schema element that may serve as a resolver coordinate.
 *
 * An object type or output field becomes an actual resolver coordinate only when it belongs to an
 * [model.registry.ExecutorRegistry]. Separate node-site and field-site wrappers are intentionally
 * absent: they would add notation while capturing only local shape constraints, not registry
 * membership, canonical ownership, special-field exclusions, or demand-graph invariants.
 */
sealed interface ResolverSite
