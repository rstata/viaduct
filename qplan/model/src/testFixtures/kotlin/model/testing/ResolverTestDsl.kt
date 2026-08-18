package model.testing

import model.ObjectEngineResult

import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.Definition
import graphql.language.Directive
import graphql.language.Document
import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.Node
import graphql.language.ObjectField
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value as GraphQLValue
import graphql.language.VariableReference
import graphql.parser.Parser
import java.lang.Math.addExact
import java.math.BigInteger
import model.EngineIDData
import model.EngineInputData
import model.Fragment
import model.Schema
import model.SourceSchemaAdapter
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf

/**
 * Compiles schema-embedded resolver fixtures into the existing test-world composition API.
 */
internal class ResolverTestDsl private constructor(
    val schemaSDL: String,
    private val fieldDefinitions: List<DslFieldResolver>,
    private val nodeDefinitions: List<DslNodeResolver>,
) {
    fun nodeResolvers(schema: Schema): Map<Schema.ObjectType, NodeResolverFunction> =
        Compiler(schema, fieldDefinitions, nodeDefinitions).nodeResolvers()

    fun fieldResolvers(schema: Schema): Map<Schema.OutputField, FieldResolverDefinition> =
        Compiler(schema, fieldDefinitions, nodeDefinitions).fieldResolvers()

    fun variableProviders(schema: Schema): Map<Value.Variable, VariableDeclaration> =
        Compiler(schema, fieldDefinitions, nodeDefinitions).variableProviders()

    companion object {
        fun parse(source: String): ResolverTestDsl {
            val document = Parser.parse(source)
            val fields = mutableListOf<DslFieldResolver>()
            val nodes = mutableListOf<DslNodeResolver>()

            document.definitions.forEach { definition ->
                when (definition) {
                    is ObjectTypeExtensionDefinition ->
                        collectObjectDefinition(
                            definition.name,
                            definition.directives,
                            definition.fieldDefinitions,
                            fields,
                            nodes,
                        )
                    is ObjectTypeDefinition ->
                        collectObjectDefinition(
                            definition.name,
                            definition.directives,
                            definition.fieldDefinitions,
                            fields,
                            nodes,
                        )
                }
            }

            require(fields.map { it.typeName to it.fieldName }.distinct().size == fields.size) {
                "A field may carry only one @$RESOLVER_DIRECTIVE directive"
            }
            require(nodes.map { it.typeName }.distinct().size == nodes.size) {
                "An object may carry only one @$NODE_RESOLVER_DIRECTIVE directive"
            }

            val stripped =
                document.transform { builder ->
                    builder.definitions(document.definitions.map(::stripDslDirectives))
                }
            return ResolverTestDsl(
                schemaSDL = BUILT_IN_SCHEMA + "\n" + AstPrinter.printAst(stripped),
                fieldDefinitions = fields,
                nodeDefinitions = nodes,
            )
        }

        private fun collectObjectDefinition(
            typeName: String,
            directives: List<Directive>,
            fieldDefinitions: List<FieldDefinition>,
            fields: MutableList<DslFieldResolver>,
            nodes: MutableList<DslNodeResolver>,
        ) {
            directives
                .filter { it.name == NODE_RESOLVER_DIRECTIVE }
                .forEach { directive ->
                    requireOnlyArguments(directive, setOf(RESULT_ARGUMENT))
                    val result = directive.requiredArgument(RESULT_ARGUMENT)
                    val entries =
                        when (result) {
                            is ArrayValue -> result.values
                            is ObjectValue -> listOf(result)
                            else ->
                                throw IllegalArgumentException(
                                    "@$NODE_RESOLVER_DIRECTIVE.$RESULT_ARGUMENT must be a list",
                                )
                        }.map { value -> parseNodeResult(typeName, value) }
                    nodes += DslNodeResolver(typeName, entries)
                }

            fieldDefinitions.forEach { field ->
                field.directives
                    .filter { it.name == RESOLVER_DIRECTIVE }
                    .forEach { directive ->
                        requireOnlyArguments(
                            directive,
                            setOf(OF_ARGUMENT, PATH_VARS_ARGUMENT, RESULT_ARGUMENT),
                        )
                        val result = directive.requiredArgument(RESULT_ARGUMENT)
                        val of =
                            directive.argument(OF_ARGUMENT)?.let { value ->
                                require(value is StringValue) {
                                    "@$RESOLVER_DIRECTIVE.$OF_ARGUMENT must be a string"
                                }
                                value.requiredValue()
                            }.orEmpty()
                        val pathVariables =
                            directive.argument(PATH_VARS_ARGUMENT)
                                ?.let(::parsePathVariables)
                                .orEmpty()
                        fields +=
                            DslFieldResolver(
                                typeName = typeName,
                                fieldName = field.name,
                                of = of,
                                pathVariables = pathVariables,
                                result = result,
                            )
                    }
            }
        }

        private fun parseNodeResult(
            typeName: String,
            value: GraphQLValue<*>,
        ): DslNodeResult {
            require(value is ObjectValue) {
                "@$NODE_RESOLVER_DIRECTIVE entries must be objects"
            }
            val fields = value.uniqueFields("$typeName @$NODE_RESOLVER_DIRECTIVE entry")
            require(fields.keys == setOf(ID_FIELD, RESULT_ARGUMENT)) {
                "@$NODE_RESOLVER_DIRECTIVE entries require exactly id and result"
            }
            return DslNodeResult(
                id = parseId(fields.getValue(ID_FIELD)),
                result = fields.getValue(RESULT_ARGUMENT),
            )
        }

        private fun parsePathVariables(value: GraphQLValue<*>): List<DslPathVariable> {
            val values =
                when (value) {
                    is ArrayValue -> value.values
                    is ObjectValue -> listOf(value)
                    else ->
                        throw IllegalArgumentException(
                            "@$RESOLVER_DIRECTIVE.$PATH_VARS_ARGUMENT must be a list",
                        )
                }
            return values.map { entry ->
                require(entry is ObjectValue) {
                    "$PATH_VARS_ARGUMENT entries must be objects"
                }
                val fields = entry.uniqueFields("$PATH_VARS_ARGUMENT entry")
                require(fields.keys == setOf(NAME_FIELD, PATH_FIELD)) {
                    "$PATH_VARS_ARGUMENT entries require exactly name and path"
                }
                val name = fields.getValue(NAME_FIELD)
                require(name is StringValue && GRAPHQL_NAME.matches(name.requiredValue())) {
                    "$PATH_VARS_ARGUMENT.name must be a GraphQL name"
                }
                val path = fields.getValue(PATH_FIELD)
                require(path is ArrayValue && path.values.isNotEmpty()) {
                    "$PATH_VARS_ARGUMENT.path must be a nonempty list"
                }
                DslPathVariable(
                    name = name.requiredValue(),
                    path =
                        path.values.map { component ->
                            require(
                                component is StringValue &&
                                    GRAPHQL_NAME.matches(component.requiredValue()),
                            ) {
                                "$PATH_VARS_ARGUMENT.path components must be GraphQL names"
                            }
                            component.requiredValue()
                        },
                )
            }.also { definitions ->
                require(definitions.map { it.name }.distinct().size == definitions.size) {
                    "$PATH_VARS_ARGUMENT variable names must be unique"
                }
            }
        }

        private fun stripDslDirectives(definition: Definition<*>): Definition<*> =
            when (definition) {
                is ObjectTypeExtensionDefinition ->
                    definition.transformExtension { builder ->
                        builder
                            .directives(definition.directives.withoutDslDirectives())
                            .fieldDefinitions(
                                definition.fieldDefinitions.map { it.withoutDslDirectives() },
                            )
                    }
                is ObjectTypeDefinition ->
                    definition.transform { builder ->
                        builder
                            .directives(definition.directives.withoutDslDirectives())
                            .fieldDefinitions(
                                definition.fieldDefinitions.map { it.withoutDslDirectives() },
                            )
                    }
                else -> definition
            }

        private fun FieldDefinition.withoutDslDirectives(): FieldDefinition =
            transform { builder -> builder.directives(directives.withoutDslDirectives()) }

        private fun List<Directive>.withoutDslDirectives(): List<Directive> =
            filterNot { it.name == RESOLVER_DIRECTIVE || it.name == NODE_RESOLVER_DIRECTIVE }

        private fun requireOnlyArguments(
            directive: Directive,
            allowed: Set<String>,
        ) {
            val unexpected = directive.arguments.map { it.name }.filterNot(allowed::contains)
            require(unexpected.isEmpty()) {
                "Unexpected @${
                    directive.name
                } arguments: ${unexpected.sorted().joinToString()}"
            }
        }

        private fun Directive.requiredArgument(name: String): GraphQLValue<*> =
            argument(name)
                ?: throw IllegalArgumentException(
                    "@${this.name} requires $name, including when null",
                )

        private fun Directive.argument(name: String): GraphQLValue<*>? =
            arguments.singleOrNull { it.name == name }?.value

        private fun parseId(value: GraphQLValue<*>): String =
            when (value) {
                is StringValue -> value.requiredValue()
                is IntValue -> value.value.toString()
                else -> throw IllegalArgumentException("NodeResult.id must be an ID literal")
            }
    }
}

