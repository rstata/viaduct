package model.testing

import model.Arguments
import model.Schema
import model.fragmentFrom
import model.merge
import model.objectKey
import model.objectOf
import model.operationSelectionsFrom
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
        val top = assertIs<Schema.InterfaceType>(schema.type("V_I_Top"))
        val objectTypes =
            listOf("Query", "A", "B")
                .map { name -> schema.type(name) as Schema.ObjectType }

        assertEquals(objectTypes.toSet(), top.possibleTypes)
        assertEquals(setOf("V_I_typename"), top.fields.keys)
        listOf(
            "Query",
            "Item",
            "Node",
            "A",
            "B",
        ).forEach { typeName ->
            val field = schema.field(typeName, "V_I_typename")
            assertEquals(typeName, field.containingType.typeName)
            assertEquals(Schema.NoArguments, field.arguments)
            assertEquals(Schema.StringType, field.typeExpr.baseType)
            assertTrue(!field.typeExpr.isNullable)
        }
        listOf("A_V_A_Bridge", "Node_V_A_Bridge").forEach { typeName ->
            assertFailsWith<Schema.MissingSchemaElementException> {
                schema.field(typeName, "V_I_typename")
            }
        }
        assertTrue((schema.type("Choice") as Schema.UnionType).fields.isEmpty())
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
                schema.field(typeName, "__typename")
            }
        }
    }

    @Test
    fun `internal fragments lower object interface union and node typename selections`() {
        val schema = TestWorld.fromSDL(SCHEMA).schema

        val objectFragment = schema.fragmentFrom("fragment F on A { __typename }")
        val objectSelection = objectFragment.subselections.single()
        assertEquals("A", objectSelection.key.field.containingType.typeName)
        assertEquals("V_I_typename", objectSelection.key.field.fieldName)
        assertEquals(
            "V_I_typename",
            objectFragment.materializeSelections.single().responseKey,
        )

        val interfaceSelection =
            schema.fragmentFrom("fragment F on Item { __typename }").subselections.single()
        assertEquals("Item", interfaceSelection.key.field.containingType.typeName)
        assertEquals(
            "A",
            interfaceSelection.key.objectKey(schema.type("A") as Schema.ObjectType)
                .field.containingType.typeName,
        )

        val unionFragment = schema.fragmentFrom("fragment F on Choice { kind: __typename }")
        val unionSelection = unionFragment.subselections.single()
        assertEquals("V_I_Top", unionSelection.key.field.containingType.typeName)
        assertEquals(
            "kind",
            unionFragment.materializeSelections.single().responseKey,
        )

        val nodeSelection =
            schema.fragmentFrom("fragment F on Query { node { __typename } }")
                .subselections
                .single()
        val bridgeType = nodeSelection.key.field.typeExpr.baseType as Schema.ObjectType
        val payload = nodeSelection.subselections.merge(bridgeType).single()
        assertEquals("node", payload.key.field.fieldName)
        assertEquals(
            "V_I_typename",
            payload.subselections.single().key.field.fieldName,
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
        val a = nested.merge(world.schema.query).single()
        assertEquals("a_V_A_node", a.key.field.fieldName)
        val bridgeType = a.key.field.typeExpr.baseType as Schema.ObjectType
        val payload = a.subselections.merge(bridgeType).single()
        assertEquals("node", payload.key.field.fieldName)
        assertTrue(payload.subselections.isEmpty())
    }

    @Test
    fun `generated typename resolvers are argumentless dependency-free constants`() {
        val world = TestWorld.fromSDL(SCHEMA)
        val schema = world.schema
        val registry = world.resolverRegistry
        val top = schema.type("V_I_Top") as Schema.InterfaceType

        top.possibleTypes.forEach { type ->
            val field = schema.objectField(type.typeName, "V_I_typename")
            val resolver = registry.resolver(field)
            assertTrue(field in registry)
            assertEquals(Schema.NoArguments, field.arguments)
            assertTrue(resolver.objectFragment.isEmpty())
            assertTrue(resolver.variables.isEmpty())
            assertTrue(registry.mayDemandFrom(field).isEmpty())
            assertEquals(
                type.typeName,
                resolver(
                    input = schema.objectOf(type.typeName),
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
