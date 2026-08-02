package semantics.arbitrary

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.VariableCoordinate
import model.objectOf
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf

/**
 * A registry recipe whose resolver coordinates, output selection sets, and values are independent
 * of any generated query. Calling [world] materializes it against one canonical decoded schema.
 */
class ArbitraryRegistry internal constructor(
    val fieldResolverSites: Set<FieldCoordinate>,
    val nodeResolverSites: Set<String>,
    val outputSelectionSets: Map<String, Set<String>>,
    val objectFragmentSources: Map<FieldCoordinate, String>,
    val variableProviderSources: Map<String, String>,
    internal val fieldValues: Map<FieldCoordinate, ValuePlan>,
    internal val nodeValues: Map<String, ObjectPlan>,
    internal val objectFragments: Map<FieldCoordinate, FragmentPlan>,
    internal val variableProviders: List<VariableProviderPlan>,
) {
    fun world(
        schema: ArbitrarySchema,
        noTransitiveDemand: Boolean = false,
    ): TestWorld {
        val world =
            TestWorld.fromSDL(
            schemaSDL = schema.sdl,
            nodeResolvers = { canonicalSchema ->
                nodeValues.map { (typeName, plan) ->
                    val type = canonicalSchema.type(typeName) as Schema.ObjectType
                    type to
                        nodeResolverOf { id ->
                            plan.materializeObject(canonicalSchema, id)
                        }
                }.toMap()
            },
            fieldResolvers = { canonicalSchema ->
                fieldValues.map { (coordinate, plan) ->
                    val field =
                        canonicalSchema.field(
                            coordinate.typeName,
                            coordinate.fieldName,
                        )
                    val owner = field.containingType as Schema.ObjectType
                    val constant = plan.materialize(canonicalSchema, field.typeExpr)
                    field to
                        fieldResolverOf(
                            objectFragment = objectFragments.getValue(coordinate).materialize(canonicalSchema),
                            function = { _, _ -> constant },
                        )
                }.toMap()
            },
            variableProviders = { canonicalSchema ->
                variableProviders.associate { provider ->
                    val sourceField =
                        schema
                            .objectNamed(provider.owner.typeName)
                            .fields
                            .single { it.name == provider.owner.fieldName }
                    val loweredToNodeBridge =
                        schema.isComposite(sourceField.type.namedType) &&
                            schema
                                .possibleObjects(sourceField.type.namedType)
                                .all { possibleType -> possibleType.name in nodeResolverSites }
                    val canonicalFieldName =
                        if (loweredToNodeBridge) {
                            provider.owner.fieldName + "\$id"
                        } else {
                            provider.owner.fieldName
                        }
                    val field =
                        canonicalSchema.field(
                            provider.owner.typeName,
                            canonicalFieldName,
                        ) as Schema.ObjectField
                    VariableCoordinate.of(
                        field,
                        Value.Variable.of(provider.variableName),
                    ) to provider.selection.materialize(canonicalSchema, field.containingType)
                }
            },
            noTransitiveDemand = noTransitiveDemand,
        )
        objectFragmentSources.values
            .filter(String::isNotEmpty)
            .forEach(world::selectionsFrom)
        variableProviderSources.values.forEach(world::selectionsFrom)
        return world
    }

    override fun toString(): String =
        buildString {
            appendLine("field resolvers:")
            fieldResolverSites.sortedBy(FieldCoordinate::toString).forEach { site ->
                appendLine("  $site OSS=${outputSelectionSets[site.toString()].orEmpty().sorted()}")
                val fragment = objectFragmentSources.getValue(site)
                if (fragment.isNotEmpty()) appendLine(fragment.prependIndent("    "))
            }
            appendLine("variables:")
            variableProviders.sortedBy(VariableProviderPlan::variableName).forEach { provider ->
                appendLine(
                    "  \$${provider.variableName} owner=${provider.owner}",
                )
                appendLine(provider.source().prependIndent("    "))
            }
            appendLine("node resolvers:")
            nodeResolverSites.sorted().forEach { site ->
                appendLine("  $site OSS=${outputSelectionSets[site].orEmpty().sorted()}")
            }
        }.trimEnd()
}

fun ArbitrarySchema.registry(config: Config = Config.default): Arb<ArbitraryRegistry> {
    val generatedSchema = this
    return arbitrary { random ->
        RegistryGenerator(generatedSchema, config, random).generate()
    }
}

