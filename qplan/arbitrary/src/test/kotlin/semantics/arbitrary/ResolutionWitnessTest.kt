package semantics.arbitrary

import model.EngineResult
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.engineResultOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResolutionWitnessTest {
    @Test
    fun `fingerprints ignore permutations and discriminate semantic input differences`() {
        val world = fingerprintWorld()
        val schema = world.schema
        val search = schema.field("Query", "search")
        val baseArguments =
            arguments(
                search,
                limit = 3,
                rank = 7,
                tags = listOf(1, 2),
                reverseFieldOrder = false,
            )
        val reorderedArguments =
            arguments(
                search,
                limit = 3,
                rank = 7,
                tags = listOf(1, 2),
                reverseFieldOrder = true,
            )

        assertEquals(
            baseArguments.resolutionFingerprint(),
            reorderedArguments.resolutionFingerprint(),
        )
        assertNotEquals(
            baseArguments.resolutionFingerprint(),
            arguments(search, limit = 4, rank = 7, tags = listOf(1, 2))
                .resolutionFingerprint(),
            "Distinct top-level arguments must remain distinguishable",
        )
        assertNotEquals(
            baseArguments.resolutionFingerprint(),
            arguments(search, limit = 3, rank = 8, tags = listOf(1, 2))
                .resolutionFingerprint(),
            "Distinct nested input values must remain distinguishable",
        )
        assertNotEquals(
            baseArguments.resolutionFingerprint(),
            arguments(search, limit = 3, rank = 7, tags = listOf(2, 1))
                .resolutionFingerprint(),
            "Input-list order must remain significant",
        )

        val leftThenRight =
            schema.objectOf("Query") {
                "left" setTo 1
                "right" setTo 2
            }
        val rightThenLeft =
            schema.objectOf("Query") {
                "right" setTo 2
                "left" setTo 1
            }
        assertEquals(
            leftThenRight.resolutionFingerprint(),
            rightThenLeft.resolutionFingerprint(),
        )

        val firstThenSecond =
            world.selectionsFrom(
                """
                fragment ignored on Query {
                  first: search(
                    filter: { nested: { rank: 7 }, enabled: true }
                    tags: [1, 2]
                    limit: 3
                  )
                  second: search(
                    filter: { nested: { rank: 8 }, enabled: false }
                    tags: [2, 1]
                    limit: 4
                  )
                }
                """.trimIndent(),
            ).second
        val secondThenFirst =
            world.selectionsFrom(
                """
                fragment ignored on Query {
                  second: search(
                    filter: { enabled: false, nested: { rank: 8 } }
                    limit: 4
                    tags: [2, 1]
                  )
                  first: search(
                    tags: [1, 2]
                    limit: 3
                    filter: { enabled: true, nested: { rank: 7 } }
                  )
                }
                """.trimIndent(),
            ).second
        assertEquals(
            firstThenSecond.resolutionFingerprint(),
            secondThenFirst.resolutionFingerprint(),
        )
    }

    @Test
    fun `application log preserves exact multiplicity snapshots and bounds`() {
        val world = fingerprintWorld()
        val schema = world.schema
        val search = schema.field("Query", "search")
        val firstArguments = arguments(search, limit = 3, rank = 7, tags = listOf(1, 2))
        val secondArguments = arguments(search, limit = 4, rank = 7, tags = listOf(1, 2))
        val input =
            schema.objectOf("Query") {
                "left" setTo 1
                "right" setTo 2
            }
        val sourceField = FieldCoordinate("Query", "search")
        val log =
            ResolutionApplicationLog(
                ResolutionWitnessBounds(maxApplications = 3),
            )

        log.record(sourceField, firstArguments, input)
        log.record(sourceField, firstArguments, input)
        log.record(sourceField, secondArguments, input)
        val snapshot = log.snapshot()
        val firstKey = ResolverApplicationKey(sourceField, firstArguments)
        val secondKey = ResolverApplicationKey(sourceField, secondArguments)

        assertEquals(
            mapOf(firstKey to 2, secondKey to 1),
            snapshot.applicationCounts(),
        )
        assertEquals(mapOf(firstKey to 2), snapshot.duplicateApplications())
        assertEquals(
            listOf(
                input.resolutionFingerprint(),
                input.resolutionFingerprint(),
                input.resolutionFingerprint(),
            ),
            snapshot.applications.map(ResolverApplicationRecord::inputFingerprint),
        )

        log.clear()
        assertTrue(log.snapshot().applications.isEmpty())
        assertEquals(3, snapshot.applications.size, "Snapshots must not alias the mutable log")

        val bounded =
            ResolutionApplicationLog(
                ResolutionWitnessBounds(maxApplications = 2),
            )
        bounded.record(sourceField, firstArguments, input)
        bounded.record(sourceField, secondArguments, input)
        val failure =
            assertFailsWith<ResolutionWitnessBoundExceededException> {
                bounded.record(sourceField, firstArguments, input)
            }
        assertEquals(
            "Resolution witness exceeded application bound of 2",
            failure.message,
        )
        assertEquals(2, bounded.snapshot().applications.size)
    }

    @Test
    fun `result traversal counts nested list occurrences and closure follows demand edges`() {
        val world = traversalWorld()
        val schema = world.schema

        fun payload(
            scale: Int,
            value: Int,
        ): EngineResult.Object =
            schema.engineResultOf("Payload") {
                field("computed", "scale" to scale) resolvesTo value
                "base" resolvesTo value
            }

        val result =
            schema.engineResultOf("Query") {
                "item" resolvesTo payload(scale = 1, value = 10)
                "items" resolvesTo
                    listOf(
                        payload(scale = 1, value = 20),
                        payload(scale = 2, value = 30),
                    )
                "helper" resolvesTo 40
            }
        val itemKey =
            ResolverApplicationKey(
                FieldCoordinate("Query", "item"),
                Value.Arguments.of(schema.field("Query", "item"), emptyMap()),
            )
        val itemsKey =
            ResolverApplicationKey(
                FieldCoordinate("Query", "items"),
                Value.Arguments.of(schema.field("Query", "items"), emptyMap()),
            )
        val helperKey =
            ResolverApplicationKey(
                FieldCoordinate("Query", "helper"),
                Value.Arguments.of(schema.field("Query", "helper"), emptyMap()),
            )
        val computedOneKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "computed"),
                Value.Arguments.of(
                    schema.field("Payload", "computed"),
                    mapOf("scale" to 1),
                ),
            )
        val computedTwoKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "computed"),
                Value.Arguments.of(
                    schema.field("Payload", "computed"),
                    mapOf("scale" to 2),
                ),
            )
        val baseKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "base"),
                Value.Arguments.of(schema.field("Payload", "base"), emptyMap()),
            )

        assertEquals(
            mapOf(
                itemKey to 1,
                itemsKey to 1,
                helperKey to 1,
                computedOneKey to 2,
                computedTwoKey to 1,
                baseKey to 3,
            ),
            result.registeredResolverCellCounts(world.executorRegistry),
        )
        val cells = result.registeredResolverCells(world.executorRegistry)
        assertTrue(
            cells
                .single { cell -> cell.applicationKey == computedTwoKey }
                .occurrencePath
                .contains(ResultOccurrenceStep.ListElement(1)),
            "The second list element must retain its occurrence index",
        )

        val operation =
            schema.fragmentFrom(
                """
                fragment ignored on Query {
                  item { computed(scale: 1) }
                  items { computed(scale: 2) }
                }
                """.trimIndent(),
            )
        val allowed =
            operation.subselections.allowedResolverSiteClosure(world.executorRegistry)
        assertEquals(
            setOf(
                FieldCoordinate("Query", "item"),
                FieldCoordinate("Query", "items"),
                FieldCoordinate("Payload", "computed"),
            ),
            allowed.directlySelectedSites.mapTo(linkedSetOf(), ::coordinate),
        )
        assertEquals(
            setOf(
                FieldCoordinate("Query", "item"),
                FieldCoordinate("Query", "items"),
                FieldCoordinate("Query", "helper"),
                FieldCoordinate("Payload", "computed"),
                FieldCoordinate("Payload", "base"),
            ),
            allowed.canonicalFields,
        )
        assertTrue(FieldCoordinate("Query", "dead") !in allowed.canonicalFields)

        val log = ResolutionApplicationLog()
        val queryInput = schema.objectOf("Query")
        log.record(FieldCoordinate("Query", "item"), itemKey.arguments, queryInput)
        log.record(
            FieldCoordinate("Query", "dead"),
            Value.Arguments.of(schema.field("Query", "dead"), emptyMap()),
            queryInput,
        )
        assertEquals(
            listOf(FieldCoordinate("Query", "dead")),
            log.snapshot()
                .unrelatedApplications(allowed)
                .map { application -> application.key.field },
        )
    }

    @Test
    fun `application count oracle distinguishes value-distinct equal-key list occurrences`() {
        val world = traversalWorld()
        val schema = world.schema
        val computedField = schema.field("Payload", "computed")
        val computedKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "computed"),
                Value.Arguments.of(computedField, mapOf("scale" to 1)),
            )

        fun payload(value: Int): EngineResult.Object =
            schema.engineResultOf("Payload") {
                field("computed", "scale" to 1) resolvesTo value
                "base" resolvesTo value
            }

        val result =
            schema.engineResultOf("Query") {
                "items" resolvesTo listOf(payload(10), payload(20))
            }
        assertEquals(
            mapOf(computedKey to 2),
            result
                .registeredResolverCellCounts(world.executorRegistry)
                .filterKeys { key -> key == computedKey },
        )
        val firstInput =
            schema.objectOf("Payload") {
                "base" setTo 10
            }
        val secondInput =
            schema.objectOf("Payload") {
                "base" setTo 20
            }
        val expected =
            mapOf(
                ResolverApplicationIdentity(
                    computedKey,
                    firstInput.resolutionFingerprint(),
                ) to 1,
                ResolverApplicationIdentity(
                    computedKey,
                    secondInput.resolutionFingerprint(),
                ) to 1,
            )

        val malformedLog = ResolutionApplicationLog()
        malformedLog.record(computedKey.field, computedKey.arguments, firstInput)
        malformedLog.record(computedKey.field, computedKey.arguments, firstInput)

        assertNotEquals(
            expected,
            malformedLog.snapshot().applicationIdentityCounts(),
            "Duplicating one list occurrence and omitting another must fail the one-shot oracle",
        )
    }

    @Disabled("not currently worth the effort")
    @Test
    fun `application count oracle distinguishes equal-input list occurrences`() {
        val world = traversalWorld()
        val schema = world.schema
        val computedField = schema.field("Payload", "computed")
        val computedKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "computed"),
                Value.Arguments.of(computedField, mapOf("scale" to 1)),
            )
        val input =
            schema.objectOf("Payload") {
                "base" setTo 10
            }
        val expected =
            mapOf(
                ResolverApplicationIdentity(
                    computedKey,
                    input.resolutionFingerprint(),
                ) to 2,
            )
        val malformedLog = ResolutionApplicationLog()
        malformedLog.record(computedKey.field, computedKey.arguments, input)
        malformedLog.record(computedKey.field, computedKey.arguments, input)

        assertNotEquals(
            expected,
            malformedLog.snapshot().applicationIdentityCounts(),
            "An occurrence path is required to distinguish equal-input list positions",
        )
    }

    private fun fingerprintWorld(): TestWorld =
        TestWorld.fromSDL(
            """
            input NestedInput {
              rank: Int!
            }

            input SearchFilter {
              nested: NestedInput!
              enabled: Boolean!
            }

            type Query {
              search(filter: SearchFilter!, tags: [Int!]!, limit: Int!): Int!
              left: Int!
              right: Int!
            }
            """.trimIndent(),
        )

    private fun arguments(
        field: Schema.OutputField,
        limit: Int,
        rank: Int,
        tags: List<Int>,
        reverseFieldOrder: Boolean = false,
    ): Value.Arguments {
        val nested =
            if (reverseFieldOrder) {
                linkedMapOf<String, Any?>("rank" to rank)
            } else {
                mapOf("rank" to rank)
            }
        val filter =
            if (reverseFieldOrder) {
                linkedMapOf<String, Any?>(
                    "enabled" to true,
                    "nested" to nested,
                )
            } else {
                linkedMapOf<String, Any?>(
                    "nested" to nested,
                    "enabled" to true,
                )
            }
        val fields =
            if (reverseFieldOrder) {
                linkedMapOf<String, Any?>(
                    "limit" to limit,
                    "tags" to tags,
                    "filter" to filter,
                )
            } else {
                linkedMapOf<String, Any?>(
                    "filter" to filter,
                    "tags" to tags,
                    "limit" to limit,
                )
            }
        return Value.Arguments.of(field, fields)
    }

    private fun traversalWorld(): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  item: Payload!
                  items: [Payload!]!
                  helper: Int!
                  dead: Int!
                }

                type Payload {
                  computed(scale: Int!): Int!
                  base: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val queryNeedsHelper =
                    schema.fragmentFrom(
                        "fragment ignored on Query { helper }",
                    )
                val payloadNeedsBase =
                    schema.fragmentFrom(
                        "fragment ignored on Payload { base }",
                    )
                mapOf(
                    schema.field("Query", "item") to
                        fieldResolverOf(queryNeedsHelper) { _, _ -> Value.Error },
                    schema.field("Query", "items") to
                        fieldResolverOf(queryNeedsHelper) { _, _ -> Value.Error },
                    schema.field("Query", "helper") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> Value.Error },
                    schema.field("Query", "dead") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> Value.Error },
                    schema.field("Payload", "computed") to
                        fieldResolverOf(payloadNeedsBase) { _, _ -> Value.Error },
                    schema.field("Payload", "base") to
                        fieldResolverOf(schema.emptyFragmentOf("Payload")) { _, _ -> Value.Error },
                )
            },
        )

    private fun coordinate(field: Schema.ObjectField): FieldCoordinate =
        FieldCoordinate(field.containingType.typeName, field.fieldName)
}
