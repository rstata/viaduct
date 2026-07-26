package model.registry

import model.Fragment
import model.Schema

sealed interface Executor

sealed interface Resolver : Executor

typealias NodeResolverFunction = (Schema.IDValue) -> Schema.ObjectValue

typealias FieldResolverFunction =
    (Schema.ObjectValue, Schema.ArgumentsValue) -> Schema.ObjectValue

class NodeResolver(
    val function: NodeResolverFunction,
) : Resolver

/**
 * A field-resolver function and the parent-object fragment it requires.
 *
 * Destructuring yields [objectFragment] followed by [function]. This class intentionally does not
 * define value equality because neither fragment nor function equality is defined by the model.
 */
class FieldResolver(
    val objectFragment: Fragment,
    val function: FieldResolverFunction,
) : Resolver {
    operator fun component1(): Fragment = objectFragment

    operator fun component2(): FieldResolverFunction = function
}

/**
 * The schema coordinate of a field resolver.
 */
data class FieldCoordinate(
    val typeName: String,
    val fieldName: String,
)

/**
 * The resolvers fixed for one reasoning world.
 *
 * Lookup is defined only for canonical definitions from the registry's schema. A missing resolver
 * at a valid coordinate throws [MissingExecutorException]. An invalid or foreign schema definition
 * does not denote a missing executor.
 */
interface ExecutorRegistry {
    fun nodeResolver(type: Schema.ObjectType): NodeResolver

    fun fieldResolver(field: Schema.OutputField): FieldResolver

    companion object {
        fun of(
            schema: Schema,
            nodeResolvers: Map<String, NodeResolver> = emptyMap(),
            fieldResolvers: Map<FieldCoordinate, FieldResolver> = emptyMap(),
        ): ExecutorRegistry =
            DefaultExecutorRegistry(
                schema = schema,
                nodeResolvers = nodeResolvers.toMap(),
                fieldResolvers = fieldResolvers.toMap(),
            )

        fun empty(schema: Schema): ExecutorRegistry = of(schema)
    }
}

/**
 * Indicates that no executor is defined at a valid schema coordinate.
 */
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

private class DefaultExecutorRegistry(
    private val schema: Schema,
    private val nodeResolvers: Map<String, NodeResolver>,
    private val fieldResolvers: Map<FieldCoordinate, FieldResolver>,
) : ExecutorRegistry {
    init {
        nodeResolvers.keys.forEach { typeName ->
            require(schema.type(typeName) is Schema.ObjectType) {
                "Node resolver coordinate is not an object type: $typeName"
            }
        }
        fieldResolvers.keys.forEach { coordinate ->
            schema.field(coordinate.typeName, coordinate.fieldName)
        }
    }

    override fun nodeResolver(type: Schema.ObjectType): NodeResolver {
        require(schema.type(type.typeName) == type) {
            "${type.typeName} is not the canonical type in this registry's schema"
        }
        return nodeResolvers[type.typeName]
            ?: throw MissingExecutorException(type.typeName)
    }

    override fun fieldResolver(field: Schema.OutputField): FieldResolver {
        val typeName = field.containingType.typeName
        require(schema.field(typeName, field.fieldName) == field) {
            "$typeName/${field.fieldName} is not the canonical field in this registry's schema"
        }
        return fieldResolvers[FieldCoordinate(typeName, field.fieldName)]
            ?: throw MissingExecutorException(typeName, field.fieldName)
    }
}