private class RegistryGenerator(
    private val schema: ArbitrarySchema,
    private val config: Config,
    private val random: RandomSource,
) {
    private lateinit var fieldSites: Set<FieldCoordinate>
    private lateinit var nodeSites: Set<String>

    fun generate(): ArbitraryRegistry {
        nodeSites =
            if (config[NodeResolversEnabled]) {
                schema.objects
                    .filter(ObjectDefinition::implementsNode)
                    .mapTo(linkedSetOf(), ObjectDefinition::name)
            } else {
                emptySet()
            }
        fieldSites =
            schema.allObjects
                .flatMap(ObjectDefinition::fields)
                .filter { field ->
                    field.ownerName == "Query" ||
                        field.arguments.isNotEmpty() ||
                        chance(config[ExplicitFieldResolverWeight])
                }.mapTo(linkedSetOf(), FieldDefinitionSpec::coordinate)

        val fieldValues =
            fieldSites.associateWith { coordinate ->
                val field = field(coordinate)
                plan(field.type, "${coordinate.typeName}.${coordinate.fieldName}")
            }
        val nodeValues =
            nodeSites.associateWith { typeName ->
                objectPlan(
                    typeName = typeName,
                    path = typeName,
                    nodeResolverRoot = true,
                )
            }
        val ranks = fieldSites.withIndex().associate { (rank, site) -> site to rank }
        val variableProviders = mutableListOf<VariableProviderPlan>()
        val objectFragments =
            fieldSites.associateWith { site ->
                fragmentPlan(site, ranks, variableProviders)
            }
        val oss =
            buildMap {
                fieldValues.forEach { (coordinate, value) ->
                    put(coordinate.toString(), value.selectedPaths())
                }
                nodeValues.forEach { (typeName, value) ->
                    put(typeName, value.selectedPaths())
                }
            }
        return ArbitraryRegistry(
            fieldResolverSites = fieldSites,
            nodeResolverSites = nodeSites,
            outputSelectionSets = oss,
            objectFragmentSources =
                objectFragments.mapValues { (_, fragment) -> fragment.source() },
            variableProviderSources =
                variableProviders.associate { provider ->
                    provider.variableName to provider.source()
                },
            fieldValues = fieldValues,
            nodeValues = nodeValues,
            objectFragments = objectFragments,
            variableProviders = variableProviders,
        )
    }

    private fun fragmentPlan(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        variableProviders: MutableList<VariableProviderPlan>,
    ): FragmentPlan {
        if (
            !config[ResolverFragmentsEnabled] ||
            !chance(config[ResolverFragmentWeight])
        ) {
            return FragmentPlan(consumer.typeName, emptyList())
        }
        val fragment =
            FragmentPlan(
            ownerName = consumer.typeName,
            selections =
                fragmentSelections(
                    ownerName = consumer.typeName,
                    consumerRank = ranks.getValue(consumer),
                    ranks = ranks,
                    depth = 0,
                ),
        )
        return fragment.withVariableProvider(consumer, ranks, variableProviders)
    }

    private fun fragmentSelections(
        ownerName: String,
        consumerRank: Int,
        ranks: Map<FieldCoordinate, Int>,
        depth: Int,
    ): List<FragmentSelectionPlan> {
        if (depth >= config[ResolverFragmentDepth]) {
            return listOf(
                FragmentSelectionPlan(
                    fieldName = "__typename",
                    arguments = emptyMap(),
                    subselections = emptyList(),
                ),
            )
        }
        val candidates =
            schema.objectNamed(ownerName).fields.filter { field ->
                (
                    !schema.isComposite(field.type.namedType) ||
                        schema.objects.any { it.name == field.type.namedType }
                ) &&
                    (
                        field.coordinate !in fieldSites ||
                            ranks.getValue(field.coordinate) < consumerRank
                    )
            }
        if (candidates.isEmpty()) {
            return listOf(
                FragmentSelectionPlan(
                    fieldName = "__typename",
                    arguments = emptyMap(),
                    subselections = emptyList(),
                ),
            )
        }
        val count = Arb.int(1..minOf(2, candidates.size)).next(random)
        return candidates
            .shuffled(random)
            .take(count)
            .map { field ->
                val objectType =
                    schema.objects.singleOrNull { it.name == field.type.namedType }
                FragmentSelectionPlan(
                    fieldName = field.name,
                    arguments =
                        field.arguments.associate { argument ->
                            argument.name to inputLiteral(argument.scalar)
                        },
                    subselections =
                        objectType?.let {
                            fragmentSelections(
                                ownerName = it.name,
                                consumerRank = consumerRank,
                                ranks = ranks,
                                depth = depth + 1,
                            )
                        }.orEmpty(),
                )
            }
    }

    private fun FragmentPlan.withVariableProvider(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        variableProviders: MutableList<VariableProviderPlan>,
    ): FragmentPlan {
        if (
            !config[ResolverVariablesEnabled] ||
            !chance(config[ResolverVariableWeight])
        ) {
            return this
        }
        val candidate =
            argumentOccurrences()
                .shuffled(random)
                .firstNotNullOfOrNull { occurrence ->
                    variableProviderPaths(
                        ownerName = ownerName,
                        scalar = occurrence.argument.scalar,
                        consumerRank = ranks.getValue(consumer),
                        ranks = ranks,
                    ).shuffled(random)
                        .firstOrNull()
                        ?.let { provider -> occurrence to provider }
                } ?: return this
        val variableName = "resolverVar${ranks.getValue(consumer)}"
        variableProviders +=
            VariableProviderPlan(
                owner = consumer,
                variableName = variableName,
                selection = candidate.second,
            )
        return copy(
            selections =
                selections.replaceArgument(
                    selectionPath = candidate.first.selectionPath,
                    argumentName = candidate.first.argument.name,
                    value = VariableInputPlan(variableName),
                ),
        )
    }

    private fun FragmentPlan.argumentOccurrences(): List<ArgumentOccurrence> =
        argumentOccurrences(
            ownerName = ownerName,
            selections = selections,
            selectionPath = emptyList(),
        )

    private fun argumentOccurrences(
        ownerName: String,
        selections: List<FragmentSelectionPlan>,
        selectionPath: List<Int>,
    ): List<ArgumentOccurrence> =
        selections.flatMapIndexed { index, selection ->
            val field =
                schema
                    .objectNamed(ownerName)
                    .fields
                    .singleOrNull { it.name == selection.fieldName }
                    ?: return@flatMapIndexed emptyList()
            val path = selectionPath + index
            field.arguments.map { argument ->
                ArgumentOccurrence(path, argument)
            } +
                (
                    schema.objects
                        .singleOrNull { it.name == field.type.namedType }
                        ?.let { nestedOwner ->
                            argumentOccurrences(
                                ownerName = nestedOwner.name,
                                selections = selection.subselections,
                                selectionPath = path,
                            )
                        }.orEmpty()
                )
        }

    private fun variableProviderPaths(
        ownerName: String,
        scalar: ScalarKind,
        consumerRank: Int,
        ranks: Map<FieldCoordinate, Int>,
    ): List<FragmentSelectionPlan> =
        schema
            .objectNamed(ownerName)
            .fields
            .filter { field ->
                field.arguments.isEmpty() &&
                    !field.type.nullable &&
                    !field.type.list &&
                    (
                        field.coordinate !in fieldSites ||
                            ranks.getValue(field.coordinate) < consumerRank
                    )
            }.flatMap { field ->
                when {
                    field.type.namedType == scalar.graphQLName ->
                        listOf(
                            FragmentSelectionPlan(
                                fieldName = field.name,
                                arguments = emptyMap(),
                                subselections = emptyList(),
                            ),
                        )

                    schema.objects.any { it.name == field.type.namedType } ->
                        variableProviderPaths(
                            ownerName = field.type.namedType,
                            scalar = scalar,
                            consumerRank = consumerRank,
                            ranks = ranks,
                        ).map { nested ->
                            FragmentSelectionPlan(
                                fieldName = field.name,
                                arguments = emptyMap(),
                                subselections = listOf(nested),
                            )
                        }

                    else -> emptyList()
                }
            }

    private fun inputLiteral(scalar: ScalarKind): InputLiteralPlan {
        val salt = Arb.int(0..10_000).next(random)
        val value: Any =
            when (scalar) {
                ScalarKind.BOOLEAN -> salt % 2 == 0
                ScalarKind.FLOAT -> salt.toDouble() + 0.5
                ScalarKind.ID -> "id-$salt"
                ScalarKind.INT -> salt
                ScalarKind.STRING -> "value-$salt"
            }
        return InputLiteralPlan(scalar, value)
    }

    private fun plan(
        type: OutputTypeSpec,
        path: String,
        allowNullOrError: Boolean = true,
    ): ValuePlan {
        if (allowNullOrError && chance(config[ErrorValueWeight])) return ErrorPlan
        if (
            allowNullOrError &&
            type.nullable &&
            chance(config[NullValueWeight])
        ) {
            return NullPlan
        }
        if (type.list) {
            val size = Arb.int(config[ListValueSize]).next(random)
            val elementType =
                type.copy(
                    nullable = type.elementNullable,
                    list = false,
                )
            return ListPlan(
                (0 until size).map { index ->
                    plan(elementType, "$path[$index]")
                },
            )
        }
        return ScalarKind.entries
            .singleOrNull { it.graphQLName == type.namedType }
            ?.let { scalarPlan(it, path) }
            ?: objectPlan(
                typeName =
                    Arb.element(schema.possibleObjects(type.namedType))
                        .next(random)
                        .name,
                path = path,
            )
    }

    private fun objectPlan(
        typeName: String,
        path: String,
        nodeResolverRoot: Boolean = false,
    ): ObjectPlan {
        val type = schema.objectNamed(typeName)
        val isNodeBoundary = typeName in nodeSites && !nodeResolverRoot
        val fields =
            buildMap {
                if (type.implementsNode) {
                    put(
                        FieldCoordinate(typeName, "id"),
                        if (nodeResolverRoot) {
                            InputIdPlan
                        } else {
                            ScalarPlan(ScalarKind.ID, "$path-id")
                        },
                    )
                }
                if (!isNodeBoundary) {
                    type.fields
                        .filter { it.arguments.isEmpty() }
                        .filter { it.coordinate !in fieldSites }
                        .forEach { field ->
                            put(
                                field.coordinate,
                                plan(field.type, "$path.${field.name}"),
                            )
                        }
                }
            }
        return ObjectPlan(typeName, fields)
    }

    private fun scalarPlan(
        scalar: ScalarKind,
        path: String,
    ): ScalarPlan {
        val salt = Arb.int(0..10_000).next(random)
        val value: Any =
            when (scalar) {
                ScalarKind.BOOLEAN -> salt % 2 == 0
                ScalarKind.FLOAT -> salt.toDouble() / 10.0
                ScalarKind.ID -> "$path-$salt"
                ScalarKind.INT -> salt
                ScalarKind.STRING -> "$path-$salt"
            }
        return ScalarPlan(scalar, value)
    }

    private fun field(coordinate: FieldCoordinate): FieldDefinitionSpec =
        schema
            .objectNamed(coordinate.typeName)
            .fields
            .single { it.name == coordinate.fieldName }

    private fun chance(weight: Double): Boolean =
        Arb.double(0.0, 1.0).next(random) < weight

    private fun <T> List<T>.shuffled(random: RandomSource): List<T> {
        val remaining = toMutableList()
        val result = mutableListOf<T>()
        while (remaining.isNotEmpty()) {
            result += remaining.removeAt(Arb.int(0 until remaining.size).next(random))
        }
        return result
    }
}

