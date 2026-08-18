package model

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ObjectEngineResultKeyTest {
    @Test
    fun `object fields always construct object keys with structural equality`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = schema.type("User") as Schema.ObjectType
        val field = user.fields.getValue("id")

        val general =
            ObjectEngineResult.Key.of(
                field = field as Schema.OutputField,
                arguments = emptyMap(),
            )
        val precise = ObjectEngineResult.GroundKey.of(field, emptyMap())

        assertIs<ObjectEngineResult.GroundKey>(general)
        assertEquals(precise, general)
        assertSame(field, general.field)
    }

    @Test
    fun `abstract fields construct plain keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.field("Node", "id")

        val key = ObjectEngineResult.Key.of(field, emptyMap())

        assertFalse(key is ObjectEngineResult.GroundKey)
    }

    @Test
    fun `concrete fields with open arguments construct object keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.objectField("Query", "find_V_A_node")
        val variable = Value.Variable.of(field, "id")

        val key =
            ObjectEngineResult.Key.of(
                field = field as Schema.OutputField,
                arguments = OpenArguments.of(field, mapOf("id" to variable)),
            )

        assertIs<ObjectEngineResult.ObjectKey>(key)
        assertFalse(key is ObjectEngineResult.GroundKey)
        assertSame(field, key.field)
    }

    @Test
    fun `stamped abstract key specializes without losing its occurrence identity`() {
        val schema =
            TestWorld.fromSDL(
                """
                interface Item {
                  computed(factor: Int): Int
                }

                type ConcreteItem implements Item {
                  computed(factor: Int): Int
                }

                type Query {
                  source: Int
                  item: Item
                }
                """.trimIndent(),
            ).schema
        val source = schema.objectField("Query", "source")
        val abstractField = schema.field("Item", "computed")
        val concreteType = schema.type("ConcreteItem") as Schema.ObjectType
        val variable = Value.Variable.of(source, "factor")
        val sourceKey =
            ObjectEngineResult.Key.of(
                abstractField,
                OpenArguments.of(abstractField, mapOf("factor" to variable)),
            )
        val selectionStamp =
            SelectionStamp(
                resolverPath = listOf(ListEngineResult.Index.of(1)),
                occurrenceLineage = listOf(SelectionOccurrenceId(sourceKey)),
            )
        val arguments =
            OpenArguments.Template
                .of(abstractField.arguments, sourceKey.arguments)
                .stamp(abstractField.arguments, selectionStamp)
        val stampedKey =
            ObjectEngineResult.Key.Stamped.of(
                selectionStamp = selectionStamp,
                field = abstractField,
                arguments = arguments,
            )
        val specialized =
            selectionForestOf(
                Selection.of(
                    key = stampedKey,
                    possibleTypes = setOf(concreteType),
                    subselections = selectionForestOf(),
                ),
            ).merge(concreteType)
                .keys()
                .single()

        assertFalse(stampedKey is ObjectEngineResult.ObjectKey)
        assertIs<ObjectEngineResult.ObjectKey.Stamped>(specialized)
        assertEquals(selectionStamp, specialized.selectionStamp)
        assertEquals(
            selectionStamp,
            specialized.arguments.usedVariables().single().selectionStamp,
        )
    }

    @Test
    fun `list index rejects negative positions`() {
        ListEngineResult.Index.of(2)
        assertFailsWith<IllegalArgumentException> { ListEngineResult.Index.of(-1) }
    }

    @Test
    fun `selection paths contain only object keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = ObjectEngineResult.GroundKey.of(schema.objectField("Query", "user_V_A_node"), emptyMap())
        val id = ObjectEngineResult.GroundKey.of(schema.objectField("User", "id"), emptyMap())

        assertEquals(listOf(user, id), listOf<PathComponent>(user, id).toSelectionPath())
        assertNull(listOf<PathComponent>(user, ListEngineResult.Index.of(0), id).toSelectionPath())
        assertNull((null as List<PathComponent>?).toSelectionPath())
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
            }

            type Query {
              user: User
              find(id: ID!): User
            }
            """.trimIndent()
    }
}
