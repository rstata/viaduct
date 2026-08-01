package model.testing

import model.Fragment
import model.Schema
import model.Selection
import model.TypeExpr
import model.Value
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

fun fieldResolverOf(
    objectFragment: Fragment,
    function: FieldResolverFunction,
): Resolver.Field = Resolver.Field.of(objectFragment, function)

internal fun executorRegistryOf(
    schema: GJSchema,
    nodeResolvers: Map<Schema.ObjectType, NodeResolverFunction>,
    fieldResolvers: Map<Schema.OutputField, Resolver.Field>,
): ExecutorRegistry {
    val lowering = NodeResolverLowering(schema, nodeResolvers, fieldResolvers)
    return TestExecutorRegistry(
        schema = schema,
        fieldResolvers = lowering.fieldResolvers,
        additionalDemand = lowering.additionalDemand,
    )
}

/**
 * Lowers source-world node references and node lookups into the canonical field-only world.
 *
 * For each eligible source field `foo(args)` with a raw field resolver, that producer is moved to
 * `foo$id(args)` and adapted to emit typed IDs. A generated resolver at every eligible `foo(args)`
 * demands that exact bridge key and dispatches the typed ID to the raw node lookup. Bridge type
 * expressions preserve the source field's list and nullability shape. Outputs of containing
 * resolvers are rewritten recursively so passive nested node references also become bridge values.
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
            .filterNot { it.fieldName.endsWith(NODE_ID_BRIDGE_SUFFIX) }
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
            require(!field.fieldName.endsWith(NODE_ID_BRIDGE_SUFFIX)) {
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
        return Resolver.Field.ofArgumentDependent(
            objectFragment = schema.emptyFragmentOf(owner.typeName),
            objectFragmentFunction = { arguments ->
                Fragment.of(
                    nominalType = owner,
                    subselections =
                        selectionForestOf(
                            Selection.of(
                                key =
                                    Value.Key.of(
                                        field = bridge,
                                        arguments = arguments.retarget(bridge),
                                    ),
                                nominalType = owner,
                                possibleTypes = setOf(owner),
                                subselections = selectionForestOf(),
                            ),
                        ),
                )
            },
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
                    nominalType = selection.nominalType,
                    possibleTypes = selection.possibleTypes,
                    subselections = loweredSubselections,
                )
            val bridgePossibleTypes =
                selection.possibleTypes.filterTo(linkedSetOf()) { possibleType ->
                    possibleType.fields[selection.key.field.fieldName] in loweredFields
                }
            val bridgeField =
                selection.nominalType.fields[
                    selection.key.field.fieldName + NODE_ID_BRIDGE_SUFFIX
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
                        nominalType = selection.nominalType,
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
            field.fieldName + NODE_ID_BRIDGE_SUFFIX,
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

    private fun Value.Arguments.retarget(field: Schema.OutputField): Value.Arguments =
        Value.Arguments.of(field, fieldValues)

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
) : ExecutorRegistry {
    private val sourceFieldResolvers = fieldResolvers
    private val fieldResolvers: Map<Schema.OutputField, Resolver.Field>
    private val outgoing: Map<Schema.OutputField, Set<Schema.OutputField>>
    private val incoming: Map<Schema.OutputField, Set<Schema.OutputField>>

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
                        !it.fieldName.endsWith(NODE_ID_BRIDGE_SUFFIX) &&
                        it !in fieldResolvers
                }
        require(missingQueryFields.isEmpty()) {
            "Query fields without field resolvers: " +
                missingQueryFields.map { it.fieldName }.sorted().joinToString()
        }

        outgoing =
            fieldResolvers.mapValues { (field, resolver) ->
                implicatedFields(resolver.objectFragment) + additionalDemand[field].orEmpty()
            }
        requireAcyclic(outgoing)
        this.fieldResolvers =
            dependencyOrder(outgoing).fold(emptyMap()) { extendedResolvers, field ->
                val resolver = fieldResolvers.getValue(field)
                extendedResolvers +
                    (
                        field to
                            resolver.withExtendedFragment(
                                extendedFragment =
                                    extendFragment(
                                        fragment = resolver.objectFragment,
                                        extendedResolvers = extendedResolvers,
                                    ),
                                extendedFragmentFunction = { arguments ->
                                    extendFragment(
                                        fragment = resolver.objectFragment(arguments),
                                        extendedResolvers = extendedResolvers,
                                    )
                                },
                            )
                    )
            }
        incoming =
            fieldResolvers.keys.associateWith { site ->
                outgoing
                    .filterValues { site in it }
                    .keys
            }
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

    override fun mayDemandFrom(field: Schema.OutputField): Set<Schema.OutputField> {
        validateCanonicalField(field)
        return outgoing[field]
            ?: throw MissingExecutorException(field.containingType.typeName, field.fieldName)
    }

    override fun mayBeDemandedBy(field: Schema.OutputField): Set<Schema.OutputField> {
        require(field in this) { "Resolver field is not registered" }
        return incoming.getValue(field)
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

    private fun implicatedFields(fragment: Fragment): Set<Schema.OutputField> {
        val result = mutableSetOf<Schema.OutputField>()
        fragment.subselections.forEach { selection ->
            result.addImplicatedBy(selection)
        }
        return result
    }

    private fun MutableSet<Schema.OutputField>.addImplicatedBy(selection: Selection) {
        selection.possibleTypes.forEach { possibleType ->
            possibleType.fields[selection.key.field.fieldName]
                ?.takeIf { it in sourceFieldResolvers }
                ?.let(::add)
        }
        selection.subselections.forEach { subselection ->
            addImplicatedBy(subselection)
        }
    }

    private fun dependencyOrder(
        outgoing: Map<Schema.OutputField, Set<Schema.OutputField>>,
        remaining: Set<Schema.OutputField> = outgoing.keys,
        ordered: List<Schema.OutputField> = emptyList(),
    ): List<Schema.OutputField> {
        if (remaining.isEmpty()) return ordered

        val ready =
            remaining.filterTo(linkedSetOf()) { field ->
                outgoing.getValue(field).none { dependency -> dependency in remaining }
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

    private fun extendFragment(
        fragment: Fragment,
        extendedResolvers: Map<Schema.OutputField, Resolver.Field>,
    ): Fragment {
        val additions = mutableListOf<Selection>()
        fragment.nominalType.possibleTypes.forEach { possibleType ->
            collectExtensions(
                selections = fragment.subselections,
                objectType = possibleType,
                path = emptyList(),
                extendedResolvers = extendedResolvers,
                additions = additions,
            )
        }
        return Fragment.of(
            nominalType = fragment.nominalType,
            subselections = fragment.subselections + additions.toSelectionForest(),
        )
    }

    private fun collectExtensions(
        selections: model.SelectionForest,
        objectType: Schema.ObjectType,
        path: List<Selection>,
        extendedResolvers: Map<Schema.OutputField, Resolver.Field>,
        additions: MutableList<Selection>,
    ) {
        selections.forEach { selection ->
            if (objectType !in selection.possibleTypes) return@forEach

            val field = objectType.fields.getValue(selection.key.field.fieldName)
            extendedResolvers[field]
                ?.extendedFragment(selection.key.arguments.retarget(field))
                ?.subselections
                ?.let { requirements ->
                    rootAt(path, requirements).forEach(additions::add)
                }

            val outputType = field.typeExpr.baseType as? Schema.CompositeType
                ?: return@forEach
            outputType.possibleTypes.forEach { possibleType ->
                collectExtensions(
                    selections = selection.subselections,
                    objectType = possibleType,
                    path = path + selection,
                    extendedResolvers = extendedResolvers,
                    additions = additions,
                )
            }
        }
    }

    private fun Value.Arguments.retarget(field: Schema.OutputField): Value.Arguments =
        Value.Arguments.of(field, fieldValues)

    private fun rootAt(
        path: List<Selection>,
        requirements: model.SelectionForest,
    ): model.SelectionForest =
        path.asReversed().fold(requirements) { rooted, selection ->
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    nominalType = selection.nominalType,
                    possibleTypes = selection.possibleTypes,
                    subselections = rooted,
                ),
            )
        }

    private fun requireAcyclic(
        outgoing: Map<Schema.OutputField, Set<Schema.OutputField>>,
    ) {
        val state = mutableMapOf<Schema.OutputField, VisitState>()

        fun visit(field: Schema.OutputField) {
            when (state[field]) {
                VisitState.VISITING ->
                    throw IllegalArgumentException(
                        "Resolver object fragments contain a demand cycle",
                    )
                VisitState.VISITED -> return
                null -> Unit
            }
            state[field] = VisitState.VISITING
            outgoing.getValue(field).forEach(::visit)
            state[field] = VisitState.VISITED
        }

        outgoing.keys.forEach(::visit)
    }

    private enum class VisitState {
        VISITING,
        VISITED,
    }
}
