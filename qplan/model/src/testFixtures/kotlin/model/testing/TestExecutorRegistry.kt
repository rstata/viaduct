package model.testing

import model.Fragment
import model.Schema
import model.Selection
import model.registry.ExecutorRegistry
import model.registry.FieldResolverFunction
import model.registry.MissingExecutorException
import model.registry.NodeResolverFunction
import model.registry.Resolver

fun nodeResolverOf(function: NodeResolverFunction): Resolver.Node =
    Resolver.Node.of(function)

fun fieldResolverOf(
    objectFragment: Fragment,
    function: FieldResolverFunction,
): Resolver.Field = Resolver.Field.of(objectFragment, function)

internal fun executorRegistryOf(
    schema: Schema,
    nodeResolvers: Map<Schema.ObjectType, Resolver.Node>,
    fieldResolvers: Map<Schema.OutputField, Resolver.Field>,
): ExecutorRegistry =
    TestExecutorRegistry(schema, nodeResolvers, fieldResolvers)

private class TestExecutorRegistry(
    private val schema: Schema,
    private val nodeResolvers: Map<Schema.ObjectType, Resolver.Node>,
    private val fieldResolvers: Map<Schema.OutputField, Resolver.Field>,
) : ExecutorRegistry {
    private val outgoing: Map<Schema.OutputField, Set<Schema.ResolverSite>>
    private val incoming: Map<Schema.ResolverSite, Set<Schema.OutputField>>

    init {
        val nodeType =
            if (nodeResolvers.isEmpty()) {
                null
            } else {
                val candidate =
                    try {
                        schema.type("Node")
                    } catch (_: Schema.MissingSchemaElementException) {
                        null
                    }
                candidate as? Schema.InterfaceType
                    ?: throw IllegalArgumentException(
                        "Node resolvers require a canonical Node interface",
                    )
            }
        val nodeIdFields =
            nodeResolvers.keys.mapTo(mutableSetOf()) { type ->
                validateCanonicalType(type)
                require(schema.relation(nodeType!!, type) == Schema.TypeRelation.WIDER_THAN) {
                    "Node-resolver type ${type.typeName} does not implement Node"
                }
                val idField =
                    type.fields["id"]
                        ?: throw IllegalArgumentException(
                            "Node-resolver type ${type.typeName} has no id field",
                        )
                require(schema.field(type.typeName, "id") == idField) {
                    "${type.typeName}/id is not canonical in this registry's schema"
                }
                require(idField.arguments == Schema.NoArguments) {
                    "Node id field ${type.typeName}/id must take no arguments"
                }
                require(idField.typeExpr.baseType == Schema.IDType) {
                    "Node id field ${type.typeName}/id must be ID-typed"
                }
                idField
            }
        fieldResolvers.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingType.typeName
            require(field.containingType is Schema.ObjectType) {
                "Field resolver $typeName/${field.fieldName} must belong to a concrete object type"
            }
            require(field !in nodeIdFields) {
                "Node id field $typeName/${field.fieldName} cannot have a field resolver"
            }
            require(field.fieldName != "__typename") {
                "Engine field $typeName/__typename cannot have a field resolver"
            }
            val fragmentType = resolver.objectFragment.nominalType
            require(schema.type(fragmentType.typeName) == fragmentType) {
                "${fragmentType.typeName} is not canonical in this registry's schema"
            }
            require(fragmentType == field.containingType) {
                "Object fragment type ${fragmentType.typeName} does not match $typeName/${field.fieldName}"
            }
        }
        val missingQueryFields =
            schema.query.fields.values
                .filter { it.fieldName != "__typename" && it !in fieldResolvers }
        require(missingQueryFields.isEmpty()) {
            "Query fields without field resolvers: " +
                missingQueryFields.map { it.fieldName }.sorted().joinToString()
        }

        outgoing =
            fieldResolvers.mapValues { (_, resolver) ->
                implicatedSites(resolver.objectFragment)
            }
        requireAcyclic(outgoing)
        val allSites: Set<Schema.ResolverSite> = nodeResolvers.keys + fieldResolvers.keys
        incoming =
            allSites.associateWith { site ->
                outgoing
                    .filterValues { site in it }
                    .keys
            }
    }

    override fun contains(site: Schema.ResolverSite): Boolean =
        when (site) {
            is Schema.ObjectType -> {
                validateCanonicalType(site)
                site in nodeResolvers
            }
            is Schema.OutputField -> {
                validateCanonicalField(site)
                site in fieldResolvers
            }
        }

    override fun resolver(type: Schema.ObjectType): Resolver.Node {
        validateCanonicalType(type)
        return nodeResolvers[type] ?: throw MissingExecutorException(type.typeName)
    }

    override fun resolver(field: Schema.OutputField): Resolver.Field {
        validateCanonicalField(field)
        return fieldResolvers[field]
            ?: throw MissingExecutorException(field.containingType.typeName, field.fieldName)
    }

    override fun mayDemandFrom(field: Schema.OutputField): Set<Schema.ResolverSite> {
        validateCanonicalField(field)
        return outgoing[field]
            ?: throw MissingExecutorException(field.containingType.typeName, field.fieldName)
    }

    override fun mayBeDemandedBy(site: Schema.ResolverSite): Set<Schema.OutputField> {
        require(site in this) { "Resolver site is not registered" }
        return incoming.getValue(site)
    }

    private fun validateCanonicalType(type: Schema.ObjectType) {
        require(schema.type(type.typeName) == type) {
            "${type.typeName} is not canonical in this registry's schema"
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

    private fun implicatedSites(fragment: Fragment): Set<Schema.ResolverSite> {
        val result = mutableSetOf<Schema.ResolverSite>()
        fragment.subselections.forEach { selection ->
            result.addImplicatedBy(selection)
        }
        return result
    }

    private fun MutableSet<Schema.ResolverSite>.addImplicatedBy(selection: Selection) {
        selection.possibleTypes.forEach { possibleType ->
            if (possibleType in nodeResolvers) add(possibleType)
            possibleType.fields[selection.key.field.fieldName]
                ?.takeIf { it in fieldResolvers }
                ?.let(::add)
        }
        selection.subselections.forEach { subselection ->
            addImplicatedBy(subselection)
        }
    }

    private fun requireAcyclic(outgoing: Map<Schema.OutputField, Set<Schema.ResolverSite>>) {
        val state = mutableMapOf<Schema.ResolverSite, VisitState>()

        fun visit(site: Schema.ResolverSite) {
            when (state[site]) {
                VisitState.VISITING ->
                    throw IllegalArgumentException(
                        "Resolver object fragments contain a demand cycle",
                    )
                VisitState.VISITED -> return
                null -> Unit
            }
            state[site] = VisitState.VISITING
            if (site is Schema.OutputField) {
                outgoing.getValue(site).forEach(::visit)
            }
            state[site] = VisitState.VISITED
        }

        (nodeResolvers.keys + fieldResolvers.keys).forEach(::visit)
    }

    private enum class VisitState {
        VISITING,
        VISITED,
    }
}
