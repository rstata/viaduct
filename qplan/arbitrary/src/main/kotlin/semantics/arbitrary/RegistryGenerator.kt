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
import model.fragmentFrom
import model.objectOf
import model.toSelectionForest
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import model.testing.nodeResolverOf
import model.testing.withErrorArguments

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
    val fromArgumentVariableCount: Int,
    val fromObjectFieldVariableCount: Int,
    val maximumFromObjectFieldPathLength: Int,
    val maximumVariablesPerOwner: Int,
    val hasNestedInputVariable: Boolean,
    val hasListVariable: Boolean,
    val hasNullableProvider: Boolean,
    val hasAbstractProviderPath: Boolean,
    val hasAbstractResolverFragment: Boolean,
)

/**
 * A registry recipe whose resolver coordinates, potential output paths, and value plans are
 * independent of any generated query. Calling [world] materializes it against one canonical
 * decoded schema.
 */
class ArbitraryRegistry internal constructor(
    val fieldResolverCoordinates: Set<FieldCoordinate>,
    val nodeResolverTypes: Set<String>,
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
    private val applicationCounts = mutableMapOf<FieldCoordinate, Long>()

    /** Source resolver fields whose generated fragments consume a `FromArgument` variable. */
    val fromArgumentVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .filterIsInstance<FromArgumentVariableProviderPlan>()
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated fragments consume a FromObjectField variable. */
    val fromObjectFieldVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .filterIsInstance<FromObjectFieldVariableProviderPlan>()
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    /** Source resolver fields whose generated fragments consume a nested FromObjectField path. */
    val nestedFromObjectFieldVariableOwnerFields: Set<FieldCoordinate> =
        variableProviders
            .filterIsInstance<FromObjectFieldVariableProviderPlan>()
            .filter { provider -> provider.responsePath().size > 1 }
            .mapTo(linkedSetOf(), VariableProviderPlan::owner)

    fun sourceResolverHasFromArgumentVariables(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in fromArgumentVariableOwnerFields

    fun sourceResolverHasFromObjectFieldVariables(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in fromObjectFieldVariableOwnerFields

    fun sourceResolverHasNestedFromObjectFieldVariable(
        canonicalField: FieldCoordinate,
    ): Boolean =
        sourceField(canonicalField) in nestedFromObjectFieldVariableOwnerFields

    /** Maps a fixture-lowered application coordinate back to its generated source resolver. */
    fun sourceResolverCoordinate(canonicalField: FieldCoordinate): FieldCoordinate =
        sourceField(canonicalField)

    fun clearResolutionWitness() {
        applicationLog.clear()
    }

    fun resolutionWitness(): ResolutionWitness = applicationLog.snapshot()

    fun <T> withoutResolutionWitnessCapture(block: () -> T): T =
        applicationLog.withoutRecording(block)

    fun clearResolutionApplicationCounts() {
        applicationCounts.clear()
    }

    fun resolutionApplicationCounts(): Map<FieldCoordinate, Long> =
        applicationCounts.toMap()

    fun resolverProgram(sourceField: FieldCoordinate): ResolverProgramKind =
        resolverPrograms.getValue(sourceField)

    fun applicationProgram(
        schema: ArbitrarySchema,
        canonicalField: FieldCoordinate,
    ): ResolverProgramKind =
        if (canonicalField.isNodeLoader(schema)) {
            ResolverProgramKind.INPUT_SENSITIVE
        } else {
            resolverProgram(sourceField(canonicalField))
        }

    fun applicationHasDependencies(
        schema: ArbitrarySchema,
        canonicalField: FieldCoordinate,
    ): Boolean =
        canonicalField.isNodeLoader(schema) ||
            objectFragmentSources.getValue(sourceField(canonicalField)).isNotEmpty()

    fun nodeLoaderPossibleTypes(
        schema: ArbitrarySchema,
        canonicalField: FieldCoordinate,
    ): Set<String> {
        if (
            canonicalField.fieldName != "\$node" ||
            !canonicalField.typeName.endsWith("\$Bridge")
        ) {
            return emptySet()
        }
        val nodeTypeName = canonicalField.typeName.removeSuffix("\$Bridge")
        if (!schema.isComposite(nodeTypeName)) return emptySet()

        val possibleTypes =
            schema
                .possibleObjects(nodeTypeName)
                .mapTo(linkedSetOf(), ObjectDefinition::name)
        return possibleTypes.takeIf { types ->
            types.isNotEmpty() && types.all { type -> type in nodeResolverTypes }
        }.orEmpty()
    }

    private fun sourceField(canonicalField: FieldCoordinate): FieldCoordinate {
        if (canonicalField in resolverPrograms) return canonicalField
        return canonicalField.fieldName
            .removeSuffix("\$bridge")
            .takeIf { fieldName -> fieldName != canonicalField.fieldName }
            ?.let { fieldName ->
                FieldCoordinate(canonicalField.typeName, fieldName)
            }?.takeIf { sourceField -> sourceField in resolverPrograms }
            ?: canonicalField
    }

    private fun FieldCoordinate.isNodeLoader(schema: ArbitrarySchema): Boolean {
        return nodeLoaderPossibleTypes(schema, this).isNotEmpty()
    }

    fun world(
        schema: ArbitrarySchema,
        resolverProgramMutation: ResolverProgramMutation = ResolverProgramMutation.NONE,
        captureSuppliedDemand: Boolean = false,
        captureResolutionWitness: Boolean = true,
    ): TestWorld {
        require(captureResolutionWitness || !captureSuppliedDemand) {
            "Supplied demand can only be retained in a resolution witness"
        }
        val firstInputs = mutableMapOf<FieldCoordinate, Value.Object>()
        val firstArguments = mutableMapOf<FieldCoordinate, Value.Arguments>()
        val applicationOrdinals = mutableMapOf<FieldCoordinate, Int>()
        fun recordApplication(
            coordinate: FieldCoordinate,
            arguments: Value.Arguments,
            input: Value.Object,
            suppliedDemand: SelectionForest?,
        ) {
            if (captureResolutionWitness) {
                applicationLog.record(
                    field = coordinate,
                    arguments = arguments,
                    input = input,
                    suppliedDemand = suppliedDemand.takeIf { captureSuppliedDemand },
                )
            } else {
                applicationCounts[coordinate] =
                    Math.addExact(applicationCounts.getOrDefault(coordinate, 0L), 1L)
            }
        }
        val world =
            TestWorld.fromSDL(
            schemaSDL = schema.sdl,
            nodeResolvers = { canonicalSchema ->
                nodeValues.map { (typeName, plan) ->
                    val type = canonicalSchema.type(typeName) as Schema.ObjectType
                    type to
                        nodeResolverOf { id ->
                            plan.materializeObject(
                                schema = canonicalSchema,
                                inputId = id,
                                generatedHashSeed =
                                    stableGeneratedHash(typeName, id.idValue),
                            )
                        }
                }.toMap()
            },
            applicationObserver = { field, input, arguments, suppliedDemand ->
                val coordinate =
                    FieldCoordinate(
                        field.containingType.typeName,
                        field.fieldName,
                    )
                recordApplication(coordinate, arguments, input, suppliedDemand)
                if (
                    resolverProgramMutation ==
                    ResolverProgramMutation.DUPLICATE_APPLICATION
                ) {
                    recordApplication(coordinate, arguments, input, suppliedDemand)
                }
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
                            objectFragment =
                                objectFragments
                                    .getValue(coordinate)
                                    .materialize(
                                        canonicalSchema,
                                        field as Schema.ObjectField,
                                    ),
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
                                val generatedHashSeed =
                                    stableGeneratedHash(
                                        effectiveInput.resolutionFingerprint().value,
                                        effectiveArguments.resolutionFingerprint().value,
                                    )
                                when (program) {
                                    ResolverProgramKind.CONSTANT -> constant
                                    else ->
                                        if (
                                            field.typeExpr !is TypeExpr.List &&
                                            field.typeExpr.baseType is Schema.SimpleType
                                        ) {
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
                                        } else {
                                            plan.materialize(
                                                schema = canonicalSchema,
                                                typeExpr = field.typeExpr,
                                                generatedHashSeed = generatedHashSeed,
                                            )
                                    }
                                }
                            },
                        )
                }.toMap()
            },
            variableProviders = { canonicalSchema ->
                variableProviders.associate { provider ->
                    val field =
                        canonicalSchema.field(
                            provider.owner.typeName,
                            provider.owner.fieldName,
                        ) as Schema.ObjectField
                    Value.Variable.of(
                        field,
                        provider.variableName,
                    ) to
                        when (provider) {
                            is FromArgumentVariableProviderPlan ->
                                canonicalSchema.fromArgument(
                                    field = field,
                                    argumentName = provider.argumentName,
                                )
                            is FromObjectFieldVariableProviderPlan ->
                                canonicalSchema.fromObjectField(
                                    objectFragmentSource =
                                        objectFragmentSources.getValue(provider.owner),
                                    responsePath = provider.responsePath(),
                                    variableField = field,
                                )
                        }
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
            fieldResolverCoordinates.sortedBy(FieldCoordinate::toString).forEach { site ->
                appendLine("  $site OSS=${outputSelectionSets[site.toString()].orEmpty().sorted()}")
                val fragment = objectFragmentSources.getValue(site)
                if (fragment.isNotEmpty()) appendLine(fragment.prependIndent("    "))
            }
            appendLine("variables:")
            variableProviders.sortedBy(VariableProviderPlan::variableName).forEach { provider ->
                when (provider) {
                    is FromArgumentVariableProviderPlan ->
                        appendLine(
                            "  \$${provider.variableName} owner=${provider.owner} " +
                                "fromArgument=${provider.argumentName}",
                        )
                    is FromObjectFieldVariableProviderPlan -> {
                        appendLine(
                            "  \$${provider.variableName} owner=${provider.owner}",
                        )
                        appendLine(provider.source().prependIndent("    "))
                    }
                }
            }
            appendLine("node resolvers:")
            nodeResolverTypes.sorted().forEach { site ->
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
                    field.ownerName != GENERATED_HASH_TYPE &&
                        !field.isGeneratedHashField() &&
                        (
                            field.ownerName == "Query" ||
                                field.arguments.isNotEmpty() ||
                                chance(config[ExplicitFieldResolverWeight])
                        )
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
                val valuePlan = fieldValues.getValue(site)
                val scalarOutput =
                    !field.type.list &&
                        ScalarKind.entries.any { it.graphQLName == field.type.namedType }
                val structuredOutput = valuePlan.containsGeneratedHash()
                val supportsSensitiveOutput = scalarOutput || structuredOutput
                val inputSensitive =
                    supportsSensitiveOutput &&
                        objectFragments.getValue(site).selections.isNotEmpty()
                val argumentSensitive =
                    supportsSensitiveOutput && field.arguments.isNotEmpty()
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
            fieldResolverCoordinates = fieldSites,
            nodeResolverTypes = nodeSites,
            outputSelectionSets = oss,
            objectFragmentSources =
                objectFragments.mapValues { (_, fragment) -> fragment.source() },
            variableProviderSources =
                variableProviders
                    .filterIsInstance<FromObjectFieldVariableProviderPlan>()
                    .associate { provider ->
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
                    fromArgumentVariableCount =
                        variableProviders.count {
                            it is FromArgumentVariableProviderPlan
                        },
                    fromObjectFieldVariableCount =
                        variableProviders.count {
                            it is FromObjectFieldVariableProviderPlan
                        },
                    maximumFromObjectFieldPathLength =
                        variableProviders
                            .filterIsInstance<FromObjectFieldVariableProviderPlan>()
                            .maxOfOrNull { provider -> provider.responsePath().size }
                            ?: 0,
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
                        variableProviders
                            .filterIsInstance<FromObjectFieldVariableProviderPlan>()
                            .any(FromObjectFieldVariableProviderPlan::abstractPath),
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
        return fragment
            .withFromArgumentVariableProvider(consumer, ranks, variableProviders)
            .withFromObjectFieldVariableProvider(consumer, ranks, variableProviders)
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
                !field.isGeneratedHashField() &&
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

    private fun FragmentPlan.withFromArgumentVariableProvider(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        variableProviders: MutableList<VariableProviderPlan>,
    ): FragmentPlan {
        if (
            !config[ResolverFromArgumentVariablesEnabled] ||
            !chance(config[ResolverVariableWeight])
        ) {
            return this
        }
        val resolverArguments = field(consumer).arguments
        val variableCount = Arb.int(config[ResolverVariableCount]).next(random)
        return (0 until variableCount).fold(this) { fragment, variableIndex ->
            val candidate =
                fragment.argumentOccurrences()
                    .shuffled(random)
                    .firstNotNullOfOrNull { occurrence ->
                        occurrence.target?.let { target ->
                            resolverArguments
                                .shuffled(random)
                                .firstOrNull { argument -> target.accepts(argument.type) }
                                ?.let { argument -> occurrence to argument }
                        }
                    } ?: return@fold fragment
            val variableName = "resolverArgVar${ranks.getValue(consumer)}_$variableIndex"
            variableProviders +=
                FromArgumentVariableProviderPlan(
                    owner = consumer,
                    variableName = variableName,
                    argumentName = candidate.second.name,
                    nestedInput = candidate.first.valuePath.isNotEmpty(),
                    listValue = candidate.second.type is ListInputTypeSpec,
                    nullable = candidate.second.type.nullable,
                )
            fragment.copy(
                selections =
                    fragment.selections.replaceArgument(
                        selectionPath = candidate.first.selectionPath,
                        argumentName = candidate.first.argument.name,
                        valuePath = candidate.first.valuePath,
                        value = VariableInputPlan(variableName),
                    ),
            )
        }
    }

    private fun FragmentPlan.withFromObjectFieldVariableProvider(
        consumer: FieldCoordinate,
        ranks: Map<FieldCoordinate, Int>,
        variableProviders: MutableList<VariableProviderPlan>,
    ): FragmentPlan {
        if (
            !config[ResolverVariablesEnabled] ||
            (config[ResolverVariablesOnQueryFieldsOnly] && consumer.typeName != "Query") ||
            variableProviders
                .filterIsInstance<FromObjectFieldVariableProviderPlan>()
                .map(VariableProviderPlan::owner)
                .distinct()
                .size >= config[ResolverFromObjectFieldVariableOwnerLimit] ||
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
                        .filter { provider ->
                            structuralBranchRank(ownerName, provider, ranks) < useRank
                        }
                        .chooseProviderPath()
                        ?.let { provider -> occurrence to provider }
                } ?: return@fold fragment
            val variableName = "resolverVar${ranks.getValue(consumer)}_$variableIndex"
            val providerSelection =
                candidate.second.withResponseAliases(variableName)
            variableProviders +=
                FromObjectFieldVariableProviderPlan(
                    owner = consumer,
                    variableName = variableName,
                    selection = providerSelection,
                    nestedInput = candidate.first.valuePath.isNotEmpty(),
                    listValue = candidate.first.target is ListVariableTarget,
                    nullable = candidate.first.target?.nullable == true,
                    abstractPath = candidate.second.hasAbstractPath(ownerName),
                )
            fragment.copy(
                selections =
                    (fragment.selections + providerSelection).replaceArgument(
                        selectionPath = candidate.first.selectionPath,
                        argumentName = candidate.first.argument.name,
                        valuePath = candidate.first.valuePath,
                        value = VariableInputPlan(variableName),
                    ),
            )
        }
    }

    /** Splits direct and nested providers so configured profiles can exercise nested paths deliberately instead of relying on their share of one shuffled candidate pool. */
    private fun List<FragmentSelectionPlan>.chooseProviderPath(): FragmentSelectionPlan? {
        if (isEmpty()) return null
        val (nested, direct) = shuffled(random).partition { selection -> selection.pathLength() > 1 }
        return if (chance(config[ResolverNestedProviderPathWeight])) {
            nested.firstOrNull() ?: direct.firstOrNull()
        } else {
            direct.firstOrNull() ?: nested.firstOrNull()
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
                !field.isGeneratedHashField() &&
                    field.arguments.isEmpty() &&
                    (
                        field.coordinate !in fieldSites ||
                            ranks.getValue(field.coordinate) < consumerRank
                    )
            }.flatMap { field ->
                when {
                    target.matches(field.type) &&
                        (!field.type.nullable || target.nullable) ->
                        listOf(
                            FragmentSelectionPlan(
                                fieldName = field.name,
                                arguments = emptyMap(),
                                subselections = emptyList(),
                            ),
                        )

                    !field.type.list &&
                        schema.isComposite(field.type.namedType) &&
                        (!field.type.nullable || target.acceptsNullableTraversal) ->
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
        if (type.namedType == GENERATED_HASH_TYPE) {
            return GeneratedHashPlan(path.hashCode())
        }
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
    fun materialize(
        schema: Schema,
        variableField: Schema.ObjectField,
    ): Fragment =
        if (selections.isEmpty()) {
            Fragment.of(
                nominalType = schema.type(ownerName) as Schema.ObjectType,
                subselections = model.selectionForestOf(),
            )
        } else {
            val parsed = schema.fragmentFrom(source(), variableField = variableField)
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
    val alias: String? = null,
) {
    fun source(indent: String): String =
        buildString {
            if (typeCondition != null) {
                appendLine("$indent... on $typeCondition {")
            }
            val fieldIndent = if (typeCondition == null) indent else "$indent  "
            append(fieldIndent)
            if (alias != null) append("$alias: ")
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
        variableField: Schema.ObjectField,
    ): Selection =
        FragmentPlan(owner.typeName, listOf(this))
            .materialize(schema, variableField)
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
            selection.withErrorArguments(
                plan.arguments
                    .filterValues { argument -> argument is ErrorInputPlan }
                    .keys,
            ).let { materializedSelection ->
                if (materializedSelection.key.field.fieldName.endsWith("\$bridge")) {
                    val payload = materializedSelection.subselections.single()
                    return@let Selection.of(
                        key = materializedSelection.key,
                        possibleTypes = materializedSelection.possibleTypes,
                        subselections =
                            model.selectionForestOf(
                                Selection.of(
                                    key = payload.key,
                                    possibleTypes = payload.possibleTypes,
                                    subselections =
                                        plan.subselections.materialize(
                                            schema,
                                            payload.subselections,
                                        ),
                                ),
                            ),
                    )
                }
                Selection.of(
                    key = materializedSelection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections =
                        plan.subselections.materialize(schema, selection.subselections),
                )
            }
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

    val acceptsNullableTraversal: Boolean

    fun matches(type: OutputTypeSpec): Boolean

    fun accepts(type: InputTypeSpec): Boolean
}

internal data class ScalarVariableTarget(
    val scalar: ScalarKind,
    override val nullable: Boolean,
) : VariableTarget {
    override val acceptsNullableTraversal: Boolean
        get() = nullable

    override fun matches(type: OutputTypeSpec): Boolean =
        !type.list && type.namedType == scalar.graphQLName

    override fun accepts(type: InputTypeSpec): Boolean =
        type is ScalarInputTypeSpec &&
            type.scalar == scalar &&
            (!type.nullable || nullable)
}

internal data class ListVariableTarget(
    val scalar: ScalarKind,
    override val nullable: Boolean,
    val elementNullable: Boolean,
) : VariableTarget {
    override val acceptsNullableTraversal: Boolean
        get() = elementNullable

    override fun matches(type: OutputTypeSpec): Boolean =
        type.list &&
            type.namedType == scalar.graphQLName &&
            (!type.elementNullable || elementNullable)

    override fun accepts(type: InputTypeSpec): Boolean {
        val list = type as? ListInputTypeSpec ?: return false
        val element = list.element as? ScalarInputTypeSpec ?: return false
        return element.scalar == scalar &&
            (!list.nullable || nullable) &&
            (!element.nullable || elementNullable)
    }
}

internal sealed interface VariableProviderPlan {
    val owner: FieldCoordinate
    val variableName: String
    val nestedInput: Boolean
    val listValue: Boolean
    val nullable: Boolean
}

internal data class FromArgumentVariableProviderPlan(
    override val owner: FieldCoordinate,
    override val variableName: String,
    val argumentName: String,
    override val nestedInput: Boolean,
    override val listValue: Boolean,
    override val nullable: Boolean,
) : VariableProviderPlan

internal data class FromObjectFieldVariableProviderPlan(
    override val owner: FieldCoordinate,
    override val variableName: String,
    val selection: FragmentSelectionPlan,
    override val nestedInput: Boolean,
    override val listValue: Boolean,
    override val nullable: Boolean,
    val abstractPath: Boolean,
) : VariableProviderPlan {
    fun source(): String =
        FragmentPlan(owner.typeName, listOf(selection)).source()

    fun responsePath(): List<String> =
        buildList {
            var current = selection
            while (true) {
                add(current.alias ?: current.fieldName)
                if (current.subselections.isEmpty()) break
                current = current.subselections.single()
            }
        }
}

private fun FragmentSelectionPlan.withResponseAliases(
    variableName: String,
    depth: Int = 0,
): FragmentSelectionPlan =
    copy(
        alias = "${variableName}Source$depth",
        subselections =
            subselections.map { selection ->
                selection.withResponseAliases(variableName, depth + 1)
            },
    )

private fun FragmentSelectionPlan.pathLength(): Int =
    1 + (subselections.singleOrNull()?.pathLength() ?: 0)

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
    /**
     * Materializes this plan. [generatedHashSeed] may affect only synthetic [GENERATED_HASH_TYPE]
     * subtrees; equal seeds and other arguments produce equal values.
     */
    fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID? = null,
        generatedHashSeed: Int = 0,
    ): Value.Output?

    fun selectedPaths(prefix: String = ""): Set<String>

    fun containsGeneratedHash(): Boolean = false
}

internal data object NullPlan : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
        generatedHashSeed: Int,
    ): Value.Output? = null

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data object ErrorPlan : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
        generatedHashSeed: Int,
    ): Value.Output = Value.Error

    override fun selectedPaths(prefix: String): Set<String> = emptySet()
}

internal data object InputIdPlan : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
        generatedHashSeed: Int,
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
        generatedHashSeed: Int,
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
        generatedHashSeed: Int,
    ): Value.OutputList {
        require(typeExpr is TypeExpr.List)
        return Value.OutputList.of(
            typeExpr = typeExpr.elementType,
            values =
                elements.map {
                    it.materialize(schema, typeExpr.elementType, inputId, generatedHashSeed)
                },
        )
    }

    override fun selectedPaths(prefix: String): Set<String> =
        elements.flatMapIndexed { index, element ->
            element.selectedPaths("$prefix[$index]")
        }.toSet()

    override fun containsGeneratedHash(): Boolean =
        elements.any(ValuePlan::containsGeneratedHash)
}

internal data class ObjectPlan(
    val typeName: String,
    val fields: Map<FieldCoordinate, ValuePlan>,
) : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
        generatedHashSeed: Int,
    ): Value.Object =
        materializeObject(schema, inputId, generatedHashSeed)

    fun materializeObject(
        schema: Schema,
        inputId: Value.ID?,
        generatedHashSeed: Int = 0,
    ): Value.Object =
        schema.objectOf(typeName) {
            fields.forEach { (coordinate, plan) ->
                require(coordinate.typeName == typeName)
                val outputField = schema.field(typeName, coordinate.fieldName)
                field(coordinate.fieldName) setTo
                    plan.materialize(
                        schema,
                        outputField.typeExpr,
                        inputId,
                        generatedHashSeed,
                    )
            }
        }

    override fun selectedPaths(prefix: String): Set<String> =
        fields.flatMap { (coordinate, plan) ->
            val path =
                if (prefix.isEmpty()) coordinate.fieldName else "$prefix.${coordinate.fieldName}"
            setOf(path) + plan.selectedPaths(path)
        }.toSet()

    override fun containsGeneratedHash(): Boolean =
        fields.values.any(ValuePlan::containsGeneratedHash)
}

