package semantics.propertytest

import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.GENERATOR_CONFIG_FORMAT_VERSION
import semantics.arbitrary.GeneratorConfigData
import semantics.resolver25.Resolver25BroadStressProfile
import semantics.resolver25.withLargeDeepResolver25Worlds
import semantics.resolver26.Resolver26BroadStressProfile
import semantics.resolver26.withLargeDeepResolver26Worlds
import java.nio.file.Files
import java.nio.file.Path

object GeneratorConfigMaterializer {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) {
            "Usage: GeneratorConfigMaterializer <resources-root>"
        }
        val root = Path.of(args.single())
        val generatorData = generatorConfigData()

        val generatorDirectory =
            root.resolve("semantics/property-tests/generator-configs")
        Files.createDirectories(generatorDirectory)
        val families = generatorConfigFamilies()
        require(
            families
                .flatMap(GeneratorConfigFamily::profileIds)
                .toSet() == generatorData.keys,
        ) {
            "Generator config families do not cover the generated profiles; " +
                "missing=${generatorData.keys - families.flatMap(GeneratorConfigFamily::profileIds).toSet()}, " +
                "unknown=${families.flatMap(GeneratorConfigFamily::profileIds).toSet() - generatorData.keys}"
        }
        val familyDocuments = layeredGeneratorConfigDocuments(generatorData)

        Files.list(generatorDirectory).use { files ->
            files
                .filter { file -> file.fileName.toString().endsWith(".json") }
                .forEach(Files::delete)
        }
        familyDocuments.forEach { (resourceId, document) ->
            write(
                generatorDirectory.resolve("$resourceId.json"),
                document,
            )
        }
        write(
            generatorDirectory.resolve("index.json"),
            GeneratorConfigResourceIndex(
                formatVersion = GENERATOR_CONFIG_INDEX_FORMAT_VERSION,
                resources =
                    families.map { family ->
                        "/semantics/property-tests/generator-configs/" +
                            "${family.resourceId}.json"
                    },
            ),
        )
    }
}

private fun generatorConfigData(): Map<String, GeneratorConfigData> =
    linkedMapOf<String, GeneratorConfigData>().apply {
        Resolver25BroadStressProfile.entries.forEach { profile ->
            add("resolver25-${profile.id}-standard", profile.config)
            add(
                "resolver25-${profile.id}-large-deep",
                profile.config.withLargeDeepResolver25Worlds(),
            )
        }
        Resolver26BroadStressProfile.entries.forEach { profile ->
            add("resolver26-${profile.id}-standard", profile.config)
            val largeDeep = profile.config.withLargeDeepResolver26Worlds()
            if (profile != Resolver26BroadStressProfile.SYMBOLIC_IDENTITY) {
                add("resolver26-${profile.id}-large-deep", largeDeep)
            }
            add(
                "resolver26-${profile.id}-large-deep-low-duplicates",
                largeDeep + (DuplicateSelectionWeight to 0.1),
            )
        }
    }

private fun layeredGeneratorConfigDocuments(
    dataById: Map<String, GeneratorConfigData>,
): Map<String, GeneratorConfigDocumentFile> =
    generatorConfigFamilies().associate { family ->
        family.resourceId to family.document(dataById)
    }

private fun MutableMap<String, GeneratorConfigData>.add(
    id: String,
    config: Config,
) {
    val file = GeneratorConfigData.from(id, config)
    val previous = putIfAbsent(id, file)
    require(previous == null || previous == file) {
        "Generator profile $id resolves to more than one configuration"
    }
}

private data class GeneratorConfigFamily(
    val resourceId: String,
    val resolver25ProfileId: String? = null,
    val resolver26ProfileId: String? = null,
) {
    fun profileIds(): List<String> =
        listOfNotNull(resolver25ProfileId, resolver26ProfileId)

    fun document(dataById: Map<String, GeneratorConfigData>): GeneratorConfigDocumentFile {
        val resolver25Data = resolver25ProfileId?.let(dataById::getValue)
        val resolver26Data = resolver26ProfileId?.let(dataById::getValue)
        val resolver25Values = resolver25Data?.valuesData()
        val resolver26Values = resolver26Data?.valuesData()
        val shared =
            when {
                resolver25Values != null && resolver26Values != null ->
                    commonValues(resolver25Values, resolver26Values)
                resolver25Values != null -> resolver25Values
                resolver26Values != null -> resolver26Values
                else -> error("Generator config family $resourceId has no resolver profiles")
            }
        return GeneratorConfigDocumentFile(
            formatVersion = GENERATOR_CONFIG_DOCUMENT_FORMAT_VERSION,
            shared = shared,
            resolver25 =
                resolver25Data?.let { data ->
                    resolverOverrides(data, resolver25Values!!, shared)
                },
            resolver26 =
                resolver26Data?.let { data ->
                    resolverOverrides(data, resolver26Values!!, shared)
                },
        )
    }
}

