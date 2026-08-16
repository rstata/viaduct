package semantics.arbitrary

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import model.Value
import model.objectOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneratorTest {
    @Test
    fun `generated schemas registries and queries form valid worlds`() {
        val random = RandomSource.seeded(90210L)

        repeat(100) {
            val schema = Arb.schema(TEST_CONFIG).next(random)
            val registry = schema.registry(TEST_CONFIG).next(random)
            val query = schema.query(TEST_CONFIG).next(random)
            val world = registry.world(schema)

            val (nominalType, selections) = world.selectionsFrom(query.source)

            assertEquals(world.schema.query, nominalType)
            assertFalse(selections.isEmpty())
        }
    }

    @Test
    fun `batch dimensions produce the full registry query product`() {
        val counts = TestCaseCount(schemas = 7, registriesPerSchema = 4, queriesPerSchema = 6)
        val batch =
            Arb.resolverTestBatch(counts)
                .next(RandomSource.seeded(1234L))

        assertEquals(4, batch.registries.size)
        assertEquals(6, batch.queries.size)
        assertEquals(24, batch.cases.count())
    }

    @Test
    fun `count-only application capture does not retain resolution witnesses`() {
        val config =
            TEST_CONFIG +
                (ArgumentsEnabled to false) +
                (NodeResolversEnabled to false)
        val random = RandomSource.seeded(8642L)
        val schema = Arb.schema(config).next(random)
        val registry = schema.registry(config).next(random)
        val coordinate =
            registry.fieldResolverCoordinates.first { field ->
                field.typeName == "Query"
            }
        val countWorld =
            registry.world(
                schema = schema,
                captureResolutionWitness = false,
            ).assumptions
        val field =
            countWorld.schema.objectField(
                coordinate.typeName,
                coordinate.fieldName,
            )
        val input = countWorld.schema.objectOf("Query")
        val arguments = Value.Arguments.of(field, emptyMap())

        registry.clearResolutionApplicationCounts()
        countWorld.resolverRegistry.resolver(field)(input, arguments)

        assertEquals(mapOf(coordinate to 1L), registry.resolutionApplicationCounts())
        assertTrue(registry.resolutionWitness().applications.isEmpty())
    }

    @Test
    fun `feature switches remove their schema and query features`() {
        val config =
            Config.default +
                (ArgumentsEnabled to false) +
                (InterfacesEnabled to false) +
                (ListsEnabled to false) +
                (QueryFragmentsEnabled to false) +
                (ResolverFragmentsEnabled to false) +
                (UnionsEnabled to false)
        val random = RandomSource.seeded(5678L)

        repeat(30) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val query = schema.query(config).next(random)

            assertFalse("interface " in schema.sdl)
            assertFalse("union " in schema.sdl)
            assertFalse("[" in schema.sdl)
            assertTrue(schema.query.fields.all { field -> field.arguments.isEmpty() })
            assertTrue(schema.objects.flatMap { it.fields }.all { field -> field.arguments.isEmpty() })
            assertTrue(registry.objectFragmentSources.values.all(String::isEmpty))
            assertFalse("... on " in query.source)
        }
    }

    @Test
    fun `query scalar field weight can add scalar provider sites`() {
        val config =
            Config.default +
                (QueryFieldCount to 6..6) +
                (QueryScalarFieldWeight to 1.0)
        val random = RandomSource.seeded(5679L)

        repeat(20) {
            val schema = Arb.schema(config).next(random)
            val scalarQueryFields =
                schema.query.fields.filter { field ->
                    ScalarKind.entries.any { scalar ->
                        scalar.graphQLName == field.type.namedType
                    }
                }

            assertTrue(scalarQueryFields.isNotEmpty())
        }
    }

    @Test
    fun `queries generate distinct literal tuples for one argument field`() {
        val config =
            Config.default +
                (DuplicateSelectionWeight to 1.0) +
                (FieldArgumentWeight to 1.0)
        val random = RandomSource.seeded(2468L)
        var sawDistinctTuples = false
        var sawAliasedDistinctTuple = false

        repeat(20) {
            val schema = Arb.schema(config).next(random)
            val query = schema.query(config).next(random)
            val invocations =
                ARGUMENT_INVOCATION
                    .findAll(query.source)
                    .map { match ->
                        ArgumentInvocation(
                            alias = match.groupValues[1].ifEmpty { null },
                            fieldName = match.groupValues[2],
                            argument = match.groupValues[3],
                        )
                    }.toList()
            invocations
                .groupBy(ArgumentInvocation::fieldName)
                .values
                .filter { values ->
                    values.map(ArgumentInvocation::argument).distinct().size > 1
                }.forEach { values ->
                    sawDistinctTuples = true
                    if (values.any { it.alias != null }) {
                        sawAliasedDistinctTuple = true
                    }
                }
        }

        assertTrue(sawDistinctTuples)
        assertTrue(sawAliasedDistinctTuple)
    }

    @Test
    fun `resolver fragments generate exact literal argument demand`() {
        val config =
            Config.default +
                (ExplicitFieldResolverWeight to 1.0) +
                (FieldArgumentWeight to 1.0) +
                (ResolverFragmentsEnabled to true)
        val random = RandomSource.seeded(1357L)
        var sawArgumentDemand = false

        repeat(50) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            registry.world(schema)
            sawArgumentDemand =
                sawArgumentDemand ||
                    registry.objectFragmentSources.values.any { source ->
                        "(arg:" in source
                    }
        }

        assertTrue(sawArgumentDemand)
    }

    @Test
    fun `resolver fragment selection targets produce a long tail`() {
        val config =
            Config.default +
                (SchemaObjectCount to 12..12) +
                (ObjectFieldCount to 10..10) +
                (QueryFieldCount to 12..12) +
                (FieldArgumentWeight to 0.05) +
                (ExplicitFieldResolverWeight to 0.05) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 8) +
                (ResolverFragmentSelectionCount to 1..2) +
                (ResolverFragmentLongTailWeight to 0.2) +
                (ResolverFragmentLongTailSelectionCount to 10..35)
        val random = RandomSource.seeded(8675309L)
        val counts =
            buildList {
                repeat(5) {
                    val schema = Arb.schema(config).next(random)
                    val registry = schema.registry(config).next(random)
                    addAll(registry.objectFragmentSelectionCounts())
                }
            }.sorted()

        assertTrue(counts.average() >= 4.0)
        assertTrue(counts[(counts.size * 0.9).toInt()] >= 10)
        assertTrue(counts.max() >= 30)
    }

    @Test
    fun `abstract selections can omit concrete implementation defaults`() {
        val config =
            Config.default +
                (ImplementationArgumentDefaultWeight to 1.0) +
                (SchemaObjectCount to 3..5) +
                (QueryFieldCount to 4..6)
        val random = RandomSource.seeded(731997L)
        var generatedSchemas = 0
        var activatedQueries = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val query = schema.query(config).next(random)
            if (schema.features.hasImplementationArgumentDefaults) {
                generatedSchemas += 1
            }
            if (query.features.hasAbstractImplementationDefaultSelection) {
                activatedQueries += 1
            }
        }

        assertTrue(generatedSchemas > 0)
        assertTrue(activatedQueries > 0)
    }

    @Test
    fun `minimum selection depth forces a valid deep query path`() {
        val config =
            Config.default +
                (MinimumSelectionDepth to 4) +
                (MaxSelectionDepth to 6) +
                (SchemaObjectCount to 4..6)
        val random = RandomSource.seeded(97531L)

        repeat(30) {
            val schema = Arb.schema(config).next(random)
            val query = schema.query(config).next(random)
            val registry = schema.registry(config).next(random)

            assertTrue(query.selectionDepth >= 4)
            assertTrue(query.selectionDepth <= 6)
            registry.world(schema).selectionsFrom(query.source)
        }
    }

    @Test
    fun `complex resolver outputs can vary with input or arguments`() {
        val config =
            Config.default +
                (SchemaObjectCount to 3..3) +
                (MinimumSelectionDepth to 1) +
                (MaxSelectionDepth to 3) +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (ResolverFragmentWeight to 1.0)
        val random = RandomSource.seeded(4815162342L)
        var complexResolvers = 0
        var sensitiveComplexResolvers = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            registry.fieldResolverCoordinates.forEach { coordinate ->
                val field =
                    schema
                        .objectNamed(coordinate.typeName)
                        .fields
                        .single { candidate -> candidate.name == coordinate.fieldName }
                if (field.type.list || schema.isComposite(field.type.namedType)) {
                    complexResolvers += 1
                    if (registry.resolverProgram(coordinate) != ResolverProgramKind.CONSTANT) {
                        sensitiveComplexResolvers += 1
                    }
                }
            }
        }

        assertTrue(complexResolvers > 0)
        assertTrue(
            sensitiveComplexResolvers > 0,
            "Generated no input- or argument-sensitive object/list resolver",
        )
    }

    @Test
    fun `complex resolver functions are deterministic for equal inputs and arguments`() {
        val config =
            Config.default +
                (ArgumentsEnabled to false) +
                (SchemaObjectCount to 3..3) +
                (MinimumSelectionDepth to 1) +
                (MaxSelectionDepth to 3) +
                (ExplicitFieldResolverWeight to 1.0) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (NodeResolversEnabled to false)
        val random = RandomSource.seeded(4815162343L)
        var checkedResolvers = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val world = registry.world(schema).assumptions
            registry.fieldResolverCoordinates.forEach { coordinate ->
                val fieldSpec =
                    schema
                        .objectNamed(coordinate.typeName)
                        .fields
                        .single { field -> field.name == coordinate.fieldName }
                if (
                    !schema.isComposite(fieldSpec.type.namedType) ||
                    registry.resolverProgram(coordinate) == ResolverProgramKind.CONSTANT
                ) {
                    return@forEach
                }

                val field = world.schema.objectField(coordinate.typeName, coordinate.fieldName)
                val input = world.schema.objectOf(coordinate.typeName)
                val arguments = Value.Arguments.of(field, emptyMap())
                val resolver = world.resolverRegistry.resolver(field)

                assertEquals(
                    resolver(input, arguments),
                    resolver(input, arguments),
                )
                checkedResolvers += 1
            }
        }

        assertTrue(checkedResolvers > 0)
    }

    @Test
    fun `generated hash values are deterministic and seed and salt sensitive`() {
        val schema = Arb.schema().next(RandomSource.seeded(4815162344L))
        val world = schema.registry().next(RandomSource.seeded(4815162345L)).world(schema).assumptions
        val hashField = world.schema.field("Object0", GENERATED_HASH_FIELD)
        val plan = GeneratedHashPlan(salt = 17)

        val first =
            plan.materialize(
                schema = world.schema,
                typeExpr = hashField.typeExpr,
                generatedHashSeed = 23,
            )
        val repeated =
            plan.materialize(
                schema = world.schema,
                typeExpr = hashField.typeExpr,
                generatedHashSeed = 23,
            )
        val different =
            plan.materialize(
                schema = world.schema,
                typeExpr = hashField.typeExpr,
                generatedHashSeed = 24,
            )
        val differentSalt =
            GeneratedHashPlan(salt = 18).materialize(
                schema = world.schema,
                typeExpr = hashField.typeExpr,
                generatedHashSeed = 23,
            )

        assertEquals(first, repeated)
        assertNotEquals(first, different)
        assertNotEquals(first, differentSalt)
    }

    @Test
    fun `resolver variables generate provider-backed fragment arguments`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.8) +
                (ExplicitFieldResolverWeight to 0.8) +
                (NullableTypeWeight to 0.1) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0)
        val random = RandomSource.seeded(86420L)
        var generatedVariables = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            generatedVariables += registry.variableProviderSources.size
            registry.variableProviderSources.keys.forEach { variableName ->
                assertTrue(
                    registry.objectFragmentSources.values.any { source ->
                        "\$$variableName" in source
                    },
                )
            }
        }

        assertTrue(generatedVariables > 0)
    }

    @Test
    fun `nested object path variable providers are generated regularly`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.8) +
                (ExplicitFieldResolverWeight to 0.8) +
                (ListTypeWeight to 0.0) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverNestedProviderPathWeight to 1.0)
        val random = RandomSource.seeded(86421L)
        var registriesWithNestedProviders = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            if (registry.features.maximumFromObjectFieldPathLength > 1) {
                registriesWithNestedProviders += 1
            }
        }

        assertTrue(
            registriesWithNestedProviders >= 10,
            "Expected nested providers regularly, found $registriesWithNestedProviders/100",
        )
    }

    @Test
    fun `object path variable shape constraints remain generative`() {
        val config =
            Config.default +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.7) +
                (ExplicitFieldResolverWeight to 0.9) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 3) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverFromObjectFieldProviderPathLength to 1..3) +
                (ResolverFromObjectFieldVariableUseDepth to 1..3)
        val random = RandomSource.seeded(86422L)
        var generatedVariables = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val providers =
                registry.variableProviders
                    .filterIsInstance<FromObjectFieldVariableProviderPlan>()

            generatedVariables += providers.size
            assertTrue(providers.all { provider -> provider.responsePath().size in 1..3 })
            assertTrue(providers.all { provider -> provider.useDepth in 1..3 })
        }

        assertTrue(generatedVariables > 0)
    }

    @Test
    fun `object path variables can be restricted to non Query owners`() {
        val config =
            Config.default +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.7) +
                (ExplicitFieldResolverWeight to 0.9) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 2) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariablesOnNonQueryFieldsOnly to true)
        val random = RandomSource.seeded(86423L)
        var generatedVariables = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val providers =
                registry.variableProviders
                    .filterIsInstance<FromObjectFieldVariableProviderPlan>()

            generatedVariables += providers.size
            assertTrue(providers.all { provider -> provider.owner.typeName != "Query" })
        }

        assertTrue(generatedVariables > 0)
    }

    @Test
    fun `object path variable owners can be biased toward an owner dependency`() {
        val config =
            Config.default +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 4..6) +
                (QueryFieldCount to 6..8) +
                (QueryScalarFieldWeight to 0.45) +
                (FieldArgumentWeight to 0.65) +
                (ExplicitFieldResolverWeight to 0.9) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 1) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 1..1) +
                (ResolverFromObjectFieldProviderPathLength to 1..1) +
                (ResolverFromObjectFieldVariableUseDepth to 1..1) +
                (ResolverFromObjectFieldVariableOwnerLimit to 4) +
                (ResolverFromObjectFieldVariableOwnerUseWeight to 1.0) +
                (ResolverVariablesOnQueryFieldsOnly to true)
        val random = RandomSource.seeded(86424L)
        var ownerDependencies = 0

        repeat(1_000) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            ownerDependencies += registry.fromObjectFieldVariableOwnerDependencies.size
        }

        assertTrue(ownerDependencies > 0)
    }

    @Test
    fun `fromArgument variables generate owner-argument-backed fragment arguments`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.8) +
                (ExplicitFieldResolverWeight to 0.8) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0)
        val random = RandomSource.seeded(97531L)
        var generatedVariables = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            generatedVariables += registry.features.fromArgumentVariableCount
            assertTrue(
                registry.variableProviders.all {
                    it is FromArgumentVariableProviderPlan
                },
            )
        }

        assertTrue(generatedVariables > 0)
    }

    @Test
    fun `resolver fragments generate literal and variable convergence`() {
        val config =
            Config.default +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 5..7) +
                (QueryFieldCount to 5..7) +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 0.8) +
                (InputScalarValueRange to 0..2) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 3) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 1..1) +
                (ResolverLiteralVariableConvergenceWeight to 1.0)
        val random = RandomSource.seeded(97532L)
        var generatedConvergences = 0

        repeat(300) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            generatedConvergences += registry.features.literalVariableConvergenceCount
        }

        assertTrue(generatedConvergences > 0)
    }

    @Test
    fun `object path variables can be used below passive top-level branches`() {
        val config =
            Config.default +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 5..7) +
                (QueryFieldCount to 5..7) +
                (FieldArgumentWeight to 0.8) +
                (ExplicitFieldResolverWeight to 0.35) +
                (InterfacesEnabled to false) +
                (NodeResolversEnabled to false) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 3) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 2..3) +
                (ResolverFromObjectFieldProviderPathLength to 1..3) +
                (ResolverFromObjectFieldVariableOwnerLimit to 1) +
                (ResolverFromObjectFieldVariableUseDepth to 2..3) +
                (ResolverFromObjectFieldPassiveUseWeight to 1.0) +
                (UnionsEnabled to false)
        val random = RandomSource.seeded(97533L)
        var generatedPassiveUses = 0
        var maximumVariablesPerOwner = 0

        repeat(300) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            generatedPassiveUses +=
                registry.features.passiveTopLevelFromObjectFieldVariableUseCount
            maximumVariablesPerOwner =
                maxOf(maximumVariablesPerOwner, registry.features.maximumVariablesPerOwner)
        }

        assertTrue(generatedPassiveUses > 0)
        assertTrue(maximumVariablesPerOwner > 1)
    }

    @Test
    fun `list variable providers preserve required element nullability`() {
        val target =
            ListVariableTarget(
                scalar = ScalarKind.ID,
                nullable = false,
                elementNullable = false,
            )

        assertTrue(
            target.matches(
                OutputTypeSpec(
                    namedType = "ID",
                    nullable = false,
                    list = true,
                    elementNullable = false,
                ),
            ),
        )
        assertFalse(
            target.matches(
                OutputTypeSpec(
                    namedType = "ID",
                    nullable = false,
                    list = true,
                    elementNullable = true,
                ),
            ),
        )
        assertFalse(
            ListVariableTarget(
                scalar = ScalarKind.ID,
                nullable = false,
                elementNullable = true,
            ).acceptsNullableTraversal,
        )
        assertTrue(
            ListVariableTarget(
                scalar = ScalarKind.ID,
                nullable = true,
                elementNullable = false,
            ).acceptsNullableTraversal,
        )
    }

    @Test
    fun `coverage profile reaches every current scope generator category`() {
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
                (RecursiveOutputEdgeWeight to 0.5) +
                (DuplicateSelectionWeight to 0.8) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 4) +
                (ResolverArgumentErrorWeight to 0.3) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 2..3)
        val random = RandomSource.seeded(440044L)
        val reached = linkedSetOf<String>()

        repeat(400) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val query = schema.query(config).next(random)
            try {
                registry.world(schema)
            } catch (failure: Throwable) {
                throw AssertionError("Generated invalid schema:\n${schema.sdl}", failure)
            }

            with(schema.features) {
                if (hasListArguments) reached += "list arguments"
                if (hasInputObjectArguments) reached += "input-object arguments"
                if (hasInputObjectListArguments) reached += "input-object list arguments"
                if (hasRecursiveInputTypes) reached += "recursive input types"
                if (hasRecursiveOutputEdges) reached += "recursive output types"
                if (hasImplementationArgumentDefaults) {
                    reached += "implementation argument defaults"
                }
                if (hasInterfaces) reached += "interfaces"
                if (hasUnions) reached += "unions"
            }
            with(registry.features) {
                if (inputSensitiveResolvers > 0) reached += "input-sensitive resolvers"
                if (argumentSensitiveResolvers > 0) reached += "argument-sensitive resolvers"
                if (inputAndArgumentSensitiveResolvers > 0) {
                    reached += "input-and-argument-sensitive resolvers"
                }
                if (resolverErrorArgumentCount > 0) reached += "resolver argument errors"
                if (maximumVariablesPerOwner > 1) reached += "multiple variables per owner"
                if (maximumFromObjectFieldPathLength > 1) reached += "nested provider paths"
                if (hasNestedInputVariable) reached += "nested input variables"
                if (hasListVariable) reached += "list variables"
                if (hasNullableProvider) reached += "nullable providers"
                if (hasAbstractProviderPath) reached += "abstract provider paths"
                if (hasAbstractResolverFragment) reached += "abstract resolver fragments"
            }
            with(query.features) {
                if (hasExactKeyAliasConvergence) reached += "exact-key alias convergence"
                if (hasDistinctArgumentSelections) reached += "distinct argument tuples"
                if (hasMultipleAbstractInlineFragmentBranches) {
                    reached += "multiple abstract branches"
                }
                if (hasAbstractImplementationDefaultSelection) {
                    reached += "abstract implementation defaults"
                }
            }

            val world = registry.world(schema)
            world.selectionsFrom(query.source)
            world.selectionsFrom(query.permutationEquivalentSource)
        }

        val expected =
            setOf(
                "list arguments",
                "input-object arguments",
                "input-object list arguments",
                "recursive input types",
                "recursive output types",
                "implementation argument defaults",
                "interfaces",
                "unions",
                "input-sensitive resolvers",
                "argument-sensitive resolvers",
                "input-and-argument-sensitive resolvers",
                "resolver argument errors",
                "multiple variables per owner",
                "nested provider paths",
                "nested input variables",
                "list variables",
                "nullable providers",
                "abstract provider paths",
                "abstract resolver fragments",
                "exact-key alias convergence",
                "distinct argument tuples",
                "multiple abstract branches",
                "abstract implementation defaults",
            )
        assertEquals(emptySet(), expected - reached, "Unreached categories: ${expected - reached}")
    }

    private companion object {
        val ARGUMENT_INVOCATION =
            Regex("""(?:(alias\d+):\s+)?(\w+)\(arg:\s+([^)]+)\)""")

        val TEST_CONFIG = Config.default
    }
}

private data class ArgumentInvocation(
    val alias: String?,
    val fieldName: String,
    val argument: String,
)

private fun Value.Arguments.containsErrorValue(): Boolean =
    fieldValues.values.any { value -> value.containsErrorValue() }

private fun Value.Input?.containsErrorValue(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.InputList -> values.any { value -> value.containsErrorValue() }
        is Value.InputObject ->
            fieldValues.values.any { value -> value.containsErrorValue() }
        else -> false
    }
