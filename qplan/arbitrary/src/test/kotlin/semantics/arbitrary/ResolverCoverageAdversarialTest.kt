package semantics.arbitrary

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import model.Value
import model.objectOf
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals

class ResolverCoverageAdversarialTest {
    @Disabled("not currently worth the effort")
    @Test
    fun `generated schemas include nested output lists`() {
        error("The generated output model cannot currently express nested lists.")
    }

    @Test
    fun `generated field and node resolvers retain distinct singular and list witness fields`() {
        listOf(false, true).forEachIndexed { index, listOutput ->
            val config =
                Config.default +
                    (ArgumentsEnabled to false) +
                    (InterfacesEnabled to true) +
                    (ListsEnabled to listOutput) +
                    (ListTypeWeight to if (listOutput) 1.0 else 0.0) +
                    (NullValueWeight to 0.0) +
                    (ErrorValueWeight to 0.0) +
                    (NodeResolversEnabled to true) +
                    (ResolverFragmentsEnabled to false) +
                    (SchemaObjectCount to 5..7) +
                    (QueryFieldCount to 4..6)
            val random = RandomSource.seeded(817_208L + index)
            val generated =
                List(200) {
                    val schema = Arb.schema(config).next(random)
                    val registry = schema.registry(config).next(random)
                    val sourceField =
                        registry.fieldResolverCoordinates.firstOrNull { coordinate ->
                            val field =
                                schema
                                    .objectNamed(coordinate.typeName)
                                    .fields
                                    .single { candidate -> candidate.name == coordinate.fieldName }
                            field.type.list == listOutput &&
                                schema.isComposite(field.type.namedType) &&
                                schema.possibleObjects(field.type.namedType)
                                    .all { possible ->
                                        possible.name in registry.nodeResolverTypes
                                    }
                        }
                    sourceField?.let { Triple(schema, registry, it) }
                }.firstNotNullOfOrNull { it }
                    ?: error("Could not generate a Node-valued resolver with list=$listOutput")
            val (schema, registry, sourceField) = generated
            val world = registry.world(schema).assumptions
            val suffix = if (listOutput) "\$ids" else "\$id"
            val loaderField = world.schema.field(sourceField.typeName, sourceField.fieldName)
            val bridgeField =
                world.schema.field(sourceField.typeName, sourceField.fieldName + suffix)
            val emptyInput = world.schema.objectOf(sourceField.typeName)
            val bridgeValue =
                world.executorRegistry
                    .resolver(bridgeField)
                    .tenantResolve(emptyInput, Value.Arguments.of(bridgeField, emptyMap()))
            val loaderInput =
                world.schema.objectOf(sourceField.typeName) {
                    field(bridgeField.fieldName) setTo bridgeValue
                }

            registry.clearResolutionWitness()
            world.executorRegistry
                .resolver(bridgeField)
                .tenantResolve(emptyInput, Value.Arguments.of(bridgeField, emptyMap()))
            world.executorRegistry
                .resolver(loaderField)
                .tenantResolve(loaderInput, Value.Arguments.of(loaderField, emptyMap()))

            assertEquals(
                listOf(
                    FieldCoordinate(sourceField.typeName, sourceField.fieldName + suffix),
                    sourceField,
                ),
                registry.resolutionWitness().applications.map { application ->
                    application.key.field
                },
            )
        }
    }

    @Disabled("not currently worth the effort")
    @Test
    fun `Resolver03 witness profile activates polymorphic passive deepening`() {
        error(
            "Generated abstract outputs occur only on active Query fields, so passive " +
                "polymorphic deepening is currently unreachable.",
        )
    }
}
