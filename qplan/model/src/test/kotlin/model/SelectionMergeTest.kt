package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionMergeTest {
    @Test
    fun `duplicate leaves merge while distinct argument tuples remain separate`() {
        val fixture = Fixture()
        val one = fixture.selection("Query", "scalar", mapOf("arg" to 1))
        val two = fixture.selection("Query", "scalar", mapOf("arg" to 2))
        val forest = selectionForestOf(one, one, two)

        val merged = context(fixture.world) { forest.merge(fixture.query) }

        assertEquals(2, merged.size)
        assertEquals(setOf(one.key, two.key), merged.keys())
        merged.forEach { selection ->
            assertEquals(fixture.query, selection.key.field.containingType)
            assertEquals(setOf(fixture.query), selection.possibleTypes)
            assertTrue(selection.subselections.isEmpty())
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

        val merged =
            context(fixture.world) {
                selectionForestOf(first, second).merge(fixture.query)
            }

        val item = merged.single()
        assertEquals(3, item.subselections.size)
        assertEquals(setOf(a.key, b.key), item.subselections.keys())
        assertEquals(
            2,
            context(fixture.world) {
                item.subselections.merge(fixture.item).size
            },
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

        val merged =
            context(fixture.world) {
                selectionForestOf(abstract, concrete).merge(fixture.item)
            }

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

        val merged =
            context(fixture.world) {
                selectionForestOf(inapplicable, applicable).merge(fixture.query)
            }

        val item = merged.single()
        assertEquals(1, item.subselections.size)
        assertEquals(setOf(visible.key), item.subselections.keys())
    }

    @Test
    fun `permuting occurrences preserves the keyed merged result`() {
        val fixture = Fixture()
        val a = fixture.selection("ConcreteItem", "a")
        val b = fixture.selection("ConcreteItem", "b")
        val first = fixture.selection("Query", "item", subselections = selectionForestOf(a))
        val second = fixture.selection("Query", "item", subselections = selectionForestOf(b))

        val forward =
            context(fixture.world) {
                selectionForestOf(first, second).merge(fixture.query)
            }.single()
        val reverse =
            context(fixture.world) {
                selectionForestOf(second, first).merge(fixture.query)
            }.single()

        assertEquals(forward.key, reverse.key)
        assertEquals(forward.possibleTypes, reverse.possibleTypes)
        assertEquals(forward.subselections.size, reverse.subselections.size)
        assertEquals(forward.subselections.keys(), reverse.subselections.keys())
    }

    @Test
    fun `nested variables merge only under structural argument equality`() {
        val fixture = Fixture()
        val variableField = fixture.schema.objectField("Query", "search")
        val sameX =
            fixture.searchSelection(
                Value.InputObject.of(
                    type = fixture.filter,
                    fields =
                        mapOf(
                            "values" to
                                listOf(Value.Variable.of("x", variableField, path = null)),
                        ),
                ),
            )
        val anotherX =
            fixture.searchSelection(
                Value.InputObject.of(
                    type = fixture.filter,
                    fields =
                        mapOf(
                            "values" to
                                listOf(Value.Variable.of("x", variableField, path = null)),
                        ),
                ),
            )
        val y =
            fixture.searchSelection(
                Value.InputObject.of(
                    type = fixture.filter,
                    fields =
                        mapOf(
                            "values" to
                                listOf(Value.Variable.of("y", variableField, path = null)),
                        ),
                ),
            )
        val literal =
            fixture.searchSelection(
                Value.InputObject.of(
                    type = fixture.filter,
                    fields = mapOf("values" to listOf(1)),
                ),
            )

        val merged =
            context(fixture.world) {
                selectionForestOf(sameX, anotherX, y, literal).merge(fixture.query)
            }

        assertEquals(3, merged.size)
        assertEquals(3, merged.keys().size)
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

        fun searchSelection(filter: Value.InputObject): Selection =
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
              search(filter: Filter!): Int!
            }
            """.trimIndent()
    }
}
