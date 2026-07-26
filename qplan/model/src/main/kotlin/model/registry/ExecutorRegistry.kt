package model.registry

import model.Fragment
import model.Schema
import model.Selection

sealed interface Executor

/**
 * A node in the registry's resolver-demand graph.
 *
 * [mayDemandFrom] contains the resolvers whose coordinates are implicated by selections reachable
 * from this resolver's object fragment. [mayBeDemandedBy] is the transpose adjacency list. The
 * relation models possible propagation of resolver demand, not every input or prerequisite of
 * resolver invocation. Both properties are established when the resolver is supplied to
 * [ExecutorRegistry.of] and are undefined before then.
 */
sealed class Resolver : Executor {
    private var demandGraph: DemandGraph? = null

    val mayDemandFrom: Set<Resolver>
        get() = demandGraph().mayDemandFrom

    val mayBeDemandedBy: Set<Resolver>
        get() = demandGraph().mayBeDemandedBy

    internal fun requireUnregistered() {
        require(demandGraph == null) {
            "A resolver can belong to only one executor registry"
        }
    }

    internal fun establishDemandEdges(
        mayDemandFrom: Set<Resolver>,
        mayBeDemandedBy: Set<Resolver>,
    ) {
        requireUnregistered()
        demandGraph =
            DemandGraph(
                mayDemandFrom = mayDemandFrom.toSet(),
                mayBeDemandedBy = mayBeDemandedBy.toSet(),
            )
    }

    private fun demandGraph(): DemandGraph =
        checkNotNull(demandGraph) {
            "Resolver demand edges are established by ExecutorRegistry.of"
        }

    private class DemandGraph(
        val mayDemandFrom: Set<Resolver>,
        val mayBeDemandedBy: Set<Resolver>,
    )
}

/**
 * The selection-independent part of a node resolver.
 *
 * Applying requested selections is a separate projection of the returned object with [snip].
 * The [Schema.IDValue] is an engine-supplied addressing input and does not create a resolver-demand
 * edge. Because a node resolver has no object fragment, its [Resolver.mayDemandFrom] set is empty.
 */
typealias NodeResolverFunction = (Schema.IDValue) -> Schema.ObjectValue

/**
 * The selection-independent part of a field resolver.
 *
 * The [Schema.ObjectValue] input is the resolved value of the resolver's required object fragment,
 * and [Schema.ArgumentsValue] contains its field arguments. When invoking the function registered
 * for a field `f`, those arguments satisfy all of the following preconditions:
 *
 * - `arguments.type == f.arguments`;
 * - every supplied variable has been instantiated;
 * - declared argument defaults have been applied;
 * - every required argument is present;
 * - an optional argument without a default is absent when omitted;
 * - every present value conforms recursively to its argument type; and
 * - no value recursively contains [Schema.VariableValue].
 *
 * Argument coercion failure is handled before resolver invocation, so this function is undefined
 * for arguments containing [Schema.ErrorValue]. These are invocation preconditions;
 * [Schema.argumentsValue] remains a general construction operation and does not establish them by
 * itself.
 *
 * The returned object is independent of requested selections. A full selective interpretation
 * supplies that conceptual additional input by applying [snip] to this result, so projections for
 * different selections are coherent by construction.
 */
typealias FieldResolverFunction =
    (Schema.ObjectValue, Schema.ArgumentsValue) -> Schema.ObjectValue

class NodeResolver(
    val function: NodeResolverFunction,
) : Resolver()

/**
 * A selection-independent field-resolver function and the parent-object fragment it requires.
 *
 * Selections reachable from [objectFragment] determine this resolver's
 * [Resolver.mayDemandFrom] edges.
 *
 * Destructuring yields [objectFragment] followed by [function]. This class intentionally does not
 * define value equality because neither fragment nor function equality is defined by the model.
 */
class FieldResolver(
    val objectFragment: Fragment,
    val function: FieldResolverFunction,
) : Resolver() {
    operator fun component1(): Fragment = objectFragment

    operator fun component2(): FieldResolverFunction = function
}

