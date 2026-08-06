package model.testing

import model.Fragment
import model.Schema
import model.Selection
import model.TypeExpr
import model.Value
import model.VariableCoordinate
import model.emptyFragmentOf
import model.registry.ExecutorRegistry
import model.registry.FieldResolverFunction
import model.registry.MissingExecutorException
import model.registry.Resolver
import model.selectionForestOf
import model.toSelectionForest

/**
 * A raw external node lookup accepted only by test-fixture composition.
 *
 * This alias is not part of the canonical resolver algebra. [executorRegistryOf] consumes these
 * functions and exposes a field-only [ExecutorRegistry].
 */
typealias NodeResolverFunction = (Value.ID) -> Value.Object

/** Marks a raw external node lookup for fixture composition. */
fun nodeResolverOf(function: NodeResolverFunction): NodeResolverFunction = function

typealias CanonicalFieldResolverApplicationObserver =
    (Schema.OutputField, Value.Object, Value.Arguments, model.SelectionForest?) -> Unit

fun fieldResolverOf(
    objectFragment: Fragment,
    function: FieldResolverFunction,
): Resolver.Field = Resolver.Field.of(objectFragment, function)

internal fun executorRegistryOf(
    schema: GJSchema,
    nodeResolvers: Map<Schema.ObjectType, NodeResolverFunction>,
    fieldResolvers: Map<Schema.OutputField, Resolver.Field>,
    variableProviders: Map<VariableCoordinate, Selection>,
    applicationObserver: CanonicalFieldResolverApplicationObserver?,
): ExecutorRegistry {
    val lowering = NodeResolverLowering(schema, nodeResolvers, fieldResolvers)
    val canonicalResolvers =
        if (applicationObserver == null) {
            lowering.fieldResolvers
        } else {
            lowering.fieldResolvers.mapValues { (field, resolver) ->
                resolver.observeApplications { input, arguments, demand ->
                    applicationObserver(field, input, arguments, demand)
                }
            }
        }
    return TestExecutorRegistry(
        schema = schema,
        fieldResolvers = canonicalResolvers,
        additionalDemand = lowering.additionalDemand,
        variableProviders = variableProviders,
    )
}

/**
 * Lowers source-world node references and node lookups into the canonical field-only world.
 *
 * For each eligible source field `foo(args)` with a raw field resolver, that producer is moved to
 * singular `foo$id(args)` or list-shaped `foo$ids(args)` bridge and adapted to emit typed IDs. A
 * generated resolver at every eligible `foo(args)` demands that exact bridge key and dispatches the
 * typed ID values to the raw node lookup. Bridge type expressions preserve the source field's list
 * and nullability shape. Outputs of containing resolvers are rewritten recursively so passive
 * nested node references also become bridge values.
 *
 * A lowered field must be declared as `Node` or a subtype whose every possible concrete type has a
 * raw node lookup. Mixed node-resolved and inline possible types are rejected at this composition
 * boundary.
 */
