package model

import viaduct.engine.api.EngineObjectData

import model.registry.snipToDemand
import model.testing.TestWorld
import model.testing.fieldResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ContextParametersTest {
    @Test
    fun `run receiver preserves the object-value extension receiver and result`() {
        val world = assumptions()
        val source = world.sourceObject()

        val result =
            context(world) {
                source.copyInWorld()
            }

        assertIs<EngineObjectData.Sync>(result)
        assertEquals(source, result)
        assertEquals(world.schema.query, result.schemaType)
    }

    @Test
    fun `an assumptions context composes implicitly across calls`() {
        val world = assumptions()
        val source = world.sourceObject()

        val result =
            context(world) {
                source.copyTwiceInWorld()
            }

        assertEquals(source, result)
    }

    @Test
    fun `a call boundary supplies the real assumptions context to snipToDemand`() {
        val world = assumptions()
        val source = world.sourceObject()

        val result =
            assertIs<EngineObjectData.Sync>(
                context(world) {
                    source.snipToDemand(selectionForestOf())
                },
            )

        assertEquals(world.schema.query, result.schemaType)
        assertEquals(
            emptyList(),
            result.getSelections().toList(),
        )
    }

    context(world: Assumptions)
    private fun EngineObjectData.Sync.copyInWorld(): EngineObjectData.Sync = world.run {
        val copiedFields = getSelections().associateWith(::get)
        engineObjectDataOf(this@copyInWorld.schemaType, copiedFields)
    }

    context(world: Assumptions)
    private fun EngineObjectData.Sync.copyTwiceInWorld(): EngineObjectData.Sync =
        copyInWorld().copyInWorld()

    private fun assumptions(): Assumptions =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val fragment = schema.emptyFragmentOf("Query")
                schema.query.fields.values
                    .filter { it.fieldName != "V_I_typename" }
                    .associateWith {
                        fieldResolverOf(
                            objectFragment = fragment,
                            function = { _, _ -> error("Not invoked") },
                        )
                    }
            },
        ).assumptions

    private fun Assumptions.sourceObject(): EngineObjectData.Sync =
        objectOf("Query") {
            "id" setTo "query"
            "name" setTo "Query"
        }

    private companion object {
        val SCHEMA_SDL =
            """
            type Query {
              id: ID!
              name: String!
              self: Query
            }
            """.trimIndent()
    }
}
