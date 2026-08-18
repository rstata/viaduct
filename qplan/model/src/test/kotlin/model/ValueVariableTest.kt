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
import kotlin.test.assertTrue

class ValueVariableTest {
    @Test
    fun `ground open values wrap error-free inputs`() {
        val typeExpr = TypeExpr.Named.of(Schema.IntType)
        val input = Value.Int.of(7)

        val ground = assertIs<OpenValue.Ground>(OpenValue.of(typeExpr, input))

        assertEquals(input, ground.data)
        assertEquals(OpenValue.Ground.of(typeExpr, input), ground)
        assertFailsWith<IllegalArgumentException> {
            OpenValue.Ground.of(typeExpr, Value.Error)
        }
        assertFailsWith<IllegalArgumentException> {
            VariableBinding.of(Value.Error)
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
        val source = schema.objectField("Query", "source")
        val template = Value.Variable.of(source, "value")
        val arguments = OpenArguments.of(source, mapOf("value" to template))

        val substituted =
            arguments.substituteTemplates(
                source.arguments,
                mapOf(template to null),
            )

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
        val template = Value.Variable.of(world.schema.objectField("Query", "source"), "value")

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

        val stamped = arguments.stampVars(consume.arguments, path)
        val stampedVariable = template.stamp(path)
        world.declareBinding(stampedVariable)
        world.completeBinding(stampedVariable, Value.Int.of(9))
        val instantiated =
            context(world) {
                stamped.instantiateBindings(consume.arguments)
            }
        val groundedArguments = assertIs<Value.Arguments>(instantiated)
        val filter =
            assertIs<Value.InputObject>(groundedArguments.fieldValues.getValue("filter"))
        val nested =
            assertIs<Value.InputList>(filter.fieldValues.getValue("nested"))
        val values =
            assertIs<Value.InputList>(groundedArguments.fieldValues.getValue("values"))

        assertEquals(Value.Int.of(9), filter.fieldValues.getValue("direct"))
        assertEquals(Value.Int.of(9), nested.values[0])
        assertEquals(Value.Int.of(1), nested.values[1])
        assertEquals(Value.Int.of(9), values.values.single())
        assertEquals(setOf(template), arguments.variableTemplates())
        assertEquals(setOf(stampedVariable), stamped.stampedVariables())
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
            ).stampVars(consume.arguments, path)
        world.declareBinding(variable)
        world.completeBinding(variable, Value.Int.of(9))

        val grounded =
            context(world) {
                arguments.instantiateBindings(consume.arguments)
            }
        val groundedArguments = assertIs<Value.Arguments>(grounded)
        val outer =
            assertIs<Value.InputList>(groundedArguments.fieldValues.getValue("values"))
        val inner = assertIs<Value.InputList>(outer.values.single())

        assertEquals(listOf(Value.Int.of(9)), inner.values)
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
        val source = world.schema.objectField("Query", "source")
        val first = world.schema.objectField("Query", "first")
        val second = world.schema.objectField("Query", "second")
        val template = Value.Variable.of(source, "value")
        val path = listOf(ListEngineResult.Index.of(1))
        val variable = template.stamp(path)
        val arguments =
            OpenArguments
                .of(
                    first,
                    mapOf("filter" to mapOf("value" to template)),
                ).stampVars(first.arguments, path)

        world.declareBinding(variable)
        world.completeBinding(variable, Value.Int.of(9))

        val grounded =
            context(world) {
                arguments.instantiateBindings(second.arguments)
            }
        val groundedArguments = assertIs<Value.Arguments>(grounded)
        val filter =
            assertIs<Value.InputObject>(
                groundedArguments.fieldValues.getValue("filter"),
            )

        assertEquals(
            Value.InputObject.of(
                assertIs<Schema.InputObjectType>(world.schema.type("FirstFilter")),
                mapOf("value" to 9),
            ),
            filter,
        )
        assertEquals(Value.Int.of(9), filter.fieldValues.getValue("value"))
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
                consume.arguments,
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
        val firstStamp = Stamp.Occurrence.of(firstPath, listOf(occurrenceId))
        val secondStamp = Stamp.Occurrence.of(secondPath, listOf(occurrenceId))
        val firstVariable = template.stamp(firstStamp)
        val secondVariable = template.stamp(secondStamp)
        world.declareBinding(firstVariable)
        world.declareBinding(secondVariable)
        world.completeBinding(firstVariable, Value.Int.of(9))
        world.completeBinding(secondVariable, Value.Int.of(9))

        fun stampedKey(selectionStamp: Stamp.Occurrence): ObjectEngineResult.ObjectKey =
            ObjectEngineResult.Key.of(
                stamp = selectionStamp,
                field = consume,
                arguments = arguments.stamp(consume.arguments, selectionStamp),
            )

        fun ground(key: ObjectEngineResult.ObjectKey): ObjectEngineResult.GroundKey =
            context(world) {
                selectionForestOf(
                    Selection.of(
                        key = key,
                        possibleTypes = setOf(world.schema.query),
                        subselections = selectionForestOf(),
                    ),
                ).merge(world.schema.query)
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
                        possibleTypes = setOf(world.schema.query),
                        subselections = selectionForestOf(),
                    ),
                ).applicableGroundSelections(world.schema.query)
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
            Value.Int.of(9),
            assertIs<Value.Arguments>(first.arguments).fieldValues.getValue("value"),
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
        val source = schema.objectField("Query", "source")
        val consume = schema.objectField("Query", "consume")
        val stampedVariable = Value.Variable.of(source, "value").stamp(emptyList())
        val arguments = OpenArguments.of(consume, mapOf("value" to stampedVariable))

        assertFailsWith<IllegalArgumentException> {
            OpenArguments.Template.of(consume.arguments, arguments)
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
        val intConsumer = schema.objectField("Query", "intConsumer")
        val stringConsumer = schema.objectField("Query", "stringConsumer")
        val arguments = OpenArguments.of(intConsumer, mapOf("value" to 1))

        assertFailsWith<IllegalArgumentException> {
            OpenArguments.Template.of(stringConsumer.arguments, arguments)
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
                Stamp.Occurrence.of(
                    stamp,
                    listOf(SelectionOccurrenceId(sourceSelection.key)),
                )
            val variable = variableTemplate.stamp(selectionStamp)
            val arguments =
                OpenArguments.Template
                    .of(consume.arguments, openArguments)
                    .stamp(consume.arguments, selectionStamp)
            world.declareBinding(variable)

            val fetched =
                async {
                    context(world) {
                        arguments.fetchBindings(consume.arguments)
                    }
                }

            assertFalse(fetched.isCompleted)
            world.completeBinding(variable, Value.Int.of(9))
            val grounded = fetched.await()
            val groundedArguments = assertIs<Value.Arguments>(grounded)
            val filter =
                assertIs<Value.InputObject>(
                    groundedArguments.fieldValues.getValue("filter"),
                )
            val values =
                assertIs<Value.InputList>(
                    filter.fieldValues.getValue("values"),
                )
            assertEquals(Value.Int.of(9), values.values.single())
        }
}
