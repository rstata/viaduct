package model.testing

import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
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
import model.retarget
import model.variableTemplates

/**
 * A raw external node lookup accepted only by test-fixture composition.
 *
 * This alias is not part of the canonical resolver algebra. [resolverRegistryOf] consumes these
 * functions and exposes a field-only [ResolverRegistry].
 */
typealias NodeResolverFunction = (Value.ID) -> Value.Object

/** Marks a raw external node lookup for fixture composition. */
fun nodeResolverOf(function: NodeResolverFunction): NodeResolverFunction = function

typealias CanonicalFieldResolverApplicationObserver =
    (Schema.OutputField, Value.Object, Value.Arguments, SelectionForest?) -> Unit

internal fun resolverRegistryOf(
    schema: GJSchema,
    nodeResolvers: Map<Schema.ObjectType, NodeResolverFunction>,
    fieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>,
    variableProviders: Map<Value.Variable.Template, VariableDeclaration>,
    applicationObserver: CanonicalFieldResolverApplicationObserver?,
): ResolverRegistry {
    val lowering = NodeResolverLowering(schema, nodeResolvers, fieldResolvers)
    require(variableProviders.keys.intersect(lowering.variableProviders.keys).isEmpty()) {
        "Variable declarations collide with generated node-loader argument variables"
    }
    val allVariableProviders = variableProviders + lowering.variableProviders
    val variablesByField =
        allVariableProviders.keys
            .groupBy(Value.Variable.Template::field)
            .mapValues { (_, variables) ->
                variables.associateBy(Value.Variable.Template::variableName)
            }
    val registryResolvers =
        lowering.fieldResolvers.mapValues { (field, resolver) ->
            val variablesByName =
                lowering
                    .variableOwner(field)
                    ?.let { owner -> variablesByField[owner] }
                    .orEmpty()
            resolver.mapObjectFragment { fragment ->
                fragment.mapVariables { variable ->
                    variablesByName[variable.variableName] ?: variable
                }
            }
        }
    val registryVariableProviders =
        allVariableProviders.mapValues { (variable, declaration) ->
            when (declaration) {
                is FromArgument -> declaration
                is FromObjectField -> {
                    val variablesByName = variablesByField.getValue(variable.field)
                    declaration.mapVariables { variable ->
                        variablesByName[variable.variableName] ?: variable
                    }
                }
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
    rawFieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>,
) {
    private val nodeType: Schema.InterfaceType? = canonicalNodeType()
    private val loweredFields: Set<Schema.ObjectField> = loweredNodeFields()
    private val loweredByField: Map<Schema.ObjectField, Schema.ObjectField> =
        loweredFields.associateWith(::bridgeField)

    val fieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>
    val variableProviders: Map<Value.Variable.Template, VariableDeclaration>

    fun variableOwner(field: Schema.OutputField): Schema.ObjectField? =
        loweredByField.entries
            .singleOrNull { (_, bridge) -> bridge == field }
            ?.key
            ?: field as? Schema.ObjectField

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
                    val bridge = bridgeField(field)
                    bridge to
                        resolver
                            .mapObjectFragment { fragment ->
                                fragment.mapVariables { variable ->
                                    if (variable.field == field) {
                                        Value.Variable.of(bridge, variable.variableName)
                                    } else {
                                        variable
                                    }
                                }
                            }.mapOutput { output ->
                            extractNodeIds(
                                output = output,
                                nodeTypeExpr = field.typeExpr,
                                idTypeExpr = bridge.typeExpr,
                            )
                        }
                }
            }.toMap()
        variableProviders =
            loweredFields
                .flatMap { field ->
                    field.arguments.fields.keys.map { argumentName ->
                        Value.Variable.of(field, argumentVariableName(argumentName)) to
                            schema.fromArgument(field, argumentName)
                    }
                }.toMap()
        val loaderResolvers =
            loweredFields.associateWith(::loaderResolver)

        fieldResolvers = ordinaryResolvers + bridgeResolvers + loaderResolvers
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
        fieldResolvers: Map<Schema.OutputField, FieldResolverDefinition>,
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

    private fun loaderResolver(field: Schema.ObjectField): FieldResolverDefinition {
        val owner = field.containingType
        val bridge = bridgeField(field)
        val objectFragment =
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
                                            argumentName ->
                                            Value.Variable.of(
                                                field,
                                                argumentVariableName(argumentName),
                                            )
                                        },
                                ),
                            possibleTypes = setOf(owner),
                            subselections = selectionForestOf(),
                        ),
                    ),
            )
        return FieldResolverDefinition.of(
            objectFragment = objectFragment,
            function = { input, arguments ->
                val bridgeKey = Value.GroundKey.of(bridge, arguments.retargetGround(bridge))
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
                    possibleType.fields[selection.key.field.fieldName]
                        ?.let { field -> field in loweredFields }
                        ?: false
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
                val id = output.fieldValues.getValue(Value.GroundKey.of(idField, emptyMap()))
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
                        Value.GroundKey.of(validateNodeIdField(type), emptyMap()),
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
                        Value.GroundKey.of(bridge, key.arguments.retargetGround(bridge)) to
                            extractNodeIds(
                                output = value,
                                nodeTypeExpr = key.field.typeExpr,
                                idTypeExpr = bridge.typeExpr,
                            )
                    }
                }.toMap(),
        )

    private fun bridgeField(field: Schema.OutputField): Schema.ObjectField =
        schema.objectField(
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

    private companion object {
        const val TYPED_ID_PREFIX = "\$node:"

        fun argumentVariableName(argumentName: String): String =
            "$argumentName\$arg"
    }
}

private sealed interface DependencyVertex {
    data class Field(
        val field: Schema.ObjectField,
    ) : DependencyVertex

    data class Variable(
        val variable: Value.Variable.Template,
    ) : DependencyVertex
}

private class TestResolverRegistry(
    private val schema: Schema,
    fieldResolverDefinitions: Map<Schema.OutputField, FieldResolverDefinition>,
    variableDeclarations: Map<Value.Variable.Template, VariableDeclaration>,
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
        variable: Value.Variable.Template,
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

    private fun model.SelectionForest.containsProviderPath(providerPath: List<Value.Key>): Boolean {
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
        variable: Value.Variable.Template,
    ): List<VariableUse> =
        buildList {
            this@variableUses.forEach { selection ->
                selection.key.arguments.fieldExpressions().forEach { (name, value) ->
                    val argument = selection.key.arguments.type.fields.getValue(name)
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

    private fun List<Value.Key>.toSelection(
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

private fun Value.Arguments.retargetGround(field: Schema.OutputField): Value.Arguments =
    Value.Arguments.of(field, fieldValues)