/**
 * The selection-independent node and field resolvers fixed for one reasoning world.
 *
 * ### Invariant: registry-coordinate-validity
 *
 * Every resolver key is a canonical definition in the registry's schema.
 *
 * ### Invariant: registry-node-id-contract
 *
 * Every registered node-resolver type has a canonical, argumentless, ID-typed `id` field. That
 * field has no field resolver.
 *
 * ### Invariant: registry-field-resolver-contract
 *
 * Every field resolver's object-fragment type is canonical and equals the registered field's
 * containing type. No field resolver is registered for `__typename`.
 *
 * ### Invariant: registry-resolver-demand-dag
 *
 * Each registered [Resolver] occurs at exactly one resolver coordinate. A field resolver's
 * [Resolver.mayDemandFrom] set contains exactly the registered node and field resolvers directly
 * implicated by any [Selection] reachable from its object fragment; a node resolver's set is empty.
 * [Resolver.mayBeDemandedBy] is exactly the transpose of [Resolver.mayDemandFrom], and this demand
 * relation is acyclic.
 *
 * ### Direct Implication
 *
 * A [Selection] directly implicates the node resolver, when present, of every object type in its
 * possible types. For each of those possible types, it also directly implicates the field resolver,
 * when present, at the coordinate formed from that concrete type and the selection key's field
 * name. A selection or fragment implicates every resolver directly implicated by any reachable
 * selection.
 *
 * ### Demand Closure
 *
 * For a set of resolvers directly implicated by an external selection forest, the resolver demand
 * implied by that forest is the least superset closed under [Resolver.mayDemandFrom]. An edge is
 * possible rather than unconditional because the selections that induce it carry type conditions.
 * This graph does not model every invocation input, value provenance, or scheduling prerequisite.
 * In particular, the `id` supplied to a node resolver is an engine bridge and creates no edge.
 *
 * ### Lookup
 *
 * Lookup is defined only for canonical definitions from the registry's schema. A missing resolver
 * at a valid coordinate throws [MissingExecutorException]. An invalid or foreign schema definition
 * does not denote a missing executor. Requested selections are interpreted separately through
 * [snip].
 */
interface ExecutorRegistry {
    /**
     * Whether [type] has a node resolver.
     *
     * [type] must be the canonical definition from this registry's schema.
     */
    fun hasNodeResolver(type: Schema.ObjectType): Boolean

    /**
     * Whether [field] has a field resolver.
     *
     * [field] must be the canonical definition from this registry's schema.
     */
    fun hasFieldResolver(field: Schema.OutputField): Boolean

    fun nodeResolver(type: Schema.ObjectType): NodeResolver

    fun fieldResolver(field: Schema.OutputField): FieldResolver