private fun generatorConfigFamilies(): List<GeneratorConfigFamily> =
    listOf(
        pairedFamily("balanced-standard", "balanced", "balanced", "standard"),
        pairedFamily(
            "descendants-standard",
            "list-descendants",
            "descendant-variables",
            "standard",
        ),
        pairedFamily(
            "nullable-errors-standard",
            "nullable-errors",
            "nullable-errors",
            "standard",
        ),
        pairedFamily(
            "variable-pressure-standard",
            "mixed-variables",
            "symbolic-identity",
            "standard",
        ),
        pairedFamily(
            "multiple-owners-standard",
            "multiple-owners",
            "multiple-owners",
            "standard",
        ),
        pairedFamily("balanced-large-deep", "balanced", "balanced", "large-deep"),
        pairedFamily(
            "descendants-large-deep",
            "list-descendants",
            "descendant-variables",
            "large-deep",
        ),
        pairedFamily(
            "nullable-errors-large-deep",
            "nullable-errors",
            "nullable-errors",
            "large-deep",
        ),
        GeneratorConfigFamily(
            resourceId = "variable-pressure-large-deep",
            resolver25ProfileId = "resolver25-mixed-variables-large-deep",
            resolver26ProfileId =
                "resolver26-symbolic-identity-large-deep-low-duplicates",
        ),
        pairedFamily(
            "multiple-owners-large-deep",
            "multiple-owners",
            "multiple-owners",
            "large-deep",
        ),
        resolver26Family("balanced-large-deep-low-duplicates"),
        resolver26Family("descendant-variables-large-deep-low-duplicates"),
        resolver26Family("nullable-errors-large-deep-low-duplicates"),
        resolver26Family("multiple-owners-large-deep-low-duplicates"),
    )

private fun pairedFamily(
    resourceId: String,
    resolver25Profile: String,
    resolver26Profile: String,
    variant: String,
): GeneratorConfigFamily =
    GeneratorConfigFamily(
        resourceId = resourceId,
        resolver25ProfileId = "resolver25-$resolver25Profile-$variant",
        resolver26ProfileId = "resolver26-$resolver26Profile-$variant",
    )

private fun resolver26Family(profileId: String): GeneratorConfigFamily =
    GeneratorConfigFamily(
        resourceId = "resolver26-$profileId",
        resolver26ProfileId = "resolver26-$profileId",
    )

private fun resolverOverrides(
    data: GeneratorConfigData,
    values: GeneratorConfigValuesData,
    shared: GeneratorConfigValuesData,
): GeneratorConfigProfileData =
    GeneratorConfigProfileData(
        id = data.id,
        booleans = values.booleans - shared.booleans.keys,
        integers = values.integers - shared.integers.keys,
        doubles = values.doubles - shared.doubles.keys,
        ranges = values.ranges - shared.ranges.keys,
    )

private fun commonValues(
    left: GeneratorConfigValuesData,
    right: GeneratorConfigValuesData,
): GeneratorConfigValuesData =
    GeneratorConfigValuesData(
        booleans = left.booleans.commonEntries(right.booleans),
        integers = left.integers.commonEntries(right.integers),
        doubles = left.doubles.commonEntries(right.doubles),
        ranges = left.ranges.commonEntries(right.ranges),
    )

private fun <T> Map<String, T>.commonEntries(
    other: Map<String, T>,
): Map<String, T> =
    filter { (name, value) -> other[name] == value }

private fun write(
    path: Path,
    value: Any,
) {
    Files.createDirectories(path.parent)
    Files.writeString(path, PropertyTestJson.write(value))
}
