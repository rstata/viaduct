package semantics.arbitrary

import model.Arguments
import model.Assumptions
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import model.registry.ProviderFragment
import model.engineObjectDataOf
import model.objectOf
import model.outputType
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneratorTest {
    @Test
    fun `random parent generation varies chains lists abstract targets and resolver inputs`() {
        val config =
            Config.default +
                (ParentFieldsEnabled to true) +
                (RandomParentFieldsEnabled to true) +
                (ListsEnabled to true) +
                (UnionsEnabled to true) +
                (MaxOutputListDepth to 2) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0)
        val random = RandomSource.seeded(2026090501L)
        var listProducers = 0
        var abstractTargets = 0
        var maximumDepth = 0
        var parentDemandResolvers = 0
        var argumentBearingParentScalars = 0
        var boundedDiagonalResolvers = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            assertTrue(schema.features.randomParentFieldCount > 0)
            assertTrue(
                schema.query.fields
                    .filter { field -> field.name.startsWith("query") }
                    .none { field ->
                        field.type.namedType.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX)
                    },
            )
            registry.world(schema)

            listProducers += schema.features.randomParentListProducerCount
            abstractTargets += schema.features.randomParentAbstractTargetCount
            maximumDepth = maxOf(maximumDepth, schema.features.maximumParentChainDepth)
            argumentBearingParentScalars +=
                schema.allObjects
                    .filter { objectType ->
                        objectType.name.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX)
                    }.flatMap(ObjectDefinition::fields)
                    .count { field ->
                        field.name.startsWith("value") && field.arguments.size >= 2
                    }
            parentDemandResolvers +=
                registry.parentDemandOwnerFields.keys.count { field ->
                    field.typeName.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX)
                }
            boundedDiagonalResolvers +=
                registry.objectFragmentSources.count { (field, source) ->
                    field.typeName.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX) &&
                        source.contains("resolverParentCoverage: parent {\n    __typename")
                }
        }

        assertTrue(listProducers > 0)
        assertTrue(abstractTargets > 0)
        assertTrue(maximumDepth >= 4)
        assertTrue(parentDemandResolvers > 0)
        assertTrue(argumentBearingParentScalars > 0)
        assertTrue(boundedDiagonalResolvers > 0)
    }

    @Test
    fun `parent generation reaches a variable-free great-grandparent selection`() {
        val config =
            Config.default +
                (ParentFieldsEnabled to true) +
                (MaxSelectionDepth to 6) +
                (ResolverVariablesEnabled to true) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0)
        val random = RandomSource.seeded(2026090402L)
        val schema = Arb.schema(config).next(random)
        val registry = schema.registry(config).next(random)
        val query = schema.query(config).next(random)
        val resultOwner =
            FieldCoordinate(
                GENERATED_PARENT_GREAT_GRANDCHILD_TYPE,
                GENERATED_PARENT_RESULT_FIELD,
            )

        assertEquals(3, schema.features.maximumParentChainDepth)
        assertEquals(3, registry.features.maximumParentSelectionDepth)
        assertEquals(3, registry.parentDemandOwnerFields.getValue(resultOwner))
        assertTrue(
            registry.objectFragmentSources.getValue(resultOwner).contains(
                "parent {\n    parent {\n      parent {",
            ),
        )
        assertFalse(registry.objectFragmentSources.getValue(resultOwner).contains('$'))
        assertTrue(query.source.contains(GENERATED_PARENT_RESULT_FIELD))
        registry.world(schema)
    }

    @Test
    fun `generated schemas registries and queries form valid worlds`() {
        val random = RandomSource.seeded(90210L)

        repeat(100) {
            val schema = Arb.schema(TEST_CONFIG).next(random)
            val registry = schema.registry(TEST_CONFIG).next(random)
            val query = schema.query(TEST_CONFIG).next(random)
            val world = registry.world(schema)

            val (nominalType, selections) = world.selectionsFrom(query.source)

            assertEquals(world.schema.requireQueryTypeDef(), nominalType)
            assertFalse(selections.isEmpty())
        }
    }

    @Test
    fun `default registry generation inserts no sometimes-passive fields`() {
        val schema = Arb.schema().next(RandomSource.seeded(10101L))
        val defaultRegistry = schema.registry().next(RandomSource.seeded(20202L))
        val explicitZeroRegistry =
            schema
                .registry(Config.default + (SometimesPassiveFieldWeight to 0.0))
                .next(RandomSource.seeded(20202L))

        assertEquals(0, defaultRegistry.features.sometimesPassiveFieldCount)
        assertEquals(defaultRegistry.fieldValues, explicitZeroRegistry.fieldValues)
        assertEquals(defaultRegistry.nodeValues, explicitZeroRegistry.nodeValues)
        assertEquals(defaultRegistry.features, explicitZeroRegistry.features)
    }

    @Test
    fun `resolver query fragment generation is independently configurable`() {
        val schema = Arb.schema().next(RandomSource.seeded(11111L))
        val disabled =
            schema
                .registry(Config.default)
                .next(RandomSource.seeded(22222L))
        val enabled =
            schema
                .registry(
                    Config.default +
                        (ResolverQueryFragmentsEnabled to true),
                ).next(RandomSource.seeded(22222L))

        assertEquals(0, disabled.features.queryFragmentCount)
        assertTrue(disabled.queryFragmentSources.values.all(String::isEmpty))
        assertEquals(
            enabled.fieldResolverCoordinates.size,
            enabled.features.queryFragmentCount,
        )
        assertTrue(enabled.queryFragmentSources.values.all(String::isNotEmpty))
        enabled.world(schema)
    }

    @Test
    fun `resolver query fragments retain acyclic concrete dependencies`() {
        val config =
            Config.default +
                (ExplicitFieldResolverWeight to 1.0) +
                (InterfacesEnabled to true) +
                (ResolverFragmentsEnabled to false) +
                (ResolverQueryFragmentsEnabled to true)
        val random = RandomSource.seeded(23232L)

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            schema.registry(config).next(random).world(schema)
        }
    }

    @Test
    fun `sometimes-passive generation supplies registered argumentless fields`() {
        val config =
            Config.default +
                (ArgumentsEnabled to false) +
                (ErrorValueWeight to 0.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (ListsEnabled to false) +
                (NodeResolversEnabled to false) +
                (NullValueWeight to 0.0) +
                (ObjectFieldCount to 3..3) +
                (ResolverFragmentsEnabled to false) +
                (SchemaObjectCount to 2..2) +
                (SometimesPassiveFieldWeight to 1.0)
        val schema = Arb.schema(config).next(RandomSource.seeded(30303L))
        val registry = schema.registry(config).next(RandomSource.seeded(40404L))
        val suppliedFields =
            registry.fieldValues.values
                .flatMapTo(linkedSetOf()) { value ->
                    value.registeredFields(registry.fieldResolverCoordinates)
                }

        assertTrue(registry.features.sometimesPassiveFieldCount > 0)
        assertTrue(suppliedFields.isNotEmpty())
        suppliedFields.forEach { supplied ->
            val field =
                schema.fieldsOn(supplied.typeName)
                    .single { field -> field.name == supplied.fieldName }
            assertTrue(field.arguments.isEmpty())
            assertEquals(
                ResolverProgramKind.CONSTANT,
                registry.resolverProgram(supplied),
            )
        }
    }

    @Test
    fun `sometimes-passive generation never supplies fields with arguments`() {
        val config =
            Config.default +
                (ArgumentsEnabled to true) +
                (ExplicitFieldResolverWeight to 1.0) +
                (FieldArgumentWeight to 0.5) +
                (ListsEnabled to false) +
                (NodeResolversEnabled to false) +
                (ObjectFieldCount to 5..5) +
                (SchemaObjectCount to 3..3) +
                (SometimesPassiveFieldWeight to 1.0)
        val random = RandomSource.seeded(50505L)
        var suppliedFieldCount = 0

        repeat(20) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val suppliedFields =
                registry.fieldValues.values
                    .flatMapTo(linkedSetOf()) { value ->
                        value.registeredFields(registry.fieldResolverCoordinates)
                    }
            suppliedFieldCount += suppliedFields.size
            suppliedFields.forEach { supplied ->
                val field =
                    schema.fieldsOn(supplied.typeName)
                        .single { field -> field.name == supplied.fieldName }
                assertTrue(field.arguments.isEmpty())
            }
        }

        assertTrue(suppliedFieldCount > 0)
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
            countWorld.schema.requireObjectField(
                coordinate.typeName,
                coordinate.fieldName,
            )
        val input = countWorld.schema.objectOf("Query")
        val arguments = Arguments.Resolved.of(field, emptyMap())

        registry.clearResolutionApplicationCounts()
        context(Assumptions.of(countWorld.schema, countWorld.resolverRegistry, false)) {
            countWorld.resolverRegistry.resolver(field)(
                input = input,
                queryValue = engineObjectDataOf(countWorld.schema.requireQueryTypeDef()),
                arguments = arguments,
            )
        }

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

                val field = world.schema.requireObjectField(coordinate.typeName, coordinate.fieldName)
                val input = world.schema.objectOf(coordinate.typeName)
                val arguments = Arguments.Resolved.of(field, emptyMap())
                val resolver = world.resolverRegistry.resolver(field)

                context(Assumptions.of(world.schema, world.resolverRegistry, false)) {
                    assertEquals(
                        resolver(
                            input,
                            engineObjectDataOf(world.schema.requireQueryTypeDef()),
                            arguments,
                        ).outputResolutionFingerprint(),
                        resolver(
                            input,
                            engineObjectDataOf(world.schema.requireQueryTypeDef()),
                            arguments,
                        ).outputResolutionFingerprint(),
                    )
                }
                checkedResolvers += 1
            }
        }

        assertTrue(checkedResolvers > 0)
    }

    @Test
    fun `generated hash values are deterministic and seed and salt sensitive`() {
        val schema = Arb.schema().next(RandomSource.seeded(4815162344L))
        val world = schema.registry().next(RandomSource.seeded(4815162345L)).world(schema).assumptions
        val hashField = world.schema.requireField("Object0", GENERATED_HASH_FIELD)
        val plan = GeneratedHashPlan(salt = 17)

        val first =
            plan.materialize(
                schema = world.schema,
                typeExpr = hashField.outputType,
                generatedHashSeed = 23,
            )
        val repeated =
            plan.materialize(
                schema = world.schema,
                typeExpr = hashField.outputType,
                generatedHashSeed = 23,
            )
        val different =
            plan.materialize(
                schema = world.schema,
                typeExpr = hashField.outputType,
                generatedHashSeed = 24,
            )
        val differentSalt =
            GeneratedHashPlan(salt = 18).materialize(
                schema = world.schema,
                typeExpr = hashField.outputType,
                generatedHashSeed = 23,
            )

        assertEquals(
            first.outputResolutionFingerprint(),
            repeated.outputResolutionFingerprint(),
        )
        assertNotEquals(
            first.outputResolutionFingerprint(),
            different.outputResolutionFingerprint(),
        )
        assertNotEquals(
            first.outputResolutionFingerprint(),
            differentSalt.outputResolutionFingerprint(),
        )
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
                    (registry.objectFragmentSources.values + registry.queryFragmentSources.values)
                        .any { source -> "\$$variableName" in source },
                )
            }
        }

        assertTrue(generatedVariables > 0)
    }

    @Test
    fun `query-only resolver fragments receive fromArgument variables`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (QueryFieldCount to 6..8) +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (ResolverFragmentArgumentFieldWeight to 1.0) +
                (ResolverFragmentsEnabled to false) +
                (ResolverQueryFragmentsEnabled to true) +
                (ResolverQueryFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableCount to 1..1) +
                (ResolverVariableWeight to 1.0)
        val random = RandomSource.seeded(86425L)
        var queryVariableUses = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            assertTrue(registry.objectFragmentSources.values.all(String::isEmpty))
            registry.variableProviders
                .filterIsInstance<FromArgumentVariableProviderPlan>()
                .forEach { provider ->
                    if (
                        "\$${provider.variableName}" in
                        registry.queryFragmentSources.getValue(provider.owner)
                    ) {
                        queryVariableUses += 1
                    }
                }
        }

        assertTrue(queryVariableUses > 0)
    }

    @Test
    fun `one fromArgument variable can be used by both resolver fragments`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (QueryFieldCount to 6..8) +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (ResolverFragmentArgumentFieldWeight to 1.0) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverQueryFragmentsEnabled to true) +
                (ResolverQueryFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableCount to 1..1) +
                (ResolverVariableWeight to 1.0)
        val random = RandomSource.seeded(86426L)
        var sharedVariableUses = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            registry.variableProviders
                .filterIsInstance<FromArgumentVariableProviderPlan>()
                .forEach { provider ->
                    val variable = "\$${provider.variableName}"
                    if (
                        variable in registry.objectFragmentSources.getValue(provider.owner) &&
                        variable in registry.queryFragmentSources.getValue(provider.owner)
                    ) {
                        sharedVariableUses += 1
                    }
                }
        }

        assertTrue(sharedVariableUses > 0)
    }

    @Test
    fun `fromObjectField providers stay object rooted while query fragments use them`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (QueryFieldCount to 6..8) +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (ResolverFragmentArgumentFieldWeight to 1.0) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverQueryFragmentsEnabled to true) +
                (ResolverQueryFragmentWeight to 1.0) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableCount to 1..1) +
                (ResolverVariableWeight to 1.0)
        val random = RandomSource.seeded(86427L)
        var queryVariableUses = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.variableProviders
                .filterIsInstance<FromFieldVariableProviderPlan>()
                .filter { provider ->
                    provider.providerFragment == ProviderFragment.OBJECT
                }
                .forEach { provider ->
                    assertTrue(
                        provider.selection in
                            registry.objectFragments.getValue(provider.owner).selections,
                        "Provider for \$${provider.variableName} was not in its object fragment",
                    )
                    if (
                        "\$${provider.variableName}" in
                        registry.queryFragmentSources.getValue(provider.owner)
                    ) {
                        queryVariableUses += 1
                    }
                }
            registry.world(schema)
        }

        assertTrue(queryVariableUses > 0)
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
    fun `from-field variable shape constraints remain generative`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (QueryFieldCount to 6..8) +
                (QueryScalarFieldWeight to 0.5) +
                (FieldArgumentWeight to 0.8) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to false) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 3) +
                (ResolverQueryFragmentsEnabled to true) +
                (ResolverQueryFragmentWeight to 1.0) +
                (ResolverVariablesEnabled to true) +
                (ResolverFromQueryFieldVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 2..3) +
                (ResolverFromFieldProviderPathLength to 1..3) +
                (ResolverFromFieldVariableUseDepth to 1..3)
        val random = RandomSource.seeded(86426L)
        val generatedProviderFragments = mutableSetOf<ProviderFragment>()

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val providers =
                registry.variableProviders
                    .filterIsInstance<FromFieldVariableProviderPlan>()

            registry.world(schema)
            generatedProviderFragments += providers.map(FromFieldVariableProviderPlan::providerFragment)
            assertTrue(providers.all { provider -> provider.responsePath().size in 1..3 })
            assertTrue(providers.all { provider -> provider.useDepth in 1..3 })
        }

        assertEquals(ProviderFragment.entries.toSet(), generatedProviderFragments)
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
                    .filterIsInstance<FromFieldVariableProviderPlan>()
                    .filter { provider ->
                        provider.providerFragment == ProviderFragment.OBJECT
                    }

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
                (ResolverFromFieldProviderPathLength to 1..1) +
                (ResolverFromFieldVariableUseDepth to 1..1) +
                (ResolverFromFieldVariableOwnerLimit to 4) +
                (ResolverFromFieldVariableOwnerUseWeight to 1.0)
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
    fun `fromArgument variables regularly traverse nested input objects`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 1.0) +
                (InputListTypeWeight to 0.0) +
                (InputObjectCount to 2..3) +
                (InputObjectTypeWeight to 0.6) +
                (MaxInputTypeDepth to 2) +
                (NullableTypeWeight to 1.0) +
                (ExplicitFieldResolverWeight to 1.0) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentNestedPathWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0)
        val random = RandomSource.seeded(97535L)
        var variables = 0
        var nestedPaths = 0
        var nullableTraversals = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            variables += registry.features.fromArgumentVariableCount
            nestedPaths += registry.features.fromArgumentNestedPathVariableCount
            nullableTraversals +=
                registry.features.fromArgumentNullableTraversalVariableCount
        }

        assertTrue(nestedPaths > 0, "variables=$variables nestedPaths=$nestedPaths")
        assertTrue(
            nullableTraversals > 0,
            "variables=$variables nullableTraversals=$nullableTraversals",
        )
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
    fun `object path variables generate literal and variable convergence`() {
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
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 2..3) +
                (ResolverLiteralVariableConvergenceWeight to 1.0)
        val random = RandomSource.seeded(97533L)
        var generatedConvergences = 0

        repeat(300) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            generatedConvergences +=
                registry.features.fromObjectFieldLiteralVariableConvergenceCount
        }

        assertTrue(generatedConvergences > 0)
    }

    @Test
    fun `one variable is reused by multiple selections in one resolver fragment`() {
        val config =
            Config.default +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 5..7) +
                (QueryFieldCount to 5..7) +
                (FieldArgumentWeight to 1.0) +
                (ExplicitFieldResolverWeight to 0.8) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFragmentDepth to 3) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 2..3)
        val random = RandomSource.seeded(97534L)
        var reusedVariables = 0

        repeat(300) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            reusedVariables += registry.features.sameFragmentVariableReuseCount
        }

        assertTrue(reusedVariables > 0)
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
                (ResolverFromFieldProviderPathLength to 1..3) +
                (ResolverFromFieldVariableOwnerLimit to 1) +
                (ResolverFromFieldVariableUseDepth to 2..3) +
                (ResolverFromFieldPassiveUseWeight to 1.0) +
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
    fun `object path variables regularly supply provider arguments`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.5) +
                (ExplicitFieldResolverWeight to 1.0) +
                (NodeResolversEnabled to false) +
                (QueryFieldCount to 6..6) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverFromFieldProviderArgumentVariableWeight to 1.0) +
                (ResolverVariableCount to 2..4) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariablesEnabled to true)
        val random = RandomSource.seeded(97534L)
        var registriesWithProviderArgumentVariables = 0

        repeat(100) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            if (registry.features.fromObjectFieldProviderArgumentVariableCount > 0) {
                registriesWithProviderArgumentVariables += 1
            }
        }

        assertTrue(
            registriesWithProviderArgumentVariables >= 10,
            "Only $registriesWithProviderArgumentVariables of 100 registries " +
                "contained a provider-argument variable dependency",
        )
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
    fun `list variable targets accept singleton coercion through every list layer`() {
        val target =
            ListVariableTarget(
                scalar = ScalarKind.INT,
                nullable = false,
                elementNullabilities = listOf(false, false),
            )

        assertTrue(
            target.accepts(
                ScalarInputTypeSpec(ScalarKind.INT, nullable = false),
                allowSingletonCoercion = true,
            ),
        )
        assertTrue(
            target.accepts(
                ListInputTypeSpec(
                    element = ScalarInputTypeSpec(ScalarKind.INT, nullable = false),
                    nullable = false,
                ),
                allowSingletonCoercion = true,
            ),
        )
        assertTrue(
            target.matches(
                OutputTypeSpec(
                    namedType = "Int",
                    nullable = false,
                    list = false,
                    elementNullable = false,
                ),
                allowSingletonCoercion = true,
            ),
        )
        assertFalse(
            target.accepts(
                ScalarInputTypeSpec(ScalarKind.INT, nullable = true),
                allowSingletonCoercion = true,
            ),
        )
    }

    @Test
    fun `resolver variables generate scalar to list singleton coercion`() {
        val config =
            Config.default +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 5..7) +
                (QueryFieldCount to 5..7) +
                (FieldArgumentWeight to 1.0) +
                (InputListTypeWeight to 0.5) +
                (MaxInputTypeDepth to 3) +
                (ExplicitFieldResolverWeight to 0.8) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (ResolverVariableCount to 2..3) +
                (ResolverVariableSingletonCoercionEnabled to true)
        val random = RandomSource.seeded(97535L)
        var singletonCoercions = 0

        repeat(300) {
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)

            registry.world(schema)
            singletonCoercions += registry.features.singletonCoercionVariableCount
        }

        assertTrue(singletonCoercions > 0)
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
                if (hasMultipleArgumentField) reached += "multiple field arguments"
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
                "multiple field arguments",
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

private fun ValuePlan.registeredFields(
    registered: Set<FieldCoordinate>,
): Set<FieldCoordinate> =
    when (this) {
        is ListPlan -> elements.flatMapTo(linkedSetOf()) { it.registeredFields(registered) }
        is ObjectPlan ->
            fields.keys.filterTo(linkedSetOf()) { it in registered } +
                fields.values.flatMapTo(linkedSetOf()) { it.registeredFields(registered) }
        else -> emptySet()
    }

private data class ArgumentInvocation(
    val alias: String?,
    val fieldName: String,
    val argument: String,
)

private fun Arguments.Resolved.containsErrorValue(): Boolean =
    false
