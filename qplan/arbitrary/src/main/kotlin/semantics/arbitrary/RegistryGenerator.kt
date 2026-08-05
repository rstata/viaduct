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
import model.TypeExpr
import model.Value
import model.VariableCoordinate
import model.fragmentFrom
import model.objectOf
import model.toSelectionForest
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf

enum class ResolverProgramKind {
    CONSTANT,
    INPUT_SENSITIVE,
    ARGUMENT_SENSITIVE,
    INPUT_AND_ARGUMENT_SENSITIVE,
}

enum class ResolverProgramMutation {
    NONE,
    CACHE_FIRST_INPUT,
    CACHE_FIRST_ARGUMENTS,
    DUPLICATE_APPLICATION,
    APPLICATION_ORDINAL_CONTAMINATION,
}

data class RegistryFeatures(
    val inputSensitiveResolvers: Int,
    val argumentSensitiveResolvers: Int,
    val inputAndArgumentSensitiveResolvers: Int,
    val resolverErrorArgumentCount: Int,
    val variableCount: Int,
    val maximumVariablesPerOwner: Int,
    val hasNestedInputVariable: Boolean,
    val hasListVariable: Boolean,
    val hasNullableProvider: Boolean,
    val hasAbstractProviderPath: Boolean,
    val hasAbstractResolverFragment: Boolean,
)

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
    internal val resolverPrograms: Map<FieldCoordinate, ResolverProgramKind>,
    val features: RegistryFeatures,
) {
    private val applicationLog = ResolutionApplicationLog()

    fun clearResolutionWitness() {
        applicationLog.clear()
    }

    fun resolutionWitness(): ResolutionWitness = applicationLog.snapshot()

    fun resolverProgram(sourceField: FieldCoordinate): ResolverProgramKind =
        resolverPrograms.getValue(sourceField)

    fun world(
        schema: ArbitrarySchema,
        resolverProgramMutation: ResolverProgramMutation = ResolverProgramMutation.NONE,
    ): TestWorld {
        val firstInputs = mutableMapOf<FieldCoordinate, Value.Object>()
        val firstArguments = mutableMapOf<FieldCoordinate, Value.Arguments>()
        val applicationOrdinals = mutableMapOf<FieldCoordinate, Int>()
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
                    val program = resolverPrograms.getValue(coordinate)
                    field to
                        fieldResolverOf(
                            objectFragment = objectFragments.getValue(coordinate).materialize(canonicalSchema),
                            function = { input, arguments ->
                                field.arguments.fields.values
                                    .filter { argument ->
                                        argument.defaultValue is Value.Default.Present
                                    }.forEach { argument ->
                                        require(argument.name in arguments.fieldValues) {
                                            "Concrete default ${coordinate.typeName}/" +
                                                "${coordinate.fieldName}(${argument.name}) " +
                                                "was not applied"
                                        }
                                    }
                                applicationLog.record(coordinate, arguments, input)
                                if (
                                    resolverProgramMutation ==
                                    ResolverProgramMutation.DUPLICATE_APPLICATION
                                ) {
                                    applicationLog.record(coordinate, arguments, input)
                                }
                                val effectiveInput =
                                    if (
                                        resolverProgramMutation ==
                                        ResolverProgramMutation.CACHE_FIRST_INPUT
                                    ) {
                                        firstInputs.getOrPut(coordinate) { input }
                                    } else {
                                        input
                                    }
                                val effectiveArguments =
                                    if (
                                        resolverProgramMutation ==
                                        ResolverProgramMutation.CACHE_FIRST_ARGUMENTS
                                    ) {
                                        firstArguments.getOrPut(coordinate) { arguments }
                                    } else {
                                        arguments
                                    }
                                val ordinal =
                                    applicationOrdinals.getOrDefault(coordinate, 0).also {
                                        applicationOrdinals[coordinate] = it + 1
                                    }
                                when (program) {
                                    ResolverProgramKind.CONSTANT -> constant
                                    ResolverProgramKind.INPUT_SENSITIVE,
                                    ResolverProgramKind.ARGUMENT_SENSITIVE,
                                    ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE,
                                    ->
                                        sensitiveScalar(
                                            scalar =
                                                ScalarKind.entries.single {
                                                    it.graphQLName ==
                                                        field.typeExpr.baseType.typeName
                                                },
                                            input = effectiveInput,
                                            arguments = effectiveArguments,
                                            applicationOrdinal =
                                                ordinal.takeIf {
                                                    resolverProgramMutation ==
                                                        ResolverProgramMutation
                                                            .APPLICATION_ORDINAL_CONTAMINATION
                                                },
                                        )
                                }
                            },
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
                }.map(FieldDefinitionSpec::coordinate)
                .shuffled(random)
                .toCollection(linkedSetOf())

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
        val resolverPrograms =
            fieldSites.associateWith { site ->
                val field = field(site)
                val scalarOutput =
                    !field.type.list &&
                        ScalarKind.entries.any { it.graphQLName == field.type.namedType }
                val inputSensitive =
                    scalarOutput && objectFragments.getValue(site).selections.isNotEmpty()
                val argumentSensitive = scalarOutput && field.arguments.isNotEmpty()
                when {
                    inputSensitive && argumentSensitive ->
                        ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE
                    inputSensitive -> ResolverProgramKind.INPUT_SENSITIVE
                    argumentSensitive -> ResolverProgramKind.ARGUMENT_SENSITIVE
                    else -> ResolverProgramKind.CONSTANT
                }
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
            resolverPrograms = resolverPrograms,
            features =
                RegistryFeatures(
                    inputSensitiveResolvers =
                        resolverPrograms.count {
                            it.value == ResolverProgramKind.INPUT_SENSITIVE ||
                                it.value == ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE
                        },
                    argumentSensitiveResolvers =
                        resolverPrograms.count {
                            it.value == ResolverProgramKind.ARGUMENT_SENSITIVE ||
                                it.value == ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE
                        },
                    inputAndArgumentSensitiveResolvers =
                        resolverPrograms.count {
                            it.value == ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE
                        },
                    resolverErrorArgumentCount =
                        objectFragments.values.sumOf(FragmentPlan::errorArgumentCount),
                    variableCount = variableProviders.size,
                    maximumVariablesPerOwner =
                        variableProviders
                            .groupingBy(VariableProviderPlan::owner)
                            .eachCount()
                            .values
                            .maxOrNull()
                            ?: 0,
                    hasNestedInputVariable =
                        variableProviders.any(VariableProviderPlan::nestedInput),
                    hasListVariable = variableProviders.any(VariableProviderPlan::listValue),
                    hasNullableProvider = variableProviders.any(VariableProviderPlan::nullable),
                    hasAbstractProviderPath =
                        variableProviders.any(VariableProviderPlan::abstractPath),
                    hasAbstractResolverFragment =
                        objectFragments.any { (_, fragment) ->
                            fragment.selections.any { selection ->
                                selection.hasAbstractPath(fragment.ownerName)
                            }
                        },
                ),
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
        val directFields = schema.fieldsOn(ownerName)
        if (directFields.isEmpty() && schema.isComposite(ownerName)) {
            return schema
                .possibleObjects(ownerName)
                .shuffled(random)
                .take(2)
                .flatMap { concrete ->
                    fragmentSelections(
                        ownerName = concrete.name,
                        consumerRank = consumerRank,
                        ranks = ranks,
                        depth = depth,
                    ).take(1)
                        .map { selection -> selection.copy(typeCondition = concrete.name) }
                }.ifEmpty {
                    listOf(
                        FragmentSelectionPlan(
                            fieldName = "__typename",
                            arguments = emptyMap(),
                            subselections = emptyList(),
                        ),
                    )
                }
        }
        val candidates =
            directFields.filter { field ->
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
                FragmentSelectionPlan(
                    fieldName = field.name,
                    arguments =
                        field.arguments.associate { argument ->
                            val literal = inputLiteral(argument.type)
                            argument.name to
                                if (chance(config[ResolverArgumentErrorWeight])) {
                                    ErrorInputPlan(literal)
                                } else {
                                    literal
                                }
                        },
                    subselections =
                        field.type.namedType
                            .takeIf(schema::isComposite)
                            ?.let {
                            fragmentSelections(
                                ownerName = it,
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
        val variableCount = Arb.int(config[ResolverVariableCount]).next(random)
        return (0 until variableCount).fold(this) { fragment, variableIndex ->
            val candidate =
                fragment.argumentOccurrences()
                .shuffled(random)
                .firstNotNullOfOrNull { occurrence ->
                    val useBranch =
                        fragment.selections[occurrence.selectionPath.first()]
                    val useRank =
                        structuralBranchRank(
                            ownerName = ownerName,
                            selection = useBranch,
                            ranks = ranks,
                        )
                    occurrence.target
                        ?.let { target ->
                            variableProviderPaths(
                                ownerName = ownerName,
                                target = target,
                                consumerRank = ranks.getValue(consumer),
                                ranks = ranks,
                            )
                        }.orEmpty()
                        .shuffled(random)
                        .firstOrNull { provider ->
                            structuralBranchRank(ownerName, provider, ranks) < useRank
                        }
                        ?.let { provider -> occurrence to provider }
                } ?: return@fold fragment
            val variableName = "resolverVar${ranks.getValue(consumer)}_$variableIndex"
            variableProviders +=
                VariableProviderPlan(
                    owner = consumer,
                    variableName = variableName,
                    selection = candidate.second,
                    nestedInput = candidate.first.valuePath.isNotEmpty(),
                    listValue = candidate.first.target is ListVariableTarget,
                    nullable = candidate.first.target?.nullable == true,
                    abstractPath = candidate.second.hasAbstractPath(ownerName),
                )
            fragment.copy(
                selections =
                    (fragment.selections + candidate.second).replaceArgument(
                        selectionPath = candidate.first.selectionPath,
                        argumentName = candidate.first.argument.name,
                        valuePath = candidate.first.valuePath,
                        value = VariableInputPlan(variableName),
                    ),
            )
        }
    }

    /**
     * Passive branches precede every resolver branch. Registered branches use the same rank that
     * makes ordinary generated resolver demand acyclic.
     */
    private fun structuralBranchRank(
        ownerName: String,
        selection: FragmentSelectionPlan,
        ranks: Map<FieldCoordinate, Int>,
    ): Int {
        val field =
            schema
                .fieldsOn(ownerName)
                .single { candidate -> candidate.name == selection.fieldName }
        return ranks[field.coordinate] ?: -1
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
            val selectionOwner = selection.typeCondition ?: ownerName
            val field =
                schema
                    .fieldsOn(selectionOwner)
                    .singleOrNull { it.name == selection.fieldName }
                    ?: return@flatMapIndexed emptyList()
            val path = selectionPath + index
            field.arguments.flatMap { argument ->
                selection.arguments
                    .getValue(argument.name)
                    .variableOccurrences()
                    .map { valueOccurrence ->
                        ArgumentOccurrence(
                            selectionPath = path,
                            argument = argument,
                            valuePath = valueOccurrence.path,
                            target = valueOccurrence.target,
                        )
                    }
            } +
                (
                    field.type.namedType
                        .takeIf(schema::isComposite)
                        ?.let { nestedOwner ->
                            argumentOccurrences(
                                ownerName = nestedOwner,
                                selections = selection.subselections,
                                selectionPath = path,
                            )
                        }.orEmpty()
                )
        }

    private fun variableProviderPaths(
        ownerName: String,
        target: VariableTarget,
        consumerRank: Int,
        ranks: Map<FieldCoordinate, Int>,
        visitedTypes: Set<String> = emptySet(),
    ): List<FragmentSelectionPlan> =
        if (ownerName in visitedTypes) {
            emptyList()
        } else {
        schema
            .fieldsOn(ownerName)
            .filter { field ->
                field.arguments.isEmpty() &&
                    (!field.type.nullable || target.nullable) &&
                    (
                        field.coordinate !in fieldSites ||
                            ranks.getValue(field.coordinate) < consumerRank
                    )
            }.flatMap { field ->
                when {
                    target.matches(field.type) ->
                        listOf(
                            FragmentSelectionPlan(
                                fieldName = field.name,
                                arguments = emptyMap(),
                                subselections = emptyList(),
                            ),
                        )

                    !field.type.list && schema.isComposite(field.type.namedType) ->
                        variableProviderPaths(
                            ownerName = field.type.namedType,
                            target = target,
                            consumerRank = consumerRank,
                            ranks = ranks,
                            visitedTypes = visitedTypes + ownerName,
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
        }

    private fun FragmentSelectionPlan.hasAbstractPath(ownerName: String): Boolean {
        val selectionOwner = typeCondition ?: ownerName
        if (fieldName == "__typename") {
            return typeCondition != null ||
                (
                    schema.isComposite(ownerName) &&
                        schema.possibleObjects(ownerName).size > 1
                )
        }
        val field = schema.fieldsOn(selectionOwner).single { it.name == fieldName }
        if (schema.isComposite(field.type.namedType)) {
            if (typeCondition != null || schema.possibleObjects(field.type.namedType).size > 1) {
                return true
            }
            return subselections.any { it.hasAbstractPath(field.type.namedType) }
        }
        return false
    }

    private fun inputLiteral(
        type: InputTypeSpec,
        objectPath: Set<String> = emptySet(),
    ): InputValuePlan {
        if (type is ListInputTypeSpec && type.element.reachesAny(objectPath)) {
            return ListInputPlan(type, emptyList())
        }
        if (
            type is InputObjectInputTypeSpec &&
            type.nullable &&
            type.reachesAny(objectPath)
        ) {
            return NullInputPlan(type)
        }
        if (type.nullable && chance(config[NullValueWeight])) {
            return NullInputPlan(type)
        }
        return when (type) {
            is ScalarInputTypeSpec -> scalarInputLiteral(type)
            is ListInputTypeSpec ->
                ListInputPlan(
                    type = type,
                    elements =
                        List(Arb.int(config[ListValueSize]).next(random)) {
                            inputLiteral(type.element, objectPath)
                        },
                )
            is InputObjectInputTypeSpec -> {
                if (type.name in objectPath) {
                    require(type.nullable) {
                        "Recursive input-object edge ${type.name} must be nullable"
                    }
                    NullInputPlan(type)
                } else {
                    val definition =
                        schema.inputObjects.single { candidate -> candidate.name == type.name }
                    ObjectInputPlan(
                        type = type,
                        fields =
                            definition.fields.associate { field ->
                                field.name to inputLiteral(field.type, objectPath + type.name)
                            },
                    )
                }
            }
        }
    }

    private fun InputTypeSpec.reachesAny(
        targets: Set<String>,
        visited: Set<String> = emptySet(),
    ): Boolean =
        when (this) {
            is ScalarInputTypeSpec -> false
            is ListInputTypeSpec -> element.reachesAny(targets, visited)
            is InputObjectInputTypeSpec -> {
                name in targets ||
                    (
                        name !in visited &&
                            schema.inputObjects
                                .single { it.name == name }
                                .fields
                                .any { field -> field.type.reachesAny(targets, visited + name) }
                    )
            }
        }

    private fun scalarInputLiteral(type: ScalarInputTypeSpec): InputLiteralPlan {
        val salt = Arb.int(0..10_000).next(random)
        val value: Any =
            when (type.scalar) {
                ScalarKind.BOOLEAN -> salt % 2 == 0
                ScalarKind.FLOAT -> salt.toDouble() + 0.5
                ScalarKind.ID -> "id-$salt"
                ScalarKind.INT -> salt
                ScalarKind.STRING -> "value-$salt"
            }
        return InputLiteralPlan(type, value)
    }

    private fun plan(
        type: OutputTypeSpec,
        path: String,
        allowNullOrError: Boolean = true,
        objectPath: Set<String> = emptySet(),
    ): ValuePlan {
        val closesRecursivePath =
            schema.isComposite(type.namedType) &&
                schema.possibleObjects(type.namedType).any { it.name in objectPath }
        if (closesRecursivePath && type.list) return ListPlan(emptyList())
        if (closesRecursivePath) {
            return if (type.nullable) NullPlan else ErrorPlan
        }
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
                    plan(elementType, "$path[$index]", objectPath = objectPath)
                },
            )
        }
        return ScalarKind.entries
            .singleOrNull { it.graphQLName == type.namedType }
            ?.let { scalarPlan(it, path) }
            ?: Arb.element(schema.possibleObjects(type.namedType))
                .next(random)
                .name
                .let { typeName ->
                    if (typeName in objectPath) {
                        require(type.nullable) {
                            "Recursive output edge to $typeName must be nullable or a list"
                        }
                        NullPlan
                    } else {
                        objectPlan(
                            typeName = typeName,
                            path = path,
                            objectPath = objectPath,
                        )
                    }
                }
    }

    private fun objectPlan(
        typeName: String,
        path: String,
        nodeResolverRoot: Boolean = false,
        objectPath: Set<String> = emptySet(),
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
                                plan(
                                    field.type,
                                    "$path.${field.name}",
                                    objectPath = objectPath + typeName,
                                ),
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
    fun materialize(schema: Schema): Fragment =
        if (selections.isEmpty()) {
            Fragment.of(
                nominalType = schema.type(ownerName) as Schema.ObjectType,
                subselections = model.selectionForestOf(),
            )
        } else {
            val parsed = schema.fragmentFrom(source())
            Fragment.of(
                nominalType = parsed.nominalType,
                subselections = selections.materialize(schema, parsed.subselections),
            )
        }

    fun errorArgumentCount(): Int =
        selections.sumOf(FragmentSelectionPlan::errorArgumentCount)

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
    val typeCondition: String? = null,
) {
    fun source(indent: String): String =
        buildString {
            if (typeCondition != null) {
                appendLine("$indent... on $typeCondition {")
            }
            val fieldIndent = if (typeCondition == null) indent else "$indent  "
            append(fieldIndent)
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
                subselections.forEach { append(it.source("$fieldIndent  ")) }
                appendLine("$fieldIndent}")
            }
            if (typeCondition != null) {
                appendLine("$indent}")
            }
        }

    fun materialize(
        schema: Schema,
        owner: Schema.ObjectType,
    ): Selection =
        FragmentPlan(owner.typeName, listOf(this))
            .materialize(schema)
            .subselections
            .single()

    fun errorArgumentCount(): Int =
        arguments.values.count { value -> value is ErrorInputPlan } +
            subselections.sumOf(FragmentSelectionPlan::errorArgumentCount)
}

private fun List<FragmentSelectionPlan>.materialize(
    schema: Schema,
    parsedSelections: model.SelectionForest,
): model.SelectionForest {
    val parsed =
        buildList {
            parsedSelections.forEach(::add)
        }
    require(size == parsed.size) {
        "Parsed resolver fragment did not preserve planned selection occurrences"
    }
    return zip(parsed)
        .map { (plan, selection) ->
            val arguments =
                selection.key.arguments.fieldValues.mapValues { (name, value) ->
                    if (plan.arguments[name] is ErrorInputPlan) Value.Error else value
                }
            Selection.of(
                key =
                    Value.Key.of(
                        selection.key.field,
                        Value.Arguments.of(selection.key.field, arguments),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = plan.subselections.materialize(schema, selection.subselections),
            )
        }.toSelectionForest()
}

internal sealed interface InputValuePlan {
    fun source(): String

    fun variableTarget(): VariableTarget?
}

internal data class InputLiteralPlan(
    val type: ScalarInputTypeSpec,
    val value: Any,
) : InputValuePlan {
    override fun source(): String =
        when (type.scalar) {
            ScalarKind.BOOLEAN, ScalarKind.FLOAT, ScalarKind.INT -> value.toString()
            ScalarKind.ID, ScalarKind.STRING ->
                "\"" + (value as String).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

    override fun variableTarget(): VariableTarget =
        ScalarVariableTarget(type.scalar, type.nullable)
}

internal data class ListInputPlan(
    val type: ListInputTypeSpec,
    val elements: List<InputValuePlan>,
) : InputValuePlan {
    override fun source(): String =
        elements.joinToString(prefix = "[", postfix = "]") { it.source() }

    override fun variableTarget(): VariableTarget? {
        if (elements.any(InputValuePlan::containsVariable)) return null
        val element = type.element as? ScalarInputTypeSpec ?: return null
        return ListVariableTarget(
            scalar = element.scalar,
            nullable = type.nullable,
            elementNullable = element.nullable,
        )
    }
}

internal data class ObjectInputPlan(
    val type: InputObjectInputTypeSpec,
    val fields: Map<String, InputValuePlan>,
) : InputValuePlan {
    override fun source(): String =
        fields.entries.joinToString(prefix = "{", postfix = "}") { (name, value) ->
            "$name: ${value.source()}"
        }

    override fun variableTarget(): VariableTarget? = null
}

internal data class NullInputPlan(
    val type: InputTypeSpec,
) : InputValuePlan {
    override fun source(): String = "null"

    override fun variableTarget(): VariableTarget? = null
}

internal data class VariableInputPlan(
    val variableName: String,
) : InputValuePlan {
    override fun source(): String = "\$$variableName"

    override fun variableTarget(): VariableTarget? = null
}

internal data class ErrorInputPlan(
    val placeholder: InputValuePlan,
) : InputValuePlan {
    override fun source(): String = placeholder.source()

    override fun variableTarget(): VariableTarget? = null
}

internal sealed interface VariableTarget {
    val nullable: Boolean

    fun matches(type: OutputTypeSpec): Boolean
}

internal data class ScalarVariableTarget(
    val scalar: ScalarKind,
    override val nullable: Boolean,
) : VariableTarget {
    override fun matches(type: OutputTypeSpec): Boolean =
        !type.list && type.namedType == scalar.graphQLName
}

internal data class ListVariableTarget(
    val scalar: ScalarKind,
    override val nullable: Boolean,
    val elementNullable: Boolean,
) : VariableTarget {
    override fun matches(type: OutputTypeSpec): Boolean =
        type.list &&
            type.namedType == scalar.graphQLName &&
            (!type.elementNullable || elementNullable)
}

internal data class VariableProviderPlan(
    val owner: FieldCoordinate,
    val variableName: String,
    val selection: FragmentSelectionPlan,
    val nestedInput: Boolean,
    val listValue: Boolean,
    val nullable: Boolean,
    val abstractPath: Boolean,
) {
    fun source(): String =
        FragmentPlan(owner.typeName, listOf(selection)).source()
}

private fun sensitiveScalar(
    scalar: ScalarKind,
    input: Value.Object,
    arguments: Value.Arguments,
    applicationOrdinal: Int? = null,
): Value.Simple {
    val fingerprint =
        input.resolutionFingerprint().value +
            "|" +
            arguments.resolutionFingerprint().value +
            applicationOrdinal?.let { "|ordinal:$it" }.orEmpty()
    val hash = fingerprint.hashCode()
    return when (scalar) {
        ScalarKind.BOOLEAN -> Value.Boolean.of(hash and 1 == 0)
        ScalarKind.FLOAT -> Value.Float.of(hash.toDouble())
        ScalarKind.ID -> Value.ID.of("generated-$hash")
        ScalarKind.INT -> Value.Int.of(hash)
        ScalarKind.STRING -> Value.String.of("generated-$hash")
    }
}

private data class ArgumentOccurrence(
    val selectionPath: List<Int>,
    val argument: ArgumentDefinitionSpec,
    val valuePath: List<InputValueStep>,
    val target: VariableTarget?,
)

private fun List<FragmentSelectionPlan>.replaceArgument(
    selectionPath: List<Int>,
    argumentName: String,
    valuePath: List<InputValueStep>,
    value: InputValuePlan,
): List<FragmentSelectionPlan> {
    val selectedIndex = selectionPath.first()
    return mapIndexed { index, selection ->
        when {
            index != selectedIndex -> selection
            selectionPath.size == 1 ->
                selection.copy(
                    arguments =
                        selection.arguments +
                            (
                                argumentName to
                                    selection.arguments
                                        .getValue(argumentName)
                                        .replace(valuePath, value)
                            ),
                )
            else ->
                selection.copy(
                    subselections =
                        selection.subselections.replaceArgument(
                            selectionPath = selectionPath.drop(1),
                            argumentName = argumentName,
                            valuePath = valuePath,
                            value = value,
                        ),
                )
        }
    }
}

private sealed interface InputValueStep {
    data class Field(
        val name: String,
    ) : InputValueStep

    data class Index(
        val index: Int,
    ) : InputValueStep
}

private data class InputValueOccurrence(
    val path: List<InputValueStep>,
    val target: VariableTarget,
)

private fun InputValuePlan.variableOccurrences(
    path: List<InputValueStep> = emptyList(),
): List<InputValueOccurrence> =
    listOfNotNull(variableTarget()?.let { InputValueOccurrence(path, it) }) +
        when (this) {
            is ListInputPlan ->
                elements.flatMapIndexed { index, element ->
                    element.variableOccurrences(path + InputValueStep.Index(index))
                }
            is ObjectInputPlan ->
                fields.flatMap { (name, field) ->
                    field.variableOccurrences(path + InputValueStep.Field(name))
                }
            is InputLiteralPlan,
            is ErrorInputPlan,
            is NullInputPlan,
            is VariableInputPlan,
            -> emptyList()
        }

private fun InputValuePlan.containsVariable(): Boolean =
    when (this) {
        is VariableInputPlan -> true
        is ListInputPlan -> elements.any(InputValuePlan::containsVariable)
        is ObjectInputPlan -> fields.values.any(InputValuePlan::containsVariable)
        is InputLiteralPlan,
        is ErrorInputPlan,
        is NullInputPlan,
        -> false
    }

private fun InputValuePlan.replace(
    path: List<InputValueStep>,
    replacement: InputValuePlan,
): InputValuePlan {
    if (path.isEmpty()) return replacement
    return when (val step = path.first()) {
        is InputValueStep.Field -> {
            require(this is ObjectInputPlan)
            copy(
                fields =
                    fields +
                        (
                            step.name to
                                fields.getValue(step.name).replace(path.drop(1), replacement)
                        ),
            )
        }
        is InputValueStep.Index -> {
            require(this is ListInputPlan)
            copy(
                elements =
                    elements.mapIndexed { index, element ->
                        if (index == step.index) {
                            element.replace(path.drop(1), replacement)
                        } else {
                            element
                        }
                    },
            )
        }
    }
}

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
