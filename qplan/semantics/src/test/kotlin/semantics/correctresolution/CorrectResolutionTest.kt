package semantics.correctresolution

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.engineResultOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.registry.ResolutionExecutionContext
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.ObjectSelectionForest
import model.testing.fieldResolverOf
import model.testing.selectiveFieldResolverOf
import viaduct.graphql.schema.ViaductSchema
import model.testing.TestWorld
import semantics.resolver26.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import semantics.shared.SharedOperationContext
import semantics.shared.ResolverInvocationObservation

class CorrectResolutionTest {
    @Test
    fun `direct resolve invocation and invocation through correctness replay do not cause invocation observations`() = runBlocking {
        val testWorld = TestWorld.fromDSL("extend type Query { value: Int @resolver(result: 7) }")
        val events = CopyOnWriteArrayList<ResolverInvocationObservation>()
        val observer = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                events += observation
            }
        }
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world, resolverObserver = observer)
        val fragment = world.fragmentFrom("fragment Main on Query { value }")
        val root = operation.resolve(fragment.subselections)
        assertEquals(1, events.size)
        repeat(2) { assertTrue(root.correctResolution(operation, fragment)) }
        val field = world.schema.requireObjectField("Query", "value")
        context(world) {
            world.resolverRegistry.resolver(field)(
                engineObjectDataOf(world.schema.requireQueryTypeDef()),
                engineObjectDataOf(world.schema.requireQueryTypeDef()),
                Arguments.Resolved.of(field, emptyMap()),
                selectionForestOf(),
                ResolutionExecutionContext.Unsupported,
            )
        }
        assertEquals(1, events.size)
    }

    @Test
    fun `correctness reapplies a selective resolver with completed output demand`() {
        val observedFields = mutableListOf<Set<String>>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      name: String!
                      age: Int!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val user = schema.requireObjectField("Query", "user")
                    mapOf(
                        user to
                            selectiveFieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _, selections ->
                                    val fields = mutableSetOf<String>()
                                    selections.forEach { selection -> fields += selection.key.field.name }
                                    observedFields += fields
                                    schema.objectOf("User") {
                                        if ("name" in fields) "name" setTo "Ada"
                                        if ("age" in fields) "age" setTo 37
                                    }
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
        val selections =
            world
                .fragmentFrom(
                    """
                    fragment ignored on Query {
                      user {
                        name
                      }
                    }
                    """.trimIndent(),
                ).subselections
        val result = operation.resolve(selections)
        val querySelections = selections.merge(world.schema.requireQueryTypeDef())

        assertTrue(result.correctResolution(operation, querySelections))
        assertEquals(listOf(setOf("name"), setOf("name")), observedFields)

        // A second judgment and each standalone predicate must reapply independently.
        assertTrue(result.correctResolution(operation, querySelections))
        assertEquals(3, observedFields.size)
        assertTrue(result.isClosedUnderResolverDemand(operation))
        assertEquals(4, observedFields.size)
        assertTrue(result.conformsToResolvers(operation))
        assertEquals(5, observedFields.size)
    }

    @Test
    fun `selections must be rooted at Query`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val result = world.engineResultOf("Query")
        val operation = SharedOperationContext.create(world)
        val profileSelections =
            ObjectSelectionForest.of(
                type = world.schema.requireType("Profile") as ViaductSchema.Object,
                selections = emptyList(),
            )

        assertFailsWith<IllegalArgumentException> {
            result.correctResolution(operation, profileSelections)
        }
    }

    @Test
    fun `resolver query fragment witness participates in correctness`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.requireObjectField("Query", "source")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          aliased: source
                                        }
                                        """.trimIndent(),
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.get("aliased")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val consumerKey = ObjectEngineResult.GroundKey.of(consumer, emptyMap())
        val selections =
            world
                .fragmentFrom(
                    """
                    fragment ignored on Query {
                      consumer
                    }
                    """.trimIndent(),
                ).subselections
                .merge(world.schema.requireQueryTypeDef())
        val result =
            world.engineResultOf("Query") {
                "consumer" resolvesTo 7
            }
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(consumerKey))
        val missingObservation = SharedOperationContext.create(world)

        assertFalse(result.correctResolution(missingObservation, selections))

        val incorrectObservation =
            SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
        incorrectObservation.resolverObserver.onQueryFragmentPrepared(
            occurrenceId,
            world.engineResultOf("Query") {
                "source" resolvesTo 8
            },
        )
        assertFalse(result.correctResolution(incorrectObservation, selections))

        val correctObservation =
            SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
        correctObservation.resolverObserver.onQueryFragmentPrepared(
            occurrenceId,
            world.engineResultOf("Query") {
                "source" resolvesTo 7
            },
        )
        assertTrue(result.correctResolution(correctObservation, selections))

        correctObservation.resolverObserver.onQueryFragmentPrepared(
            occurrenceId,
            world.engineResultOf("Query") {
                "source" resolvesTo 7
            },
        )
        assertFalse(result.correctResolution(correctObservation, selections))
    }

    @Test
    fun `resolver fromArgument binding must agree with its owning arguments`() {
        val world =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      consumer(seed: Int!): Int!
                        @resolver(
                          of: "source(value: ${'$'}seed)"
                          result: "sum(source)"
                        )
                      source(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            ).assumptions
        val operation = SharedOperationContext.create(world)
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val source = world.schema.requireObjectField("Query", "source")
        val consumerKey =
            ObjectEngineResult.GroundKey.of(
                consumer,
                mapOf("seed" to 7),
            )
        val result =
            ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
                mutable = true,
            )
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(consumerKey))
        val variable = Arguments.Variable.of(consumer, "seed").instantiate(occurrenceId)
        operation.variableBindings.bindVariable(requireNotNull(variable.instanceId), 99)
        val symbolicSourceKey =
            ObjectEngineResult.ObjectKey.of(
                field = source,
                arguments = Arguments.of(source, mapOf("value" to variable)),
            )
        result.reserveCell(symbolicSourceKey).apply {
            setValue(99)
        }
        result.reserveCell(consumerKey).apply {
            setValue(99)
        }
        result.freeze()
        val selections =
            world
                .fragmentFrom(
                    """
                    fragment ignored on Query {
                      consumer(seed: 7)
                    }
                    """.trimIndent(),
                ).subselections
                .merge(world.schema.requireQueryTypeDef())

        assertFalse(result.correctResolution(operation, selections))
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type Profile {
              name: String!
            }

            type Query {
              profile: Profile!
            }
            """.trimIndent()
    }
}
