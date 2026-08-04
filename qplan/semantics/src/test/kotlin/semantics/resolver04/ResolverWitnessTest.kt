package semantics.resolver04

import io.kotest.property.PropertyTesting
import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.Selection
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.fragmentFrom
import model.objectOf
import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.InputListTypeWeight
import semantics.arbitrary.InputObjectCount
import semantics.arbitrary.InputObjectFieldCount
import semantics.arbitrary.InputObjectTypeWeight
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.MaxInputTypeDepth
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverProgramKind
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.allowedResolverSiteClosure
import semantics.arbitrary.checkResolverTestCases
import semantics.arbitrary.registeredResolverCellCounts
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generated Resolver04 witnesses for exact applications, variable profiles, and permutation parity.
 *
 * Keep independent trace-oracle checks here; large-volume execution belongs in the stress suite.
 */
class ResolverWitnessTest {
    @Test
    fun `generated variable construction witness is exact minimal and permutation invariant`(): Unit =
        runBlocking {
            withPropertySeed(20260802L) {
            val counts = TestCaseCount(schemas = 20, registriesPerSchema = 3, queriesPerSchema = 5)
            val config =
                Config.default +
                    (SchemaObjectCount to 5..7) +
                    (ObjectFieldCount to 5..7) +
                    (QueryFieldCount to 4..6) +
                    (InputObjectCount to 2..3) +
                    (InputObjectFieldCount to 2..4) +
                    (InputObjectTypeWeight to 0.6) +
                    (InputListTypeWeight to 0.6) +
                    (MaxInputTypeDepth to 3) +
                    (FieldArgumentWeight to 0.9) +
                    (ExplicitFieldResolverWeight to 0.8) +
                    (ListTypeWeight to 0.45) +
                    (NullableTypeWeight to 0.5) +
                    (DuplicateSelectionWeight to 0.8) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFragmentDepth to 4) +
                    (ResolverVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariableCount to 2..3) +
                    (NodeResolversEnabled to false)
            val activation = ActivationCounts()

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                val world = testWorld.assumptions
                val registry = testCase.registry
                val fragment = world.fragmentFrom(testCase.query.source)

                registry.clearResolutionWitness()
                val result =
                    context(world) {
                        world.objectOf("Query").resolve(fragment.subselections)
                    }
                val witness = registry.resolutionWitness()
                assertEquals(
                    result.registeredResolverCellCounts(world.executorRegistry),
                    witness.applicationCounts(),
                )
                assertTrue(
                    witness.unrelatedApplications(
                        fragment.subselections.allowedResolverSiteClosure(world.executorRegistry),
                    ).isEmpty(),
                    "Resolver applied outside operation/registry demand closure",
                )

                val activeVariables = result.activeVariables()
                val profiles =
                    activeVariables.associateWith { variable ->
                        variable.activationProfile(testCase.registry, world)
                    }
                val profilesByOwner = profiles.values.groupBy(VariableActivationProfile::owner)
                witness.applications.forEach { application ->
                    activation.recordProgram(
                        registry.resolverProgram(application.key.sourceField),
                    )
                    profilesByOwner[application.key.sourceField]
                        ?.let(activation::recordVariableApplication)
                }

                // Snapshot all execution-only evidence before invoking the extensional oracle.
                assertTrue(context(world) { result.correctResolution(fragment) })

                val permuted =
                    world.fragmentFrom(testCase.query.permutationEquivalentSource)
                registry.clearResolutionWitness()
                val permutedResult =
                    context(world) {
                        world.objectOf("Query").resolve(permuted.subselections)
                    }
                val permutedWitness = registry.resolutionWitness()
                assertEquals(
                    permutedResult.registeredResolverCellCounts(world.executorRegistry),
                    permutedWitness.applicationCounts(),
                )
                assertTrue(
                    permutedWitness.unrelatedApplications(
                        permuted.subselections.allowedResolverSiteClosure(world.executorRegistry),
                    ).isEmpty(),
                    "Permuted resolver applied outside operation/registry demand closure",
                )

                // Snapshot the permuted execution before invoking its extensional oracle too.
                assertTrue(context(world) { permutedResult.correctResolution(permuted) })
                assertEquals(result, permutedResult)
                assertEquals(witness.applicationCounts(), permutedWitness.applicationCounts())
                assertEquals(
                    witness.applications
                        .map { it.key to it.inputFingerprint }
                        .groupingBy { it }
                        .eachCount(),
                    permutedWitness.applications
                        .map { it.key to it.inputFingerprint }
                        .groupingBy { it }
                        .eachCount(),
                )
            }

