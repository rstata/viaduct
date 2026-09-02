package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult
import model.CoercedDefaultValue
import model.Fragment
import model.EngineErrorData
import model.EngineOutputData
import model.Arguments
import model.Selection
import model.SelectionForest
import model.SourceSchemaAdapter
import model.inputType
import model.lowering.ALL_SOURCE_OBJECTS_TYPE
import model.lowering.LOWERED_TYPENAME_FIELD
import model.lowering.NODE_BRIDGE_ID_FIELD
import model.lowering.NODE_BRIDGE_PAYLOAD_FIELD
import model.lowering.NODE_BRIDGE_TYPE_SUFFIX
import model.lowering.TYPED_NODE_ID_PREFIX
import model.lowering.nodeBridgeTypeName
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fieldExpressions
import model.matchingVariableTypes
import model.requireArg
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.schemaType
import model.registry.FieldResolver
import model.registry.MissingResolverException
import model.registry.ProviderFragment
import model.registry.ResolverRegistry
import model.registry.VariableDefinition
import model.selectionForestOf
import model.toSelectionForest
import model.variableTemplates
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.utils.GraphQLTypeRelation

/**
 * A raw external node lookup accepted only by test-fixture composition.
 *
 * The returned object is partial and need not repeat the input ID. Fixture lowering retains the
 * authoritative ID supplied by the node-valued producer, matching production's node-reference
 * behavior.
 *
 * This alias is not part of the canonical resolver algebra. [resolverRegistryOf] consumes these
 * functions and exposes a field-only [ResolverRegistry].
 */
typealias NodeResolverFunction = (String) -> EngineOutputData?

/** Marks a raw external node lookup for fixture composition. */
fun nodeResolverOf(function: NodeResolverFunction): NodeResolverFunction = function

typealias CanonicalFieldResolverApplicationObserver =
    (ViaductSchema.Field, EngineObjectData.Sync, Arguments.Resolved, SelectionForest?) -> Unit

