package semantics.resolver09

import model.Assumptions
import model.PathComponent
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.registry.FieldResolver
import model.registry.ResolverRegistry
import semantics.ReactorEvent
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReactorTest {
    @Test
    fun `readiness progressively discovers an active grandchild below an active sibling`() {
        val testWorld = activeGrandchildWorld()
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { viewer { message shallow } }",
            )
        val events = mutableListOf<ReactorEvent>()

        val result =
            resolveWithDependencyValidation(
                world = world,
                root = world.objectOf("Query"),
                selections = fragment.subselections,
                eventObserver = events::add,
            )

        val messageEvaluations =
            events
                .filterIsInstance<ReactorEvent.ReadinessEvaluated>()
                .filter { event -> event.coordinate.lastFieldName() == "message" }
        assertTrue(messageEvaluations.size >= 3)
        assertEquals(
            setOf("Query.viewer/User.profile"),
            messageEvaluations[0].requiredCoordinates.mapTo(linkedSetOf()) {
                it.pathSignature()
            },
        )
        assertEquals(
            setOf(
                "Query.viewer/User.profile",
                "Query.viewer/User.profile/Profile.rendered",
            ),
            messageEvaluations[1].requiredCoordinates.mapTo(linkedSetOf()) {
                it.pathSignature()
            },
        )
        assertTrue(messageEvaluations.last().absentCoordinates.isEmpty())

        val shallowCompletion =
            events.indexOfFirst { event ->
                event is ReactorEvent.ResolverFinished &&
                    event.coordinate.lastFieldName() == "shallow"
            }
        val renderedCompletion =
            events.indexOfFirst { event ->
                event is ReactorEvent.ResolverFinished &&
                    event.coordinate.lastFieldName() == "rendered"
            }
        assertTrue(shallowCompletion in 0 until renderedCompletion)
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `resolver waits for an active descendant below a passive sibling`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Profile { rendered: String! }
                    type User {
                      profile: Profile!
                      message: String!
                    }
                    type Query { viewer: User! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val profileKey = schema.groundKey("User", "profile")
                    val renderedKey = schema.groundKey("Profile", "rendered")
                    mapOf(
                        schema.field("Query", "viewer") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("User") {
                                    "profile" setTo objectOf("Profile")
                                }
                            },
                        schema.field("Profile", "rendered") to
                            fieldResolverOf(schema.emptyFragmentOf("Profile")) { _, _ ->
                                Value.String.of("ready")
                            },
                        schema.field("User", "message") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { profile { rendered } }",
                                ),
                            ) { input, _ ->
                                val profile =
                                    input.fieldValues.getValue(profileKey) as Value.Object
                                profile.fieldValues.getValue(renderedKey) as Value.String
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { viewer { message } }")
        val events = mutableListOf<ReactorEvent>()

        val result =
            resolveWithDependencyValidation(
                world = world,
                root = world.objectOf("Query"),
                selections = fragment.subselections,
                eventObserver = events::add,
            )

        val renderedCompletion =
            events.indexOfFirst { event ->
                event is ReactorEvent.ResolverFinished &&
                    event.coordinate.lastFieldName() == "rendered"
            }
        val messageCompletion =
            events.indexOfFirst { event ->
                event is ReactorEvent.ResolverFinished &&
                    event.coordinate.lastFieldName() == "message"
            }
        val messageDependencies =
            events
                .filterIsInstance<ReactorEvent.ResolverDependenciesApplied>()
                .single { event -> event.coordinate.lastFieldName() == "message" }
                .dependencyCoordinates
                .mapTo(linkedSetOf(), List<PathComponent>::pathSignature)
        assertTrue(
            "Query.viewer/User.profile/Profile.rendered" in messageDependencies,
        )
        assertTrue(renderedCompletion in 0 until messageCompletion)
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `readiness refresh crosses a passive field and two active fields`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Detail { rendered: String! }
                    type Profile { detail: Detail! }
                    type User {
                      profile: Profile!
                      message: String!
                    }
                    type Query { viewer: User! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val profileKey = schema.groundKey("User", "profile")
                    val detailKey = schema.groundKey("Profile", "detail")
                    val renderedKey = schema.groundKey("Detail", "rendered")
                    mapOf(
                        schema.field("Query", "viewer") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("User") {
                                    "profile" setTo objectOf("Profile")
                                }
                            },
                        schema.field("Profile", "detail") to
                            fieldResolverOf(schema.emptyFragmentOf("Profile")) { _, _ ->
                                schema.objectOf("Detail")
                            },
                        schema.field("Detail", "rendered") to
                            fieldResolverOf(schema.emptyFragmentOf("Detail")) { _, _ ->
                                Value.String.of("ready")
                            },
                        schema.field("User", "message") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on User {
                                      profile { detail { rendered } }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val profile =
                                    input.fieldValues.getValue(profileKey) as Value.Object
                                val detail =
                                    profile.fieldValues.getValue(detailKey) as Value.Object
                                detail.fieldValues.getValue(renderedKey) as Value.String
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { viewer { message } }")
        val events = mutableListOf<ReactorEvent>()

        val result =
            resolveWithDependencyValidation(
                world = world,
                root = world.objectOf("Query"),
                selections = fragment.subselections,
                eventObserver = events::add,
            )

        val messageEvaluations =
            events
                .filterIsInstance<ReactorEvent.ReadinessEvaluated>()
                .filter { event -> event.coordinate.lastFieldName() == "message" }
        assertEquals(
            setOf("Query.viewer/User.profile/Profile.detail"),
            messageEvaluations.first().requiredCoordinates.mapTo(linkedSetOf()) {
                it.pathSignature()
            },
        )
        assertEquals(
            setOf(
                "Query.viewer/User.profile/Profile.detail",
                "Query.viewer/User.profile/Profile.detail/Detail.rendered",
            ),
            messageEvaluations.last().requiredCoordinates.mapTo(linkedSetOf()) {
                it.pathSignature()
            },
        )
        assertTrue(messageEvaluations.last().absentCoordinates.isEmpty())
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `readiness enumerates exact list positions after their active ancestor completes`() {
        val testWorld = activeListGrandchildWorld()
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom("fragment ignored on Query { viewer { message } }")
        val events = mutableListOf<ReactorEvent>()

        val result =
            resolveWithDependencyValidation(
                world = world,
                root = world.objectOf("Query"),
                selections = fragment.subselections,
                eventObserver = events::add,
            )

        val messageEvaluations =
            events
                .filterIsInstance<ReactorEvent.ReadinessEvaluated>()
                .filter { event -> event.coordinate.lastFieldName() == "message" }
        assertEquals(
            setOf("Query.viewer/User.profiles"),
            messageEvaluations.first().requiredCoordinates.mapTo(linkedSetOf()) {
                it.pathSignature()
            },
        )
        val expanded =
            messageEvaluations.first { evaluation ->
                evaluation.requiredCoordinates.any { coordinate ->
                    coordinate.any { component -> component is Value.ListIndex }
                }
            }
        assertEquals(
            setOf(
                "Query.viewer/User.profiles",
                "Query.viewer/User.profiles/[2]/Profile.rendered",
                "Query.viewer/User.profiles/[3]/Profile.rendered",
            ),
            expanded.requiredCoordinates.mapTo(linkedSetOf()) { it.pathSignature() },
        )
        assertTrue(
            expanded.requiredCoordinates.none { coordinate ->
                coordinate.pathSignature().contains("/[0]/") ||
                    coordinate.pathSignature().contains("/[1]/")
            },
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `quiescence reports missing passive producer structure`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { passive: Int!, result: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "passive") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                        schema.field("Query", "result") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { passive }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(
                                    schema.groundKey("Query", "passive"),
                                ) as Value.Int
                            },
                    )
                },
            )
        val passive = testWorld.schema.objectField("Query", "passive")
        val malformedRegistry =
            registryOverride(testWorld.resolverRegistry) { field, delegate ->
                if (field == passive) null else delegate.resolver(field)
            }
        val world =
            Assumptions.of(
                schema = testWorld.schema,
                resolverRegistry = malformedRegistry,
            )
        val fragment =
            world.fragmentFrom("fragment ignored on Query { result }")

        val failure =
            assertFailsWith<IllegalResolverStateException> {
                context(world) {
                    world.objectOf("Query") {
                        "passive" setTo 1
                    }.resolve(fragment.subselections)
                }
            }

        assertTrue(failure.message.orEmpty().contains("missing producer structure"))
        assertTrue(failure.message.orEmpty().contains("missing passive or engine-owned content"))
        assertTrue(failure.message.orEmpty().contains("Query.passive"))
    }

    @Test
    fun `quiescence reports a transitive resolver cycle`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { first: Int!, second: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "first") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { second }",
                                ),
                            ) { _, _ -> Value.Int.of(1) },
                        schema.field("Query", "second") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(2)
                            },
                    )
                },
            )
        val first = testWorld.schema.objectField("Query", "first")
        val second = testWorld.schema.objectField("Query", "second")
        val malformedRegistry =
            registryOverride(testWorld.resolverRegistry) { field, delegate ->
                when (field) {
                    first -> delegate.resolver(first)
                    second -> delegate.resolver(first)
                    else -> null
                }
            }
        val world =
            Assumptions.of(
                schema = testWorld.schema,
                resolverRegistry = malformedRegistry,
            )
        val fragment =
            world.fragmentFrom("fragment ignored on Query { first }")

        val failure =
            assertFailsWith<IllegalResolverStateException> {
                context(world) {
                    world.objectOf("Query").resolve(fragment.subselections)
                }
            }

        assertTrue(failure.message.orEmpty().contains("dependency cycle"))
        assertTrue(failure.message.orEmpty().contains("Dependency cycle:"))
        assertTrue(failure.message.orEmpty().contains("Query.second"))
    }

    private fun activeGrandchildWorld(): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Profile { raw: String!, rendered: String! }
                type User {
                  profile: Profile!
                  message: String!
                  shallow: String!
                }
                type Query { viewer: User! }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val profileKey = schema.groundKey("User", "profile")
                val renderedKey = schema.groundKey("Profile", "rendered")
                val rawKey = schema.groundKey("Profile", "raw")
                mapOf(
                    schema.field("Query", "viewer") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            schema.objectOf("User")
                        },
                    schema.field("User", "profile") to
                        fieldResolverOf(schema.emptyFragmentOf("User")) { _, _ ->
                            schema.objectOf("Profile") {
                                "raw" setTo "ready"
                            }
                        },
                    schema.field("Profile", "rendered") to
                        fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ignored on Profile { raw }",
                            ),
                        ) { input, _ ->
                            input.fieldValues.getValue(rawKey) as Value.String
                        },
                    schema.field("User", "message") to
                        fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ignored on User { profile { rendered } }",
                            ),
                        ) { input, _ ->
                            val profile =
                                input.fieldValues.getValue(profileKey) as Value.Object
                            profile.fieldValues.getValue(renderedKey) as Value.String
                        },
                    schema.field("User", "shallow") to
                        fieldResolverOf(schema.emptyFragmentOf("User")) { _, _ ->
                            Value.String.of("shallow")
                        },
                )
            },
        )

    private fun activeListGrandchildWorld(): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Profile { raw: Int!, rendered: Int! }
                type User {
                  profiles: [Profile]
                  message: Int!
                }
                type Query { viewer: User! }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val profiles = schema.field("User", "profiles")
                val profileElement =
                    (profiles.typeExpr as TypeExpr.List<Schema.OutputType>).elementType
                val profilesKey = schema.groundKey("User", "profiles")
                val renderedKey = schema.groundKey("Profile", "rendered")
                val rawKey = schema.groundKey("Profile", "raw")
                mapOf(
                    schema.field("Query", "viewer") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            schema.objectOf("User")
                        },
                    profiles to
                        fieldResolverOf(schema.emptyFragmentOf("User")) { _, _ ->
                            Value.OutputList.of(
                                profileElement,
                                listOf(
                                    null,
                                    Value.Error,
                                    schema.objectOf("Profile") {
                                        "raw" setTo 2
                                    },
                                    schema.objectOf("Profile") {
                                        "raw" setTo 3
                                    },
                                ),
                            )
                        },
                    schema.field("Profile", "rendered") to
                        fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ignored on Profile { raw }",
                            ),
                        ) { input, _ ->
                            input.fieldValues.getValue(rawKey) as Value.Int
                        },
                    schema.field("User", "message") to
                        fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ignored on User { profiles { rendered } }",
                            ),
                        ) { input, _ ->
                            val values =
                                (input.fieldValues.getValue(profilesKey)
                                    as Value.OutputList).values
                            Value.Int.of(
                                values
                                    .filter { value ->
                                        value is Value.Object && value !== Value.Error
                                    }.sumOf { value ->
                                    val profile = value as Value.Object
                                    (
                                        profile.fieldValues.getValue(renderedKey)
                                            as Value.Int
                                    ).intValue
                                },
                            )
                        },
                )
            },
        )
}

private fun registryOverride(
    delegate: ResolverRegistry,
    resolver: (Schema.ObjectField, ResolverRegistry) -> FieldResolver?,
): ResolverRegistry =
    object : ResolverRegistry {
        override fun contains(field: Schema.ObjectField): Boolean =
            resolver(field, delegate) != null

        override fun resolver(field: Schema.ObjectField): FieldResolver =
            resolver(field, delegate)
                ?: error("Missing overridden resolver: ${field.containingType.typeName}.${field.fieldName}")

        override fun mayDemandFrom(field: Schema.ObjectField): Set<Schema.ObjectField> =
            delegate.mayDemandFrom(field)
    }

private fun Schema.groundKey(
    typeName: String,
    fieldName: String,
): Value.GroundKey =
    Value.GroundKey.of(
        objectField(typeName, fieldName),
        emptyMap(),
    )

private fun List<PathComponent>.lastFieldName(): String =
    (last() as Value.GroundKey).field.fieldName

private fun List<PathComponent>.pathSignature(): String =
    joinToString("/") { component ->
        when (component) {
            is Value.GroundKey ->
                "${component.field.containingType.typeName}.${component.field.fieldName}"
            is Value.ListIndex -> "[${component.index}]"
        }
    }
