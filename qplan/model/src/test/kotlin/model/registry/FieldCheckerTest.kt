package model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import model.fragmentFrom
import model.merge
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

class FieldCheckerTest {
    @Test
    fun `retains named selections and unions construction demand independently per root`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val itemType = schema.requireType("Item") as ViaductSchema.Object
        val queryType = schema.requireQueryTypeDef()
        val ownerSelection = selectionSet(schema, itemType, "fragment Owner on Item { owner }")
        val regionSelection = selectionSet(schema, itemType, "fragment Region on Item { region }")
        val viewerSelection = selectionSet(schema, queryType, "fragment Viewer on Query { viewer }")
        val policySelection = selectionSet(schema, queryType, "fragment Policy on Query { policy }")
        val checker =
            FieldChecker.of(
                field = schema.requireObjectField("Item", "secured"),
                queryType = queryType,
                objectSelectionSets =
                    mapOf(
                        "ownerInput" to ownerSelection,
                        "regionInput" to regionSelection,
                        "emptyInput" to null,
                    ),
                querySelectionSets =
                    mapOf(
                        "viewerInput" to viewerSelection,
                        "policyInput" to policySelection,
                    ),
            ) { _, _ -> CheckerResult.Success }

        val required = checker.requiredSelections
        assertSame(ownerSelection, required.objectSelectionSets.getValue("ownerInput"))
        assertSame(regionSelection, required.objectSelectionSets.getValue("regionInput"))
        assertNull(required.objectSelectionSets.getValue("emptyInput"))
        assertSame(viewerSelection, required.querySelectionSets.getValue("viewerInput"))
        assertSame(policySelection, required.querySelectionSets.getValue("policyInput"))
        assertEquals(
            setOf("owner", "region"),
            required.objectConstructionSelections
                .merge(itemType)
                .keys()
                .mapTo(mutableSetOf()) { key -> key.field.name },
        )
        assertEquals(
            setOf("viewer", "policy"),
            required.queryConstructionSelections
                .merge(queryType)
                .keys()
                .mapTo(mutableSetOf()) { key -> key.field.name },
        )
    }

    @Test
    fun `object and Query selection names share one namespace`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val itemType = schema.requireType("Item") as ViaductSchema.Object
        val queryType = schema.requireQueryTypeDef()

        assertFailsWith<IllegalArgumentException> {
            FieldChecker.of(
                field = schema.requireObjectField("Item", "secured"),
                queryType = queryType,
                objectSelectionSets =
                    mapOf(
                        "duplicate" to
                            selectionSet(
                                schema,
                                itemType,
                                "fragment Owner on Item { owner }",
                            ),
                    ),
                querySelectionSets =
                    mapOf(
                        "duplicate" to
                            selectionSet(
                                schema,
                                queryType,
                                "fragment Viewer on Query { viewer }",
                            ),
                    ),
            ) { _, _ -> CheckerResult.Success }
        }
    }

    private fun selectionSet(
        schema: ViaductSchema,
        type: ViaductSchema.Object,
        fragment: String,
    ): CheckerSelectionSet =
        CheckerSelectionSet.of(type, schema.fragmentFrom(fragment).materializeSelections)

    private companion object {
        val SCHEMA_SDL =
            """
            type Query {
              item: Item
              viewer: Int
              policy: Int
            }

            type Item {
              secured: Int
              owner: Int
              region: Int
            }
            """.trimIndent()
    }
}
