package model.testing

import jakarta.inject.Qualifier

/**
 * The external GraphQL SDL from which the source and augmented canonical schemas are constructed.
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class SchemaSDL

/**
 * The raw external node-resolver functions supplied before fixture lowering.
 *
 * Registry composition consumes these functions and exposes only generated field resolvers to the
 * reasoning world.
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class NodeResolvers

/**
 * The raw external field resolvers supplied before fixture lowering.
 *
 * Registry composition may relocate and adapt node-valued producers before exposing canonical
 * field resolvers to the reasoning world.
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class FieldResolvers

/** Resolver-relative variable provider selections supplied before registry assembly. */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class VariableProviders