internal data class FragmentPlan(
    val ownerName: String,
    val selections: List<FragmentSelectionPlan>,
) {
    fun materialize(schema: Schema): Fragment {
        val owner = schema.type(ownerName) as Schema.ObjectType
        return Fragment.of(
            nominalType = owner,
            subselections = selections.materialize(schema, owner),
        )
    }

    fun source(): String =
        if (selections.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine("fragment Generated on $ownerName {")
                selections.forEach { append(it.source("  ")) }
                append("}")
            }
        }
}

internal data class FragmentSelectionPlan(
    val fieldName: String,
    val arguments: Map<String, InputValuePlan>,
    val subselections: List<FragmentSelectionPlan>,
) {
    fun source(indent: String): String =
        buildString {
            append(indent)
            append(fieldName)
            if (arguments.isNotEmpty()) {
                append(
                    arguments.entries.joinToString(prefix = "(", postfix = ")") { (name, value) ->
                        "$name: ${value.source()}"
                    },
                )
            }
            if (subselections.isEmpty()) {
                appendLine()
            } else {
                appendLine(" {")
                subselections.forEach { append(it.source("$indent  ")) }
                appendLine("$indent}")
            }
    }

    fun materialize(
        schema: Schema,
        owner: Schema.ObjectType,
    ): Selection =
        listOf(this).materialize(schema, owner).single()
}

