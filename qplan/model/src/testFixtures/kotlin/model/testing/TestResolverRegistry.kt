package model.testing

import model.ObjectEngineResult

import model.Fragment
import model.OpenArguments
import model.Schema
import model.Selection
import model.SelectionForest
import model.SourceSchemaAdapter
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fieldExpressions
import model.matchingVariableTypes
import model.registry.FieldResolver
import model.registry.MissingResolverException
import model.registry.ResolverRegistry
import model.registry.VariableDefinition
import model.selectionForestOf
import model.toSelectionForest
import model.variableTemplates

/**
 * A raw external node lookup accepted only by test-fixture composition.
 *
 * This alias is not part of the canonical resolver algebra. [resolverRegistryOf] consumes these
 * functions and exposes a field-only [ResolverRegistry].
 */
typealias NodeResolverFunction = (Value.ID) -> Value.Output?

/** Marks a raw external node lookup for fixture composition. */
fun nodeResolverOf(function: NodeResolverFunction): NodeResolverFunction = function

typealias CanonicalFieldResolverApplicationObserver =
    (Schema.OutputField, Value.Object, Value.Arguments, SelectionForest?) -> Unit

internal fun resolverRegistryOf(
    schema: GJSchema,
    nodeResolvers: Map<Schema.ObjectType, NodeResolverFunction>,
    fieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>,
    variableProviders: Map<Value.Variable, VariableDeclaration>,
    applicationObserver: CanonicalFieldResolverApplicationObserver?,
): ResolverRegistry {
    val lowering = NodeResolverLowering(schema, nodeResolvers, fieldResolvers)
    val variablesByField =
        variableProviders.keys
            .groupBy(Value.Variable::field)
            .mapValues { (_, variables) ->
                variables.associateBy(Value.Variable::variableName)
            }
    val registryResolvers =
        lowering.fieldResolvers.mapValues { (field, resolver) ->
            val variablesByName = variablesByField[field].orEmpty()
            resolver.mapObjectFragment { fragment ->
                fragment.mapVariables { variable ->
                    variablesByName[variable.variableName]
                        ?: variable
                }
            }
        }
    val registryVariableProviders =
        variableProviders.mapValues { (variable, declaration) ->
            if (declaration is FromObjectField) {
                val variablesByName = variablesByField.getValue(variable.field)
                declaration.mapVariables { referenced ->
                    variablesByName[referenced.variableName]
                        ?: referenced
                }
            } else {
                declaration
            }
        }
    val observedResolvers =
        if (applicationObserver == null) {
            registryResolvers
        } else {
            registryResolvers.mapValues { (field, resolver) ->
                resolver.observeApplications { input, arguments, demand ->
                    applicationObserver(field, input, arguments, demand)
                }
            }
        }
    return TestResolverRegistry(
        schema = schema,
        fieldResolverDefinitions = observedResolvers,
        variableDeclarations = registryVariableProviders,
    )
}

/**
 * Lowers source-world node references and node lookups into the canonical field-only world.
 *
 * For each node-valued source field `foo(args)`, fixture composition identifies its canonical
 * `foo_V_A_node(args)` producer and adapts source-shaped node references to same-shaped bridge
 * objects. For each used declared Node subtype `T` whose possible concrete types have raw node
 * lookups, one generated resolver at `T_V_A_Bridge.node` requires `id` and dispatches that typed ID
 * to the raw lookup.
 *
 * A lowered field must be declared as `Node` or a subtype whose every possible concrete type has a
 * raw node lookup. Mixed node-resolved and inline possible types are rejected at this composition
 * boundary.
 */