private class NodeResolverLowering(
    private val schema: GJSchema,
    private val nodeResolvers: Map<Schema.ObjectType, NodeResolverFunction>,
    rawFieldResolvers: Map<Schema.OutputField, Resolver.Field>,
) {
    private val nodeType: Schema.InterfaceType? = canonicalNodeType()
    private val loweredFields: Set<Schema.OutputField> = loweredNodeFields()
    private val loweredByField: Map<Schema.OutputField, Schema.OutputField> =
        loweredFields.associateWith(::bridgeField)

    val fieldResolvers: Map<Schema.OutputField, Resolver.Field>
    val additionalDemand: Map<Schema.OutputField, Set<Schema.OutputField>>

    init {
        validateRawFieldResolvers(rawFieldResolvers)

        val ordinaryResolvers =
            rawFieldResolvers
                .filterKeys { it !in loweredFields }
                .mapValues { (field, resolver) ->
                    resolver.mapOutput { output ->
                        lowerNestedOutput(output, field.typeExpr)
                    }.mapDemand(::lowerDemand)
                }
        val bridgeResolvers =
            loweredFields.mapNotNull { field ->
                rawFieldResolvers[field]?.let { resolver ->
                    bridgeField(field) to
                        resolver.mapOutput { output ->
                            extractNodeIds(
                                output = output,
                                nodeTypeExpr = field.typeExpr,
                                idTypeExpr = bridgeField(field).typeExpr,
                            )
                        }
                }
            }.toMap()
        val loaderResolvers =
            loweredFields.associateWith(::loaderResolver)

        fieldResolvers = ordinaryResolvers + bridgeResolvers + loaderResolvers
        additionalDemand =
            loweredFields.associateWith { field ->
                bridgeField(field)
                    .takeIf { it in fieldResolvers }
                    ?.let(::setOf)
                    .orEmpty()
            }
    }

    private fun canonicalNodeType(): Schema.InterfaceType? {
        if (nodeResolvers.isEmpty()) return null
        val candidate =
            try {
                schema.type("Node")
            } catch (_: Schema.MissingSchemaElementException) {
                null
            }
        return candidate as? Schema.InterfaceType
            ?: throw IllegalArgumentException(
                "Node resolvers require a canonical Node interface",
            )
    }

    private fun loweredNodeFields(): Set<Schema.OutputField> {
        nodeResolvers.forEach { (type, _) ->
            validateCanonicalType(type)
            require(schema.relation(nodeType!!, type) == Schema.TypeRelation.WIDER_THAN) {
                "Node-resolver type ${type.typeName} does not implement Node"
            }
            validateNodeIdField(type)
        }

        return schema.objectTypes
            .flatMap { it.fields.values }
            .filterNot { isNodeIdBridgeName(it.fieldName) }
            .mapNotNullTo(linkedSetOf()) { field ->
                val outputType = field.typeExpr.baseType as? Schema.CompositeType
                    ?: return@mapNotNullTo null
                val registeredTypes = outputType.possibleTypes.filterTo(linkedSetOf()) {
                    it in nodeResolvers
                }
                if (registeredTypes.isEmpty()) return@mapNotNullTo null

                val isDeclaredNode =
                    nodeType != null &&
                        schema.relation(nodeType, outputType) in
                        setOf(
                            Schema.TypeRelation.SAME,
                            Schema.TypeRelation.WIDER_THAN,
                        )
                require(isDeclaredNode && registeredTypes == outputType.possibleTypes) {
                    "Field ${field.containingType.typeName}/${field.fieldName} mixes node-resolved " +
                        "and inline object values; declare a Node output whose every possible type " +
                        "has a node resolver"
                }
                field
            }
    }

    private fun validateRawFieldResolvers(
        fieldResolvers: Map<Schema.OutputField, Resolver.Field>,
    ) {
        val nodeIdFields = nodeResolvers.keys.mapTo(linkedSetOf(), ::validateNodeIdField)
        fieldResolvers.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingType.typeName
            require(field.containingType is Schema.ObjectType) {
                "Field resolver $typeName/${field.fieldName} must belong to a concrete object type"
            }
            require(!isNodeIdBridgeName(field.fieldName)) {
                "Synthetic field $typeName/${field.fieldName} cannot be supplied directly"
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
                "Object fragment type ${fragmentType.typeName} does not match " +
                    "$typeName/${field.fieldName}"
            }
        }
    }

    private fun loaderResolver(field: Schema.OutputField): Resolver.Field {
        val owner = field.containingType as Schema.ObjectType
        val bridge = bridgeField(field)
        val representativeFragment =
            Fragment.of(
                nominalType = owner,
                subselections =
                    selectionForestOf(
                        Selection.of(
                            key =
                                Value.Key.of(
                                    field = bridge,
                                    arguments =
                                        bridge.arguments.fields.keys.associateWith {
                                            Value.Error
                                        },
                                ),
                            possibleTypes = setOf(owner),
                            subselections = selectionForestOf(),
                        ),
                    ),
            )
        return Resolver.Field.ofArgumentRetargeting(
            objectFragment = representativeFragment,
            retargetArguments = { _, arguments -> arguments.retarget(bridge) },
            function = { input, arguments ->
                val bridgeKey = Value.Key.of(bridge, arguments.retarget(bridge))
                loadNodes(
                    ids = input.fieldValues.getValue(bridgeKey),
                    nodeTypeExpr = field.typeExpr,
                    idTypeExpr = bridge.typeExpr,
                )
            },
        ).mapDemand(::lowerDemand)
    }

    private fun lowerDemand(selections: model.SelectionForest): model.SelectionForest =
        selections.flatMap { selection ->
            val loweredSubselections = lowerDemand(selection.subselections)
            val original =
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = loweredSubselections,
                )
            val bridgePossibleTypes =
                selection.possibleTypes.filterTo(linkedSetOf()) { possibleType ->
                    possibleType.fields[selection.key.field.fieldName] in loweredFields
                }
            val bridgeField =
                selection.key.field.containingType.fields[
                    nodeIdBridgeName(selection.key.field)
                ]
            if (bridgeField == null || bridgePossibleTypes.isEmpty()) {
                selectionForestOf(original)
            } else {
                selectionForestOf(
                    original,
                    Selection.of(
                        key =
                            Value.Key.of(
                                bridgeField,
                                selection.key.arguments.retarget(bridgeField),
                            ),
                        possibleTypes = bridgePossibleTypes,
                        subselections = selectionForestOf(),
                    ),
                )
            }
        }

    private fun extractNodeIds(
        output: Value.Output?,
        nodeTypeExpr: TypeExpr<Schema.OutputType>,
        idTypeExpr: TypeExpr<Schema.OutputType>,
    ): Value.Output? =
        when {
            output == null || output == Value.Error -> output
            nodeTypeExpr is TypeExpr.List && idTypeExpr is TypeExpr.List -> {
                require(output is Value.OutputList) {
                    "Node-list field resolver did not return a list"
                }
                Value.OutputList.of(
                    typeExpr = idTypeExpr.elementType,
                    values =
                        output.values.map { value ->
                            extractNodeIds(
                                output = value,
                                nodeTypeExpr = nodeTypeExpr.elementType,
                                idTypeExpr = idTypeExpr.elementType,
                            )
                        },
                )
            }
            nodeTypeExpr is TypeExpr.Named && idTypeExpr is TypeExpr.Named -> {
                require(output is Value.Object) {
                    "Node field resolver did not return a node reference"
                }
                val idField = validateNodeIdField(output.type)
                val id = output.fieldValues.getValue(Value.Key.of(idField, emptyMap()))
                require(id != Value.Error && id is Value.ID) {
                    "Node reference ${output.type.typeName}/id must contain a non-error ID"
                }
                typedId(output.type, id)
            }
            else -> error("Node and ID bridge type expressions have different list shapes")
        }

    private fun loadNodes(
        ids: Value.Output?,
        nodeTypeExpr: TypeExpr<Schema.OutputType>,
        idTypeExpr: TypeExpr<Schema.OutputType>,
    ): Value.Output? =
        when {
            ids == null || ids == Value.Error -> ids
            nodeTypeExpr is TypeExpr.List && idTypeExpr is TypeExpr.List -> {
                require(ids is Value.OutputList) {
                    "Node-ID bridge did not contain a list"
                }
                Value.OutputList.of(
                    typeExpr = nodeTypeExpr.elementType,
                    values =
                        ids.values.map { value ->
                            loadNodes(
                                ids = value,
                                nodeTypeExpr = nodeTypeExpr.elementType,
                                idTypeExpr = idTypeExpr.elementType,
                            )
                        },
                )
            }
            nodeTypeExpr is TypeExpr.Named && idTypeExpr is TypeExpr.Named -> {
                require(ids is Value.ID) {
                    "Node-ID bridge did not contain an ID"
                }
                val (type, id) = decodeTypedId(ids)
                require(type in (nodeTypeExpr.baseType as Schema.CompositeType).possibleTypes) {
                    "Typed node ID ${type.typeName} is not valid for ${nodeTypeExpr.baseType.typeName}"
                }
                val result =
                    nodeResolvers[type]?.invoke(id)
                        ?: throw IllegalArgumentException(
                            "No fixture node resolver for ${type.typeName}",
                        )
                require(result.type == type) {
                    "Node resolver for ${type.typeName} returned ${result.type.typeName}"
                }
                val returnedId =
                    result.fieldValues.getValue(
                        Value.Key.of(validateNodeIdField(type), emptyMap()),
                    )
                require(returnedId == id) {
                    "Node resolver for ${type.typeName} did not repeat its input ID"
                }
                lowerObject(result)
            }
            else -> error("Node and ID bridge type expressions have different list shapes")
        }

    private fun lowerNestedOutput(
        output: Value.Output?,
        typeExpr: TypeExpr<Schema.OutputType>,
    ): Value.Output? =
        when {
            output == null || output == Value.Error -> output
            typeExpr is TypeExpr.List -> {
                require(output is Value.OutputList)
                Value.OutputList.of(
                    typeExpr = typeExpr.elementType,
                    values = output.values.map { lowerNestedOutput(it, typeExpr.elementType) },
                )
            }
            typeExpr.baseType is Schema.CompositeType -> {
                require(output is Value.Object)
                lowerObject(output)
            }
            else -> output
        }

    private fun lowerObject(output: Value.Object): Value.Object =
        Value.Object.of(
            type = output.type,
            fields =
                output.fieldValues.map { (key, value) ->
                    val bridge = loweredByField[key.field]
                    if (bridge == null) {
                        key to lowerNestedOutput(value, key.field.typeExpr)
                    } else {
                        Value.Key.of(bridge, key.arguments.retarget(bridge)) to
                            extractNodeIds(
                                output = value,
                                nodeTypeExpr = key.field.typeExpr,
                                idTypeExpr = bridge.typeExpr,
                            )
                    }
                }.toMap(),
        )

    private fun bridgeField(field: Schema.OutputField): Schema.OutputField =
        schema.field(
            field.containingType.typeName,
            nodeIdBridgeName(field),
        )

    private fun typedId(
        type: Schema.ObjectType,
        id: Value.ID,
    ): Value.ID =
        Value.ID.of(
            "$TYPED_ID_PREFIX${type.typeName.length}:${type.typeName}${id.idValue}",
        )

    private fun decodeTypedId(id: Value.ID): Pair<Schema.ObjectType, Value.ID> {
        require(id.idValue.startsWith(TYPED_ID_PREFIX)) {
            "Synthetic node-ID bridge contains an untyped ID"
        }
        val encoded = id.idValue.removePrefix(TYPED_ID_PREFIX)
        val separator = encoded.indexOf(':')
        require(separator > 0) { "Malformed typed node ID" }
        val typeNameLength = encoded.substring(0, separator).toInt()
        val typeNameStart = separator + 1
        val typeNameEnd = typeNameStart + typeNameLength
        require(typeNameEnd <= encoded.length) { "Malformed typed node ID" }
        val type = schema.type(encoded.substring(typeNameStart, typeNameEnd)) as Schema.ObjectType
        return type to Value.ID.of(encoded.substring(typeNameEnd))
    }

    private fun validateNodeIdField(type: Schema.ObjectType): Schema.OutputField {
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
        return idField
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

    private companion object {
        const val TYPED_ID_PREFIX = "\$node:"
    }
}