            assertTrue(activation.inputSensitiveApplications >= 10)
            assertTrue(activation.argumentSensitiveApplications >= 10)
            assertTrue(activation.variableBearingApplications > 0)
            assertTrue(activation.multipleVariableApplications > 0)
            assertTrue(activation.nestedInputVariableApplications > 0)
            assertTrue(activation.listVariableApplications > 0)
            assertTrue(activation.nullableVariableApplications > 0)
            assertTrue(activation.nullableProviderApplications > 0)
            }
        }

    private fun Value.Variable.activationProfile(
        registry: semantics.arbitrary.ArbitraryRegistry,
        world: model.Assumptions,
    ): VariableActivationProfile {
        val coordinate = world.executorRegistry.variableCoordinate(this)
        val owner =
            FieldCoordinate(
                coordinate.field.containingType.typeName,
                coordinate.field.fieldName,
            )
        val source = registry.objectFragmentSources.getValue(owner)
        val occurrences =
            world
                .fragmentFrom(source)
                .subselections
                .variableOccurrences(this)
        require(occurrences.isNotEmpty()) {
            "Active variable $this does not occur in its owner's source fragment"
        }
        val provider = world.executorRegistry.variable(this)
        return VariableActivationProfile(
            variable = this,
            owner = owner,
            nestedInput = occurrences.any { occurrence -> occurrence.depth > 0 },
            listValue = occurrences.any { occurrence -> occurrence.type is TypeExpr.List },
            nullableValue = occurrences.any { occurrence -> occurrence.type.isNullable },
            nullableProvider = provider.terminalType().isNullable,
            abstractProviderPath = provider.hasAbstractPath(),
        )
    }

    private fun SelectionForest.variableOccurrences(
        variable: Value.Variable,
    ): List<VariableOccurrence> =
        buildList {
            this@variableOccurrences.forEach { selection ->
                selection.key.arguments.fieldValues.forEach { (name, value) ->
                    addAll(
                        value.variableOccurrences(
                            variable = variable,
                            type = selection.key.arguments.type.fields.getValue(name).typeExpr,
                            depth = 0,
                        ),
                    )
                }
                addAll(selection.subselections.variableOccurrences(variable))
            }
        }

    private fun Value.Input?.variableOccurrences(
        variable: Value.Variable,
        type: TypeExpr<Schema.InputType>,
        depth: Int,
    ): List<VariableOccurrence> =
        when (this) {
            variable -> listOf(VariableOccurrence(type, depth))
            is Value.InputList -> {
                val listType = type as TypeExpr.List
                values.flatMap { value ->
                    value.variableOccurrences(variable, listType.elementType, depth + 1)
                }
            }
            is Value.InputObject ->
                fieldValues.flatMap { (name, value) ->
                    value.variableOccurrences(
                        variable,
                        this.type.fields.getValue(name).typeExpr,
                        depth + 1,
                    )
                }
            null,
            Value.Error,
            is Value.Simple,
            is Value.Variable,
            -> emptyList()
        }

    private fun Selection.terminalType(): TypeExpr<Schema.OutputType> =
        if (subselections.isEmpty()) {
            key.field.typeExpr
        } else {
            subselections.single().terminalType()
        }

    private fun Selection.hasAbstractPath(): Boolean {
        val outputType = key.field.typeExpr.baseType
        return nominalType.possibleTypes.size > 1 ||
            (outputType is Schema.CompositeType && outputType.possibleTypes.size > 1) ||
            subselections.anySelection { selection -> selection.hasAbstractPath() }
    }

    private fun SelectionForest.anySelection(predicate: (Selection) -> Boolean): Boolean {
        var matched = false
        forEach { selection ->
            if (predicate(selection)) matched = true
        }
        return matched
    }

    private fun EngineResult?.activeVariables(): Set<Value.Variable> =
        when (this) {
            is EngineResult.Object ->
                variableValues.keys +
                    cells.values.flatMapTo(linkedSetOf()) { cell ->
                        cell.value.activeVariables()
                    }
            is EngineResult.List ->
                flatMapTo(linkedSetOf()) { cell -> cell.value.activeVariables() }
            null,
            Value.Error,
            is Value.Simple,
            -> emptySet()
        }

    private data class VariableOccurrence(
        val type: TypeExpr<Schema.InputType>,
        val depth: Int,
    )

    private data class VariableActivationProfile(
        val variable: Value.Variable,
        val owner: FieldCoordinate,
        val nestedInput: Boolean,
        val listValue: Boolean,
        val nullableValue: Boolean,
        val nullableProvider: Boolean,
        val abstractProviderPath: Boolean,
    )

    private class ActivationCounts {
        var inputSensitiveApplications = 0
        var argumentSensitiveApplications = 0
        var variableBearingApplications = 0
        var multipleVariableApplications = 0
        var nestedInputVariableApplications = 0
        var listVariableApplications = 0
        var nullableVariableApplications = 0
        var nullableProviderApplications = 0
        var abstractProviderApplications = 0

        fun recordProgram(program: ResolverProgramKind) {
            when (program) {
                ResolverProgramKind.INPUT_SENSITIVE ->
                    inputSensitiveApplications += 1
                ResolverProgramKind.ARGUMENT_SENSITIVE ->
                    argumentSensitiveApplications += 1
                ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE -> {
                    inputSensitiveApplications += 1
                    argumentSensitiveApplications += 1
                }
                ResolverProgramKind.CONSTANT -> Unit
            }
        }

        fun recordVariableApplication(profiles: List<VariableActivationProfile>) {
            variableBearingApplications += 1
            if (profiles.size > 1) multipleVariableApplications += 1
            if (profiles.any(VariableActivationProfile::nestedInput)) {
                nestedInputVariableApplications += 1
            }
            if (profiles.any(VariableActivationProfile::listValue)) {
                listVariableApplications += 1
            }
            if (profiles.any(VariableActivationProfile::nullableValue)) {
                nullableVariableApplications += 1
            }
            if (profiles.any(VariableActivationProfile::nullableProvider)) {
                nullableProviderApplications += 1
            }
            if (profiles.any(VariableActivationProfile::abstractProviderPath)) {
                abstractProviderApplications += 1
            }
        }
    }
}

private suspend fun <T> withPropertySeed(
    seed: Long,
    block: suspend () -> T,
): T {
    val previousSeed = PropertyTesting.defaultSeed
    PropertyTesting.defaultSeed = seed
    return try {
        block()
    } finally {
        PropertyTesting.defaultSeed = previousSeed
    }
}
