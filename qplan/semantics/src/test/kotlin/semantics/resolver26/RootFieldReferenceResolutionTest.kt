package semantics.resolver26

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import model.Arguments
import model.ListEngineResult
import model.ObjectEngineResult
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.requireQueryTypeDef
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromQueryField
import semantics.contract.contractKey
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import semantics.correctresolution.correctResolution
import semantics.shared.OperationContext
import semantics.shared.RecordingResolverObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import viaduct.engine.api.EngineObjectData

class RootFieldReferenceResolutionTest {
    @Test
    fun `referenced resolver retains Query fragment sourced variables`() {
        val targetQueryFragment =
            "fragment TargetQuery on Query { providedSource: provided querySide: label(value: ${'$'}provided) }"
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      product: Product!
                      provided: Int!
                      label(value: Int!): String!
                    }

                    type Container {
                      product: Product!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    val container = schema.requireObjectField("Query", "container")
                    val product = schema.requireObjectField("Query", "product")
                    val provided = schema.requireObjectField("Query", "provided")
                    val label = schema.requireObjectField("Query", "label")
                    mapOf(
                        container to
                            fieldResolverOf(empty) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(listOf(product), emptyMap())
                                }
                            },
                        product to
                            fieldResolverOf(
                                objectFragment = empty,
                                queryFragment = schema.fragmentFrom(targetQueryFragment),
                            ) { _, queryValue, _ ->
                                schema.objectOf("Product") {
                                    "value" setTo queryValue.get("querySide")
                                }
                            },
                        provided to fieldResolverOf(empty) { _, _ -> 7 },
                        label to
                            fieldResolverOf(empty) { _, arguments ->
                                "value-${arguments.fieldValues.getValue("value")}"
                            },
                    )
                },
                variableProviders = { schema ->
                    val product = schema.requireObjectField("Query", "product")
                    mapOf(
                        Arguments.Variable.of(product, "provided") to
                            schema.fromQueryField(
                                targetQueryFragment,
                                listOf("providedSource"),
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val result = resolve(world, "fragment Result on Query { container { product { value } } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container
                    .getCell(world.schema.contractKey("Container", "product"))
                    .getValue()
                    .get(),
            )

        assertEquals(
            "value-7",
            product.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
    }

    @Test
    fun `embedded reference invokes namespace root field with query input consumer demand and arguments`() {
        val targetApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      catalog: Catalog!
                      suffix: String!
                    }

                    type Container {
                      product: Product!
                    }

                    type Catalog {
                      prefix: String!
                      product(id: ID!): Product!
                    }

                    type Product {
                      value: String!
                      omitted: String
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val catalog = schema.requireObjectField("Query", "catalog")
                    val suffix = schema.requireObjectField("Query", "suffix")
                    val prefix = schema.requireObjectField("Catalog", "prefix")
                    val product = schema.requireObjectField("Catalog", "product")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(catalog, product),
                                            arguments = mapOf("id" to "42"),
                                    )
                                }
                            },
                        catalog to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Catalog") {}
                            },
                        prefix to
                            fieldResolverOf(schema.emptyFragmentOf("Catalog")) { _, _ -> "product" },
                        suffix to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "resolved" },
                        product to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Catalog"),
                                schema.fragmentFrom(
                                    "fragment QueryInput on Query { catalog { prefix } suffix }",
                                ),
                            ) { _, queryValue, arguments ->
                                targetApplications.incrementAndGet()
                                val catalogInput =
                                    queryValue.get("catalog") as EngineObjectData.Sync
                                schema.objectOf("Product") {
                                    "value" setTo
                                        "${catalogInput.get("prefix")}-${arguments.fieldValues.getValue("id")}-${queryValue.get("suffix")}"
                                    "omitted" setTo "must not be materialized"
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val query = world.fragmentFrom("fragment Result on Query { container { product { value } } }")
        val operation =
            OperationContext(world, resolverObserver = RecordingResolverObserver())
        val result = context(operation) { resolve(query.subselections) }
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container.getCell(world.schema.contractKey("Container", "product")).getValue().get(),
            )

        assertEquals(
            "product-42-resolved",
            product.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
        assertEquals(setOf(world.schema.contractKey("Product", "value")), product.keys)
        assertEquals(1, targetApplications.get())
        assertEquals(
            true,
            context(operation) {
                result.correctResolution(query.subselections.merge(world.schema.requireQueryTypeDef()))
            },
        )
    }

    @Test
    fun `direct resolver result tail resolves every hop with a fresh occurrence root`() {
        val applications = CopyOnWriteArrayList<Resolver26ApplicationObservation>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      first: Product!
                      second: Product!
                      third: Product!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val first = schema.requireObjectField("Query", "first")
                    val second = schema.requireObjectField("Query", "second")
                    val third = schema.requireObjectField("Query", "third")
                    mapOf(
                        first to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                RootFieldReferenceData.of(listOf(second), emptyMap())
                            },
                        second to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                RootFieldReferenceData.of(listOf(third), emptyMap())
                            },
                        third to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Product") { "value" setTo "done" }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val operation =
            OperationContext(world, resolverObserver = RecordingResolverObserver())
        val query = world.fragmentFrom("fragment Result on Query { first { value } }")
        val result =
            context(operation) {
                resolveObserved(query.subselections, applications::add)
            }
        val product =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "first")).getValue().get(),
            )

        assertEquals(
            "done",
            product.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
        assertEquals(listOf("first", "second", "third"), applications.map { it.field.name })
        assertNotEquals(applications[0].resolverOccurrenceId, applications[1].resolverOccurrenceId)
        assertNotEquals(applications[1].resolverOccurrenceId, applications[2].resolverOccurrenceId)
        assertEquals(
            true,
            context(operation) {
                result.correctResolution(query.subselections.merge(world.schema.requireQueryTypeDef()))
            },
        )
        assertEquals(
            3,
            context(operation) {
                result.registeredResolverOccurrenceApplicationIdentityCounts().values.sum()
            },
        )
    }

    @Test
    fun `static resolver may passively override a registered list field with mixed references and values`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      product(id: Int!): Product!
                    }

                    type Container {
                      products: [Product!]!
                    }

                    type Product {
                      value: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "product")
                    val products = schema.requireObjectField("Container", "products")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "products" setTo
                                        listOf(
                                            RootFieldReferenceData.of(listOf(target), mapOf("id" to 1)),
                                            schema.objectOf("Product") { "value" setTo 2 },
                                            RootFieldReferenceData.of(listOf(target), mapOf("id" to 3)),
                                        )
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                schema.objectOf("Product") {
                                    "value" setTo arguments.fieldValues.getValue("id")
                                }
                            },
                        products to
                            fieldResolverOf(schema.emptyFragmentOf("Container")) { _, _ ->
                                error("passively overridden list resolver must not run")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val result =
            resolve(
                world,
                "fragment Result on Query { container { products { value } } }",
            )
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
            )
        val products =
            assertIs<ListEngineResult>(
                container.getCell(world.schema.contractKey("Container", "products")).getValue().get(),
            )

        assertEquals(
            listOf(1, 2, 3),
            products.map { cell ->
                val product = assertIs<ObjectEngineResult>(cell.getValue().get())
                product.getCell(world.schema.contractKey("Product", "value")).getValue().get()
            },
        )
    }

    @Test
    fun `static resolver root-field reference overrides a registered consumer resolver`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      product: Product!
                    }

                    type Container {
                      product: Product!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "product")
                    val consumer = schema.requireObjectField("Container", "product")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(listOf(target), emptyMap())
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Product") { "value" setTo "target" }
                            },
                        consumer to
                            fieldResolverOf(schema.emptyFragmentOf("Container")) { _, _ ->
                                error("registered consumer resolver must not run")
                            },
                    )
                },
            )

        val world = testWorld.assumptions
        val result =
            resolve(
                world,
                "fragment Result on Query { container { product { value } } }",
            )
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container
                    .getCell(world.schema.contractKey("Container", "product"))
                    .getValue()
                    .get(),
            )

        assertEquals(
            "target",
            product.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
    }

    @Test
    fun `root-field reference override does not activate registered resolver input demand`() {
        val dependencyApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      product: Product!
                    }

                    type Container {
                      product: Product!
                      dependency: String!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "product")
                    val consumer = schema.requireObjectField("Container", "product")
                    val dependency = schema.requireObjectField("Container", "dependency")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(listOf(target), emptyMap())
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Product") { "value" setTo "target" }
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ConsumerInput on Container { dependency }",
                                ),
                            ) { _, _ ->
                                error("overridden consumer resolver must not run")
                            },
                        dependency to
                            fieldResolverOf(schema.emptyFragmentOf("Container")) { _, _ ->
                                dependencyApplications.incrementAndGet()
                                "dependency"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val result =
            resolve(
                world,
                "fragment Result on Query { container { product { value } } }",
            )
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container
                    .getCell(world.schema.contractKey("Container", "product"))
                    .getValue()
                    .get(),
            )

        assertEquals(
            "target",
            product.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
        assertEquals(0, dependencyApplications.get())
    }

    @Test
    fun `referenced resolver cannot declare an object fragment`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      product: Product!
                      prefix: String!
                    }

                    type Container {
                      product: Product!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "product")
                    val prefix = schema.requireObjectField("Query", "prefix")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(listOf(target), emptyMap())
                                }
                            },
                        prefix to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "prefix" },
                        target to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { prefix }"),
                            ) { _, _ ->
                                schema.objectOf("Product") { "value" setTo "unreachable" }
                            },
                    )
                },
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                resolve(
                    testWorld.assumptions,
                    "fragment Result on Query { container { product { value } } }",
                )
            }
        assertEquals(
            "Root-field-reference target Query/product must not declare an object fragment",
            failure.message,
        )
    }

    private fun resolve(
        world: model.Assumptions,
        query: String,
    ): ObjectEngineResult {
        val fragment = world.fragmentFrom(query)
        return context(OperationContext(world)) {
            resolve(fragment.subselections)
        }
    }
}
