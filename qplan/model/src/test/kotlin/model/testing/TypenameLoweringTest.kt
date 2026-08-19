package model.testing

import model.Arguments
import model.Schema
import model.fragmentFrom
import model.merge
import model.objectKey
import model.objectOf
import model.operationSelectionsFrom
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TypenameLoweringTest {
    @Test
    fun `lowered schema owns ordinary typename fields and a universal top interface`() {
        val world = TestWorld.fromSDL(SCHEMA)
        val schema = world.schema
        val top = assertIs<Schema.Interface>(schema.requireType("V_I_Top"))
        val objectTypes =
            listOf("Query", "A", "B")
                .map { name -> schema.requireType(name) as Schema.Object }

        assertEquals(objectTypes.toSet(), top.possibleObjectTypes)
        assertEquals(setOf("V_I_typename"), top.fields.mapTo(linkedSetOf(), Schema.Field::name))
        listOf(
            "Query",
            "Item",
            "Node",
            "A",
            "B",
        ).forEach { typeName ->
            val field = schema.requireField(typeName, "V_I_typename")
            assertEquals(typeName, field.containingDef.name)
            assertEquals(Schema.NoArguments, field.arguments)
            assertEquals(Schema.StringType, field.type.baseType)
            assertTrue(!field.type.isNullable)
        }
        listOf("A_V_A_Bridge", "Node_V_A_Bridge").forEach { typeName ->
            assertFailsWith<Schema.MissingSchemaElementException> {
                schema.requireField(typeName, "V_I_typename")
            }
        }
        assertTrue((schema.requireType("Choice") as Schema.Union).fields.isEmpty())
        listOf(
            "Query",
            "Item",
            "Node",
            "Choice",
            "A",
            "B",
            "A_V_A_Bridge",
            "Node_V_A_Bridge",
        ).forEach { typeName ->
            assertFailsWith<Schema.MissingSchemaElementException> {
                schema.requireField(typeName, "__typename")
            }
        }
    }

    @Test
    fun `internal fragments lower object interface union and node typename selections`() {
        val schema = TestWorld.fromSDL(SCHEMA).schema

        val objectFragment = schema.fragmentFrom("fragment F on A { __typename }")
        val objectSelection = objectFragment.subselections.single()
        assertEquals("A", objectSelection.key.field.containingDef.name)
        assertEquals("V_I_typename", objectSelection.key.field.name)
        assertEquals(
            "V_I_typename",
            objectFragment.materializeSelections.single().responseKey,
        )

        val interfaceSelection =
            schema.fragmentFrom("fragment F on Item { __typename }").subselections.single()
        assertEquals("Item", interfaceSelection.key.field.containingDef.name)
        assertEquals(
            "A",
            interfaceSelection.key.objectKey(schema.requireType("A") as Schema.Object)
                .field.containingDef.name,
        )

        val unionFragment = schema.fragmentFrom("fragment F on Choice { kind: __typename }")
        val unionSelection = unionFragment.subselections.single()
        assertEquals("V_I_Top", unionSelection.key.field.containingDef.name)
        assertEquals(
            "kind",
            unionFragment.materializeSelections.single().responseKey,
        )

        val nodeSelection =
            schema.fragmentFrom("fragment F on Query { node { __typename } }")
                .subselections
                .single()
        val bridgeType = nodeSelection.key.field.type.baseType as Schema.Object
        val payload = nodeSelection.subselections.merge(bridgeType).single()
        assertEquals("node", payload.key.field.name)
        assertEquals(
            "V_I_typename",
            payload.subselections.single().key.field.name,
        )
    }

    @Test
    fun `external operations erase typename while preserving composite selections`() {
        val world = TestWorld.fromSDL(SCHEMA)

        assertTrue(world.assumptions.operationSelectionsFrom("query { __typename }").isEmpty())

        val nested =
            world.assumptions.operationSelectionsFrom(
                """
                query {
                  a {
                    __typename
                    ...OnlyTypename
                    ... on A {
                      nested: __typename
                    }
                  }
                }

                fragment OnlyTypename on A {
                  aliased: __typename
                }
                """.trimIndent(),
            )
        val a = nested.merge(world.schema.requireQueryTypeDef()).single()
        assertEquals("a_V_A_node", a.key.field.name)
        val bridgeType = a.key.field.type.baseType as Schema.Object
        val payload = a.subselections.merge(bridgeType).single()
        assertEquals("node", payload.key.field.name)
        assertTrue(payload.subselections.isEmpty())
    }

    @Test
    fun `generated typename resolvers are argumentless dependency-free constants`() {
        val world = TestWorld.fromSDL(SCHEMA)
        val schema = world.schema
        val registry = world.resolverRegistry
        val top = schema.requireType("V_I_Top") as Schema.Interface

        top.possibleObjectTypes.forEach { type ->
            val field = schema.requireObjectField(type.name, "V_I_typename")
            val resolver = registry.resolver(field)
            assertTrue(field in registry)
            assertEquals(Schema.NoArguments, field.arguments)
            assertTrue(resolver.objectFragment.isEmpty())
            assertTrue(resolver.variables.isEmpty())
            assertTrue(registry.mayDemandFrom(field).isEmpty())
            assertEquals(
                type.name,
                resolver(
                    input = schema.objectOf(type.name),
                    arguments = Arguments.Resolved.of(field, emptyMap()),
                ),
            )
        }
    }

    @Test
    fun `rejects source names in the typename lowering namespace`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL("type Query { V_I_typename: String }")
            }

        assertTrue(exception.message.orEmpty().contains("reserved token V_I"))
        assertTrue(exception.message.orEmpty().contains("V_I_typename"))
    }

    private companion object {
        val SCHEMA =
            """
            interface Node {
              id: ID!
            }

            interface Item {
              value: Int!
            }

            union Choice = A | B

            type A implements Node & Item {
              id: ID!
              value: Int!
            }

            type B implements Item {
              value: Int!
            }

            type Query {
              a: A!
              item: Item
              choice: Choice
              node: Node
            }
            """.trimIndent()
    }
}