internal sealed interface InputValuePlan {
    fun materialize(): Any?

    fun source(): String
}

internal data class InputLiteralPlan(
    val scalar: ScalarKind,
    val value: Any,
) : InputValuePlan {
    override fun materialize(): Any = value

    override fun source(): String =
        when (scalar) {
            ScalarKind.BOOLEAN, ScalarKind.FLOAT, ScalarKind.INT -> value.toString()
            ScalarKind.ID, ScalarKind.STRING ->
                "\"" + (value as String).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}

internal data class VariableInputPlan(
    val variableName: String,
) : InputValuePlan {
    override fun materialize(): Value.Variable = Value.Variable.of(variableName)

    override fun source(): String = "\$$variableName"
}

internal data class VariableProviderPlan(
    val owner: FieldCoordinate,
    val variableName: String,
    val selection: FragmentSelectionPlan,
) {
    fun source(): String =
        FragmentPlan(owner.typeName, listOf(selection)).source()
}

private data class ArgumentOccurrence(
    val selectionPath: List<Int>,
    val argument: ArgumentDefinitionSpec,
)

private fun List<FragmentSelectionPlan>.replaceArgument(
    selectionPath: List<Int>,
    argumentName: String,
    value: InputValuePlan,
): List<FragmentSelectionPlan> {
    val selectedIndex = selectionPath.first()
    return mapIndexed { index, selection ->
        when {
            index != selectedIndex -> selection
            selectionPath.size == 1 ->
                selection.copy(arguments = selection.arguments + (argumentName to value))
            else ->
                selection.copy(
                    subselections =
                        selection.subselections.replaceArgument(
                            selectionPath = selectionPath.drop(1),
                            argumentName = argumentName,
                            value = value,
                        ),
                )
        }
    }
}

private fun List<FragmentSelectionPlan>.materialize(
    schema: Schema,
    owner: Schema.ObjectType,
): SelectionForest =
    selectionForestOf(
        *map { plan ->
            val field = schema.field(owner.typeName, plan.fieldName)
            val resultType = field.typeExpr.baseType
            val nestedOwner = resultType as? Schema.ObjectType
            Selection.of(
                key =
                    Value.Key.of(
                        field,
                        plan.arguments.mapValues { (_, value) -> value.materialize() },
                    ),
                nominalType = owner,
                possibleTypes = setOf(owner),
                subselections =
                    if (nestedOwner == null) {
                        selectionForestOf()
                    } else {
                        plan.subselections.materialize(schema, nestedOwner)
                    },
            )
        }.toTypedArray(),
    )

internal sealed interface ValuePlan {
    fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID? = null,
    ): Value.Output?

    fun selectedPaths(prefix: String = ""): Set<String>
}

