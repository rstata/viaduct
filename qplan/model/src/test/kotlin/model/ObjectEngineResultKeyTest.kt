package model

import viaduct.graphql.schema.ViaductSchema

import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ObjectEngineResultKeyTest {
    @Test
    fun `object fields always construct object keys with structural equality`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = schema.requireType("User") as ViaductSchema.Object
        val field = user.requireField("id")

        val general =
            ObjectEngineResult.Key.of(
                field = field as ViaductSchema.Field,
                arguments = emptyMap(),
            )
        val precise = ObjectEngineResult.GroundKey.of(field, emptyMap())

        assertIs<ObjectEngineResult.GroundKey>(general)
        assertEquals(precise, general)
        assertEquals(Stamp.VariableFreeOccurrence, general.stamp)
        assertSame(field, general.field)
    }

    @Test
    fun `abstract fields construct plain keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.requireField("Node", "id")

        val key = ObjectEngineResult.Key.of(field, emptyMap())

        assertFalse(key is ObjectEngineResult.GroundKey)
    }

    @Test
    fun `concrete fields with open arguments construct object keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.requireObjectField("Query", "find_V_A_node")
        val variable = Arguments.Variable.of(field, "id")

        val key =
            ObjectEngineResult.Key.of(
                field = field as ViaductSchema.Field,
                arguments = Arguments.of(field, mapOf("id" to variable)),
            )

        assertIs<ObjectEngineResult.ObjectKey>(key)
        assertFalse(key is ObjectEngineResult.GroundKey)
        assertNull(key.stamp)
        assertSame(field, key.field)
    }

    @Test
    fun `key stamps distinguish templates ordinary occurrences and explicit selection occurrences`() {
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
        val variable = Arguments.Variable.of(source, "value")
        val templateKey =
            ObjectEngineResult.Key.of(
                consume,
                Arguments.of(consume, mapOf("value" to variable)),
            )
        val ordinaryKey = ObjectEngineResult.GroundKey.of(consume, mapOf("value" to 7))
        val occurrence =
            Stamp.Occurrence.of(
                resolverPath = listOf(ordinaryKey),
                occurrenceLineage = listOf(SelectionOccurrenceId(templateKey)),
            )
        val occurrenceKey =
            ObjectEngineResult.GroundKey.of(
                stamp = occurrence,
                field = consume,
                arguments = ordinaryKey.arguments,
            )
        val equalOccurrenceKey =
            ObjectEngineResult.GroundKey.of(
                stamp = occurrence,
                field = consume,
                arguments = ordinaryKey.arguments,
            )
        val otherOccurrenceKey =
            ObjectEngineResult.GroundKey.of(
                stamp =
                    Stamp.Occurrence.of(
                        resolverPath = listOf(ordinaryKey),
                        occurrenceLineage = listOf(SelectionOccurrenceId(templateKey)),
                    ),
                field = consume,
                arguments = ordinaryKey.arguments,
            )

        assertNull(templateKey.stamp)
        assertEquals(Stamp.VariableFreeOccurrence, ordinaryKey.stamp)
        assertEquals(occurrence, occurrenceKey.stamp)
        assertEquals(occurrenceKey, equalOccurrenceKey)
        assertNotEquals(ordinaryKey, occurrenceKey)
        assertNotEquals(occurrenceKey, otherOccurrenceKey)
    }

    @Test
    fun `ordinary key factories do not infer occurrence identity from stamped variables`() {
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
        val stampedVariable =
            Arguments.Variable
                .of(source, "value")
                .stamp(listOf(ListEngineResult.Index.of(0)))
        val key =
            ObjectEngineResult.Key.of(
                consume,
                Arguments.of(consume, mapOf("value" to stampedVariable)),
            )

        assertEquals(Stamp.VariableFreeOccurrence, key.stamp)
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
        val source = schema.requireObjectField("Query", "source")
        val abstractField = schema.requireField("Item", "computed")
        val concreteType = schema.requireType("ConcreteItem") as ViaductSchema.Object
        val variable = Arguments.Variable.of(source, "factor")
        val sourceKey =
            ObjectEngineResult.Key.of(
                abstractField,
                Arguments.of(abstractField, mapOf("factor" to variable)),
            )
        val selectionStamp =
            Stamp.Occurrence.of(
                resolverPath = listOf(ListEngineResult.Index.of(1)),
                occurrenceLineage = listOf(SelectionOccurrenceId(sourceKey)),
            )
        val arguments =
            Arguments.Template
                .of(abstractField, sourceKey.arguments)
                .stamp(abstractField, selectionStamp)
        val stampedKey =
            ObjectEngineResult.Key.of(
                stamp = selectionStamp,
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
        assertIs<ObjectEngineResult.ObjectKey>(specialized)
        assertEquals(selectionStamp, specialized.stamp)
        assertEquals(
            selectionStamp,
            specialized.arguments.usedVariables().single().stamp,
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
        val user = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Query", "user_V_A_node"), emptyMap())
        val id = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "id"), emptyMap())

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
