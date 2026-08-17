package model

import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectionMergeTest {
    @Test
    fun `object keys classify selections precisely`() {
        val fixture = Fixture()
        val concrete = fixture.selection("Query", "item")
        val abstract =
            fixture.selection(
                typeName = "Item",
                fieldName = "computed",
                possibleTypes = setOf(fixture.item),
            )

        assertIs<ObjectSelection>(concrete)
        assertFalse(abstract is ObjectSelection)
    }

    @Test
    fun `duplicate leaves merge while distinct argument tuples remain separate`() {
        val fixture = Fixture()
        val one = fixture.selection("Query", "scalar", mapOf("arg" to 1))
        val two = fixture.selection("Query", "scalar", mapOf("arg" to 2))
        val forest = selectionForestOf(one, one, two)

        val merged = forest.merge(fixture.query)
        val oneKey =
            Value.GroundKey.of(
                fixture.schema.objectField("Query", "scalar"),
                mapOf("arg" to 1),
            )
        val missingKey =
            Value.GroundKey.of(
                fixture.schema.objectField("Query", "scalar"),
                mapOf("arg" to 3),
            )

        assertEquals(fixture.query, merged.type)
        assertEquals(2, merged.size)
        assertEquals(setOf(one.key, two.key), merged.keys())
        assertEquals(merged.keys(), merged.byKey().keys)
        assertSame(merged.byKey().getValue(oneKey), merged[oneKey])
        assertFailsWith<NoSuchElementException> { merged[missingKey] }
        val filtered: ObjectSelectionForest =
            merged.filter { selection -> selection.key == oneKey }
        assertEquals(setOf(oneKey), filtered.keys())
        merged.forEach { selection ->
            assertIs<ObjectSelection>(selection)
            assertEquals(fixture.query, selection.key.field.containingType)
            assertEquals(setOf(fixture.query), selection.possibleTypes)
            assertTrue(selection.subselections.isEmpty())
        }
    }

    @Test
    fun `object selection forest validates parent type exclusive applicability and unique keys`() {
        val fixture = Fixture()
        val querySelection =
            assertIs<ObjectSelection>(fixture.selection("Query", "item"))
        val itemSelection =
            assertIs<ObjectSelection>(fixture.selection("ConcreteItem", "a"))
        val inapplicableQuerySelection =
            ObjectSelection.of(
                key = querySelection.key,
                possibleTypes = emptySet(),
                subselections = querySelection.subselections,
            )

        assertFailsWith<IllegalArgumentException> {
            ObjectSelectionForest.of(fixture.query, listOf(itemSelection))
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectSelectionForest.of(fixture.query, listOf(inapplicableQuerySelection))
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectSelectionForest.of(fixture.query, listOf(querySelection, querySelection))
        }
    }

    @Test
    fun `composite duplicates concatenate children without recursively merging them`() {
        val fixture = Fixture()
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")
        val first = fixture.selection("Query", "item", subselections = selectionForestOf(a))
        val second =
            fixture.selection(
                "Query",
                "item",
                subselections = selectionForestOf(a, b),
            )

        val merged = selectionForestOf(first, second).merge(fixture.query)

        val item = merged.single()
        assertEquals(3, item.subselections.size)
        assertEquals(
            setOf(a.key, b.key),
            item.subselections.merge(fixture.item).keys(),
        )
        assertEquals(
            2,
            item.subselections.merge(fixture.item).size,
        )
    }

    @Test
    fun `forest concatenation preserves every occurrence`() {
        val fixture = Fixture()
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")

        val concatenated =
            listOf(
                selectionForestOf(a),
                selectionForestOf(),
                selectionForestOf(a, b),
            ).concatenateSelectionForests()

        assertEquals(3, concatenated.size)
        assertEquals(
            setOf(a.key, b.key),
            concatenated.merge(fixture.item).keys(),
        )
    }

    @Test
    fun `abstract and concrete fields merge after concrete default specialization`() {
        val fixture = Fixture()
        val abstract =
            fixture.selection(
                typeName = "Item",
                fieldName = "computed",
                possibleTypes = setOf(fixture.item),
            )
        val concrete =
            fixture.selection(
                typeName = "ConcreteItem",
                fieldName = "computed",
                arguments = mapOf("factor" to 7),
            )

        val merged = selectionForestOf(abstract, concrete).merge(fixture.item)

        assertEquals(1, merged.size)
        assertEquals(
            Value.Key.of(
                fixture.schema.field("ConcreteItem", "computed"),
                mapOf("factor" to 7),
            ),
            merged.single().key,
        )
    }

    @Test
    fun `inapplicable occurrences contribute neither keys nor descendants`() {
        val fixture = Fixture()
        val hidden = fixture.selection("ConcreteItem", "a")
        val visible = fixture.selection("ConcreteItem", "b")
        val inapplicable =
            fixture.selection(
                typeName = "Query",
                fieldName = "item",
                possibleTypes = emptySet(),
                subselections = selectionForestOf(hidden),
            )
        val applicable =
            fixture.selection(
                typeName = "Query",
                fieldName = "item",
                subselections = selectionForestOf(visible),
            )

        val merged = selectionForestOf(inapplicable, applicable).merge(fixture.query)

        val item = merged.single()
        assertEquals(1, item.subselections.size)
        assertEquals(
            setOf(visible.key),
            item.subselections.merge(fixture.item).keys(),
        )
    }

    @Test
    fun `permuting occurrences preserves the keyed merged result`() {
        val fixture = Fixture()
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")
        val first = fixture.selection("Query", "item", subselections = selectionForestOf(a))
        val second = fixture.selection("Query", "item", subselections = selectionForestOf(b))

        val forward = selectionForestOf(first, second).merge(fixture.query).single()
        val reverse = selectionForestOf(second, first).merge(fixture.query).single()

        assertEquals(forward.key, reverse.key)
        assertEquals(forward.possibleTypes, reverse.possibleTypes)
        assertEquals(forward.subselections.size, reverse.subselections.size)
        assertEquals(
            forward.subselections.merge(fixture.item).keys(),
            reverse.subselections.merge(fixture.item).keys(),
        )
    }

    @Test
    fun `merge coalesces equal open object keys`() {
        val fixture = Fixture()
        val variableField = fixture.schema.objectField("Query", "search")
        val sameX =
            fixture.searchSelection(
                mapOf(
                    "values" to
                        listOf(Value.Variable.of(variableField, "x")),
                ),
            )
        val anotherX =
            fixture.searchSelection(
                mapOf(
                    "values" to
                        listOf(Value.Variable.of(variableField, "x")),
                ),
            )
        val y =
            fixture.searchSelection(
                mapOf(
                    "values" to
                        listOf(Value.Variable.of(variableField, "y")),
                ),
            )
        val literal =
            fixture.searchSelection(
                mapOf("values" to listOf(1)),
            )

        val merged =
            selectionForestOf(sameX, anotherX, y, literal).merge(fixture.query)

        assertEquals(3, merged.size)
        merged.forEach { selection ->
            assertIs<ObjectSelection>(selection)
        }
        assertFailsWith<IllegalStateException> {
            merged.groundKeys()
        }
    }

    @Test
    fun `instantiation rejects an unstamped variable template`() {
        val fixture = Fixture()
        val variableField = fixture.schema.objectField("Query", "search")
        val symbolic =
            fixture.searchSelection(
                mapOf(
                    "values" to
                        listOf(Value.Variable.of(variableField, "x")),
                ),
            )

        assertFailsWith<IllegalStateException> {
            context(fixture.world) {
                selectionForestOf(symbolic)
                    .merge(fixture.query)
                    .instantiateBindings()
            }
        }
    }

    @Test
    fun `instantiation rejects an unbound stamped variable`() {
        val fixture = Fixture()
        val variableField = fixture.schema.objectField("Query", "search")
        val variable = Value.Variable.of(variableField, "x").stamp(emptyList())
        val symbolic =
            fixture.searchSelection(
                mapOf("values" to listOf(variable)),
            )

        assertFailsWith<IllegalStateException> {
            context(fixture.world) {
                selectionForestOf(symbolic)
                    .merge(fixture.query)
                    .instantiateBindings()
            }
        }
    }

    @Test
    fun `bound nested variables merge with equal concrete arguments`() {
        val fixture = Fixture()
        val variableField = fixture.schema.objectField("Query", "search")
        val variable =
            Value.Variable.of(variableField, "x")
                .stamp(listOf(Value.ListIndex.of(0)))
        val symbolic =
            fixture.searchSelection(
                mapOf("values" to listOf(variable)),
            )
        val concrete =
            fixture.searchSelection(
                mapOf("values" to listOf(1)),
            )
        fixture.world.declareBinding(variable)
        fixture.world.completeBinding(variable, Value.Int.of(1))

        val merged =
            context(fixture.world) {
                selectionForestOf(symbolic, concrete)
                    .merge(fixture.query)
                    .instantiateBindings()
            }

        assertEquals(1, merged.size)
        assertEquals(concrete.objectKey(fixture.query), merged.single().key)
    }

    @Test
    fun `repeated merge preserves a key containing substituted list bindings`() {
        val fixture = Fixture()
        val source = fixture.schema.objectField("Query", "source")
        val variable = Value.Variable.of(source, "values").stamp(emptyList())
        val binding =
            Value.Arguments
                .of(source, mapOf("values" to listOf(1, 2)))
                .fieldValues
                .getValue("values")
        val symbolic =
            fixture.selection(
                typeName = "Query",
                fieldName = "nested",
                arguments = mapOf("values" to listOf(variable, variable)),
            )
        fixture.world.declareBinding(variable)
        fixture.world.completeBinding(variable, binding)

        val once =
            context(fixture.world) {
                selectionForestOf(symbolic)
                    .merge(fixture.query)
                    .instantiateBindings()
            }
        val twice =
            context(fixture.world) {
                once.instantiateBindings()
            }

        assertEquals(once.single().key, twice.single().key)
    }

    @Test
    fun `variable markers manufacture and identify ordinary ground selections`() {
        val fixture = Fixture()
        val definingField = fixture.schema.objectField("Query", "search")
        val firstVariable =
            Value.Variable.of(definingField, "first").stamp(emptyList())
        val secondVariable =
            Value.Variable.of(definingField, "second").stamp(emptyList())
        val ordinary = fixture.selection("Query", "scalar", mapOf("arg" to 1))
        val firstMarker =
            Selection.of(
                key = Value.VariableKey.of(ordinary.key, firstVariable),
                possibleTypes = ordinary.possibleTypes,
                subselections = ordinary.subselections,
            )
        val secondMarker =
            Selection.of(
                key = Value.VariableKey.of(ordinary.key, secondVariable),
                possibleTypes = ordinary.possibleTypes,
                subselections = ordinary.subselections,
            )
        val groundKey = ordinary.key as Value.GroundKey
        val value = Value.Int.of(9)
        val resultValue = IntEngineResult.of(9)
        val result =
            ObjectEngineResult.of(
                fixture.query,
                mapOf(groundKey to resultValue),
            )

        val markerOnly:
            Pair<
                ObjectSelectionForest,
                Map<Value.Variable.Stamped, Value.Input?>,
            > =
            runBlocking {
                context(fixture.world) {
                    selectionForestOf(firstMarker).mergeWithVariables(result)
                }
            }

        assertEquals(setOf(groundKey), markerOnly.first.groundKeys())
        assertEquals(mapOf(firstVariable to value), markerOnly.second)

        val combined:
            Pair<
                ObjectSelectionForest,
                Map<Value.Variable.Stamped, Value.Input?>,
            > =
            runBlocking {
                context(fixture.world) {
                    selectionForestOf(ordinary, firstMarker, secondMarker)
                        .mergeWithVariables(result)
                }
            }

        assertEquals(1, combined.first.size)
        assertEquals(
            mapOf(
                firstVariable to value,
                secondVariable to value,
            ),
            combined.second,
        )
    }

    @Test
    fun `variable markers report no binding for absent or incomplete result keys`() {
        val fixture = Fixture()
        val definingField = fixture.schema.objectField("Query", "search")
        val variable = Value.Variable.of(definingField, "value").stamp(emptyList())
        val ordinary = fixture.selection("Query", "scalar", mapOf("arg" to 1))
        val marker =
            Selection.of(
                key = Value.VariableKey.of(ordinary.key, variable),
                possibleTypes = ordinary.possibleTypes,
                subselections = ordinary.subselections,
            )
        val result = ObjectEngineResult.of(fixture.query, mutable = true)

        val absent =
            runBlocking {
                context(fixture.world) {
                    selectionForestOf(marker).mergeWithVariables(result)
                }
            }
        result.reserveCell(ordinary.key as Value.GroundKey).createValuePromise()
        val incomplete =
            runBlocking {
                context(fixture.world) {
                    selectionForestOf(marker).mergeWithVariables(result)
                }
            }

        assertTrue(absent.second.isEmpty())
        assertTrue(incomplete.second.isEmpty())
        assertEquals(setOf(ordinary.key), absent.first.groundKeys())
        assertEquals(absent.first.keys(), incomplete.first.keys())
    }

    @Test
    fun `intermediate markers continue through objects and bind premature values`() {
        val fixture = Fixture()
        val definingField = fixture.schema.objectField("Query", "search")
        val variable = Value.Variable.of(definingField, "value").stamp(emptyList())
        val leaf = fixture.selection("ConcreteItem", "a")
        val markedLeaf =
            Selection.of(
                key = Value.VariableKey.of(leaf.key, variable),
                possibleTypes = leaf.possibleTypes,
                subselections = leaf.subselections,
            )
        val intermediate =
            fixture.selection(
                "Query",
                "optionalItem",
                subselections = selectionForestOf(markedLeaf),
            )
        val markedIntermediate =
            Selection.of(
                key = Value.VariableKey.of(intermediate.key, variable),
                possibleTypes = intermediate.possibleTypes,
                subselections = intermediate.subselections,
            )
        val intermediateKey = intermediate.key as Value.GroundKey
        val leafKey = leaf.key as Value.GroundKey
        val leafValue = Value.Int.of(7)
        val leafResult = IntEngineResult.of(7)
        val child =
            ObjectEngineResult.of(
                fixture.item,
                mapOf(leafKey to leafResult),
            )
        val continuedResult =
            ObjectEngineResult.of(
                fixture.query,
                mapOf(intermediateKey to child),
            )

        val continued =
            runBlocking {
                context(fixture.world) {
                    selectionForestOf(markedIntermediate)
                        .mergeWithVariables(continuedResult)
                }
            }
        val terminal =
            runBlocking {
                context(fixture.world) {
                    continued.first.single().subselections.mergeWithVariables(child)
                }
            }

        assertTrue(continued.second.isEmpty())
        assertEquals(mapOf(variable to leafValue), terminal.second)

        listOf<EngineResult?>(null, ErrorEngineResult).forEach { prematureValue ->
            val prematureResult =
                ObjectEngineResult.of(
                    fixture.query,
                    mapOf(intermediateKey to prematureValue),
                )
            val premature =
                runBlocking {
                    context(fixture.world) {
                        selectionForestOf(markedIntermediate)
                            .mergeWithVariables(prematureResult)
                    }
            }
            assertEquals(
                mapOf<Value.Variable.Stamped, Value.Input?>(
                    variable to
                        if (prematureValue == ErrorEngineResult) Value.Error else null,
                ),
                premature.second,
            )
        }
    }

    private class Fixture {
        val testWorld = TestWorld.fromSDL(SCHEMA)
        val world = testWorld.assumptions
        val schema = world.schema
        val query = schema.query
        val item = schema.type("ConcreteItem") as Schema.ObjectType
        val filter = schema.type("Filter") as Schema.InputObjectType

        fun selection(
            typeName: String,
            fieldName: String,
            arguments: Map<String, Any?> = emptyMap(),
            possibleTypes: Set<Schema.ObjectType> =
                (schema.type(typeName) as Schema.CompositeType).possibleTypes,
            subselections: SelectionForest = selectionForestOf(),
        ): Selection =
            Selection.of(
                key = Value.Key.of(schema.field(typeName, fieldName), arguments),
                possibleTypes = possibleTypes,
                subselections = subselections,
            )

        fun searchSelection(filter: Any): Selection =
            selection(
                typeName = "Query",
                fieldName = "search",
                arguments = mapOf("filter" to filter),
            )
    }

    private companion object {
        val SCHEMA =
            """
            input Filter {
              values: [Int!]!
            }

            interface Item {
              computed: Int!
            }

            type ConcreteItem implements Item {
              computed(factor: Int = 7): Int!
              a: Int!
              b: Int!
            }

            type Query {
              scalar(arg: Int!): Int!
              item: ConcreteItem!
              optionalItem: ConcreteItem
              search(filter: Filter!): Int!
              source(values: [Int!]!): Int!
              nested(values: [[Int]]): Int!
            }
            """.trimIndent()
    }
}