internal data object NullPlan : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
    ): Value.Output? = null

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data object ErrorPlan : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
    ): Value.Output = Value.Error

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data object InputIdPlan : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
    ): Value.Output = requireNotNull(inputId)

    override fun selectedPaths(prefix: String): Set<String> = setOf(prefix)
}

internal data class ScalarPlan(
    val scalar: ScalarKind,
    val value: Any,
) : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
    ): Value.Simple =
        when (scalar) {
            ScalarKind.BOOLEAN -> Value.Boolean.of(value as Boolean)
            ScalarKind.FLOAT -> Value.Float.of(value as Double)
            ScalarKind.ID -> Value.ID.of(value as String)
            ScalarKind.INT -> Value.Int.of(value as Int)
            ScalarKind.STRING -> Value.String.of(value as String)
        }

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data class ListPlan(
    val elements: List<ValuePlan>,
) : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
    ): Value.OutputList {
        require(typeExpr is TypeExpr.List)
        return Value.OutputList.of(
            typeExpr = typeExpr.elementType,
            values = elements.map { it.materialize(schema, typeExpr.elementType, inputId) },
        )
    }

    override fun selectedPaths(prefix: String): Set<String> =
        elements.flatMapIndexed { index, element ->
            element.selectedPaths("$prefix[$index]")
        }.toSet()
}

internal data class ObjectPlan(
    val typeName: String,
    val fields: Map<FieldCoordinate, ValuePlan>,
) : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
    ): Value.Object =
        materializeObject(schema, inputId)

    fun materializeObject(
        schema: Schema,
        inputId: Value.ID?,
    ): Value.Object =
        schema.objectOf(typeName) {
            fields.forEach { (coordinate, plan) ->
                require(coordinate.typeName == typeName)
                val outputField = schema.field(typeName, coordinate.fieldName)
                field(coordinate.fieldName) setTo
                    plan.materialize(schema, outputField.typeExpr, inputId)
            }
        }

    override fun selectedPaths(prefix: String): Set<String> =
        fields.flatMap { (coordinate, plan) ->
            val path =
                if (prefix.isEmpty()) coordinate.fieldName else "$prefix.${coordinate.fieldName}"
            setOf(path) + plan.selectedPaths(path)
        }.toSet()
}
