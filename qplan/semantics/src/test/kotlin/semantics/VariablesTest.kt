package semantics

import model.ObjectSelectionForest
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.merge
import model.selectionForestOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals

class VariablesTest {
    @Test
    fun `object forest substitution remerges converging keys`() {
        val schema = TestWorld.fromSDL(SCHEMA).schema
        val query = schema.query
        val item = schema.objectField("Query", "item")
        val x = Value.Variable.of("x", item, path = null)
        val y = Value.Variable.of("y", item, path = null)
        val a = selection(schema.objectField("Item", "a"))
        val b = selection(schema.objectField("Item", "b"))
        val symbolic: ObjectSelectionForest =
            selectionForestOf(
                selection(item, x, selectionForestOf(a)),
                selection(item, y, selectionForestOf(b)),
            ).merge(query)

        val instantiated =
            symbolic.instantiateVariables(
                mapOf(
                    x to Value.Int.of(1),
                    y to Value.Int.of(1),
                ),
            )

        assertEquals(1, instantiated.size)
        assertEquals(2, instantiated.single().subselections.size)
    }

    private fun selection(
        field: Schema.ObjectField,
        id: Value.Input? = null,
        subselections: SelectionForest = selectionForestOf(),
    ): Selection =
        Selection.of(
            key =
                Value.ObjectKey.of(
                    field,
                    if (id == null) emptyMap() else mapOf("id" to id),
                ),
            possibleTypes = setOf(field.containingType),
            subselections = subselections,
        )

    private companion object {
        val SCHEMA =
            """
            type Item {
              a: Int!
              b: Int!
            }

            type Query {
              item(id: Int!): Item!
            }
            """.trimIndent()
    }
}
