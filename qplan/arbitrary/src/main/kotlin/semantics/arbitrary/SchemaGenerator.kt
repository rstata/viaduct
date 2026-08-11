package semantics.arbitrary

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputValueDefinition
import graphql.language.IntValue
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import java.math.BigInteger

internal const val GENERATED_HASH_TYPE = "Hash"
internal const val GENERATED_HASH_FIELD = "hash"
internal const val GENERATED_HASH_NESTED_FIELD = "nested"

class ArbitrarySchema internal constructor(
    val sdl: String,
    internal val objects: List<ObjectDefinition>,
    internal val query: ObjectDefinition,
    internal val hashType: ObjectDefinition,
    internal val interfaces: List<InterfaceDefinitionSpec>,
    internal val unions: List<UnionDefinitionSpec>,
    internal val inputObjects: List<InputObjectDefinitionSpec>,
    internal val deepFields: Map<String, String>,
    val features: SchemaFeatures,
) {
    val domainObjectTypeNames: Set<String> =
        objects.mapTo(linkedSetOf(), ObjectDefinition::name)

    internal val allObjects: List<ObjectDefinition>
        get() = listOf(query) + objects + hashType

    internal fun objectNamed(name: String): ObjectDefinition =
        allObjects.single { it.name == name }

    internal fun possibleObjects(typeName: String): List<ObjectDefinition> =
        allObjects.singleOrNull { it.name == typeName }?.let(::listOf)
            ?: interfaces.singleOrNull { it.name == typeName }?.members
                ?.map(::objectNamed)
            ?: unions.singleOrNull { it.name == typeName }?.members
                ?.map(::objectNamed)
            ?: error("Unknown output type $typeName")

    internal fun fieldsOn(typeName: String): List<FieldDefinitionSpec> =
        allObjects.singleOrNull { it.name == typeName }?.fields
            ?: interfaces.singleOrNull { it.name == typeName }?.fields
            ?: emptyList()

    internal fun isComposite(typeName: String): Boolean =
        allObjects.any { it.name == typeName } ||
            interfaces.any { it.name == typeName } ||
            unions.any { it.name == typeName }

    override fun toString(): String = sdl
}

data class SchemaFeatures(
    val hasArguments: Boolean,
    val hasScalarArguments: Boolean,
    val hasListArguments: Boolean,
    val hasInputObjectArguments: Boolean,
    val hasInputObjectListArguments: Boolean,
    val hasInputObjects: Boolean,
    val hasRecursiveInputTypes: Boolean,
    val hasOutputLists: Boolean,
    val hasRecursiveOutputEdges: Boolean,
    val hasNullableRecursiveOutputEdges: Boolean,
    val hasListRecursiveOutputEdges: Boolean,
    val hasImplementationArgumentDefaults: Boolean,
    val hasInterfaces: Boolean,
    val hasUnions: Boolean,
)

internal data class ObjectDefinition(
    val name: String,
    val implementsNode: Boolean,
    val interfaces: Set<String>,
    val fields: List<FieldDefinitionSpec>,
)

internal data class InterfaceDefinitionSpec(
    val name: String,
    val members: Set<String>,
    val fields: List<FieldDefinitionSpec>,
)

internal data class UnionDefinitionSpec(
    val name: String,
    val members: Set<String>,
)

internal data class FieldDefinitionSpec(
    val ownerName: String,
    val name: String,
    val type: OutputTypeSpec,
    val arguments: List<ArgumentDefinitionSpec>,
) {
    val coordinate: FieldCoordinate
        get() = FieldCoordinate(ownerName, name)
}

data class FieldCoordinate(
    val typeName: String,
    val fieldName: String,
) {
    override fun toString(): String = "$typeName/$fieldName"
}

internal data class ArgumentDefinitionSpec(
    val name: String,
    val type: InputTypeSpec,
    val defaultValue: graphql.language.Value<*>? = null,
)

internal fun FieldDefinitionSpec.isGeneratedHashField(): Boolean =
    name == GENERATED_HASH_FIELD && type.namedType == GENERATED_HASH_TYPE

internal data class InputObjectDefinitionSpec(
    val name: String,
    val fields: List<InputFieldDefinitionSpec>,
)

internal data class InputFieldDefinitionSpec(
    val name: String,
    val type: InputTypeSpec,
)