private class Compiler(
    private val schema: Schema,
    private val fieldDefinitions: List<DslFieldResolver>,
    private val nodeDefinitions: List<DslNodeResolver>,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val resolverCoordinates =
        fieldDefinitions.mapTo(linkedSetOf()) { it.typeName to it.fieldName }
    private val nodeEntries: List<CompiledNodeResult> =
        nodeDefinitions.flatMap { definition ->
            val type = schema.type(definition.typeName) as? Schema.ObjectType
                ?: throw IllegalArgumentException(
                    "@$NODE_RESOLVER_DIRECTIVE requires an object type: ${definition.typeName}",
                )
            require(type in nodeType().possibleTypes) {
                "${definition.typeName} does not implement Node"
            }
            definition.results.map { result ->
                CompiledNodeResult(type, result.id, result.result)
            }
        }
    private val nodesById: Map<String, CompiledNodeResult> =
        nodeEntries.associateBy(CompiledNodeResult::id).also { byId ->
            require(byId.size == nodeEntries.size) {
                "NodeResult ids must be globally unique"
            }
        }
    private val evaluator =
        ResultEvaluator(
            schema = schema,
            resolverCoordinates = resolverCoordinates,
            nodesById = nodesById,
        )

    fun nodeResolvers(): Map<Schema.ObjectType, NodeResolverFunction> =
        nodeEntries
            .groupBy(CompiledNodeResult::type)
            .mapValues { (type, entries) ->
                val byId = entries.associateBy(CompiledNodeResult::id)
                nodeResolverOf { id ->
                    byId[id.idValue]?.let { entry ->
                        evaluator.evaluateNodeResult(entry)
                    }
                }
            }

    fun fieldResolvers(): Map<Schema.OutputField, FieldResolverDefinition> {
        val compiled = mutableMapOf<Schema.OutputField, FieldResolverDefinition>()
        fieldDefinitions.forEach { definition ->
                val field = sourceSchema.field(definition.typeName, definition.fieldName)
                require(field is Schema.ObjectField) {
                    "@$RESOLVER_DIRECTIVE requires a concrete object field: " +
                        "${definition.typeName}.${definition.fieldName}"
                }
                val fragment = objectFragment(field, definition.of)
                compiled[field] =
                    fieldResolverOf(fragment) { input, arguments ->
                        evaluator.evaluateFieldResult(
                            field = field,
                            result = definition.result,
                            input = input,
                            arguments = arguments,
                        )
                    }
            }

        val queryNode = sourceSchema.field("Query", "node")
        require(queryNode is Schema.ObjectField)
        compiled[queryNode] =
            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                val id = arguments.fieldValues.getValue(ID_FIELD)
                when (id) {
                    null -> null
                    is EngineIDData ->
                        nodesById[id.id]?.let { entry ->
                            schema.objectOf(entry.type.typeName) {
                                ID_FIELD setTo id.id
                            }
                        }
                    else -> throw IllegalArgumentException("Query.node id is not an ID")
                }
            }
        return compiled
    }

    fun variableProviders(): Map<Value.Variable, VariableDeclaration> =
        buildMap {
            fieldDefinitions.forEach { definition ->
                val field = sourceSchema.field(definition.typeName, definition.fieldName)
                require(field is Schema.ObjectField)
                val argumentNames = field.arguments.fields.keys
                val pathVariables = definition.pathVariables.associateBy(DslPathVariable::name)
                require(pathVariables.keys.intersect(argumentNames).isEmpty()) {
                    "${definition.typeName}.${definition.fieldName} $PATH_VARS_ARGUMENT may not " +
                        "redefine field arguments"
                }
                val usedVariables = variablesIn(definition, field)
                val unusedPathVariables = pathVariables.keys - usedVariables
                require(unusedPathVariables.isEmpty()) {
                    "Unused $PATH_VARS_ARGUMENT variables on ${definition.typeName}." +
                        "${definition.fieldName}: ${unusedPathVariables.sorted().joinToString()}"
                }
                usedVariables.forEach { name ->
                    val variable = Value.Variable.of(field, name)
                    put(
                        variable,
                        when {
                            name in argumentNames -> schema.fromArgument(field, name)
                            name in pathVariables ->
                                preparedObjectFragment(field, definition.of).let { fragment ->
                                    schema.fromObjectField(
                                        objectFragmentSource = fragment.source,
                                        responsePath = pathVariables.getValue(name).path,
                                        variableField = field,
                                        bindings = fragment.bindings,
                                    )
                                }
                            else ->
                                throw IllegalArgumentException(
                                    "Variable \$$name on ${definition.typeName}." +
                                        "${definition.fieldName} is neither an argument nor a " +
                                        "$PATH_VARS_ARGUMENT definition",
                                )
                        },
                    )
                }
            }
        }

    private fun objectFragment(
        field: Schema.ObjectField,
        source: String,
    ): Fragment =
        if (source.isBlank()) {
            schema.emptyFragmentOf(field.containingType.typeName)
        } else {
            val fragment = preparedObjectFragment(field, source)
            requireNoAliases(fragment.source)
            schema.fragmentFrom(
                source = fragment.source,
                bindings = fragment.bindings,
                variableField = field,
            )
        }

    private fun variablesIn(
        definition: DslFieldResolver,
        field: Schema.ObjectField,
    ): Set<String> {
        if (definition.of.isBlank()) return emptySet()
        val fragment = preparedObjectFragment(field, definition.of)
        requireNoAliases(fragment.source)
        val variables = linkedSetOf<String>()
        Parser.parse(fragment.source).visitRecursively { node ->
            if (node is VariableReference) variables += node.name
        }
        return variables - fragment.bindings.keys
    }

    private fun requireNoAliases(source: String) {
        Parser.parse(source).visitRecursively { node ->
            require(node !is Field || node.alias == null) {
                "@$RESOLVER_DIRECTIVE.$OF_ARGUMENT does not support aliases"
            }
        }
    }

    private fun objectFragmentSource(
        field: Schema.ObjectField,
        source: String,
    ): String =
        "fragment ResolverTestDsl on ${field.containingType.typeName} { $source }"

    private fun preparedObjectFragment(
        field: Schema.ObjectField,
        source: String,
    ): PreparedObjectFragment {
        val fragmentSource = objectFragmentSource(field, source)
        val occupiedVariableNames = linkedSetOf<String>()

        Parser.parse(fragmentSource).visitRecursively { node ->
            if (node is VariableReference) occupiedVariableNames += node.name
        }

        var nextBindingIndex = 0
        val bindings = linkedMapOf<String, EngineInputData?>()
        val preparedSource =
            ERROR_ARGUMENT_LITERAL.replace(fragmentSource) {
                val name =
                    generateSequence {
                        "${ERROR_VARIABLE_PREFIX}${nextBindingIndex++}"
                    }.first { candidate ->
                        candidate !in occupiedVariableNames && candidate !in bindings
                    }
                bindings[name] = ErroneousVariableValue
                "\$$name"
            }
        return PreparedObjectFragment(preparedSource, bindings)
    }

    private fun nodeType(): Schema.InterfaceType =
        schema.type("Node") as? Schema.InterfaceType
            ?: throw IllegalArgumentException("The resolver-test DSL requires interface Node")
}

