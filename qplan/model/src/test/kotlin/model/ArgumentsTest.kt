package model

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArgumentsTest {
    @Test
    fun `empty resolved arguments reuse one value without skipping defaults or required checks`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  argumentless: Int
                  optional(value: Int): Int
                  defaulted(value: Int = 7): Int
                  required(value: Int!): Int
                }
                """.trimIndent(),
            ).schema
        val argumentless = schema.requireObjectField("Query", "argumentless")
        val optional = schema.requireObjectField("Query", "optional")
        val defaulted = schema.requireObjectField("Query", "defaulted")
        val required = schema.requireObjectField("Query", "required")

        val empty = Arguments.Resolved.of(argumentless, emptyMap())
        val optionalEmpty = Arguments.Resolved.of(optional, emptyMap())
        val defaultedArguments = Arguments.Resolved.of(defaulted, emptyMap())

        assertSame(empty, Arguments.Resolved.of(argumentless, emptyMap()))
        assertSame(empty, optionalEmpty)
        assertTrue(empty.fieldValues.isEmpty())
        assertEquals(7, defaultedArguments.fieldValues.getValue("value"))
        assertFailsWith<ClassCastException> {
            Arguments.Resolved.of(required, emptyMap())
        }
    }

    @Test
    fun `resolved arguments retain natural input data`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  consume(value: Int!): Int
                }
                """.trimIndent(),
            ).schema
        val consume = schema.requireObjectField("Query", "consume")

        val arguments = Arguments.Resolved.of(consume, mapOf("value" to 7))

        assertEquals(7, arguments.fieldValues.getValue("value"))
        assertFailsWith<ClassCastException> {
            Arguments.Resolved.of(consume, mapOf("value" to EngineErrorData.of()))
        }
    }

    @Test
    fun `template substitution preserves a present null binding`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  source(value: Int): Int
                }
                """.trimIndent(),
            ).schema
        val source = schema.requireObjectField("Query", "source")
        val template = Arguments.Variable.of(source, "value")
        val arguments = Arguments.of(source, mapOf("value" to template))

        val substituted =
            arguments.substituteTemplates(
                source,
                mapOf(template to null),
            )

        assertIs<Arguments.Resolved>(substituted)
        assertEquals(null, substituted.fieldValues.getValue("value"))
    }

    @Test
    fun `template identity contains its name and defining field`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  first: Int
                  second: Int
                }
                """.trimIndent(),
            ).schema
        val first = schema.requireObjectField("Query", "first")
        val second = schema.requireObjectField("Query", "second")
        val template = Arguments.Variable.of(first, "value")

        assertEquals(Arguments.Variable.of(first, "value"), template)
        assertNotEquals(Arguments.Variable.of(first, "other"), template)
        assertNotEquals(Arguments.Variable.of(second, "value"), template)
        assertTrue(template.isTemplate)
        assertFalse(template.isStamped)
        assertNull(template.stamp)
        assertEquals("Variable.Template(name=value, field=Query/first)", "$template")
    }

    @Test
    fun `stamp identity contains its template and occurrence path`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  first: Int
                  second: Int
                }
                """.trimIndent(),
            ).schema
        val first = schema.requireObjectField("Query", "first")
        val second = schema.requireObjectField("Query", "second")
        val template = Arguments.Variable.of(first, "value")
        val path = listOf(ListEngineResult.Index.of(0))
        val stamp = template.stamp(path)

        assertEquals(template.stamp(path), stamp)
        assertNotEquals(Arguments.Variable.of(first, "other").stamp(path), stamp)
        assertNotEquals(Arguments.Variable.of(second, "value").stamp(path), stamp)
        assertNotEquals<Arguments.Variable>(template, stamp)
        assertNotEquals(template.stamp(emptyList()), stamp)
        assertFalse(stamp.isTemplate)
        assertTrue(stamp.isStamped)
        assertEquals(path, stamp.stamp?.resolverPath)
        assertFailsWith<IllegalArgumentException> {
            stamp.stamp(path)
        }
        assertEquals(
            "Variable.Occurrence(name=value, field=Query/first, path=[index=0])",
            "$stamp",
        )
    }

    @Test
    fun `symbolic equality uses stamped variable identity and recursive position`() {
        val world =
            TestWorld.fromSDL(
                """
                input Filter {
                  direct: Int
                  nested: [Int]
                }

                type Query {
                  source: Int
                  consume(filter: Filter): Int
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val consume = world.schema.requireObjectField("Query", "consume")
        val firstPath = listOf(ListEngineResult.Index.of(0))
        val secondPath = listOf(ListEngineResult.Index.of(1))
        val firstVariable = Arguments.Variable.of(source, "value").stamp(firstPath)
        val equalFirstVariable = Arguments.Variable.of(source, "value").stamp(firstPath)
        val secondVariable = Arguments.Variable.of(source, "value").stamp(secondPath)
        val differentlyNamedVariable = Arguments.Variable.of(source, "other").stamp(firstPath)

        fun arguments(variable: Arguments.Variable): Arguments =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to variable,
                            "nested" to listOf(1, variable),
                        ),
                ),
            )

        val first = arguments(firstVariable)
        val equalFirst = arguments(equalFirstVariable)
        val second = arguments(secondVariable)
        val differentlyNamed = arguments(differentlyNamedVariable)
        val literal =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to 7,
                            "nested" to listOf(1, 7),
                        ),
                ),
            )
        val differentPositions =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to 1,
                            "nested" to listOf(firstVariable, 1),
                        ),
                ),
            )
        val firstHash = first.hashCode()
        val secondHash = second.hashCode()

        assertEquals(first, equalFirst)
        assertEquals(firstHash, equalFirst.hashCode())
        assertEquals("first", mapOf(first to "first")[equalFirst])
        assertNotEquals(first, second)
        assertNotEquals(first, differentlyNamed)
        assertNotEquals(first, literal)
        assertNotEquals(first, differentPositions)

        world.declareBinding(firstVariable)
        world.declareBinding(secondVariable)
        world.completeBinding(firstVariable, 7)
        world.completeBinding(secondVariable, 7)

        assertEquals(firstHash, first.hashCode())
        assertEquals(secondHash, second.hashCode())
        assertNotEquals(first, second)
    }

    @Test
    fun `argument defaults normalize before structural equality`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  consume(value: Int = 7): Int
                }
                """.trimIndent(),
            ).schema
        val consume = schema.requireObjectField("Query", "consume")
        val omitted = Arguments.of(consume, emptyMap())
        val explicit = Arguments.of(consume, mapOf("value" to 7))

        assertEquals(omitted, explicit)
        assertEquals(omitted.hashCode(), explicit.hashCode())
    }

    @Test
    fun `binding storage rejects a variable template`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int
                }
                """.trimIndent(),
            ).assumptions
        val template = Arguments.Variable.of(world.schema.requireObjectField("Query", "source"), "value")

        assertFailsWith<IllegalArgumentException> {
            world.declareBinding(template)
        }
    }

    @Test
    fun `variable-only stamping recursively stamps and instantiates variable templates`() {
        val world =
            TestWorld.fromSDL(
                """
                input Filter {
                  direct: Int
                  nested: [Int]
                }

                type Query {
                  first: Int
                  consume(filter: Filter, values: [Int]): Int
                }
                """.trimIndent(),
            ).assumptions
        val schema = world.schema
        val first = schema.requireObjectField("Query", "first")
        val consume = schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(first, "value")
        val arguments =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to template,
                            "nested" to listOf(template, 1),
                        ),
                    "values" to listOf(template),
                ),
            )
        val path = listOf(ListEngineResult.Index.of(2))

        val stamped = arguments.stampVars(consume, path)
        val stampedVariable = template.stamp(path)
        world.declareBinding(stampedVariable)
        world.completeBinding(stampedVariable, 9)
        val instantiated =
            context(world) {
                stamped.instantiateBindings(consume)
            }
        val groundedArguments = assertIs<Arguments.Resolved>(instantiated)
        val filter =
            assertIs<EngineInputObjectData>(
                groundedArguments.fieldValues.getValue("filter"),
            )
        val nested =
            assertIs<EngineInputListData>(filter["nested"])
        val values =
            assertIs<EngineInputListData>(groundedArguments.fieldValues.getValue("values"))

        assertEquals(9, filter["direct"])
        assertEquals(9, nested[0])
        assertEquals(1, nested[1])
        assertEquals(9, values.single())
        assertEquals(setOf(template), arguments.variableTemplates())
        assertEquals(setOf(stampedVariable), stamped.stampedVariables())
    }

    @Test
    fun `grounding does not coerce a scalar variable binding through nested input lists`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int!
                  consume(values: [[Int!]!]!): Int!
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val consume = world.schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(source, "value")
        val path = listOf(ListEngineResult.Index.of(2))
        val variable = template.stamp(path)
        val arguments =
            Arguments.of(
                consume,
                mapOf("values" to template),
            ).stampVars(consume, path)
        world.declareBinding(variable)
        world.completeBinding(variable, 9)

        assertFailsWith<ClassCastException> {
            context(world) {
                arguments.instantiateBindings(consume)
            }
        }
    }

    @Test
    fun `grounding uses the supplied argument definition instead of carried metadata`() {
        val world =
            TestWorld.fromSDL(
                """
                input FirstFilter {
                  value: Int
                }

                input SecondFilter {
                  value: Int
                }

                type Query {
                  source: Int
                  first(filter: FirstFilter): Int
                  second(filter: SecondFilter): Int
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val first = world.schema.requireObjectField("Query", "first")
        val second = world.schema.requireObjectField("Query", "second")
        val template = Arguments.Variable.of(source, "value")
        val path = listOf(ListEngineResult.Index.of(1))
        val variable = template.stamp(path)
        val arguments =
            Arguments
                .of(
                    first,
                    mapOf("filter" to mapOf("value" to template)),
                ).stampVars(first, path)

        world.declareBinding(variable)
        world.completeBinding(variable, 9)

        val grounded =
            context(world) {
                arguments.instantiateBindings(second)
            }
        val groundedArguments = assertIs<Arguments.Resolved>(grounded)
        val filter =
            assertIs<EngineInputObjectData>(
                groundedArguments.fieldValues.getValue("filter"),
            )

        assertEquals(
            mapOf("value" to 9),
            filter,
        )
        assertEquals(9, filter["value"])
    }

    @Test
    fun `symbolic keys distinguish variable instances and coalesce after grounding`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int
                  consume(value: Int): Int
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val consume = world.schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(source, "value")
        val arguments =
            Arguments.Template.of(
                consume,
                Arguments.of(consume, mapOf("value" to template)),
            )
        val firstPath = listOf(ListEngineResult.Index.of(1))
        val secondPath = listOf(ListEngineResult.Index.of(2))
        val firstStamp = Stamp.Occurrence.of(firstPath)
        val secondStamp = Stamp.Occurrence.of(secondPath)
        val firstVariable = template.stamp(firstStamp)
        val secondVariable = template.stamp(secondStamp)
        world.declareBinding(firstVariable)
        world.declareBinding(secondVariable)
        world.completeBinding(firstVariable, 9)
        world.completeBinding(secondVariable, 9)

        fun symbolicKey(variableStamp: Stamp.Occurrence): ObjectEngineResult.ObjectKey =
            ObjectEngineResult.Key.of(
                field = consume,
                arguments = arguments.stamp(consume, variableStamp),
            )

        fun ground(key: ObjectEngineResult.ObjectKey): ObjectEngineResult.GroundKey =
            context(world) {
                selectionForestOf(
                    Selection.of(
                        key = key,
                        possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                        subselections = selectionForestOf(),
                    ),
                ).merge(world.schema.requireQueryTypeDef())
                    .instantiateBindings()
                    .groundKeys()
                    .single()
            }

        val firstOpen = symbolicKey(firstStamp)
        val equalFirstOpen = symbolicKey(firstStamp)
        val secondOpen = symbolicKey(secondStamp)
        val first = ground(firstOpen)
        val equalFirst = ground(equalFirstOpen)
        val second = ground(secondOpen)
        val unstamped = ObjectEngineResult.GroundKey.of(consume, mapOf("value" to 9))
        val reapplied =
            context(world) {
                selectionForestOf(
                    Selection.of(
                        key = first,
                        possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                        subselections = selectionForestOf(),
                    ),
                ).applicableGroundSelections(world.schema.requireQueryTypeDef())
                    .groundKeys()
                    .single()
            }

        assertEquals(setOf(firstVariable), firstOpen.stampedVariables())
        assertEquals(firstOpen, equalFirstOpen)
        assertNotEquals(firstOpen, secondOpen)
        assertIs<ObjectEngineResult.GroundKey>(first)
        assertEquals(first, equalFirst)
        assertEquals(first, reapplied)
        assertEquals(first, second)
        assertEquals<ObjectEngineResult.GroundKey>(first, unstamped)
        assertEquals(
            9,
            assertIs<Arguments.Resolved>(first.arguments).fieldValues.getValue("value"),
        )
    }

    @Test
    fun `argument template rejects an existing stamped variable`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int
                  consume(value: Int): Int
                }
                """.trimIndent(),
            ).schema
        val source = schema.requireObjectField("Query", "source")
        val consume = schema.requireObjectField("Query", "consume")
        val stampedVariable = Arguments.Variable.of(source, "value").stamp(emptyList())
        val arguments = Arguments.of(consume, mapOf("value" to stampedVariable))

        assertFailsWith<IllegalArgumentException> {
            Arguments.Template.of(consume, arguments)
        }
    }

    @Test
    fun `argument template validates its expected argument definition`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  intConsumer(value: Int): Int
                  stringConsumer(value: String): Int
                }
                """.trimIndent(),
            ).schema
        val intConsumer = schema.requireObjectField("Query", "intConsumer")
        val stringConsumer = schema.requireObjectField("Query", "stringConsumer")
        val arguments = Arguments.of(intConsumer, mapOf("value" to 1))

        assertFailsWith<IllegalArgumentException> {
            Arguments.Template.of(stringConsumer, arguments)
        }
    }

    @Test
    fun `open arguments recursively fetch incomplete stamped variables`(): Unit =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    """
                    input Filter {
                      values: [Int]
                    }

                    type Query {
                      source: Int
                      consume(filter: Filter): Int
                    }
                    """.trimIndent(),
                ).assumptions
            val source = world.schema.requireObjectField("Query", "source")
            val consume = world.schema.requireObjectField("Query", "consume")
            val stamp = listOf(ListEngineResult.Index.of(1))
            val variableTemplate = Arguments.Variable.of(source, "value")
            val openArguments =
                Arguments.of(
                    consume,
                    mapOf(
                        "filter" to
                            mapOf(
                                "values" to listOf(variableTemplate),
                            ),
                    ),
                )
            val sourceSelection =
                Selection.of(
                    key = ObjectEngineResult.Key.of(consume, openArguments),
                    possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                    subselections = selectionForestOf(),
                )
            val selectionStamp =
                Stamp.Occurrence.of(stamp)
            val variable = variableTemplate.stamp(selectionStamp)
            val arguments =
                Arguments.Template
                    .of(consume, openArguments)
                    .stamp(consume, selectionStamp)
            world.declareBinding(variable)

            val fetched =
                async {
                    context(world) {
                        arguments.fetchBindings(consume)
                    }
                }

            assertFalse(fetched.isCompleted)
            world.completeBinding(variable, 9)
            val grounded = fetched.await()
            val groundedArguments = assertIs<Arguments.Resolved>(grounded)
            val filter =
                assertIs<EngineInputObjectData>(
                    groundedArguments.fieldValues.getValue("filter"),
                )
            val values =
                assertIs<EngineInputListData>(
                    filter["values"],
                )
            assertEquals(9, values.single())
        }
}