    companion object {
        /**
         * Constructs a registry keyed by canonical schema definitions.
         *
         * @throws IllegalArgumentException when a resolver key is foreign to [schema], or when a
         * field resolver's object-fragment type is foreign to [schema] or differs from the
         * registered field's containing type; when a node-resolver type lacks a canonical,
         * argumentless, ID-typed `id` field; or when a field resolver is registered for that `id`
         * field or for `__typename`; when one resolver object occurs at multiple coordinates or
         * already belongs to a registry; or when the resolver demand relation contains a cycle
         */
        fun of(
            schema: Schema,
            nodeResolvers: Map<Schema.ObjectType, NodeResolver> = emptyMap(),
            fieldResolvers: Map<Schema.OutputField, FieldResolver> = emptyMap(),
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
    private val nodeResolvers: Map<Schema.ObjectType, NodeResolver>,
    private val fieldResolvers: Map<Schema.OutputField, FieldResolver>,
) : ExecutorRegistry {
    init {
        val resolvers = nodeResolvers.values + fieldResolvers.values
        require(resolvers.toSet().size == resolvers.size) {
            "Each resolver object must occur at exactly one resolver coordinate"
        }
        resolvers.forEach(Resolver::requireUnregistered)

        val nodeIdFields =
            nodeResolvers.keys.mapTo(mutableSetOf()) { type ->
                validateCanonicalType(type)
                val idField =
                    type.fields["id"]
                        ?: throw IllegalArgumentException(
                            "Node-resolver type ${type.typeName} has no id field",
                        )
                require(schema.field(type.typeName, "id") == idField) {
                    "${type.typeName}/id is not the canonical node id field in this registry's schema"
                }
                require(idField.arguments == Schema.NoArguments) {
                    "Node id field ${type.typeName}/id must take no arguments"
                }
                require(idField.type.baseType == Schema.IDType) {
                    "Node id field ${type.typeName}/id must be ID-typed"
                }
                idField
            }
        fieldResolvers.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingType.typeName
            require(field !in nodeIdFields) {
                "Node id field $typeName/${field.fieldName} cannot have a field resolver"
            }
            require(field.fieldName != "__typename") {
                "Engine field $typeName/__typename cannot have a field resolver"
            }
            val fragmentType = resolver.objectFragment.nominalType
            require(schema.type(fragmentType.typeName) == fragmentType) {
                "${fragmentType.typeName} is not the canonical object-fragment type in this " +
                    "registry's schema"
            }
            require(fragmentType == field.containingType) {
                "Object fragment type ${fragmentType.typeName} does not match field resolver " +
                    "parent type ${field.containingType.typeName} at " +
                    "$typeName/${field.fieldName}"
            }
        }

        val mayDemandFrom =
            buildMap<Resolver, Set<Resolver>> {
                nodeResolvers.values.forEach { resolver ->
                    put(resolver, emptySet())
                }
                fieldResolvers.values.forEach { resolver ->
                    put(resolver, implicatedResolvers(resolver.objectFragment))
                }
            }
        requireAcyclic(mayDemandFrom)
        val mayBeDemandedBy =
            resolvers.associateWith { mutableSetOf<Resolver>() }
                .also { transpose ->
                    mayDemandFrom.forEach { (demander, demandedResolvers) ->
                        demandedResolvers.forEach { demandedResolver ->
                            transpose.getValue(demandedResolver).add(demander)
                        }
                    }
                }
        resolvers.forEach { resolver ->
            resolver.establishDemandEdges(
                mayDemandFrom = mayDemandFrom.getValue(resolver),
                mayBeDemandedBy = mayBeDemandedBy.getValue(resolver),
            )
        }
    }

    override fun hasNodeResolver(type: Schema.ObjectType): Boolean {
        validateCanonicalType(type)
        return type in nodeResolvers
    }

    override fun hasFieldResolver(field: Schema.OutputField): Boolean {
        validateCanonicalField(field)
        return field in fieldResolvers
    }

    override fun nodeResolver(type: Schema.ObjectType): NodeResolver {
        validateCanonicalType(type)
        return nodeResolvers[type]
            ?: throw MissingExecutorException(type.typeName)
    }

    override fun fieldResolver(field: Schema.OutputField): FieldResolver {
        validateCanonicalField(field)
        val typeName = field.containingType.typeName
        return fieldResolvers[field]
            ?: throw MissingExecutorException(typeName, field.fieldName)
    }

    private fun validateCanonicalType(type: Schema.ObjectType) {
        require(schema.type(type.typeName) == type) {
            "${type.typeName} is not the canonical type in this registry's schema"
        }
    }

    private fun validateCanonicalField(
        field: Schema.OutputField,
        role: String = "field",
    ) {
        val typeName = field.containingType.typeName
        require(schema.field(typeName, field.fieldName) == field) {
            "$typeName/${field.fieldName} is not the canonical $role in this registry's schema"
        }
    }

    private fun implicatedResolvers(fragment: Fragment): Set<Resolver> =
        buildSet {
            fragment.subselections.forEach { selection ->
                addImplicatedBy(selection)
            }
        }

    private fun MutableSet<Resolver>.addImplicatedBy(selection: Selection) {
        selection.possibleTypes.forEach { possibleType ->
            nodeResolvers[possibleType]?.let(::add)
            possibleType.fields[selection.key.field.fieldName]
                ?.let(fieldResolvers::get)
                ?.let(::add)
        }
        selection.subselections.forEach { subselection ->
            addImplicatedBy(subselection)
        }
    }

    private fun requireAcyclic(mayDemandFrom: Map<Resolver, Set<Resolver>>) {
        val state = mutableMapOf<Resolver, VisitState>()

        fun visit(resolver: Resolver) {
            when (state[resolver]) {
                VisitState.VISITING ->
                    throw IllegalArgumentException(
                        "Resolver object fragments contain a demand cycle",
                    )

                VisitState.VISITED -> return
                null -> Unit
            }

            state[resolver] = VisitState.VISITING
            mayDemandFrom.getValue(resolver).forEach(::visit)
            state[resolver] = VisitState.VISITED
        }

        mayDemandFrom.keys.forEach(::visit)
    }

    private enum class VisitState {
        VISITING,
        VISITED,
    }
}