private class ResultEvaluator(
    private val schema: Schema,
    private val resolverCoordinates: Set<Pair<String, String>>,
    private val nodesById: Map<String, CompiledNodeResult>,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val nodeType = schema.type("Node") as Schema.InterfaceType

    fun evaluateFieldResult(
        field: Schema.ObjectField,
        result: GraphQLValue<*>,
        input: Value.Object,
        arguments: Value.Arguments,
    ): Value.Output? =
        evaluate(
            typeExpr = sourceSchema.typeExpr(field),
            source = result,
            context = EvaluationContext(input, arguments),
        )

    fun evaluateNodeResult(entry: CompiledNodeResult): Value.Output? =
        evaluate(
            typeExpr = TypeExpr.Named.of(entry.type, isNullable = true),
            source = entry.result,
            context = EvaluationContext(schema.objectOf(entry.type.typeName), null),
            nodeRoot = entry,
        )

    private fun evaluate(
        typeExpr: TypeExpr<Schema.OutputType>,
        source: GraphQLValue<*>,
        context: EvaluationContext,
        nodeRoot: CompiledNodeResult? = null,
    ): Value.Output? {
        if (source is StringValue && source.value == ERROR_SENTINEL) return Value.Error
        if (source is NullValue) {
            require(typeExpr.isNullable) { "null does not conform to $typeExpr" }
            return null
        }
        return when (typeExpr) {
            is TypeExpr.List -> {
                require(source is ArrayValue) { "Expected a list result for $typeExpr" }
                Value.OutputList.of(
                    typeExpr = typeExpr.elementType,
                    values =
                        source.values.map { value ->
                            evaluate(typeExpr.elementType, value, context)
                        },
                )
            }
            is TypeExpr.Named ->
                when (val type = typeExpr.baseType) {
                    Schema.IntType ->
                        evaluateInt(source, context).also { result ->
                            require(result != null || typeExpr.isNullable) {
                                "null does not conform to $typeExpr"
                            }
                        }
                    Schema.IDType ->
                        Value.ID.of(
                            parseResultId(source, context),
                        )
                    is Schema.SimpleType ->
                        throw IllegalArgumentException(
                            "Resolver-test DSL leaves must be Int, not ${type.typeName}",
                        )
                    is Schema.CompositeType ->
                        evaluateComposite(type, source, context, nodeRoot)
                }
        }
    }

    private fun evaluateComposite(
        declaredType: Schema.CompositeType,
        source: GraphQLValue<*>,
        context: EvaluationContext,
        nodeRoot: CompiledNodeResult?,
    ): Value.Output? {
        require(source is ObjectValue) {
            "Expected an object result for ${declaredType.typeName}"
        }
        if (nodeRoot != null) {
            require(declaredType == nodeRoot.type)
            return evaluateObject(
                type = nodeRoot.type,
                source = source,
                context = context,
                injectedNodeId = nodeRoot.id,
            )
        }
        if (isNodeType(declaredType)) {
            return evaluateNodeReference(declaredType, source, context)
        }

        val fields = source.uniqueFields("result for ${declaredType.typeName}")
        val concreteType =
            when (declaredType) {
                is Schema.ObjectType -> {
                    require(TYPENAME_FIELD !in fields) {
                        "__typename may not be supplied for object type ${declaredType.typeName}"
                    }
                    declaredType
                }
                else -> {
                    val typename = fields[TYPENAME_FIELD]
                    require(typename is StringValue) {
                        "Result for abstract type ${declaredType.typeName} requires __typename"
                    }
                    val concrete = schema.type(typename.requiredValue()) as? Schema.ObjectType
                        ?: throw IllegalArgumentException(
                            "${typename.requiredValue()} is not an object type",
                        )
                    require(concrete in declaredType.possibleTypes) {
                        "${concrete.typeName} is not a possible ${declaredType.typeName}"
                    }
                    concrete
                }
            }
        return evaluateObject(concreteType, source, context)
    }

    private fun evaluateObject(
        type: Schema.ObjectType,
        source: ObjectValue,
        context: EvaluationContext,
        injectedNodeId: String? = null,
    ): Value.Object {
        val fields = source.uniqueFields("result for ${type.typeName}")
        if (injectedNodeId != null) {
            require(ID_FIELD !in fields) {
                "@$NODE_RESOLVER_DIRECTIVE result for ${type.typeName} may not contain id"
            }
            require(TYPENAME_FIELD !in fields) {
                "@$NODE_RESOLVER_DIRECTIVE result for ${type.typeName} may not contain __typename"
            }
        }
        return schema.objectOf(type.typeName) {
            if (injectedNodeId != null) {
                ID_FIELD setTo injectedNodeId
            }
            fields.forEach { (fieldName, fieldValue) ->
                if (fieldName == TYPENAME_FIELD) return@forEach
                require((type.typeName to fieldName) !in resolverCoordinates) {
                    "Result for ${type.typeName} may not supply resolver field $fieldName"
                }
                val field = sourceSchema.field(type.typeName, fieldName)
                require(field is Schema.ObjectField)
                field(fieldName) setTo
                    evaluate(
                        typeExpr = sourceSchema.typeExpr(field),
                        source = fieldValue,
                        context = context,
                    )
            }
        }
    }

    private fun evaluateNodeReference(
        declaredType: Schema.CompositeType,
        source: ObjectValue,
        context: EvaluationContext,
    ): Value.Object {
        val fields = source.uniqueFields("Node reference")
        require(fields.keys == setOf(ID_FIELD)) {
            "Node-typed results may contain only id"
        }
        val id = parseResultId(fields.getValue(ID_FIELD), context)
        val entry =
            nodesById[id]
                ?: throw IllegalArgumentException("No @$NODE_RESOLVER_DIRECTIVE result for id $id")
        require(entry.type in declaredType.possibleTypes) {
            "Node id $id has type ${entry.type.typeName}, not ${declaredType.typeName}"
        }
        return schema.objectOf(entry.type.typeName) {
            ID_FIELD setTo id
        }
    }

    private fun evaluateInt(
        source: GraphQLValue<*>,
        context: EvaluationContext,
    ): Value.Output? =
        when (source) {
            is IntValue -> Value.Int.of(source.value.toIntExact("GraphQL Int result"))
            is StringValue -> evaluateExpression(source.requiredValue(), context)
            else -> throw IllegalArgumentException("Int results require an integer or expression")
        }

    private fun evaluateExpression(
        source: String,
        context: EvaluationContext,
    ): Value.Output? {
        val match = EXPRESSION.matchEntire(source)
            ?: throw IllegalArgumentException("Invalid resolver-test expression: $source")
        val operation = match.groupValues[1]
        val terms =
            match.groupValues[2]
                .takeIf(String::isNotBlank)
                ?.split(',')
                ?.map(String::trim)
                .orEmpty()

        if (operation == "value") {
            require(terms.size == 1) { "value(...) requires exactly one value" }
            val values = expressionValues(terms.single(), context, preserveNulls = true)
            require(values.size == 1) {
                "value(...) requires exactly one reachable value, found ${values.size}"
            }
            return values.single().also { value ->
                require(value == null || value == Value.Error || value is Value.Int) {
                    "value(...) result is not an Int"
                }
            }
        }

        var sum = if (operation == "sumplus1") 1 else 0
        terms.forEach { term ->
            val values = expressionValues(term, context, preserveNulls = false)
            values.forEach { value ->
                when (value) {
                    null -> Unit
                    Value.Error -> return Value.Error
                    is Value.Int -> sum = addExact(sum, value.intValue)
                    else ->
                        throw IllegalArgumentException(
                            "Resolver-test expression value is not an Int: $term",
                        )
                }
            }
        }
        return Value.Int.of(sum)
    }

    private fun expressionValues(
        term: String,
        context: EvaluationContext,
        preserveNulls: Boolean,
    ): List<Value.Output?> =
        if (term.startsWith("$")) {
            val value = argumentValue(term.removePrefix("$"), context)
            when (value) {
                null -> listOf(null)
                is Int -> listOf(Value.Int.of(value))
                else ->
                    throw IllegalArgumentException(
                        "Resolver-test expression value is not an output value: $term",
                    )
            }
        } else {
            require(PATH.matches(term)) { "Invalid resolver-test value: $term" }
            fieldPathValues(context.input, term.split('.'), preserveNulls)
        }

    private fun argumentValue(
        name: String,
        context: EvaluationContext,
    ): EngineInputData? {
        require(GRAPHQL_NAME.matches(name)) { "Invalid argument reference: \$$name" }
        val arguments =
            context.arguments
                ?: throw IllegalArgumentException(
                    "Node resolver results cannot reference arguments",
                )
        require(name in arguments.fieldValues) { "No resolver argument named $name" }
        return arguments.fieldValues.getValue(name)
    }

    private fun fieldPathValues(
        input: Value.Object,
        path: List<String>,
        preserveNulls: Boolean = false,
    ): List<Value.Output?> {
        fun visit(
            value: Value.Output?,
            index: Int,
        ): List<Value.Output?> =
            when {
                value == null -> if (preserveNulls) listOf(null) else emptyList()
                value == Value.Error -> listOf(Value.Error)
                value is Value.OutputList -> value.values.flatMap { visit(it, index) }
                index == path.size -> listOf(value)
                value is Value.Object -> {
                    val field = sourceSchema.field(value.type.typeName, path[index])
                    require(field is Schema.ObjectField)
                    val matches =
                        value.fieldValues.entries.filter { (key, _) ->
                            key == field.fieldName || key.startsWith("${field.fieldName}(")
                        }
                    require(matches.size == 1) {
                        "Path ${path.joinToString(".")} does not identify one value at " +
                            "${value.type.typeName}.${path[index]}"
                    }
                    val selected = matches.single().value
                    if (schema is GJSchema && schema.isLoweredNodeField(field)) {
                        unwrapNodeBridge(selected).flatMap { visit(it, index + 1) }
                    } else {
                        visit(selected, index + 1)
                    }
                }
                else ->
                    throw IllegalArgumentException(
                        "Path ${path.joinToString(".")} traverses a non-object value",
                    )
            }

        return visit(input, 0)
    }

    private fun unwrapNodeBridge(value: Value.Output?): List<Value.Output?> =
        when (value) {
            null -> emptyList()
            Value.Error -> listOf(Value.Error)
            is Value.OutputList -> value.values.flatMap(::unwrapNodeBridge)
            is Value.Object -> {
                val payload = schema.objectField(value.type.typeName, NODE_BRIDGE_PAYLOAD_FIELD)
                listOf(value.fieldValues.getValue(payload.fieldName))
            }
            else -> throw IllegalArgumentException("Malformed lowered Node bridge")
        }

    private fun isNodeType(type: Schema.CompositeType): Boolean =
        type.possibleTypes.isNotEmpty() &&
            type.possibleTypes.all(nodeType.possibleTypes::contains)

    private fun parseResultId(
        value: GraphQLValue<*>,
        context: EvaluationContext,
    ): String =
        when (value) {
            is StringValue -> {
                val source = value.requiredValue()
                val match = ID_FROM_ARGUMENT_EXPRESSION.matchEntire(source)
                if (match == null) {
                    require(!source.startsWith(ID_FROM_ARGUMENT_PREFIX)) {
                        "Invalid resolver-test ID expression: $source"
                    }
                    require(source != ERROR_SENTINEL) {
                        "$ERROR_SENTINEL is reserved as an error sentinel"
                    }
                    source
                } else {
                    val argumentName = match.groupValues[1]
                    when (val argument = argumentValue(argumentName, context)) {
                        is EngineIDData -> argument.id
                        else ->
                            throw IllegalArgumentException(
                                "idFrom(\$$argumentName) requires a non-null ID argument",
                            )
                    }
                }
            }
            is IntValue -> value.value.toString()
            else -> throw IllegalArgumentException("Node id must be an ID literal")
        }

}

