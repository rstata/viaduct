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
        assertEquals(emptyList(), stamp.stamp?.occurrenceLineage)
        assertFailsWith<IllegalArgumentException> {
            stamp.stamp(path)
        }
        assertEquals(
            "Variable.Occurrence(name=value, field=Query/first, path=[index=0], lineage=0)",
            "$stamp",
        )
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
    fun `open key survives grounding as stamped ground-key identity`() {
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
        val sourceSelection =
            Selection.of(
                key = ObjectEngineResult.Key.of(consume, arguments),
                possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                subselections = selectionForestOf(),
            )
        val firstPath = listOf(ListEngineResult.Index.of(1))
        val secondPath = listOf(ListEngineResult.Index.of(2))
        val occurrenceId = SelectionOccurrenceId(sourceSelection.key)
        val firstStamp = Stamp.Occurrence.of(firstPath, listOf(occurrenceId))
        val secondStamp = Stamp.Occurrence.of(secondPath, listOf(occurrenceId))
        val firstVariable = template.stamp(firstStamp)
        val secondVariable = template.stamp(secondStamp)
        world.declareBinding(firstVariable)
        world.declareBinding(secondVariable)
        world.completeBinding(firstVariable, 9)
        world.completeBinding(secondVariable, 9)

        fun stampedKey(selectionStamp: Stamp.Occurrence): ObjectEngineResult.ObjectKey =
            ObjectEngineResult.Key.of(
                stamp = selectionStamp,
                field = consume,
                arguments = arguments.stamp(consume, selectionStamp),
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

        val firstOpen = stampedKey(firstStamp)
        val equalFirstOpen = stampedKey(firstStamp)
        val secondOpen = stampedKey(secondStamp)
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

        assertEquals(firstStamp, firstOpen.stamp)
        assertEquals(firstVariable.stamp, firstOpen.stamp)
        assertEquals(setOf(firstVariable), firstOpen.stampedVariables())
        assertEquals(firstOpen, equalFirstOpen)
        assertNotEquals(firstOpen, secondOpen)
        assertIs<ObjectEngineResult.GroundKey>(first)
        assertEquals(firstStamp, first.stamp)
        assertEquals(first, equalFirst)
        assertEquals(first, reapplied)
        assertNotEquals(first, second)
        assertNotEquals<ObjectEngineResult.GroundKey>(first, unstamped)
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
                Stamp.Occurrence.of(
                    stamp,
                    listOf(SelectionOccurrenceId(sourceSelection.key)),
                )
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