private class NodeResolverLowering(
    private val schema: GJSchema,
    private val nodeResolvers: Map<Schema.ObjectType, NodeResolverFunction>,
    rawFieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val nodeType: Schema.InterfaceType? = canonicalNodeType()
    private val loweredFields: Set<Schema.ObjectField> = loweredNodeFields()
    private val payloadTypes: Set<Schema.CompositeType> =
        loweredFields
            .mapTo(linkedSetOf()) { field ->
                sourceSchema.typeExpr(field).baseType as Schema.CompositeType
            }.filterTo(linkedSetOf()) { type ->
                type.possibleTypes.isNotEmpty() &&
                    type.possibleTypes.all { it in nodeResolvers }
            }

    val fieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>

    init {
        validateRawFieldResolvers(rawFieldResolvers)

        val ordinaryResolvers =
            rawFieldResolvers
                .filterKeys { it !in loweredFields }
                .mapValues { (_, resolver) -> resolver }
        val bridgeResolvers =
            loweredFields.mapNotNull { field ->
                rawFieldResolvers[field]?.let { resolver ->
                    field to
                        resolver
                            .mapOutput { output ->
                                sourceSchema.lowerOutput(field, output)
                            }
                }
            }.toMap()
        val payloadResolvers =
            payloadTypes.associate { type ->
                val payload = payloadField(type)
                payload to payloadResolver(type)
            }

        fieldResolvers = ordinaryResolvers + bridgeResolvers + payloadResolvers
    }

    private fun canonicalNodeType(): Schema.InterfaceType? {
        val candidate =
            try {
                schema.type("Node")
            } catch (_: Schema.MissingSchemaElementException) {
                null
            }
        if (candidate == null && nodeResolvers.isEmpty()) return null
        return candidate as? Schema.InterfaceType
            ?: throw IllegalArgumentException("Node resolvers require a canonical Node interface")
    }

    private fun loweredNodeFields(): Set<Schema.ObjectField> {
        nodeResolvers.forEach { (type, _) ->
            validateCanonicalType(type)
            require(schema.relation(nodeType!!, type) == Schema.TypeRelation.WIDER_THAN) {
                "Node-resolver type ${type.typeName} does not implement Node"
            }
            validateNodeIdField(type)
        }

        return schema.objectTypes
            .flatMap { it.fields.values }
            .mapNotNullTo(linkedSetOf()) { field ->
                if (!schema.isLoweredNodeField(field)) return@mapNotNullTo null
                val outputType =
                    sourceSchema.typeExpr(field).baseType as Schema.CompositeType
                val registeredTypes = outputType.possibleTypes.filterTo(linkedSetOf()) {
                    it in nodeResolvers
                }
                val isDeclaredNode =
                    nodeType != null &&
                        schema.relation(nodeType, outputType) in
                        setOf(
                            Schema.TypeRelation.SAME,
                            Schema.TypeRelation.WIDER_THAN,
                        )
                require(isDeclaredNode) {
                    "Synthetic bridge ${field.containingType.typeName}/${field.fieldName} " +
                        "does not correspond to a Node-valued source field"
                }
                require(
                    registeredTypes.isEmpty() ||
                        registeredTypes == outputType.possibleTypes,
                ) {
                    "Field ${field.containingType.typeName}/${field.fieldName} mixes node-resolved " +
                        "and inline object values; declare a Node output whose every possible type " +
                        "has a node resolver"
                }
                field
            }
    }

    private fun validateRawFieldResolvers(
        fieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>,
    ) {
        val nodeIdFields = nodeResolvers.keys.mapTo(linkedSetOf(), ::validateNodeIdField)
        fieldResolvers.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingType.typeName
            require(field.containingType is Schema.ObjectType) {
                "Field resolver $typeName/${field.fieldName} must belong to a concrete object type"
            }
            require(!field.containingType.typeName.endsWith(NODE_BRIDGE_TYPE_SUFFIX)) {
                "Synthetic node bridge field $typeName/${field.fieldName} cannot be supplied directly"
            }
            require(field !in nodeIdFields) {
                "Node id field $typeName/${field.fieldName} cannot have a field resolver"
            }
            require(field.fieldName != "__typename") {
                "Generated field $typeName/__typename cannot have a field resolver"
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

    private fun payloadResolver(nodeOutputType: Schema.CompositeType): FieldResolverDefinition {
        val bridgeType = schema.nodeBridgeType(nodeOutputType)
        val idField = schema.objectField(bridgeType.typeName, NODE_BRIDGE_ID_FIELD)
        val objectFragment =
            Fragment.of(
                nominalType = bridgeType,
                subselections =
                    selectionForestOf(
                        Selection.of(
                            key = ObjectEngineResult.Key.of(idField, emptyMap()),
                            possibleTypes = setOf(bridgeType),
                            subselections = selectionForestOf(),
                        ),
                    ),
            )
        return FieldResolverDefinition.of(
            objectFragment = objectFragment,
            function = { input, _ ->
                loadNode(
                    typedId =
                        input.fieldValues.getValue(
                            ObjectEngineResult.GroundKey.of(idField, emptyMap()),
                        ),
                    nodeOutputType = nodeOutputType,
                )
            },
        )
    }

    private fun loadNode(
        typedId: Value.Output?,
        nodeOutputType: Schema.CompositeType,
    ): Value.Output? {
        if (typedId == null || typedId == Value.Error) return typedId
        require(typedId is Value.ID) {
            "Node bridge ${nodeBridgeTypeName(nodeOutputType)} did not contain an ID"
        }
        val (type, id) = decodeTypedId(typedId)
        require(type in nodeOutputType.possibleTypes) {
            "Typed node ID ${type.typeName} is not valid for ${nodeOutputType.typeName}"
        }
        val resolver =
            nodeResolvers[type]
                ?: throw IllegalArgumentException("No fixture node resolver for ${type.typeName}")
        val result = resolver(id)
        if (result == null || result == Value.Error) return result
        require(result is Value.Object) {
            "Node resolver for ${type.typeName} returned a non-object value"
        }
        require(result.type == type) {
            "Node resolver for ${type.typeName} returned ${result.type.typeName}"
        }
        val returnedId =
            result.fieldValues.getValue(
                ObjectEngineResult.GroundKey.of(validateNodeIdField(type), emptyMap()),
            )
        require(returnedId == id) {
            "Node resolver for ${type.typeName} did not repeat its input ID"
        }
        return result
    }

    private fun payloadField(nodeOutputType: Schema.CompositeType): Schema.ObjectField =
        schema.objectField(
            nodeBridgeTypeName(nodeOutputType),
            NODE_BRIDGE_PAYLOAD_FIELD,
        )

    private fun decodeTypedId(id: Value.ID): Pair<Schema.ObjectType, Value.ID> {
        require(id.idValue.startsWith(TYPED_NODE_ID_PREFIX)) {
            "Synthetic node-ID bridge contains an untyped ID"
        }
        val encoded = id.idValue.removePrefix(TYPED_NODE_ID_PREFIX)
        val separator = encoded.indexOf(':')
        require(separator > 0) { "Malformed typed node ID" }
        val typeNameLength = encoded.substring(0, separator).toInt()
        val typeNameStart = separator + 1
        val typeNameEnd = typeNameStart + typeNameLength
        require(typeNameEnd <= encoded.length) { "Malformed typed node ID" }
        val type = schema.type(encoded.substring(typeNameStart, typeNameEnd)) as Schema.ObjectType
        return type to Value.ID.of(encoded.substring(typeNameEnd))
    }

    private fun validateNodeIdField(type: Schema.ObjectType): Schema.ObjectField {
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

}

private sealed interface DependencyVertex {
    data class Field(
        val field: Schema.ObjectField,
    ) : DependencyVertex

    data class Variable(
        val variable: Value.Variable,
    ) : DependencyVertex
}

private class TestResolverRegistry(
    private val schema: Schema,
    fieldResolverDefinitions: Map<Schema.OutputField, FieldResolverDefinition>,
    variableDeclarations: Map<Value.Variable, VariableDeclaration>,
) : ResolverRegistry {
    private val sourceFieldResolvers = fieldResolverDefinitions
    private val fieldResolvers: Map<Schema.OutputField, FieldResolver>
    private val variableDefinitions =
        variableDeclarations.mapValues { (_, declaration) ->
            when (declaration) {
                is FromArgument ->
                    VariableDefinition.FromArgument.of(declaration.argument)
                is FromObjectField ->
                    VariableDefinition.FromObjectField.of(declaration.keyPath)
            }
        }
    private val outgoing: Map<DependencyVertex, Set<DependencyVertex>>

    init {
        fieldResolverDefinitions.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingType.typeName
            require(field.containingType is Schema.ObjectType) {
                "Field resolver $typeName/${field.fieldName} must belong to a concrete object type"
            }
            require(field.fieldName != "__typename") {
                "Generated field $typeName/__typename cannot have a field resolver"
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
                        it !in fieldResolverDefinitions
                }
        require(missingQueryFields.isEmpty()) {
            "Query fields without field resolvers: " +
                missingQueryFields.map { it.fieldName }.sorted().joinToString()
        }

        variableDeclarations.forEach { (variable, declaration) ->
            validateCanonicalField(variable.field, "variable-defining field")
            require(variable.field in fieldResolverDefinitions) {
                "Variable ${variable.variableName} belongs to an unregistered resolver"
            }
            when (declaration) {
                is FromArgument -> {
                    require(declaration.argument.containingType == variable.field.arguments) {
                        "Variable ${variable.variableName} argument " +
                            "${declaration.argument.argumentName} does not belong to " +
                            "${variable.field.containingType.typeName}/${variable.field.fieldName}"
                    }
                }
                is FromObjectField -> {
                    require(declaration.objectFragment.nominalType == variable.field.containingType) {
                        "Variable ${variable.variableName} declaration is not relative to " +
                            "${variable.field.containingType.typeName}/${variable.field.fieldName}"
                    }
                    validateProviderContainment(
                        variable.field,
                        fieldResolverDefinitions.getValue(variable.field).objectFragment,
                    )
                    validateVariableUses(
                        variable = variable,
                        declaration = declaration,
                        fragment = fieldResolverDefinitions.getValue(variable.field).objectFragment,
                    )
                }
            }
        }

        val objectFieldResolvers =
            fieldResolverDefinitions.mapKeys { (field, _) -> field as Schema.ObjectField }
        outgoing =
            buildMap {
                objectFieldResolvers.forEach { (field, resolver) ->
                    put(
                        DependencyVertex.Field(field),
                        implicatedVertices(resolver.objectFragment, field),
                    )
                }
                variableDefinitions.forEach { (variable, definition) ->
                    put(
                        DependencyVertex.Variable(variable),
                        when (definition) {
                            is VariableDefinition.FromArgument -> emptySet<DependencyVertex>()
                            is VariableDefinition.FromObjectField ->
                                implicatedVertices(
                                    Fragment.of(
                                        variable.field.containingType,
                                        selectionForestOf(
                                            definition.path.toSelection(
                                                setOf(variable.field.containingType),
                                            ),
                                        ),
                                    ),
                                    variable.field,
                                )
                        },
                    )
                }
            }
        val assembledResolvers = mutableMapOf<Schema.OutputField, FieldResolver>()
        dependencyOrder(outgoing).forEach { site ->
            when (site) {
                is DependencyVertex.Field -> {
                    val definition = fieldResolverDefinitions.getValue(site.field)
                    assembledResolvers[site.field] =
                        definition.assemble(
                            variables =
                                variableDefinitions.filterKeys { variable ->
                                    variable.field == site.field
                                },
                            validateObjectFragment = { fragment ->
                                validateProviderContainment(site.field, fragment)
                            },
                            field = site.field,
                        )
                }
                is DependencyVertex.Variable -> Unit
            }
        }
        this.fieldResolvers = assembledResolvers
        BranchOrderValidator(
            fieldResolvers = assembledResolvers,
        ).validate()
    }

    override fun contains(field: Schema.ObjectField): Boolean {
        validateCanonicalField(field)
        return field in fieldResolvers
    }

    override fun resolveRootQuery(): Value.Object {
        val query = schema.query
        val typename =
            ObjectEngineResult.GroundKey.of(
                field = query.fields.getValue("__typename"),
                arguments = emptyMap(),
            )
        return Value.Object.of(
            type = query,
            fields = mapOf(typename to Value.String.of(query.typeName)),
        )
    }

    override fun resolver(field: Schema.ObjectField): FieldResolver {
        validateCanonicalField(field)
        return fieldResolvers[field]
            ?: throw MissingResolverException(field.containingType.typeName, field.fieldName)
    }

    override fun mayDemandFrom(field: Schema.ObjectField): Set<Schema.ObjectField> {
        require(field in this) { "Resolver field is not registered" }
        return outgoing
            .getValue(DependencyVertex.Field(field))
            .mapNotNullTo(linkedSetOf()) { vertex ->
                (vertex as? DependencyVertex.Field)?.field
            }
    }

    private fun validateProviderContainment(
        field: Schema.ObjectField,
        fragment: Fragment,
    ) {
        variableDefinitions.forEach { (variable, definition) ->
            if (variable.field != field) return@forEach
            if (definition !is VariableDefinition.FromObjectField) return@forEach
            require(fragment.subselections.containsProviderPath(definition.path)) {
                "Variable ${variable.variableName} provider path is not contained by " +
                    "${field.containingType.typeName}/${field.fieldName} object fragment"
            }
        }
    }

    private fun validateVariableUses(
        variable: Value.Variable,
        declaration: FromObjectField,
        fragment: Fragment,
    ) {
        fragment.subselections
            .variableUses(variable)
            .forEach { use ->
                require(
                    declaration.isCompatibleWith(
                        locationType = use.typeExpr,
                        locationHasDefault = use.hasDefault,
                    ),
                ) {
                    "Variable ${variable.variableName} provider path " +
                        declaration.responsePath.joinToString(".") +
                        " is incompatible with one of its argument locations"
                }
            }
    }

    private fun model.SelectionForest.containsProviderPath(providerPath: List<ObjectEngineResult.Key>): Boolean {
        val provider = providerPath.first()
        val remaining = providerPath.drop(1)
        return toSelectionList().any { selection ->
            selection.key == provider &&
                (
                    remaining.isEmpty() ||
                        selection.subselections.containsProviderPath(remaining)
                )
        }
    }

    private fun model.SelectionForest.toSelectionList(): List<Selection> =
        buildList {
            this@toSelectionList.forEach(::add)
        }

    private data class VariableUse(
        val typeExpr: TypeExpr<Schema.InputType>,
        val hasDefault: Boolean,
    )

    private fun model.SelectionForest.variableUses(
        variable: Value.Variable,
    ): List<VariableUse> =
        buildList {
            this@variableUses.forEach { selection ->
                if (selection.key.arguments != OpenArguments.Ground.Error) {
                    selection.key.arguments.fieldExpressions().forEach { (name, value) ->
                        val argument = selection.key.field.arguments.fields.getValue(name)
                        addAll(
                            value
                                .matchingVariableTypes(
                                    variable = variable,
                                    typeExpr = argument.typeExpr,
                                    hasDefault = argument.defaultValue is Value.Default.Present,
                                ).map { (typeExpr, hasDefault) ->
                                    VariableUse(typeExpr, hasDefault)
                                },
                        )
                    }
                }
                addAll(selection.subselections.variableUses(variable))
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

    private fun List<ObjectEngineResult.Key>.toSelection(
        possibleTypes: Set<Schema.ObjectType>,
    ): Selection {
        val key = first()
        val remaining = drop(1)
        val outputType = key.field.typeExpr.baseType
        return Selection.of(
            key = key,
            possibleTypes = possibleTypes,
            subselections =
                if (remaining.isEmpty()) {
                    selectionForestOf()
                } else {
                    require(outputType is Schema.CompositeType)
                    selectionForestOf(remaining.toSelection(outputType.possibleTypes))
                },
        )
    }

    private fun implicatedVertices(
        fragment: Fragment,
        ownerField: Schema.ObjectField,
    ): Set<DependencyVertex> {
        val result = mutableSetOf<DependencyVertex>()
        fragment.subselections.forEach { selection ->
            result.addImplicatedBy(selection, ownerField)
        }
        return result
    }

    private fun MutableSet<DependencyVertex>.addImplicatedBy(
        selection: Selection,
        ownerField: Schema.ObjectField,
    ) {
        selection.possibleTypes.forEach { possibleType ->
            possibleType.fields[selection.key.field.fieldName]
                ?.takeIf { it in sourceFieldResolvers }
                ?.let { add(DependencyVertex.Field(it as Schema.ObjectField)) }
        }
        selection.key.arguments.variableTemplates().forEach { variable ->
            require(variable in variableDefinitions) {
                "Missing variable definition: \$${variable.variableName}"
            }
            require(variable.field == ownerField) {
                "Variable \$${variable.variableName} is not defined by " +
                    "${ownerField.containingType.typeName}/${ownerField.fieldName}"
            }
            add(DependencyVertex.Variable(variable))
        }
        selection.subselections.forEach { subselection ->
            addImplicatedBy(subselection, ownerField)
        }
    }

    private fun dependencyOrder(
        outgoing: Map<DependencyVertex, Set<DependencyVertex>>,
        remaining: Set<DependencyVertex> = outgoing.keys,
        ordered: List<DependencyVertex> = emptyList(),
    ): List<DependencyVertex> {
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

}