private data class EvaluationContext(
    val input: Value.Object,
    val arguments: Value.Arguments?,
)

private data class PreparedObjectFragment(
    val source: String,
    val bindings: Map<String, EngineInputData?>,
)

private data class DslFieldResolver(
    val typeName: String,
    val fieldName: String,
    val of: String,
    val pathVariables: List<DslPathVariable>,
    val result: GraphQLValue<*>,
)

private data class DslPathVariable(
    val name: String,
    val path: List<String>,
)

private data class DslNodeResolver(
    val typeName: String,
    val results: List<DslNodeResult>,
)

private data class DslNodeResult(
    val id: String,
    val result: GraphQLValue<*>,
)

private data class CompiledNodeResult(
    val type: Schema.ObjectType,
    val id: String,
    val result: GraphQLValue<*>,
)

private fun BigInteger.toIntExact(context: String): Int =
    try {
        intValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$context is outside the 32-bit range: $this")
    }

private fun StringValue.requiredValue(): String =
    requireNotNull(value) { "GraphQL string literal has no value" }

private fun ObjectValue.uniqueFields(context: String): Map<String, GraphQLValue<*>> {
    require(objectFields.map(ObjectField::getName).distinct().size == objectFields.size) {
        "$context contains duplicate fields"
    }
    return objectFields.associate { requireNotNull(it.name) to it.value }
}

