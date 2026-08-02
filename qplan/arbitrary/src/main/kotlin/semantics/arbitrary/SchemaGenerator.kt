package semantics.arbitrary

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.FieldDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.InputValueDefinition
import graphql.language.UnionTypeDefinition
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next

class ArbitrarySchema internal constructor(
    val sdl: String,
    internal val objects: List<ObjectDefinition>,
    internal val query: ObjectDefinition,
    internal val interfaces: List<InterfaceDefinitionSpec>,
    internal val unions: List<UnionDefinitionSpec>,
    internal val deepFields: Map<String, String>,
) {
    internal val allObjects: List<ObjectDefinition>
        get() = listOf(query) + objects

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
    val scalar: ScalarKind,
)

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
        val nodeNames =
            if (config[InterfacesEnabled] && config[NodeResolversEnabled]) {
                objectNames.filter { chance(0.35) }.toSet()
            } else {
                emptySet()
            }
        val baseObjects =
            objectNames.mapIndexed { index, name ->
                val laterObjects = objectNames.drop(index + 1)
                val fieldCount = Arb.int(config[ObjectFieldCount]).next(random)
                val generatedFields =
                    (0 until fieldCount).map { fieldIndex ->
                        field(
                            ownerName = name,
                            name = "field$fieldIndex",
                            objectTargets = laterObjects,
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
        val objects =
            baseObjects.map { objectType ->
                if (objectType.name !in generatedInterface?.members.orEmpty()) {
                    objectType
                } else {
                    objectType.copy(
                        interfaces = objectType.interfaces + generatedInterface!!.name,
                        fields =
                            objectType.fields +
                                generatedInterface.fields.map { field ->
                                    field.copy(ownerName = objectType.name)
                                },
                    )
                }
            }
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
                    preferObject = true,
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
                addAll(interfaces.map(::interfaceType))
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
        return ArbitrarySchema(sdl, objects, query, interfaces, unions, deepFields)
    }

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
        preferObject: Boolean = false,
    ): FieldDefinitionSpec {
        val useObject =
            objectTargets.isNotEmpty() &&
                (preferObject || chance(0.45))
        val namedType =
            if (useObject) {
                Arb.element(objectTargets).next(random)
            } else {
                Arb.element(ScalarKind.entries).next(random).graphQLName
            }
        val isList = config[ListsEnabled] && chance(config[ListTypeWeight])
        val arguments =
            if (config[ArgumentsEnabled] && chance(config[FieldArgumentWeight])) {
                listOf(
                    ArgumentDefinitionSpec(
                        name = "arg",
                        scalar = Arb.element(ScalarKind.entries).next(random),
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
                    nullable = chance(config[NullableTypeWeight]),
                    list = isList,
                    elementNullable = chance(config[NullableTypeWeight]),
                ),
            arguments = arguments,
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
                    InputValueDefinition
                        .newInputValueDefinition()
                        .name(argument.name)
                        .type(nonNull(TypeName(argument.scalar.graphQLName)))
                        .build()
                },
            ).build()
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