internal fun resolverRegistryOf(
    schema: GJSchema,
    nodeResolvers: Map<ViaductSchema.Object, NodeResolverFunction>,
    fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>,
    variableProviders: Map<Arguments.Variable, VariableDeclaration>,
    applicationObserver: CanonicalFieldResolverApplicationObserver?,
): ResolverRegistry {
    val lowering = NodeResolverLowering(schema, nodeResolvers, fieldResolvers)
    val variablesByField =
        variableProviders.keys
            .groupBy(Arguments.Variable::field)
            .mapValues { (_, variables) ->
                variables.associateBy(Arguments.Variable::variableName)
            }
    val registryResolvers =
        lowering.fieldResolvers.mapValues { (field, resolver) ->
            val variablesByName = variablesByField[field].orEmpty()
            resolver
                .mapObjectFragment { fragment ->
                    fragment.mapVariables { variable ->
                        variablesByName[variable.variableName]
                            ?: variable
                    }
                }.mapQueryFragment { fragment ->
                    fragment.mapVariables { variable ->
                        variablesByName[variable.variableName]
                            ?: variable
                    }
                }
        }
    val registryVariableProviders =
        variableProviders.mapValues { (variable, declaration) ->
            val variablesByName = variablesByField.getValue(variable.field)
            when (declaration) {
                is FromField ->
                    declaration.mapVariables { referenced ->
                        variablesByName[referenced.variableName] ?: referenced
                    }
                else -> declaration
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
 * objects. For each concrete Node object `O` with a raw node lookup, one generated resolver at
 * `O_V_A_Bridge.node` requires `id` and dispatches that typed ID to the raw lookup.
 *
 * A lowered field must be declared as `Node` or a subtype whose every possible concrete type has a
 * raw node lookup. Mixed node-resolved and inline possible types are rejected at this composition
 * boundary.
 */
private class NodeResolverLowering(
    private val schema: GJSchema,
    private val nodeResolvers: Map<ViaductSchema.Object, NodeResolverFunction>,
    rawFieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val nodeType: ViaductSchema.Interface? = canonicalNodeType()
    private val loweredFields: Set<ViaductSchema.ObjectField> = loweredNodeFields()
    private val payloadTypes: Set<ViaductSchema.Object> = nodeResolvers.keys

    val fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>

    init {
        validateRawFieldResolvers(rawFieldResolvers)

        val ordinaryResolvers =
            rawFieldResolvers
                .filterKeys { it !in loweredFields }
                .mapValues { (field, resolver) ->
                    resolver.mapOutput { output ->
                        sourceSchema.lowerOutput(field, output)
                    }
                }
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
        val typenameResolvers =
            (schema.requireType(ALL_SOURCE_OBJECTS_TYPE) as ViaductSchema.Interface)
                .possibleObjectTypes
                .associate { type ->
                    val field = schema.requireObjectField(type.name, LOWERED_TYPENAME_FIELD)
                    field to
                        FieldResolverDefinition.of(
                            objectFragment = schema.emptyFragmentOf(type.name),
                            function = { _, _ -> type.name },
                        )
                }

        fieldResolvers =
            ordinaryResolvers + bridgeResolvers + payloadResolvers + typenameResolvers
    }

    private fun canonicalNodeType(): ViaductSchema.Interface? {
        val candidate = schema.types["Node"]
        if (candidate == null && nodeResolvers.isEmpty()) return null
        return candidate as? ViaductSchema.Interface
            ?: throw IllegalArgumentException("Node resolvers require a canonical Node interface")
    }

    private fun loweredNodeFields(): Set<ViaductSchema.ObjectField> {
        nodeResolvers.forEach { (type, _) ->
            validateCanonicalType(type)
            require(
                schema.typeRelations.relationUnwrapped(
                    schema.sourceCompositeType(nodeType!!),
                    schema.sourceCompositeType(type),
                ) == GraphQLTypeRelation.WiderThan,
            ) {
                "Node-resolver type ${type.name} does not implement Node"
            }
            validateNodeIdField(type)
        }

        return schema.objectTypes
            .flatMap { it.fields }
            .mapNotNullTo(linkedSetOf()) { field ->
                if (!schema.isLoweredNodeField(field)) return@mapNotNullTo null
                val outputType =
                    sourceSchema.typeExpr(field).baseTypeDef as ViaductSchema.CompositeTypeDef
                val registeredTypes = outputType.possibleObjectTypes.filterTo(linkedSetOf()) {
                    it in nodeResolvers
                }
                val isDeclaredNode =
                    nodeType != null &&
                        schema.typeRelations.relationUnwrapped(
                            schema.sourceCompositeType(nodeType),
                            schema.sourceCompositeType(outputType),
                        ) in
                        setOf(
                            GraphQLTypeRelation.Same,
                            GraphQLTypeRelation.WiderThan,
                        )
                require(isDeclaredNode) {
                    "Synthetic bridge ${field.containingDef.name}/${field.name} " +
                        "does not correspond to a Node-valued source field"
                }
                require(
                    registeredTypes.isEmpty() ||
                        registeredTypes == outputType.possibleObjectTypes,
                ) {
                    "Field ${field.containingDef.name}/${field.name} mixes node-resolved " +
                        "and inline object values; declare a Node output whose every possible type " +
                        "has a node resolver"
                }
                field
            }
    }

    private fun validateRawFieldResolvers(
        fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>,
    ) {
        val nodeIdFields = nodeResolvers.keys.mapTo(linkedSetOf(), ::validateNodeIdField)
        fieldResolvers.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingDef.name
            require(field.containingDef is ViaductSchema.Object) {
                "Field resolver $typeName/${field.name} must belong to a concrete object type"
            }
            require(!field.containingDef.name.endsWith(NODE_BRIDGE_TYPE_SUFFIX)) {
                "Synthetic node bridge field $typeName/${field.name} cannot be supplied directly"
            }
            require(field !in nodeIdFields) {
                "Node id field $typeName/${field.name} cannot have a field resolver"
            }
            require(field.name != LOWERED_TYPENAME_FIELD) {
                "Generated field $typeName/$LOWERED_TYPENAME_FIELD cannot be supplied directly"
            }
            val fragmentType = resolver.objectFragment.nominalType
            require(schema.requireType(fragmentType.name) == fragmentType) {
                "${fragmentType.name} is not canonical in this registry's schema"
            }
            require(fragmentType == field.containingDef) {
                "Object fragment type ${fragmentType.name} does not match " +
                    "$typeName/${field.name}"
            }
        }
    }

    private fun payloadResolver(nodeOutputType: ViaductSchema.Object): FieldResolverDefinition {
        val bridgeType = schema.nodeBridgeType(nodeOutputType) as ViaductSchema.Object
        val idField = schema.requireObjectField(bridgeType.name, NODE_BRIDGE_ID_FIELD)
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
                        input.get(
                            idField.name,
                        ),
                    nodeOutputType = nodeOutputType,
                )
            },
        )
    }

    private fun loadNode(
        typedId: EngineOutputData?,
        nodeOutputType: ViaductSchema.Object,
    ): EngineOutputData? {
        if (typedId == null || typedId is EngineErrorData) return typedId
        require(typedId is String) {
            "Node bridge ${nodeBridgeTypeName(nodeOutputType.name)} did not contain an ID"
        }
        val (type, id) = decodeTypedId(typedId)
        require(type in nodeOutputType.possibleObjectTypes) {
            "Typed node ID ${type.name} is not valid for ${nodeOutputType.name}"
        }
        val resolver =
            nodeResolvers[type]
                ?: throw IllegalArgumentException("No fixture node resolver for ${type.name}")
        val sourceResult = resolver(id)
        if (sourceResult == null || sourceResult is EngineErrorData) return sourceResult
        require(sourceResult is EngineObjectData.Sync) {
            "Node resolver for ${type.name} returned a non-object value"
        }
        val result =
            sourceSchema.lowerOutput(payloadField(type), sourceResult)
                as EngineObjectData.Sync
        val resultType = result.schemaType
        require(resultType == type) {
            "Node resolver for ${type.name} returned ${resultType.name}"
        }
        val fields =
            result.getSelections().associateWith { selection ->
                result.get(selection)
            }

        /*
         * Node identity belongs to the producing field's fringe value, which the synthetic bridge
         * carries into this lookup. Production's NodeEngineObjectDataImpl likewise returns its
         * NodeReference ID independently of the materialized node-resolver object. Reconstitute
         * that effective object here, in shared fixture lowering, with the fringe ID authoritative.
         */
        val idField = validateNodeIdField(type)
        return engineObjectDataOf(resultType, fields + (idField.name to id))
    }

    private fun payloadField(nodeOutputType: ViaductSchema.Object): ViaductSchema.ObjectField =
        schema.requireObjectField(
            nodeBridgeTypeName(nodeOutputType.name),
            NODE_BRIDGE_PAYLOAD_FIELD,
        )

    private fun decodeTypedId(id: String): Pair<ViaductSchema.Object, String> {
        require(id.startsWith(TYPED_NODE_ID_PREFIX)) {
            "Synthetic node-ID bridge contains an untyped ID"
        }
        val encoded = id.removePrefix(TYPED_NODE_ID_PREFIX)
        val separator = encoded.indexOf(':')
        require(separator > 0) { "Malformed typed node ID" }
        val typeNameLength = encoded.substring(0, separator).toInt()
        val typeNameStart = separator + 1
        val typeNameEnd = typeNameStart + typeNameLength
        require(typeNameEnd <= encoded.length) { "Malformed typed node ID" }
        val type = schema.requireType(encoded.substring(typeNameStart, typeNameEnd)) as ViaductSchema.Object
        return type to encoded.substring(typeNameEnd)
    }

    private fun validateNodeIdField(type: ViaductSchema.Object): ViaductSchema.ObjectField {
        val idField =
            type.field("id")
                ?: throw IllegalArgumentException(
                    "Node-resolver type ${type.name} has no id field",
                )
        require(schema.requireField(type.name, "id") == idField) {
            "${type.name}/id is not canonical in this registry's schema"
        }
        require(idField.args.isEmpty()) {
            "Node id field ${type.name}/id must take no arguments"
        }
        require(
            (idField.type.baseTypeDef as? ViaductSchema.Scalar)?.name == "ID",
        ) {
            "Node id field ${type.name}/id must be ID-typed"
        }
        return idField
    }

    private fun validateCanonicalType(type: ViaductSchema.Object) {
        require(schema.requireType(type.name) == type) {
            "${type.name} is not canonical in this registry's schema"
        }
    }

    private fun validateCanonicalField(
        field: ViaductSchema.Field,
        role: String = "field",
    ) {
        val typeName = field.containingDef.name
        require(schema.requireField(typeName, field.name) == field) {
            "$typeName/${field.name} is not the canonical $role in this registry's schema"
        }
    }

}