private fun Node<*>.visitRecursively(visitor: (Node<*>) -> Unit) {
    visitor(this)
    children.forEach { child -> child.visitRecursively(visitor) }
}

private const val RESOLVER_DIRECTIVE = "resolver"
private const val NODE_RESOLVER_DIRECTIVE = "nodeResolver"
private const val RESULT_ARGUMENT = "result"
private const val OF_ARGUMENT = "of"
private const val PATH_VARS_ARGUMENT = "pathVars"
private const val NAME_FIELD = "name"
private const val PATH_FIELD = "path"
private const val ID_FIELD = "id"
private const val TYPENAME_FIELD = "__typename"
private const val ERROR_SENTINEL = "ERROR"
private const val ERROR_VARIABLE_PREFIX = "__resolverTestError"
private const val ID_FROM_ARGUMENT_PREFIX = "idFrom("
private val GRAPHQL_NAME = Regex("[_A-Za-z][_0-9A-Za-z]*")
private val PATH = Regex("[_A-Za-z][_0-9A-Za-z]*(\\.[_A-Za-z][_0-9A-Za-z]*)*")
private val EXPRESSION = Regex("(sum|sumplus1|value)\\((.*)\\)")
private val ID_FROM_ARGUMENT_EXPRESSION =
    Regex("idFrom\\(\\$(${GRAPHQL_NAME.pattern})\\)")
private val ERROR_ARGUMENT_LITERAL = Regex("\"ERROR\"")

private val BUILT_IN_SCHEMA =
    """
    interface Node { id: ID! }
    type Query { node(id: ID!): Node }
    """.trimIndent()