/**
 * A bounded synthetic object subtree used to make structured resolver outputs value-sensitive.
 *
 * The invocation seed is mixed with this plan's fixed [salt], so list positions and nested object
 * plans can have distinct shapes without consulting application order or mutable state. The
 * terminal object omits its nullable `nested` field.
 */
internal data class GeneratedHashPlan(
    val salt: Int,
) : ValuePlan {
    override fun materialize(
        schema: Schema,
        typeExpr: TypeExpr<Schema.OutputType>,
        inputId: Value.ID?,
        generatedHashSeed: Int,
    ): Value.Object {
        require(typeExpr.baseType.typeName == GENERATED_HASH_TYPE)
        val rootHash = mixGeneratedHash(generatedHashSeed, salt)
        return generatedHashObject(
            schema = schema,
            hash = rootHash,
            remainingDepth = depth(generatedHashSeed),
        )
    }

    internal fun depth(generatedHashSeed: Int = 0): Int =
        Math.floorMod(
            mixGeneratedHash(generatedHashSeed, salt),
            MAX_GENERATED_HASH_DEPTH + 1,
        )

    override fun selectedPaths(prefix: String): Set<String> =
        (0..MAX_GENERATED_HASH_DEPTH).flatMapTo(linkedSetOf()) { depth ->
            val nestedPrefix =
                (0 until depth).fold(prefix) { path, _ ->
                    "$path.$GENERATED_HASH_NESTED_FIELD"
                }
            listOf(nestedPrefix, "$nestedPrefix.$GENERATED_HASH_FIELD")
        }

    override fun containsGeneratedHash(): Boolean = true
}

private const val MAX_GENERATED_HASH_DEPTH = 4
private const val GENERATED_HASH_NESTED_SALT = -1640531527

private fun generatedHashObject(
    schema: Schema,
    hash: Int,
    remainingDepth: Int,
): Value.Object =
    schema.objectOf(GENERATED_HASH_TYPE) {
        GENERATED_HASH_FIELD setTo hash
        if (remainingDepth > 0) {
            GENERATED_HASH_NESTED_FIELD setTo
                generatedHashObject(
                    schema = schema,
                    hash = mixGeneratedHash(hash, GENERATED_HASH_NESTED_SALT),
                    remainingDepth = remainingDepth - 1,
                )
        }
    }

private fun mixGeneratedHash(
    hash: Int,
    value: Int,
): Int = hash * 31 + value

private fun stableGeneratedHash(vararg components: String): Int =
    components.fold(1) { result, component ->
        component.fold(result * 31 + component.length) { hash, character ->
            mixGeneratedHash(hash, character.code)
        }
    }