private sealed interface DependencyVertex {
    data class Field(
        val field: ViaductSchema.ObjectField,
    ) : DependencyVertex

    data class Variable(
        val variable: Arguments.Variable,
    ) : DependencyVertex
}

private class TestResolverRegistry(
    private val schema: ViaductSchema,
    fieldResolverDefinitions: Map<ViaductSchema.Field, FieldResolverDefinition>,
    variableDeclarations: Map<Arguments.Variable, VariableDeclaration>,
) : ResolverRegistry {
    private val sourceFieldResolvers = fieldResolverDefinitions
    private val fieldResolvers: Map<ViaductSchema.Field, FieldResolver>
    private val variableDefinitions =
        variableDeclarations.mapValues { (_, declaration) ->
            when (declaration) {
                is FromArgument ->
                    VariableDefinition.FromArgument.of(
                        argument = declaration.argument,
                        inputPath = declaration.inputPath,
                    )
                is FromField ->
                    VariableDefinition.FromField.of(
                        providerFragment = declaration.providerFragment,
                        path = declaration.keyPath,
                    )
            }
        }
    private val outgoing: Map<DependencyVertex, Set<DependencyVertex>>

    init {
        fieldResolverDefinitions.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingDef.name
            require(field.containingDef is ViaductSchema.Object) {
                "Field resolver $typeName/${field.name} must belong to a concrete object type"
            }
            val fragmentType = resolver.objectFragment.nominalType
            require(schema.requireType(fragmentType.name) == fragmentType) {
                "${fragmentType.name} is not canonical in this registry's schema"
            }
            require(fragmentType == field.containingDef) {
                "Object fragment type ${fragmentType.name} does not match " +
                    "$typeName/${field.name}"
            }
            resolver.queryFragment?.let { queryFragment ->
                require(queryFragment.nominalType == schema.requireQueryTypeDef()) {
                    "Query fragment type ${queryFragment.nominalType.name} does not match Query"
                }
            }
        }
        val missingQueryFields =
            schema.requireQueryTypeDef().fields
                .filter { it !in fieldResolverDefinitions }
        require(missingQueryFields.isEmpty()) {
            "Query fields without field resolvers: " +
                missingQueryFields.map { it.name }.sorted().joinToString()
        }

        variableDeclarations.forEach { (variable, declaration) ->
            validateCanonicalField(variable.field, "variable-defining field")
            require(variable.field in fieldResolverDefinitions) {
                "Variable ${variable.variableName} belongs to an unregistered resolver"
            }
            when (declaration) {
                is FromArgument -> {
                    val resolver = fieldResolverDefinitions.getValue(variable.field)
                    require(declaration.argument.containingDef == variable.field) {
                        "Variable ${variable.variableName} argument " +
                            "${declaration.argument.name} does not belong to " +
                            "${variable.field.containingDef.name}/${variable.field.name}"
                    }
                    validateVariableUses(
                        variable = variable,
                        fragments = listOfNotNull(resolver.objectFragment, resolver.queryFragment),
                        sourceDescription =
                            "argument path " +
                                (listOf(declaration.argument.name) +
                                    declaration.inputPath.map(ViaductSchema.Field::name))
                                    .joinToString("."),
                        isCompatible = declaration::isCompatibleWith,
                    )
                }
                is FromField -> {
                    val resolver = fieldResolverDefinitions.getValue(variable.field)
                    val expectedType =
                        when (declaration.providerFragment) {
                            ProviderFragment.OBJECT -> variable.field.containingDef
                            ProviderFragment.QUERY -> schema.requireQueryTypeDef()
                        }
                    require(declaration.fragment.nominalType == expectedType) {
                        "Variable ${variable.variableName} declaration is not relative to " +
                            expectedType.name
                    }
                    val providerFragment =
                        when (declaration.providerFragment) {
                            ProviderFragment.OBJECT -> resolver.objectFragment
                            ProviderFragment.QUERY ->
                                requireNotNull(resolver.queryFragment) {
                                    "Variable ${variable.variableName} requires a Query fragment"
                                }
                        }
                    validateProviderContainment(
                        field = variable.field,
                        fragment = providerFragment,
                        providerFragment = declaration.providerFragment,
                    )
                    validateVariableUses(
                        variable = variable,
                        fragments = listOfNotNull(resolver.objectFragment, resolver.queryFragment),
                        sourceDescription =
                            "${declaration.providerFragment.name.lowercase()} provider path " +
                                declaration.responsePath.joinToString("."),
                        isCompatible = declaration::isCompatibleWith,
                    )
                }
            }
        }

        val objectFieldResolvers =
            fieldResolverDefinitions.mapKeys { (field, _) -> field as ViaductSchema.ObjectField }
        outgoing =
            buildMap {
                objectFieldResolvers.forEach { (field, resolver) ->
                    put(
                        DependencyVertex.Field(field),
                        implicatedVertices(resolver.objectFragment, field) +
                            implicatedVertices(resolver.queryFragment, field),
                    )
                }
                variableDefinitions.forEach { (variable, definition) ->
                    put(
                        DependencyVertex.Variable(variable),
                        when (definition) {
                            is VariableDefinition.FromArgument -> emptySet<DependencyVertex>()
                            is VariableDefinition.FromField -> {
                                val providerType =
                                    when (definition.providerFragment) {
                                        ProviderFragment.OBJECT -> variable.field.containingDef
                                        ProviderFragment.QUERY -> schema.requireQueryTypeDef()
                                    }
                                implicatedVertices(
                                    Fragment.of(
                                        providerType,
                                        selectionForestOf(
                                            definition.path.toSelection(
                                                setOf(providerType),
                                            ),
                                        ),
                                    ),
                                    variable.field,
                                )
                            }
                        },
                    )
                }
            }
        val assembledResolvers = mutableMapOf<ViaductSchema.Field, FieldResolver>()
        dependencyOrder(outgoing).forEach { site ->
            when (site) {
                is DependencyVertex.Field -> {
                    val definition = fieldResolverDefinitions.getValue(site.field)
                    assembledResolvers[site.field] =
                        definition.assemble(
                            queryType = schema.requireQueryTypeDef(),
                            variables =
                                variableDefinitions.filterKeys { variable ->
                                    variable.field == site.field
                                },
                            validateObjectFragment = { fragment ->
                                validateProviderContainment(
                                    field = site.field,
                                    fragment = fragment,
                                    providerFragment = ProviderFragment.OBJECT,
                                )
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

    override fun contains(field: ViaductSchema.ObjectField): Boolean {
        validateCanonicalField(field)
        return field in fieldResolvers
    }

    override fun createRootQueryInput(): EngineObjectData.Sync {
        val query = schema.requireQueryTypeDef()
        return engineObjectDataOf(schemaType = query)
    }

    override fun resolver(field: ViaductSchema.ObjectField): FieldResolver {
        validateCanonicalField(field)
        return fieldResolvers[field]
            ?: throw MissingResolverException(field.containingDef.name, field.name)
    }

    override fun mayDemandFrom(field: ViaductSchema.ObjectField): Set<ViaductSchema.ObjectField> {
        require(field in this) { "Resolver field is not registered" }
        return outgoing
            .getValue(DependencyVertex.Field(field))
            .mapNotNullTo(linkedSetOf()) { vertex ->
                (vertex as? DependencyVertex.Field)?.field
            }
    }

    private fun validateProviderContainment(
        field: ViaductSchema.ObjectField,
        fragment: Fragment,
        providerFragment: ProviderFragment,
    ) {
        variableDefinitions.forEach { (variable, definition) ->
            if (variable.field != field) return@forEach
            if (
                definition !is VariableDefinition.FromField ||
                definition.providerFragment != providerFragment
            ) {
                return@forEach
            }
            require(fragment.subselections.containsProviderPath(definition.path)) {
                "Variable ${variable.variableName} provider path is not contained by " +
                    "${field.containingDef.name}/${field.name} " +
                    "${providerFragment.name.lowercase()} fragment"
            }
        }
    }

    private fun validateVariableUses(
        variable: Arguments.Variable,
        fragments: List<Fragment>,
        sourceDescription: String,
        isCompatible: (
            ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
            Boolean,
        ) -> Boolean,
    ) {
        fragments
            .flatMap { fragment -> fragment.subselections.variableUses(variable) }
            .forEach { use ->
                require(
                    isCompatible(
                        use.typeExpr,
                        use.hasDefault,
                    ),
                ) {
                    "Variable ${variable.variableName} $sourceDescription is incompatible " +
                        "with one of its argument locations"
                }
            }
    }

    private fun SelectionForest.containsProviderPath(providerPath: List<ObjectEngineResult.Key>): Boolean {
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

    private fun SelectionForest.toSelectionList(): List<Selection> =
        buildList {
            this@toSelectionList.forEach(::add)
        }

    private data class VariableUse(
        val typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
        val hasDefault: Boolean,
    )

    private fun SelectionForest.variableUses(
        variable: Arguments.Variable,
    ): List<VariableUse> =
        buildList {
            this@variableUses.forEach { selection ->
                if (selection.key.arguments != Arguments.Error) {
                    selection.key.arguments.fieldExpressions().forEach { (name, value) ->
                        val argument = selection.key.field.requireArg(name)
                        addAll(
                            value
                                .matchingVariableTypes(
                                    variable = variable,
                                    typeExpr = argument.inputType,
                                    hasDefault = argument.hasDefault,
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
        field: ViaductSchema.Field,
        role: String = "field",
    ) {
        val typeName = field.containingDef.name
        require(schema.requireField(typeName, field.name) == field) {
            "$typeName/${field.name} is not the canonical $role in this registry's schema"
        }
    }

    private fun List<ObjectEngineResult.Key>.toSelection(
        possibleTypes: Set<ViaductSchema.Object>,
    ): Selection {
        val key = first()
        val remaining = drop(1)
        val outputType = key.field.type.baseTypeDef
        return Selection.of(
            key = key,
            possibleTypes = possibleTypes,
            subselections =
                if (remaining.isEmpty()) {
                    selectionForestOf()
                } else {
                    require(outputType is ViaductSchema.CompositeTypeDef)
                    selectionForestOf(remaining.toSelection(outputType.possibleObjectTypes))
                },
        )
    }

    private fun implicatedVertices(
        fragment: Fragment?,
        ownerField: ViaductSchema.ObjectField,
    ): Set<DependencyVertex> {
        if (fragment == null) return emptySet()
        val result = mutableSetOf<DependencyVertex>()
        fragment.subselections.forEach { selection ->
            result.addImplicatedBy(selection, ownerField)
        }
        return result
    }

    private fun MutableSet<DependencyVertex>.addImplicatedBy(
        selection: Selection,
        ownerField: ViaductSchema.ObjectField,
    ) {
        selection.possibleTypes.forEach { possibleType ->
            possibleType.field(selection.key.field.name)
                ?.takeIf { it in sourceFieldResolvers }
                ?.let { add(DependencyVertex.Field(it as ViaductSchema.ObjectField)) }
        }
        selection.key.arguments.variableTemplates().forEach { variable ->
            require(variable in variableDefinitions) {
                "Missing variable definition: \$${variable.variableName}"
            }
            require(variable.field == ownerField) {
                "Variable \$${variable.variableName} is not defined by " +
                    "${ownerField.containingDef.name}/${ownerField.name}"
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
