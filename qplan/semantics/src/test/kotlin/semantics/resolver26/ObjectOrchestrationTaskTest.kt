package semantics.resolver26

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import model.ObjectEngineResult
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.testing.TestWorld
import semantics.shared.OperationContext
import viaduct.graphql.schema.ViaductSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ObjectOrchestrationTaskTest {
    @Test
    fun `object orchestration validates source and target types at construction`() =
        runBlocking(resolver26CoroutineContext()) {
            coroutineScope {
                val world =
                    TestWorld
                        .fromSDL(
                            """
                            type Query {
                              item: Item
                            }

                            type Item {
                              value: Int
                            }
                            """.trimIndent(),
                        ).assumptions
                val baseOperation = OperationContext(world)
                val operation =
                    Resolver26OperationContext(
                        base = baseOperation,
                        requestScope = this,
                        resolverObserver =
                            baseOperation.resolverObserver.withResolver26Applications {},
                    )
                val root =
                    ObjectEngineResult.of(
                        world.schema.requireQueryTypeDef(),
                        mutable = true,
                    )
                val target =
                    ObjectEngineResult.of(
                        world.schema.requireType("Item") as ViaductSchema.Object,
                        mutable = true,
                    )

                assertFailsWith<IllegalArgumentException> {
                    ObjectOrchestrationTask(
                        operation = operation,
                        occurrence =
                            OEROccurrenceContext(
                                root = root,
                                path =
                                    listOf(
                                        ObjectEngineResult.GroundKey.of(
                                            world.schema.requireObjectField("Query", "item"),
                                            emptyMap(),
                                        ),
                                    ),
                                target = target,
                            ),
                        source = world.resolverRegistry.createRootQueryInput(),
                        initialDemand = selectionForestOf(),
                    )
                }
            }
        }
}
