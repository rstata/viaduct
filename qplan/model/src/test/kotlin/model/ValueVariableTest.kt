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

class ValueVariableTest {
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
        val source = schema.objectField("Query", "source")
        val template = Value.Variable.of(source, "value")
        val arguments = OpenArguments.of(source, mapOf("value" to template))

        val substituted = arguments.substituteTemplates(mapOf(template to null))

        assertIs<Value.Arguments>(substituted)
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
        val first = schema.objectField("Query", "first")
        val second = schema.objectField("Query", "second")
        val template = Value.Variable.of(first, "value")

        assertEquals(Value.Variable.of(first, "value"), template)
        assertNotEquals(Value.Variable.of(first, "other"), template)
        assertNotEquals(Value.Variable.of(second, "value"), template)
        assertEquals("Variable.Template(name=value, field=Query/first)", "$template")
    }

    @Test
    fun `stamp identity contains its template and opaque occurrence path`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  first: Int
                  second: Int
                }
                """.trimIndent(),
            ).schema
        val first = schema.objectField("Query", "first")
        val second = schema.objectField("Query", "second")
        val template = Value.Variable.of(first, "value")
        val path = listOf(ListEngineResult.Index.of(0))
        val stamp = template.stamp(path)

        assertEquals(template.stamp(path), stamp)
        assertNotEquals(Value.Variable.of(first, "other").stamp(path), stamp)
        assertNotEquals(Value.Variable.of(second, "value").stamp(path), stamp)
        assertNotEquals<Value.Variable>(template, stamp)
        assertNotEquals(template.stamp(emptyList()), stamp)
        assertEquals(
            "Variable.Stamped(name=value, field=Query/first, path=[index=0])",
            "$stamp",
        )
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
        val first = schema.objectField("Query", "first")
        val consume = schema.objectField("Query", "consume")
        val template = Value.Variable.of(first, "value")
        val arguments =
            OpenArguments.of(
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

        val stamped = arguments.stampVars(path)
        val stampedVariable = template.stamp(path)
        world.declareBinding(stampedVariable)
        world.completeBinding(stampedVariable, Value.Int.of(9))
        val instantiated =
            context(world) {
                stamped.instantiateBindings()
            }
        val filter =
            assertIs<Value.InputObject>(instantiated.fieldValues.getValue("filter"))
        val nested =
            assertIs<Value.InputList>(filter.fieldValues.getValue("nested"))
        val values =
            assertIs<Value.InputList>(instantiated.fieldValues.getValue("values"))

        assertEquals(Value.Int.of(9), filter.fieldValues.getValue("direct"))
        assertEquals(Value.Int.of(9), nested.values[0])
        assertEquals(Value.Int.of(1), nested.values[1])
        assertEquals(Value.Int.of(9), values.values.single())
        assertEquals(setOf(template), arguments.variableTemplates())
        assertFalse(stamped is OpenArguments.Stamped)
    }

    @Test
    fun `grounding singleton coerces a variable binding through nested input lists`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int!
                  consume(values: [[Int!]!]!): Int!
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.objectField("Query", "source")
        val consume = world.schema.objectField("Query", "consume")
        val template = Value.Variable.of(source, "value")
        val path = listOf(ListEngineResult.Index.of(2))
        val variable = template.stamp(path)
        val arguments =
            OpenArguments.of(
                consume,
                mapOf("values" to template),
            ).stampVars(path)
        world.declareBinding(variable)
        world.completeBinding(variable, Value.Int.of(9))

        val grounded =
            context(world) {
                arguments.instantiateBindings()
            }
        val outer = assertIs<Value.InputList>(grounded.fieldValues.getValue("values"))
        val inner = assertIs<Value.InputList>(outer.values.single())

        assertEquals(listOf(Value.Int.of(9)), inner.values)
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
        val source = world.schema.objectField("Query", "source")
        val consume = world.schema.objectField("Query", "consume")
        val template = Value.Variable.of(source, "value")
        val arguments =
            OpenArguments.Template.of(
                OpenArguments.of(consume, mapOf("value" to template)),
            )
        val sourceSelection =
            Selection.of(
                key = ObjectEngineResult.Key.of(consume, arguments),
                possibleTypes = setOf(world.schema.query),
                subselections = selectionForestOf(),
            )
        val firstPath = listOf(ListEngineResult.Index.of(1))
        val secondPath = listOf(ListEngineResult.Index.of(2))
        val occurrenceId = SelectionOccurrenceId(sourceSelection.key)
        val firstStamp = SelectionStamp(firstPath, listOf(occurrenceId))
        val secondStamp = SelectionStamp(secondPath, listOf(occurrenceId))
        val firstVariable = template.stamp(firstStamp)
        val secondVariable = template.stamp(secondStamp)
        world.declareBinding(firstVariable)
        world.declareBinding(secondVariable)
        world.completeBinding(firstVariable, Value.Int.of(9))
        world.completeBinding(secondVariable, Value.Int.of(9))

        fun ground(arguments: OpenArguments): ObjectEngineResult.GroundKey =
            context(world) {
                selectionForestOf(
                    Selection.of(
                        key = ObjectEngineResult.Key.of(consume, arguments),
                        possibleTypes = setOf(world.schema.query),
                        subselections = selectionForestOf(),
                    ),
                ).merge(world.schema.query)
                    .instantiateBindings()
                    .groundKeys()
                    .single()
            }

        val first = ground(arguments.stamp(firstStamp))
        val equalFirst = ground(arguments.stamp(firstStamp))
        val second = ground(arguments.stamp(secondStamp))
        val unstamped = ObjectEngineResult.GroundKey.of(consume, mapOf("value" to 9))
        val reapplied =
            context(world) {
                selectionForestOf(
                    Selection.of(
                        key = first,
                        possibleTypes = setOf(world.schema.query),
                        subselections = selectionForestOf(),
                    ),
                ).applicableGroundSelections(world.schema.query)
                    .groundKeys()
                    .single()
            }

        assertIs<ObjectEngineResult.GroundKey.Stamped>(first)
        assertEquals(first, equalFirst)
        assertEquals(first, reapplied)
        assertNotEquals(first, second)
        assertNotEquals<ObjectEngineResult.GroundKey>(first, unstamped)
        assertEquals(Value.Int.of(9), first.arguments.fieldValues.getValue("value"))
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
        val source = schema.objectField("Query", "source")
        val consume = schema.objectField("Query", "consume")
        val stampedVariable = Value.Variable.of(source, "value").stamp(emptyList())
        val arguments = OpenArguments.of(consume, mapOf("value" to stampedVariable))

        assertFailsWith<IllegalArgumentException> {
            OpenArguments.Template.of(arguments)
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
            val source = world.schema.objectField("Query", "source")
            val consume = world.schema.objectField("Query", "consume")
            val stamp = listOf(ListEngineResult.Index.of(1))
            val variableTemplate = Value.Variable.of(source, "value")
            val openArguments =
                OpenArguments.of(
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
                    possibleTypes = setOf(world.schema.query),
                    subselections = selectionForestOf(),
                )
            val selectionStamp =
                SelectionStamp(
                    stamp,
                    listOf(SelectionOccurrenceId(sourceSelection.key)),
                )
            val variable = variableTemplate.stamp(selectionStamp)
            val arguments =
                OpenArguments.Template.of(openArguments).stamp(selectionStamp)
            world.declareBinding(variable)

            val fetched =
                async {
                    context(world) {
                        arguments.fetchBindings()
                    }
                }

            assertFalse(fetched.isCompleted)
            world.completeBinding(variable, Value.Int.of(9))
            val grounded = fetched.await()
            val filter =
                assertIs<Value.InputObject>(
                    grounded.fieldValues.getValue("filter"),
                )
            val values =
                assertIs<Value.InputList>(
                    filter.fieldValues.getValue("values"),
                )
            assertEquals(Value.Int.of(9), values.values.single())
        }
}
