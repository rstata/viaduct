package model.testing

import jakarta.inject.Qualifier

/**
 * The GraphQL SDL from which the reasoning world's schema is constructed.
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
 * The model variable values fixed for the reasoning world.
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class VariableValues

/**
 * The node-resolver functions fixed for the reasoning world.
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
 * The field-resolver functions fixed for the reasoning world.
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class FieldResolvers