private class TestExecutorRegistry(
    private val schema: Schema,
    fieldResolvers: Map<Schema.OutputField, Resolver.Field>,
    additionalDemand: Map<Schema.OutputField, Set<Schema.OutputField>>,
    variableProviders: Map<VariableCoordinate, Selection>,
) : ExecutorRegistry {
    private val sourceFieldResolvers = fieldResolvers
    private val fieldResolvers: Map<Schema.OutputField, Resolver.Field>
    private val variableProviders: Map<VariableCoordinate, Selection> = variableProviders
    private val coordinatesByVariable: Map<Value.Variable, VariableCoordinate>
    private val outgoing: Map<Schema.ResolverSite, Set<Schema.ResolverSite>>
    private val incoming: Map<Schema.ResolverSite, Set<Schema.ResolverSite>>

    init {
        fieldResolvers.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingType.typeName
            require(field.containingType is Schema.ObjectType) {
                "Field resolver $typeName/${field.fieldName} must belong to a concrete object type"
            }
            require(field.fieldName != "__typename") {
                "Engine field $typeName/__typename cannot have a field resolver"
            }
            val fragmentType = resolver.objectFragment.nominalType
            require(schema.type(fragmentType.typeName) == fragmentType) {
                "${fragmentType.typeName} is not canonical in this registry's schema"
            }
            require(fragmentType == field.containingType) {
                "Object fragment type ${fragmentType.typeName} does not match " +
                    "$typeName/${field.fieldName}"
            }
        }
        val missingQueryFields =
            schema.query.fields.values
                .filter {
                    it.fieldName != "__typename" &&
                        !isNodeIdBridgeName(it.fieldName) &&
                        it !in fieldResolvers
                }
        require(missingQueryFields.isEmpty()) {
            "Query fields without field resolvers: " +
                missingQueryFields.map { it.fieldName }.sorted().joinToString()
        }

        variableProviders.forEach { (coordinate, selection) ->
            validateCanonicalField(coordinate.field, "variable-defining field")
            require(coordinate.field in fieldResolvers) {
                "Variable ${coordinate.variable.variableName} belongs to an unregistered resolver"
            }
            require(selection.key.field.containingType == coordinate.field.containingType) {
                "Variable ${coordinate.variable.variableName} selection is not relative to " +
                    "${coordinate.field.containingType.typeName}/${coordinate.field.fieldName}"
            }
            require(coordinate.field.containingType in selection.possibleTypes) {
                "Variable ${coordinate.variable.variableName} selection does not apply to its object"
            }
            validateVariablePath(selection)
            validateProviderContainment(
                coordinate.field,
                fieldResolvers.getValue(coordinate.field).objectFragment,
            )
        }
        val coordinatesByName = variableProviders.keys.groupBy { it.variable.variableName }
        require(coordinatesByName.values.all { it.size == 1 }) {
            "Variable names must be globally unique across field resolvers"
        }
        coordinatesByVariable =
            variableProviders.keys.associateBy(VariableCoordinate::variable)

        val objectFieldResolvers =
            fieldResolvers.mapKeys { (field, _) -> field as Schema.ObjectField }
        outgoing =
            buildMap {
                objectFieldResolvers.forEach { (field, resolver) ->
                    put(
                        field,
                        implicatedSites(resolver.objectFragment, field) +
                            additionalDemand[field].orEmpty().map {
                                it as Schema.ObjectField
                            },
                    )
                }
                variableProviders.forEach { (coordinate, selection) ->
                    put(
                        coordinate,
                        implicatedSites(
                            Fragment.of(
                                coordinate.field.containingType,
                                selectionForestOf(selection),
                            ),
                            coordinate.field,
                        ),
                    )
                }
            }
        val predecessorResolvers = mutableMapOf<Schema.OutputField, Resolver.Field>()
        dependencyOrder(outgoing).forEach { site ->
            when (site) {
                is Schema.ObjectField -> {
                    val resolver = fieldResolvers.getValue(site)
                    predecessorResolvers[site] =
                        resolver.withPredecessorDemand(
                            predecessorDemand =
                                closePredecessorDemand(
                                    fragment = resolver.objectFragment,
                                    predecessorResolvers = predecessorResolvers,
                                ),
                            predecessorDemandFunction = { arguments ->
                                closePredecessorDemand(
                                    fragment = resolver.objectFragment(arguments),
                                    predecessorResolvers = predecessorResolvers,
                                )
                            },
                            validateObjectFragment = { fragment ->
                                validateProviderContainment(site, fragment)
                            },
                        )
                }
                is VariableCoordinate -> Unit
            }
        }
        this.fieldResolvers = predecessorResolvers
        incoming =
            outgoing.keys.associateWith { site ->
                outgoing
                    .filterValues { site in it }
                    .keys
            }
        BranchOrderValidator(
            fieldResolvers = predecessorResolvers,
            variableProviders = variableProviders,
        ).validate()
    }

    override fun contains(field: Schema.OutputField): Boolean {
        validateCanonicalField(field)
        return field in fieldResolvers
    }

    override fun resolver(field: Schema.OutputField): Resolver.Field {
        validateCanonicalField(field)
        return fieldResolvers[field]
            ?: throw MissingExecutorException(field.containingType.typeName, field.fieldName)
    }

    override fun variable(variable: Value.Variable): Selection =
        variableProviders.getValue(variableCoordinate(variable))

    override fun variableCoordinate(variable: Value.Variable): VariableCoordinate =
        coordinatesByVariable[variable]
            ?: throw NoSuchElementException("Missing variable provider: \$${variable.variableName}")

    override fun mayDemandFrom(site: Schema.ResolverSite): Set<Schema.ResolverSite> {
        validateResolverSite(site)
        return outgoing.getValue(site)
    }

    override fun mayBeDemandedBy(site: Schema.ResolverSite): Set<Schema.ResolverSite> {
        validateResolverSite(site)
        return incoming.getValue(site)
    }

    private fun validateResolverSite(site: Schema.ResolverSite) {
        when (site) {
            is Schema.ObjectField -> require(site in this) { "Resolver field is not registered" }
            is VariableCoordinate ->
                require(variableProviders.keys.any { it == site }) {
                    "Variable coordinate is not registered"
                }
        }
    }

    private fun validateVariablePath(selection: Selection) {
        val outputType = selection.key.field.typeExpr
        if (selection.subselections.isEmpty()) {
            require(outputType.baseType is Schema.InputType) {
                "Variable provider paths must terminate at input-compatible values"
            }
        } else {
            require(outputType is TypeExpr.Named && outputType.baseType is Schema.CompositeType) {
                "Variable provider paths cannot traverse lists or simple values"
            }
            validateVariablePath(selection.subselections.single())
        }
    }

    private fun validateProviderContainment(
        field: Schema.ObjectField,
        fragment: Fragment,
    ) {
        variableProviders.forEach { (coordinate, provider) ->
            if (coordinate.field != field) return@forEach
            require(fragment.subselections.containsProviderPath(provider)) {
                "Variable ${coordinate.variable.variableName} provider path is not contained by " +
                    "${field.containingType.typeName}/${field.fieldName} object fragment"
            }
        }
    }

    private fun model.SelectionForest.containsProviderPath(provider: Selection): Boolean =
        toSelectionList().any { selection ->
            selection.key == provider.key &&
                provider.possibleTypes.all { it in selection.possibleTypes } &&
                (
                    provider.subselections.isEmpty() ||
                        selection.subselections.containsProviderPath(
                            provider.subselections.single(),
                        )
                )
        }

    private fun model.SelectionForest.toSelectionList(): List<Selection> =
        buildList {
            this@toSelectionList.forEach(::add)
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

    private fun implicatedSites(
        fragment: Fragment,
        ownerField: Schema.ObjectField,
    ): Set<Schema.ResolverSite> {
        val result = mutableSetOf<Schema.ResolverSite>()
        fragment.subselections.forEach { selection ->
            result.addImplicatedBy(selection, ownerField)
        }
        return result
    }

    private fun MutableSet<Schema.ResolverSite>.addImplicatedBy(
        selection: Selection,
        ownerField: Schema.ObjectField,
    ) {
        selection.possibleTypes.forEach { possibleType ->
            possibleType.fields[selection.key.field.fieldName]
                ?.takeIf { it in sourceFieldResolvers }
                ?.let { add(it as Schema.ObjectField) }
        }
        selection.key.arguments.variables().forEach { variable ->
            val coordinate = variableCoordinate(variable)
            require(coordinate.field == ownerField) {
                "Variable \$${variable.variableName} is not defined by " +
                    "${ownerField.containingType.typeName}/${ownerField.fieldName}"
            }
            add(coordinate)
        }
        selection.subselections.forEach { subselection ->
            addImplicatedBy(subselection, ownerField)
        }
    }

    private fun dependencyOrder(
        outgoing: Map<Schema.ResolverSite, Set<Schema.ResolverSite>>,
        remaining: Set<Schema.ResolverSite> = outgoing.keys,
        ordered: List<Schema.ResolverSite> = emptyList(),
    ): List<Schema.ResolverSite> {
        if (remaining.isEmpty()) return ordered

        val ready =
            remaining.filterTo(linkedSetOf()) { site ->
                outgoing.getValue(site).none { dependency -> dependency in remaining }
            }
        require(ready.isNotEmpty()) {
            "Resolver object fragments contain a demand cycle"
        }
        return dependencyOrder(
            outgoing = outgoing,
            remaining = remaining - ready,
            ordered = ordered + ready,
        )
    }

    private fun closePredecessorDemand(
        fragment: Fragment,
        predecessorResolvers: Map<Schema.OutputField, Resolver.Field>,
    ): Fragment {
        val additions = mutableListOf<Selection>()
        fragment.nominalType.possibleTypes.forEach { possibleType ->
            collectPredecessorDemand(
                selections = fragment.subselections,
                objectType = possibleType,
                path = emptyList(),
                predecessorResolvers = predecessorResolvers,
                additions = additions,
            )
        }
        return Fragment.of(
            nominalType = fragment.nominalType,
            subselections = fragment.subselections + additions.toSelectionForest(),
        )
    }

    private fun collectPredecessorDemand(
        selections: model.SelectionForest,
        objectType: Schema.ObjectType,
        path: List<Selection>,
        predecessorResolvers: Map<Schema.OutputField, Resolver.Field>,
        additions: MutableList<Selection>,
    ) {
        selections.forEach { selection ->
            if (objectType !in selection.possibleTypes) return@forEach
            if (selection.key.arguments.containsErrorValue()) return@forEach

            val field = objectType.fields.getValue(selection.key.field.fieldName)
            predecessorResolvers[field]
                ?.predecessorDemand(selection.key.arguments.retarget(field))
                ?.subselections
                ?.let { requirements ->
                    rootAt(path, requirements).forEach(additions::add)
                }

            val outputType = field.typeExpr.baseType as? Schema.CompositeType
                ?: return@forEach
            outputType.possibleTypes.forEach { possibleType ->
                collectPredecessorDemand(
                    selections = selection.subselections,
                    objectType = possibleType,
                    path = path + selection,
                    predecessorResolvers = predecessorResolvers,
                    additions = additions,
                )
            }
        }
    }

    private fun rootAt(
        path: List<Selection>,
        requirements: model.SelectionForest,
    ): model.SelectionForest =
        path.asReversed().fold(requirements) { rooted, selection ->
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = rooted,
                ),
            )
        }

}

private fun Value.Arguments.variables(): Set<Value.Variable> =
    fieldValues.values.flatMapTo(linkedSetOf()) { it.variables() }

private fun Value.Arguments.containsErrorValue(): Boolean =
    fieldValues.values.any { value -> value.containsErrorValue() }

private fun Value.Input?.containsErrorValue(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.InputList -> values.any { value -> value.containsErrorValue() }
        is Value.InputObject ->
            fieldValues.values.any { value -> value.containsErrorValue() }
        else -> false
    }

private fun Value.Arguments.retarget(field: Schema.OutputField): Value.Arguments =
    Value.Arguments.of(field, fieldValues)

private fun Value.Input?.variables(): Set<Value.Variable> =
    when {
        this == null || this == Value.Error -> emptySet()
        this is Value.Variable -> setOf(this)
        this is Value.InputList -> values.flatMapTo(linkedSetOf()) { it.variables() }
        this is Value.InputObject ->
            fieldValues.values.flatMapTo(linkedSetOf()) { it.variables() }
        else -> emptySet()
    }
