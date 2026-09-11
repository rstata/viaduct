package semantics.resolver26

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import model.Arguments
import model.EngineErrorData
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SourceSchemaAdapter
import model.VariableBinding
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.requireQueryTypeDef
import model.requireObjectField
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import model.testing.fromQueryField
import model.testing.nodeResolverOf
import semantics.contract.contractKey
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import semantics.correctresolution.correctResolution
import semantics.shared.OperationContext
import semantics.shared.RecordingResolverObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

class RootFieldReferenceResolutionTest {
    @Test
    fun `list provider awaits root-reference elements`() {
        val resultFragment =
            "fragment ResultInput on Query { numbers echo(values: ${'$'}provided) }"
        val providerFragment = "fragment ProviderInput on Query { numbers }"
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      numbers: [Int!]!
                      one: Int!
                      echo(values: [Int!]!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    val result = schema.requireObjectField("Query", "result")
                    val numbers = schema.requireObjectField("Query", "numbers")
                    val one = schema.requireObjectField("Query", "one")
                    val echo = schema.requireObjectField("Query", "echo")
                    mapOf(
                        result to
                            fieldResolverOf(
                                schema.fragmentFrom(resultFragment, variableField = result),
                            ) { input, _ -> input.get("echo") },
                        numbers to
                            fieldResolverOf(empty) { _, _ ->
                                listOf(RootFieldReferenceData.of(listOf(one), emptyMap()))
                            },
                        one to fieldResolverOf(empty) { _, _ -> 7 },
                        echo to
                            fieldResolverOf(empty) { _, arguments ->
                                (arguments.fieldValues.getValue("values") as List<*>).single()
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "provided") to
                            schema.fromObjectField(
                                objectFragmentSource = providerFragment,
                                responsePath = listOf("numbers"),
                                variableField = result,
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val query = world.fragmentFrom("fragment Result on Query { result }")
        val observer = RecordingResolverObserver()
        val operation = OperationContext(world, resolverObserver = observer)

        val resolved = context(operation) { resolve(query.subselections) }

        assertEquals(
            7,
            resolved.getCell(world.schema.contractKey("Query", "result")).getValue().get(),
        )
        assertEquals(1, observer.rootFieldReferenceInvocations().size)
    }

    @Test
    fun `node resolver may return a root field reference`() {
        val nodeApplications = AtomicInteger()
        val targetApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    interface ReferencedFoo {
                      id: ID!
                      value: String!
                    }

                    type Foo implements Node & ReferencedFoo {
                      id: ID!
                      value: String!
                    }

                    type Query {
                      foo: Foo!
                      referencedFoo: ReferencedFoo!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val foo =
                        SourceSchemaAdapter(schema).field("Query", "foo")
                            as ViaductSchema.ObjectField
                    val referencedFoo = schema.requireObjectField("Query", "referencedFoo")
                    mapOf(
                        foo to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Foo") { "id" setTo "source-id" }
                            },
                        referencedFoo to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                targetApplications.incrementAndGet()
                                schema.objectOf("Foo") {
                                    "id" setTo "target-id"
                                    "value" setTo "from-reference"
                                }
                            },
                    )
                },
                nodeResolvers = { schema ->
                    val foo = schema.requireType("Foo") as ViaductSchema.Object
                    val referencedFoo = schema.requireObjectField("Query", "referencedFoo")
                    mapOf(
                        foo to
                            nodeResolverOf { id ->
                                nodeApplications.incrementAndGet()
                                assertEquals("source-id", id)
                                RootFieldReferenceData.of(
                                    path = listOf(referencedFoo),
                                    arguments = emptyMap(),
                                )
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val query = world.fragmentFrom("fragment Result on Query { foo { value } }")
        val operation = OperationContext(world, resolverObserver = RecordingResolverObserver())
        val result = context(operation) { resolve(query.subselections) }
        val bridge =
            assertIs<ObjectEngineResult>(
                result
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Query", "foo_V_A_node"),
                            emptyMap(),
                        ),
                    ).getValue()
                    .get(),
            )
        val foo =
            assertIs<ObjectEngineResult>(
                bridge
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Foo_V_A_Bridge", "node"),
                            emptyMap(),
                        ),
                    ).getValue()
                    .get(),
            )

        assertEquals(
            "from-reference",
            foo.getCell(world.schema.contractKey("Foo", "value")).getValue().get(),
        )
        assertEquals(1, nodeApplications.get())
        assertEquals(1, targetApplications.get())
        assertTrue(
            context(operation) {
                result.correctResolution(query.subselections.merge(world.schema.requireQueryTypeDef()))
            },
        )
    }

