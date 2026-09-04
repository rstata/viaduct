package model

import model.testing.GJSchema
import model.testing.TestWorld
import model.invariants.conformsToSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParentFieldsTest {
    @Test
    fun `parent fields create parent keys and identify their list-producing field`() {
        val assumptions = TestWorld.fromSDL(PARENT_SCHEMA).assumptions
        val schema = assumptions.schema
        val parentField = schema.requireObjectField("Child", "parent")
        val producerField = schema.requireObjectField("Parent", "children")

        val key = ObjectEngineResult.GroundKey.of(parentField, emptyMap())
        val relatedProducer = assumptions.parentFieldRelations[parentField]

        val parentKey = assertIs<ObjectEngineResult.ParentKey>(key)
        assertTrue(parentKey.arguments.fieldValues.isEmpty())
        assertSame(producerField, relatedProducer)
        assertFailsWith<IllegalArgumentException> {
            ObjectEngineResult.ParentKey.of(producerField)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectEngineResult.GroundKey.of(parentField, Arguments.Error)
        }
    }

    @Test
    fun `parent relation rejects an argument-bearing child producer`() {
        val schema =
            GJSchema.fromSDL(
                """
                directive @parent on FIELD_DEFINITION
                type Query { parent: Parent }
                type Parent { child(id: ID!): Child }
                type Child { parent: Parent @parent }
                """.trimIndent(),
            )

        assertFailsWith<IllegalArgumentException> {
            parentFieldRelations(schema)
        }
    }

    @Test
    fun `parent references form finite cyclic results with structural conformance`() {
        val assumptions = TestWorld.fromSDL(SINGULAR_PARENT_SCHEMA).assumptions
        val first = assumptions.parentResult()
        val second = assumptions.parentResult()

        assertTrue(context(assumptions) { first.conformsToSchema() })
        assertTrue(first.sameCompletedResultAs(second))
        assertFailsWith<IllegalArgumentException> { first.union(second) }
    }

    @Test
    fun `object union rejects a one-sided parent result subtree`() {
        val assumptions = TestWorld.fromSDL(SINGULAR_PARENT_SCHEMA).assumptions
        val withChild = assumptions.parentResult()
        val withoutChild = ObjectEngineResult.of(withChild.type)

        assertFailsWith<IllegalArgumentException> { withChild.union(withoutChild) }
        assertFailsWith<IllegalArgumentException> { withoutChild.union(withChild) }
    }

    @Test
    fun `parent conformance rejects a reference to a different parent occurrence`() {
        val assumptions = TestWorld.fromSDL(SINGULAR_PARENT_SCHEMA).assumptions
        val schema = assumptions.schema
        val unrelatedParent = ObjectEngineResult.of(schema.requireType("Parent") as viaduct.graphql.schema.ViaductSchema.Object)
        val result = assumptions.parentResult(parentOverride = unrelatedParent)

        assertFalse(context(assumptions) { result.conformsToSchema() })
    }

    @Test
    fun `parent conformance rejects a child reached through a different producer field`() {
        val assumptions = TestWorld.fromSDL(ABSTRACT_CHILD_SCHEMA).assumptions
        val schema = assumptions.schema
        val parentType = schema.requireType("Parent") as viaduct.graphql.schema.ViaductSchema.Object
        val childType = schema.requireType("Child") as viaduct.graphql.schema.ViaductSchema.Object
        val parent = ObjectEngineResult.of(parentType, mutable = true)
        val child =
            ObjectEngineResult.of(
                childType,
                values =
                    mapOf(
                        ObjectEngineResult.ParentKey.of(
                            schema.requireObjectField("Child", "parent"),
                        ) to parent,
                    ),
            )
        val alternateProducer =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Parent", "entity"),
                emptyMap(),
            )
        parent.reserveCell(alternateProducer).also { cell ->
            cell.setValue(child)
            cell.setAccessResult(true)
        }

        assertFalse(context(assumptions) { parent.conformsToSchema() })
    }

    private fun Assumptions.parentResult(
        parentOverride: ObjectEngineResult? = null,
    ): ObjectEngineResult {
        val parentType = schema.requireType("Parent") as viaduct.graphql.schema.ViaductSchema.Object
        val childType = schema.requireType("Child") as viaduct.graphql.schema.ViaductSchema.Object
        val childKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Parent", "child"), emptyMap())
        val parentKey = ObjectEngineResult.ParentKey.of(schema.requireObjectField("Child", "parent"))
        val parent = ObjectEngineResult.of(parentType, mutable = true)
        val child =
            ObjectEngineResult.of(
                childType,
                values = mapOf(parentKey to (parentOverride ?: parent)),
            )
        parent.reserveCell(childKey).setValue(child)
        parent.reserveCell(childKey).setAccessResult(true)
        return parent
    }

    private companion object {
        val PARENT_SCHEMA =
            """
            directive @parent on FIELD_DEFINITION
            type Query { parent: Parent }
            type Parent { children: [[Child]] }
            type Child { parent: Parent @parent }
            """.trimIndent()

        val SINGULAR_PARENT_SCHEMA =
            """
            directive @parent on FIELD_DEFINITION
            type Query { parent: Parent }
            type Parent { child: Child }
            type Child { parent: Parent @parent }
            """.trimIndent()

        val ABSTRACT_CHILD_SCHEMA =
            """
            directive @parent on FIELD_DEFINITION
            type Query { parent: Parent }
            interface Entity { id: ID }
            type Parent { child: Child, entity: Entity }
            type Child implements Entity { id: ID, parent: Parent @parent }
            """.trimIndent()
    }
}