internal sealed interface InputTypeSpec {
    val nullable: Boolean
}

internal data class ScalarInputTypeSpec(
    val scalar: ScalarKind,
    override val nullable: Boolean,
) : InputTypeSpec

internal data class ListInputTypeSpec(
    val element: InputTypeSpec,
    override val nullable: Boolean,
) : InputTypeSpec

internal data class InputObjectInputTypeSpec(
    val name: String,
    override val nullable: Boolean,
) : InputTypeSpec

internal data class OutputTypeSpec(
    val namedType: String,
    val nullable: Boolean,
    val list: Boolean,
    val elementNullable: Boolean,
)

internal enum class ScalarKind(
    val graphQLName: String,
) {
    BOOLEAN("Boolean"),
    FLOAT("Float"),
    ID("ID"),
    INT("Int"),
    STRING("String"),
}

fun Arb.Companion.schema(config: Config = Config.default): Arb<ArbitrarySchema> =
    arbitrary { random ->
        SchemaGenerator(config, random).generate()
    }

private class SchemaGenerator(
    private val config: Config,
    private val random: RandomSource,
) {
    fun generate(): ArbitrarySchema {
        val minimumDepth = config[MinimumSelectionDepth]
        require(minimumDepth <= config[MaxSelectionDepth]) {
            "Minimum selection depth cannot exceed maximum selection depth"
        }
        require(minimumDepth <= config[SchemaObjectCount].last) {
            "Schema object count must permit the minimum selection depth"
        }
        val objectCountRange =
            maxOf(config[SchemaObjectCount].first, minimumDepth)..config[SchemaObjectCount].last
        val objectCount = Arb.int(objectCountRange).next(random)
        val objectNames = (0 until objectCount).map { "Object$it" }
        val inputObjects = inputObjects()
        val nodeNames =
            if (config[InterfacesEnabled] && config[NodeResolversEnabled]) {
                objectNames.filter { chance(config[NodeObjectWeight]) }.toSet()
            } else {
                emptySet()
            }
        val baseObjects =
            objectNames.mapIndexed { index, name ->
                val laterObjects = objectNames.drop(index + 1)
                val recursiveObjects =
                    if (config[RecursiveOutputEdgesEnabled]) {
                        objectNames.take(index + 1)
                    } else {
                        emptyList()
                    }
                val fieldCount = Arb.int(config[ObjectFieldCount]).next(random)
                val generatedFields =
                    (0 until fieldCount).map { fieldIndex ->
                        field(
                            ownerName = name,
                            name = "field$fieldIndex",
                            objectTargets = laterObjects,
                            recursiveObjectTargets = recursiveObjects,
                            inputObjectNames = inputObjects.map(InputObjectDefinitionSpec::name),
                        )
                    }
                val fields =
                    if (index < minimumDepth - 1) {
                        listOf(deepField(name, objectNames[index + 1])) +
                            generatedFields.drop(1)
                    } else {
                        generatedFields
                    }
                ObjectDefinition(
                    name = name,
                    implementsNode = name in nodeNames,
                    interfaces = emptySet(),
                    fields = fields,
                )
            }
        val nonNodeObjectNames = objectNames.filterNot(nodeNames::contains)
        val generatedInterface =
            if (config[InterfacesEnabled] && nonNodeObjectNames.isNotEmpty()) {
                val members = nonEmptySubset(nonNodeObjectNames)
                val commonField =
                    FieldDefinitionSpec(
                        ownerName = "GeneratedInterface",
                        name = "common",
                        type =
                            OutputTypeSpec(
                                namedType = "String",
                                nullable = chance(config[NullableTypeWeight]),
                                list = false,
                                elementNullable = true,
                            ),
                        arguments = emptyList(),
                    )
                InterfaceDefinitionSpec(
                    name = "GeneratedInterface",
                    members = members,
                    fields = listOf(commonField),
                )
            } else {
                null
            }
        val domainObjects =
            baseObjects.map { objectType ->
                if (objectType.name !in generatedInterface?.members.orEmpty()) {
                    objectType
                } else {
                    objectType.copy(
                        interfaces = objectType.interfaces + generatedInterface!!.name,
                        fields =
                            objectType.fields +
                                generatedInterface.fields.map { field ->
                                    field.copy(
                                        ownerName = objectType.name,
                                        arguments =
                                            field.arguments +
                                                implementationArgumentDefault(),
                                    )
                                },
                    )
                }
            }
        val objects =
            domainObjects.map { objectType ->
                objectType.copy(fields = objectType.fields + generatedHashField(objectType.name))
            }
        val hashType =
            ObjectDefinition(
                name = GENERATED_HASH_TYPE,
                implementsNode = false,
                interfaces = emptySet(),
                fields =
                    listOf(
                        FieldDefinitionSpec(
                            ownerName = GENERATED_HASH_TYPE,
                            name = GENERATED_HASH_NESTED_FIELD,
                            type =
                                OutputTypeSpec(
                                    namedType = GENERATED_HASH_TYPE,
                                    nullable = true,
                                    list = false,
                                    elementNullable = false,
                                ),
                            arguments = emptyList(),
                        ),
                        FieldDefinitionSpec(
                            ownerName = GENERATED_HASH_TYPE,
                            name = GENERATED_HASH_FIELD,
                            type =
                                OutputTypeSpec(
                                    namedType = "Int",
                                    nullable = false,
                                    list = false,
                                    elementNullable = false,
                                ),
                            arguments = emptyList(),
                        ),
                    ),
            )
        val generatedUnion =
            if (config[UnionsEnabled] && nonNodeObjectNames.isNotEmpty()) {
                UnionDefinitionSpec(
                    name = "GeneratedUnion",
                    members = nonEmptySubset(nonNodeObjectNames),
                )
            } else {
                null
            }
        val interfaces =
            buildList {
                if (nodeNames.isNotEmpty()) {
                    add(
                        InterfaceDefinitionSpec(
                            name = "Node",
                            members = nodeNames,
                            fields =
                                listOf(
                                    FieldDefinitionSpec(
                                        ownerName = "Node",
                                        name = "id",
                                        type =
                                            OutputTypeSpec(
                                                namedType = "ID",
                                                nullable = false,
                                                list = false,
                                                elementNullable = false,
                                            ),
                                        arguments = emptyList(),
                                    ),
                                ),
                        ),
                    )
                }
                generatedInterface?.let(::add)
            }
        val unions = listOfNotNull(generatedUnion)
        val queryTargets =
            objectNames +
                interfaces.map(InterfaceDefinitionSpec::name) +
                unions.map(UnionDefinitionSpec::name)
        val queryFieldCount = Arb.int(config[QueryFieldCount]).next(random)
        val generatedQueryFields =
            (0 until queryFieldCount).map { index ->
                field(
                    ownerName = "Query",
                    name = "query$index",
                    objectTargets = queryTargets,
                    recursiveObjectTargets = emptyList(),
                    inputObjectNames = inputObjects.map(InputObjectDefinitionSpec::name),
                    preferObject = true,
                    forceScalar =
                        config[QueryScalarFieldWeight] > 0.0 &&
                            chance(config[QueryScalarFieldWeight]),
                )
            }
        val query =
            ObjectDefinition(
                name = "Query",
                implementsNode = false,
                interfaces = emptySet(),
                fields =
                    if (minimumDepth > 0) {
                        listOf(deepField("Query", objectNames.first(), "query0")) +
                            generatedQueryFields.drop(1)
                    } else {
                        generatedQueryFields
                    },
            )
        val definitions =
            buildList {
                addAll(inputObjects.map(::inputObjectType))
                addAll(interfaces.map(::interfaceType))
                add(objectType(hashType))
                addAll(objects.map(::objectType))
                addAll(unions.map(::unionType))
                add(objectType(query))
            }
        val sdl =
            AstPrinter.printAst(
                Document.newDocument().definitions(definitions).build(),
            ).trim()
        val deepFields =
            buildMap {
                if (minimumDepth > 0) put("Query", "query0")
                (0 until minimumDepth - 1).forEach { index ->
                    put(objectNames[index], "field0")
                }
            }
        val features =
            schemaFeatures(
                objects = objects,
                query = query,
                interfaces = interfaces,
                unions = unions,
                inputObjects = inputObjects,
            )
        return ArbitrarySchema(
            sdl = sdl,
            objects = objects,
            query = query,
            hashType = hashType,
            interfaces = interfaces,
            unions = unions,
            inputObjects = inputObjects,
            deepFields = deepFields,
            features = features,
        )
    }

    private fun generatedHashField(ownerName: String): FieldDefinitionSpec =
        FieldDefinitionSpec(
            ownerName = ownerName,
            name = GENERATED_HASH_FIELD,
            type =
                OutputTypeSpec(
                    namedType = GENERATED_HASH_TYPE,
                    nullable = false,
                    list = false,
                    elementNullable = false,
                ),
            arguments = emptyList(),
        )

    private fun deepField(
        ownerName: String,
        targetName: String,
        fieldName: String = "field0",
    ): FieldDefinitionSpec =
        FieldDefinitionSpec(
            ownerName = ownerName,
            name = fieldName,
            type =
                OutputTypeSpec(
                    namedType = targetName,
                    nullable = false,
                    list = false,
                    elementNullable = false,
                ),
            arguments = emptyList(),
        )

    private fun field(
        ownerName: String,
        name: String,
        objectTargets: List<String>,
        recursiveObjectTargets: List<String>,
        inputObjectNames: List<String>,
        preferObject: Boolean = false,
        forceScalar: Boolean = false,
    ): FieldDefinitionSpec {
        val useRecursiveTarget =
            recursiveObjectTargets.isNotEmpty() &&
                chance(config[RecursiveOutputEdgeWeight])
        val availableObjectTargets =
            if (useRecursiveTarget) {
                recursiveObjectTargets
            } else {
                objectTargets
            }
        val useObject =
            !forceScalar &&
                availableObjectTargets.isNotEmpty() &&
                (preferObject || chance(0.45))
        val namedType =
            if (useObject) {
                Arb.element(availableObjectTargets).next(random)
            } else {
                Arb.element(ScalarKind.entries).next(random).graphQLName
            }
        val isList = config[ListsEnabled] && chance(config[ListTypeWeight])
        // Every back edge can terminate as null or an empty list.
        val nullable =
            chance(config[NullableTypeWeight]) ||
                (useObject && useRecursiveTarget && !isList)
        val arguments =
            if (config[ArgumentsEnabled] && chance(config[FieldArgumentWeight])) {
                listOf(
                    ArgumentDefinitionSpec(
                        name = "arg",
                        type = inputType(inputObjectNames),
                    ),
                )
            } else {
                emptyList()
            }
        return FieldDefinitionSpec(
            ownerName = ownerName,
            name = name,
            type =
                OutputTypeSpec(
                    namedType = namedType,
                    nullable = nullable,
                    list = isList,
                    elementNullable = chance(config[NullableTypeWeight]),
                ),
            arguments = arguments,
        )
    }

    private fun implementationArgumentDefault(): List<ArgumentDefinitionSpec> =
        if (
            config[ArgumentsEnabled] &&
            chance(config[ImplementationArgumentDefaultWeight])
        ) {
            listOf(
                ArgumentDefinitionSpec(
                    name = "implementationDefault",
                    type = ScalarInputTypeSpec(ScalarKind.INT, nullable = true),
                    defaultValue =
                        IntValue
                            .newIntValue(BigInteger.valueOf(7))
                            .build(),
                ),
            )
        } else {
            emptyList()
        }

    private fun inputObjects(): List<InputObjectDefinitionSpec> {
        if (!config[ArgumentsEnabled] || !config[InputObjectsEnabled]) return emptyList()

        val inputObjectCount = Arb.int(config[InputObjectCount]).next(random)
        val names = (0 until inputObjectCount).map { "InputObject$it" }
        return names.mapIndexed { ownerIndex, name ->
            val fieldCount = Arb.int(config[InputObjectFieldCount]).next(random)
            InputObjectDefinitionSpec(
                name = name,
                fields =
                    (0 until fieldCount).map { fieldIndex ->
                        InputFieldDefinitionSpec(
                            name = "input$fieldIndex",
                            type =
                                inputType(
                                    inputObjectNames = names,
                                    ownerInputObjectIndex = ownerIndex,
                                ),
                        )
                    },
            )
        }
    }

    private fun inputType(
        inputObjectNames: List<String>,
        depth: Int = 0,
        ownerInputObjectIndex: Int? = null,
    ): InputTypeSpec {
        val canRecurse = depth < config[MaxInputTypeDepth]
        if (
            canRecurse &&
            config[ListsEnabled] &&
            chance(config[InputListTypeWeight])
        ) {
            val element =
                inputType(
                    inputObjectNames = inputObjectNames,
                    depth = depth + 1,
                    ownerInputObjectIndex = ownerInputObjectIndex,
                )
            val closesRequiredCycle =
                ownerInputObjectIndex != null &&
                    element
                        .referencedInputObjects()
                        .any { target ->
                            inputObjectNames.indexOf(target) <= ownerInputObjectIndex
                        }
            return ListInputTypeSpec(
                element = element,
                nullable =
                    closesRequiredCycle ||
                        chance(config[NullableTypeWeight]),
            )
        }

        if (
            canRecurse &&
            inputObjectNames.isNotEmpty() &&
            chance(config[InputObjectTypeWeight])
        ) {
            val candidates =
                if (
                    ownerInputObjectIndex != null &&
                    !config[RecursiveInputTypesEnabled]
                ) {
                    inputObjectNames.drop(ownerInputObjectIndex + 1)
                } else {
                    inputObjectNames
            }
            if (candidates.isNotEmpty()) {
                val target = Arb.element(candidates).next(random)
                val targetIndex = inputObjectNames.indexOf(target)
                val closesRequiredCycle =
                    ownerInputObjectIndex != null &&
                        targetIndex <= ownerInputObjectIndex
                return InputObjectInputTypeSpec(
                    name = target,
                    nullable =
                        closesRequiredCycle ||
                            chance(config[NullableTypeWeight]),
                )
            }
        }

        return ScalarInputTypeSpec(
            scalar = Arb.element(ScalarKind.entries).next(random),
            nullable = chance(config[NullableTypeWeight]),
        )
    }

    private fun interfaceType(definition: InterfaceDefinitionSpec): InterfaceTypeDefinition =
        InterfaceTypeDefinition
            .newInterfaceTypeDefinition()
            .name(definition.name)
            .definitions(definition.fields.map(::fieldDefinition))
            .build()

    private fun objectType(definition: ObjectDefinition): ObjectTypeDefinition {
        val fields =
            buildList {
                if (definition.implementsNode) {
                    add(
                        FieldDefinition
                            .newFieldDefinition()
                            .name("id")
                            .type(nonNull(TypeName("ID")))
                            .build(),
                    )
                }
                addAll(definition.fields.map(::fieldDefinition))
            }
        return ObjectTypeDefinition
            .newObjectTypeDefinition()
            .name(definition.name)
            .implementz(
                buildList {
                    if (definition.implementsNode) add(TypeName("Node"))
                    addAll(definition.interfaces.map(::TypeName))
                },
            ).fieldDefinitions(fields)
            .build()
    }

    private fun unionType(definition: UnionDefinitionSpec): UnionTypeDefinition =
        UnionTypeDefinition
            .newUnionTypeDefinition()
            .name(definition.name)
            .memberTypes(definition.members.map(::TypeName))
            .build()

    private fun inputObjectType(
        definition: InputObjectDefinitionSpec,
    ): InputObjectTypeDefinition =
        InputObjectTypeDefinition
            .newInputObjectDefinition()
            .name(definition.name)
            .inputValueDefinitions(
                definition.fields.map { field ->
                    InputValueDefinition
                        .newInputValueDefinition()
                        .name(field.name)
                        .type(inputType(field.type))
                        .build()
                },
            ).build()

    private fun fieldDefinition(field: FieldDefinitionSpec): FieldDefinition {
        val named = TypeName(field.type.namedType)
        val wrapped: Type<*> =
            if (field.type.list) {
                val element: Type<*> =
                    if (field.type.elementNullable) named else nonNull(named)
                ListType(element)
            } else {
                named
            }
        val outputType =
            if (field.type.nullable) wrapped else nonNull(wrapped)
        return FieldDefinition
            .newFieldDefinition()
            .name(field.name)
            .type(outputType)
            .inputValueDefinitions(
                field.arguments.map { argument ->
                    val builder =
                        InputValueDefinition
                        .newInputValueDefinition()
                        .name(argument.name)
                        .type(inputType(argument.type))
                    argument.defaultValue?.let(builder::defaultValue)
                    builder.build()
                },
            ).build()
    }

    private fun inputType(type: InputTypeSpec): Type<*> {
        val unwrapped: Type<*> =
            when (type) {
                is ScalarInputTypeSpec -> TypeName(type.scalar.graphQLName)
                is InputObjectInputTypeSpec -> TypeName(type.name)
                is ListInputTypeSpec -> ListType(inputType(type.element))
            }
        return if (type.nullable) unwrapped else nonNull(unwrapped)
    }

    private fun schemaFeatures(
        objects: List<ObjectDefinition>,
        query: ObjectDefinition,
        interfaces: List<InterfaceDefinitionSpec>,
        unions: List<UnionDefinitionSpec>,
        inputObjects: List<InputObjectDefinitionSpec>,
    ): SchemaFeatures {
        val objectIndices = objects.mapIndexed { index, objectType -> objectType.name to index }.toMap()
        val recursiveOutputFields =
            objects.flatMap { objectType ->
                objectType.fields.filter { field ->
                    val targetIndex = objectIndices[field.type.namedType]
                    targetIndex != null && targetIndex <= objectIndices.getValue(objectType.name)
                }
            }
        val arguments =
            (objects.flatMap(ObjectDefinition::fields) + query.fields)
                .flatMap(FieldDefinitionSpec::arguments)
        return SchemaFeatures(
            hasArguments = arguments.isNotEmpty(),
            hasScalarArguments = arguments.any { it.type is ScalarInputTypeSpec },
            hasListArguments = arguments.any { it.type is ListInputTypeSpec },
            hasInputObjectArguments = arguments.any { it.type is InputObjectInputTypeSpec },
            hasInputObjectListArguments =
                arguments.any { it.type.hasInputObjectInsideList() },
            hasInputObjects = inputObjects.isNotEmpty(),
            hasRecursiveInputTypes = inputObjects.haveTypeCycle(),
            hasOutputLists =
                (objects.flatMap(ObjectDefinition::fields) + query.fields)
                    .any { it.type.list },
            hasRecursiveOutputEdges = recursiveOutputFields.isNotEmpty(),
            hasNullableRecursiveOutputEdges = recursiveOutputFields.any { it.type.nullable },
            hasListRecursiveOutputEdges = recursiveOutputFields.any { it.type.list },
            hasImplementationArgumentDefaults =
                interfaces.any { interfaceType ->
                    interfaceType.members.any { memberName ->
                        val member = objects.single { it.name == memberName }
                        interfaceType.fields.any { interfaceField ->
                            val interfaceArguments =
                                interfaceField.arguments.mapTo(linkedSetOf()) { it.name }
                            member.fields
                                .singleOrNull { it.name == interfaceField.name }
                                ?.arguments
                                .orEmpty()
                                .any { argument ->
                                    argument.name !in interfaceArguments &&
                                        argument.defaultValue != null
                                }
                        }
                    }
                },
            hasInterfaces = interfaces.isNotEmpty(),
            hasUnions = unions.isNotEmpty(),
        )
    }

    private fun List<InputObjectDefinitionSpec>.haveTypeCycle(): Boolean {
        val inputObjectNames = map(InputObjectDefinitionSpec::name).toSet()
        val edges =
            associate { definition ->
                definition.name to
                    definition.fields
                        .flatMap { it.type.referencedInputObjects() }
                        .filter(inputObjectNames::contains)
            }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun reachesCycle(name: String): Boolean {
            if (name in visiting) return true
            if (!visited.add(name)) return false
            visiting += name
            val found = edges.getValue(name).any(::reachesCycle)
            visiting -= name
            return found
        }

        return any { reachesCycle(it.name) }
    }

    private fun InputTypeSpec.referencedInputObjects(): List<String> =
        when (this) {
            is ScalarInputTypeSpec -> emptyList()
            is InputObjectInputTypeSpec -> listOf(name)
            is ListInputTypeSpec -> element.referencedInputObjects()
        }

    private fun InputTypeSpec.hasInputObjectInsideList(
        insideList: Boolean = false,
    ): Boolean =
        when (this) {
            is ScalarInputTypeSpec -> false
            is InputObjectInputTypeSpec -> insideList
            is ListInputTypeSpec -> element.hasInputObjectInsideList(insideList = true)
        }

    private fun nonNull(type: Type<*>): NonNullType =
        NonNullType(type)

    private fun chance(weight: Double): Boolean =
        Arb.double(0.0, 1.0).next(random) < weight

    private fun nonEmptySubset(values: List<String>): Set<String> =
        values
            .filter { chance(0.6) }
            .toSet()
            .ifEmpty { setOf(Arb.element(values).next(random)) }
}