    @Test
    fun `abstract target resolves when all possible types conform to the consumer`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      lookup(kind: Int!): LookupResult!
                    }

                    type Container {
                      item: Entity!
                    }

                    interface Entity {
                      value: String!
                    }

                    type Product implements Entity {
                      value: String!
                    }

                    type Service implements Entity {
                      value: String!
                    }

                    union LookupResult = Product | Service
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val lookup = schema.requireObjectField("Query", "lookup")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "item" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(lookup),
                                            arguments = mapOf("kind" to 1),
                                        )
                                }
                            },
                        lookup to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                schema.objectOf("Service") {
                                    "value" setTo "service-${arguments.fieldValues.getValue("kind")}"
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val query = world.fragmentFrom("fragment Result on Query { container { item { value } } }")
        val operation = OperationContext(world, resolverObserver = RecordingResolverObserver())
        val result = context(operation) { resolve(query.subselections) }
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
            )
        val item =
            assertIs<ObjectEngineResult>(
                container.getCell(world.schema.contractKey("Container", "item")).getValue().get(),
            )

        assertEquals("Service", item.type.name)
        assertEquals(
            "service-1",
            item.getCell(world.schema.contractKey("Service", "value")).getValue().get(),
        )
        assertTrue(
            context(operation) {
                result.correctResolution(query.subselections.merge(world.schema.requireQueryTypeDef()))
            },
        )
    }

    @Test
    fun `abstract target is rejected when only some possible types conform to the consumer`() {
        val targetApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      lookup: LookupResult!
                    }

                    type Container {
                      item: ProductEntity!
                    }

                    interface ProductEntity {
                      value: String!
                    }

                    type Product implements ProductEntity {
                      value: String!
                    }

                    type Service {
                      value: String!
                    }

                    union LookupResult = Product | Service
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val lookup = schema.requireObjectField("Query", "lookup")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "item" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(lookup),
                                            arguments = emptyMap(),
                                        )
                                }
                            },
                        lookup to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                targetApplications.incrementAndGet()
                                schema.objectOf("Product") { "value" setTo "unreachable" }
                            },
                    )
                },
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                resolve(
                    testWorld.assumptions,
                    "fragment Result on Query { container { item { value } } }",
                )
            }

        assertEquals(0, targetApplications.get())
        assertTrue(failure.message.orEmpty().contains("Container/item"))
    }

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
                      product: Merchandise!
                      provided: Int!
                      label(value: Int!): String!
                    }

                    type Container {
                      product: Merchandise!
                    }

                    interface Merchandise {
                      value: String!
                    }

                    type Product implements Merchandise {
                      value: String!
                    }

                    type Service implements Merchandise {
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
    fun `tail reference targets retain argument and provider variables per occurrence`() {
        val firstProviderArguments = CopyOnWriteArrayList<Int>()
        val secondProviderArguments = CopyOnWriteArrayList<Int>()
        val firstQueryValues = CopyOnWriteArrayList<String>()
        val secondQueryValues = CopyOnWriteArrayList<String>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      first(id: Int!): Product!
                      second(id: Int!): Product!
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
                    val container = schema.requireObjectField("Query", "container")
                    val first = schema.requireObjectField("Query", "first")
                    val second = schema.requireObjectField("Query", "second")
                    val label = schema.requireObjectField("Query", "label")
                    val firstQueryFragment =
                        """
                        fragment FirstQuery on Query {
                          argumentLabel: label(value: ${'$'}argument)
                          providerLabel: label(value: ${'$'}provided)
                        }
                        """.trimIndent()
                    val secondQueryFragment =
                        """
                        fragment SecondQuery on Query {
                          argumentLabel: label(value: ${'$'}argument)
                          providerLabel: label(value: ${'$'}provided)
                        }
                        """.trimIndent()
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(first),
                                            arguments = mapOf("id" to 5),
                                        )
                                }
                            },
                        first to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(firstQueryFragment, variableField = first),
                            ) { _, queryValue, _ ->
                                firstQueryValues +=
                                    "${queryValue.get("argumentLabel")}/${queryValue.get("providerLabel")}"
                                RootFieldReferenceData.of(
                                    path = listOf(second),
                                    arguments = mapOf("id" to 9),
                                )
                            }.withVariablesProvider(setOf("provided")) { arguments ->
                                val id = arguments.fieldValues.getValue("id") as Int
                                firstProviderArguments += id
                                mapOf("provided" to id + 1)
                            },
                        second to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(secondQueryFragment, variableField = second),
                            ) { _, queryValue, _ ->
                                val value =
                                    "${queryValue.get("argumentLabel")}/${queryValue.get("providerLabel")}"
                                secondQueryValues += value
                                schema.objectOf("Product") { "value" setTo value }
                            }.withVariablesProvider(setOf("provided")) { arguments ->
                                val id = arguments.fieldValues.getValue("id") as Int
                                secondProviderArguments += id
                                mapOf("provided" to id + 1)
                            },
                        label to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                "value-${arguments.fieldValues.getValue("value")}"
                            },
                    )
                },
                variableProviders = { schema ->
                    val first = schema.requireObjectField("Query", "first")
                    val second = schema.requireObjectField("Query", "second")
                    mapOf(
                        Arguments.Variable.of(first, "argument") to
                            schema.fromArgument(first, "id"),
                        Arguments.Variable.of(second, "argument") to
                            schema.fromArgument(second, "id"),
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
                container
                    .getCell(world.schema.contractKey("Container", "product"))
                    .getValue()
                    .get(),
            )

        assertEquals(
            "value-9/value-10",
            product.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
        assertEquals(listOf(5), firstProviderArguments)
        assertEquals(listOf(9), secondProviderArguments)
        assertEquals(listOf("value-5/value-6"), firstQueryValues)
        assertEquals(listOf("value-9/value-10"), secondQueryValues)
        assertTrue(
            context(operation) {
                result.correctResolution(query.subselections.merge(world.schema.requireQueryTypeDef()))
            },
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
    fun `embedded reference reaches its target through three namespace fields`() {
        val applications = CopyOnWriteArrayList<Resolver26ApplicationObservation>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      factories: Factories
                    }

                    type Container {
                      product: Product!
                    }

                    type Factories {
                      commerce: CommerceFactories
                    }

                    type CommerceFactories {
                      products: ProductFactory
                    }

                    type ProductFactory {
                      create(id: ID!): Product!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val factories = schema.requireObjectField("Query", "factories")
                    val commerce = schema.requireObjectField("Factories", "commerce")
                    val products = schema.requireObjectField("CommerceFactories", "products")
                    val create = schema.requireObjectField("ProductFactory", "create")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(factories, commerce, products, create),
                                            arguments = mapOf("id" to "42"),
                                        )
                                }
                            },
                        factories to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Factories")
                            },
                        commerce to
                            fieldResolverOf(schema.emptyFragmentOf("Factories")) { _, _ ->
                                schema.objectOf("CommerceFactories")
                            },
                        products to
                            fieldResolverOf(schema.emptyFragmentOf("CommerceFactories")) { _, _ ->
                                schema.objectOf("ProductFactory")
                            },
                        create to
                            fieldResolverOf(schema.emptyFragmentOf("ProductFactory")) { _, arguments ->
                                schema.objectOf("Product") {
                                    "value" setTo "product-${arguments.fieldValues.getValue("id")}"
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val operation =
            OperationContext(world, resolverObserver = RecordingResolverObserver())
        val query = world.fragmentFrom("fragment Result on Query { container { product { value } } }")
        val result =
            context(operation) {
                resolveObserved(query.subselections, applications::add)
            }
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
        val create = world.schema.requireObjectField("ProductFactory", "create")
        val targetApplication = applications.single { it.field == create }

        assertEquals(
            "product-42",
            product.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
        assertEquals(
            listOf("factories", "commerce", "products", "create"),
            targetApplication.occurrencePath.map { component ->
                assertIs<ObjectEngineResult.ObjectKey>(component).field.name
            },
        )
        assertEquals(
            listOf("container", "create"),
            applications.map { it.field.name },
        )
        assertTrue(
            context(operation) {
                result.correctResolution(query.subselections.merge(world.schema.requireQueryTypeDef()))
            },
        )
    }

    @Test
    fun `root-field references publish scalar and enum values at every consumer shape`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      factory: Factory
                      indirectNumber: Int!
                    }

                    type Container {
                      numbers: [Int!]!
                      status: Status!
                    }

                    type Factory {
                      number(value: Int!): Int!
                      status: Status!
                    }

                    enum Status {
                      READY
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val factory = schema.requireObjectField("Query", "factory")
                    val indirectNumber = schema.requireObjectField("Query", "indirectNumber")
                    val number = schema.requireObjectField("Factory", "number")
                    val status = schema.requireObjectField("Factory", "status")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "numbers" setTo
                                        listOf(
                                            RootFieldReferenceData.of(
                                                path = listOf(factory, number),
                                                arguments = mapOf("value" to 1),
                                            ),
                                            2,
                                            RootFieldReferenceData.of(
                                                path = listOf(factory, number),
                                                arguments = mapOf("value" to 3),
                                            ),
                                        )
                                    "status" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(factory, status),
                                            arguments = emptyMap(),
                                        )
                                }
                            },
                        factory to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Factory")
                            },
                        indirectNumber to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                RootFieldReferenceData.of(
                                    path = listOf(factory, number),
                                    arguments = mapOf("value" to 42),
                                )
                            },
                        number to
                            fieldResolverOf(schema.emptyFragmentOf("Factory")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        status to
                            fieldResolverOf(schema.emptyFragmentOf("Factory")) { _, _ ->
                                "READY"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val operation =
            OperationContext(world, resolverObserver = RecordingResolverObserver())
        val query =
            world.fragmentFrom(
                "fragment Result on Query { indirectNumber container { numbers status } }",
            )
        val result = context(operation) { resolve(query.subselections) }
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
            )
        val numbers =
            assertIs<ListEngineResult>(
                container.getCell(world.schema.contractKey("Container", "numbers")).getValue().get(),
            )
        val status =
            assertIs<ViaductSchema.EnumValue>(
                container.getCell(world.schema.contractKey("Container", "status")).getValue().get(),
            )

        assertEquals(
            42,
            result.getCell(world.schema.contractKey("Query", "indirectNumber")).getValue().get(),
        )
        assertEquals(listOf(1, 2, 3), numbers.map { cell -> cell.getValue().get() })
        assertEquals("READY", status.name)
        assertTrue(
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
    fun `failing list-element reference preserves passive and successful siblings`() {
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
                                            RootFieldReferenceData.of(listOf(target), mapOf("id" to 2)),
                                            schema.objectOf("Product") { "value" setTo 3 },
                                        )
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                val id = arguments.fieldValues.getValue("id") as Int
                                if (id == 2) {
                                    EngineErrorData.of()
                                } else {
                                    schema.objectOf("Product") { "value" setTo id }
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
        val query =
            world.fragmentFrom("fragment Result on Query { container { products { value } } }")
        val operation =
            OperationContext(world, resolverObserver = RecordingResolverObserver())
        val result = context(operation) { resolve(query.subselections) }
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
            )
        val products =
            assertIs<ListEngineResult>(
                container.getCell(world.schema.contractKey("Container", "products")).getValue().get(),
            )
        val first = assertIs<ObjectEngineResult>(products[0].getValue().get())
        val third = assertIs<ObjectEngineResult>(products[2].getValue().get())

        assertEquals(
            1,
            first.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
        assertIs<ErrorEngineResult>(products[1].getValue().get())
        assertEquals(
            3,
            third.getCell(world.schema.contractKey("Product", "value")).getValue().get(),
        )
        assertTrue(
            context(operation) {
                result.correctResolution(query.subselections.merge(world.schema.requireQueryTypeDef()))
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
    fun `argument condition activates an embedded root-field-reference occurrence`() {
        listOf(false, true).forEach { enabled ->
            val targetApplications = AtomicInteger()
            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          result(enabled: Boolean!): Int!
                          container: Container!
                          product: Merchandise!
                        }

                        type Container {
                          product: Merchandise!
                        }

                        interface Merchandise {
                          value: String!
                        }

                        type Product implements Merchandise {
                          value: String!
                        }

                        type Service implements Merchandise {
                          value: String!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val result = schema.requireObjectField("Query", "result")
                        val container = schema.requireObjectField("Query", "container")
                        val target = schema.requireObjectField("Query", "product")
                        val consumer = schema.requireObjectField("Container", "product")
                        mapOf(
                            result to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        """
                                        fragment ResultInput on Query {
                                          container {
                                            product @include(if: ${'$'}enabled) { value }
                                          }
                                        }
                                        """.trimIndent(),
                                        variableField = result,
                                    ),
                                ) { _, _ -> 1 },
                            container to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    schema.objectOf("Container") {
                                        "product" setTo
                                            RootFieldReferenceData.of(listOf(target), emptyMap())
                                    }
                                },
                            target to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    targetApplications.incrementAndGet()
                                    schema.objectOf("Product") { "value" setTo "target" }
                                },
                            consumer to
                                fieldResolverOf(schema.emptyFragmentOf("Container")) { _, _ ->
                                    error("passively overridden consumer resolver must not run")
                                },
                        )
                    },
                    variableProviders = { schema ->
                        val result = schema.requireObjectField("Query", "result")
                        mapOf(
                            Arguments.Variable.of(result, "enabled") to
                                schema.fromArgument(result, "enabled"),
                        )
                    },
                )
            val world = testWorld.assumptions
            val query =
                world.fragmentFrom(
                    "fragment Result on Query { result(enabled: $enabled) }",
                )
            val operation =
                OperationContext(world, resolverObserver = RecordingResolverObserver())
            val resolved = context(operation) { resolve(query.subselections) }
            val container =
                assertIs<ObjectEngineResult>(
                    resolved.getCell(world.schema.contractKey("Query", "container")).getValue().get(),
                )
            val referenceCell =
                container.getCell(world.schema.contractKey("Container", "product"))

            assertEquals(if (enabled) 1 else 0, targetApplications.get())
            if (enabled) {
                assertTrue(runBlocking { referenceCell.fetchActivated() })
            } else {
                assertFalse(runBlocking { referenceCell.fetchActivated() })
            }
            assertTrue(
                context(operation) {
                    resolved.correctResolution(
                        query.subselections.merge(world.schema.requireQueryTypeDef()),
                    )
                },
            )
        }
    }

    @Test
    fun `excluded passive list does not invoke its root-field-reference elements`() {
        val resolution = resolveConditionalPassiveListReference(enabled = false)

        assertEquals(0, resolution.targetApplications.get())
    }

    @Test
    fun `included passive list invokes its root-field-reference elements`() {
        val resolution = resolveConditionalPassiveListReference(enabled = true)

        assertEquals(1, resolution.targetApplications.get())
    }

    @Test
    fun `application-count oracle excludes references beneath an excluded passive list`() {
        val resolution = resolveConditionalPassiveListReference(enabled = false)
        val expectedApplications =
            context(resolution.operation) {
                resolution.result.registeredResolverOccurrenceApplicationIdentityCounts()
            }

        assertEquals(
            emptySet(),
            expectedApplications.keys
                .mapTo(linkedSetOf()) { identity ->
                    identity.applicationIdentity.key.field
                }.filterTo(linkedSetOf()) { field -> field.fieldName == "product" },
        )
    }

    @Test
    fun `application-count oracle rejects an unowned root-reference observation`() {
        val resolution = resolveConditionalPassiveListReference(enabled = true)
        val observation =
            assertIs<RecordingResolverObserver>(resolution.operation.resolverObserver)
                .rootFieldReferenceInvocations()
                .single()
        val malformedObserver = RecordingResolverObserver()
        malformedObserver.onRootFieldReferenceInvocation(observation)
        malformedObserver.onRootFieldReferenceInvocation(
            observation.copy(
                invocationRoot =
                    ObjectEngineResult.of(resolution.operation.schema.requireQueryTypeDef()),
            ),
        )
        val validationOperation =
            OperationContext(
                world = resolution.operation.world,
                variableBindingsState = resolution.operation.variableBindingsState,
                resolverObserver = malformedObserver,
            )
        val expectedApplications =
            context(validationOperation) {
                resolution.result.registeredResolverOccurrenceApplicationIdentityCounts()
            }

        assertEquals(
            1,
            expectedApplications
                .filterKeys { identity ->
                    identity.applicationIdentity.key.field.fieldName == "product"
                }.values.sum(),
        )
    }

    @Test
    fun `error-valued resolver occurrence owns references in its query fragment`() {
        val queryFragmentSource =
            "fragment ConsumerQuery on Query { container { product { value } } }"
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      consumer(value: Int!): Int!
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
                    val empty = schema.emptyFragmentOf("Query")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    val container = schema.requireObjectField("Query", "container")
                    val product = schema.requireObjectField("Query", "product")
                    mapOf(
                        consumer to
                            fieldResolverOf(
                                objectFragment = empty,
                                queryFragment = schema.fragmentFrom(queryFragmentSource),
                            ) { _, _, _ -> error("error-valued consumer must not run") },
                        container to
                            fieldResolverOf(empty) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(listOf(product), emptyMap())
                                }
                            },
                        product to
                            fieldResolverOf(empty) { _, _ ->
                                schema.objectOf("Product") { "value" setTo "target" }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val observer = RecordingResolverObserver()
        val operation = OperationContext(world, resolverObserver = observer)
        val queryFragment = world.fragmentFrom(queryFragmentSource)
        val queryResult = context(operation) { resolve(queryFragment.subselections) }
        assertEquals(1, observer.rootFieldReferenceInvocations().size)

        val primaryRoot =
            ObjectEngineResult.of(
                operation.schema.requireQueryTypeDef(),
                mutable = true,
            )
        val consumer = operation.schema.requireObjectField("Query", "consumer")
        val variable =
            Arguments.Variable.of(consumer, "provided").instantiate(
                ResolverOccurrenceId.at(primaryRoot, emptyList()),
            )
        operation.variableBindingsState.bindVariable(
            requireNotNull(variable.instanceId),
            VariableBinding.Error,
        )
        val consumerKey =
            ObjectEngineResult.ObjectKey.of(
                consumer,
                Arguments.of(consumer, mapOf("value" to variable)),
            )
        primaryRoot.setCellValue(consumerKey, ErrorEngineResult.of(EngineErrorData.of()))
        observer.onQueryFragmentResult(
            ResolverOccurrenceId.at(primaryRoot, listOf(consumerKey)),
            queryResult,
        )
        val selections = model.selectionForestOf().merge(operation.schema.requireQueryTypeDef())

        assertTrue(context(operation) { primaryRoot.correctResolution(selections) })
        assertEquals(
            1,
            context(operation) {
                primaryRoot.registeredResolverOccurrenceApplicationIdentityCounts()
            }.filterKeys { identity ->
                identity.applicationIdentity.key.field.fieldName == "product"
            }.values.sum(),
        )
    }

    @Test
    fun `correctness replays a root reference with canonical rather than observed demand`() {
        val resolution = resolveConditionalPassiveListReference(enabled = true)
        val observation =
            assertIs<RecordingResolverObserver>(resolution.operation.resolverObserver)
                .rootFieldReferenceInvocations()
                .single()
        val malformedObserver = RecordingResolverObserver()
        malformedObserver.onRootFieldReferenceInvocation(
            observation.copy(suppliedDemand = model.selectionForestOf()),
        )
        val validationOperation =
            OperationContext(
                world = resolution.operation.world,
                variableBindingsState = resolution.operation.variableBindingsState,
                resolverObserver = malformedObserver,
            )
        val query =
            resolution.operation.world.fragmentFrom(
                "fragment Result on Query { result(enabled: true) }",
            )

        assertTrue(
            context(validationOperation) {
                resolution.result.correctResolution(
                    query.subselections.merge(validationOperation.schema.requireQueryTypeDef()),
                )
            },
        )
    }

    @Test
    fun `correctness rejects a root-reference witness with a noncanonical invocation path`() {
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
                    )
                },
            )
        val world = testWorld.assumptions
        val query = world.fragmentFrom("fragment Result on Query { container { product { value } } }")
        val observer = RecordingResolverObserver()
        val operation = OperationContext(world, resolverObserver = observer)
        val result = context(operation) { resolve(query.subselections) }
        val observation = observer.rootFieldReferenceInvocations().single()
        val malformedObserver = RecordingResolverObserver()
        malformedObserver.onRootFieldReferenceInvocation(
            observation.copy(
                invocationPath =
                    listOf(ListEngineResult.Index.of(0)) + observation.invocationPath,
            ),
        )
        val validationOperation =
            OperationContext(
                world = world,
                variableBindingsState = operation.variableBindingsState,
                resolverObserver = malformedObserver,
            )

        assertFalse(
            context(validationOperation) {
                result.correctResolution(
                    query.subselections.merge(world.schema.requireQueryTypeDef()),
                )
            },
        )
    }

    @Test
    fun `correctness rejects sibling root references that reuse one invocation root`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      product: Product!
                    }

                    type Container {
                      first: Product!
                      second: Product!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "product")
                    val reference = RootFieldReferenceData.of(listOf(target), emptyMap())
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "first" setTo reference
                                    "second" setTo reference
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Product") { "value" setTo "target" }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val query =
            world.fragmentFrom(
                "fragment Result on Query { container { first { value } second { value } } }",
            )
        val observer = RecordingResolverObserver()
        val operation = OperationContext(world, resolverObserver = observer)
        val result = context(operation) { resolve(query.subselections) }
        val observations = observer.rootFieldReferenceInvocations()
        assertEquals(2, observations.size)
        val malformedObserver = RecordingResolverObserver()
        malformedObserver.onRootFieldReferenceInvocation(observations[0])
        malformedObserver.onRootFieldReferenceInvocation(
            observations[1].copy(
                invocationRoot = observations[0].invocationRoot,
                invocationPath = observations[0].invocationPath,
                invocationKey = observations[0].invocationKey,
            ),
        )
        val validationOperation =
            OperationContext(
                world = world,
                variableBindingsState = operation.variableBindingsState,
                resolverObserver = malformedObserver,
            )

        assertFalse(
            context(validationOperation) {
                result.correctResolution(
                    query.subselections.merge(world.schema.requireQueryTypeDef()),
                )
            },
        )
    }

    private fun resolveConditionalPassiveListReference(
        enabled: Boolean,
    ): ConditionalPassiveListResolution {
        val targetApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result(enabled: Boolean!): Int!
                      container: Container!
                      product: Product!
                    }

                    type Container {
                      products: [Product!]!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "product")
                    mapOf(
                        result to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ResultInput on Query {
                                      container {
                                        products @include(if: ${'$'}enabled) { value }
                                      }
                                    }
                                    """.trimIndent(),
                                    variableField = result,
                                ),
                            ) { _, _ -> 1 },
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "products" setTo
                                        listOf(
                                            RootFieldReferenceData.of(
                                                listOf(target),
                                                emptyMap(),
                                            ),
                                        )
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                targetApplications.incrementAndGet()
                                schema.objectOf("Product") { "value" setTo "target" }
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "enabled") to
                            schema.fromArgument(result, "enabled"),
                    )
                },
            )
        val world = testWorld.assumptions
        val query = world.fragmentFrom("fragment Result on Query { result(enabled: $enabled) }")
        val operation = OperationContext(world, resolverObserver = RecordingResolverObserver())
        val result = context(operation) { resolve(query.subselections) }
        return ConditionalPassiveListResolution(result, operation, targetApplications)
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

    private data class ConditionalPassiveListResolution(
        val result: ObjectEngineResult,
        val operation: OperationContext,
        val targetApplications: AtomicInteger,
    )
}
