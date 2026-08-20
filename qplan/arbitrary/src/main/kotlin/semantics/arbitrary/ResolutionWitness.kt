package semantics.arbitrary

import model.EngineResult
import model.EngineErrorData
import model.EngineOutputData
import model.EngineInputData
import model.EngineInputListData
import model.EngineInputObjectData
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Arguments
import model.PathComponent
import model.Schema
import model.Selection
import model.SelectionForest
import model.TypeExpr
import model.requireArg
import model.requireField
import viaduct.engine.api.EngineObjectData
import model.registry.ResolverRegistry
import java.security.MessageDigest

/**
 * Resource limits for diagnostic resolution witnesses.
 *
 * Witnesses are test infrastructure, not semantic results. Bounds make accidental use on an
 * unexpectedly large generated value fail explicitly instead of retaining an unbounded trace.
 */
data class ResolutionWitnessBounds(
    val maxApplications: Int = 1_000_000,
    val maxResultNodes: Int = 1_000_000,
    val maxOperationSelections: Int = 100_000,
    val maxClosureSites: Int = 10_000,
    val maxFingerprintNodes: Int = 100_000,
    val maxFingerprintCharacters: Int = 1_000_000,
) {
    init {
        require(maxApplications > 0)
        require(maxResultNodes > 0)
        require(maxOperationSelections > 0)
        require(maxClosureSites > 0)
        require(maxFingerprintNodes > 0)
        require(maxFingerprintCharacters > 0)
    }
}

class ResolutionWitnessBoundExceededException(
    bound: String,
    maximum: Int,
) : IllegalStateException("Resolution witness exceeded $bound bound of $maximum")

/** A deterministic, permutation-invariant structural description used only for diagnostics. */
@JvmInline
value class ResolutionFingerprint(
    val value: String,
)

/** The canonical field identity of one field-resolver application after fixture lowering. */
data class ResolverApplicationKey(
    val field: FieldCoordinate,
    val arguments: Arguments.Resolved,
)

/**
 * The observable identity of one deterministic field-resolver application.
 *
 * Separate result occurrences with equal field coordinates and arguments remain distinguishable
 * when their materialized resolver inputs differ.
 */
data class ResolverApplicationIdentity(
    val key: ResolverApplicationKey,
    val inputFingerprint: ResolutionFingerprint,
)

data class ResolverApplicationObservation(
    val identity: ResolverApplicationIdentity,
    val suppliedDemandFingerprint: ResolutionFingerprint?,
)

/** One application identity qualified by its exact root-to-field OER path. */
data class ResolverOccurrenceApplicationIdentity(
    val occurrencePath: List<PathComponent>,
    val applicationIdentity: ResolverApplicationIdentity,
)

/** One path-qualified application identity and its supplied-demand fingerprint. */
data class ResolverOccurrenceApplicationObservation(
    val identity: ResolverOccurrenceApplicationIdentity,
    val suppliedDemandFingerprint: ResolutionFingerprint?,
)

data class ResolverApplicationRecord(
    val key: ResolverApplicationKey,
    val inputFingerprint: ResolutionFingerprint,
    val suppliedDemandFingerprint: ResolutionFingerprint?,
) {
    val identity: ResolverApplicationIdentity
        get() = ResolverApplicationIdentity(key, inputFingerprint)

    val observation: ResolverApplicationObservation
        get() = ResolverApplicationObservation(identity, suppliedDemandFingerprint)

    companion object {
        fun capture(
            field: FieldCoordinate,
            arguments: Arguments.Resolved,
            input: EngineObjectData.Sync,
            suppliedDemand: SelectionForest? = null,
            bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
        ): ResolverApplicationRecord =
            ResolverApplicationRecord(
                key = ResolverApplicationKey(field, arguments),
                inputFingerprint = input.resolutionFingerprint(bounds),
                suppliedDemandFingerprint = suppliedDemand?.resolutionDigest(bounds),
            )
    }
}

data class ResolverOccurrenceApplicationRecord(
    val occurrencePath: List<PathComponent>,
    val application: ResolverApplicationRecord,
) {
    val identity: ResolverOccurrenceApplicationIdentity
        get() = ResolverOccurrenceApplicationIdentity(occurrencePath, application.identity)

    val observation: ResolverOccurrenceApplicationObservation
        get() =
            ResolverOccurrenceApplicationObservation(
                identity,
                application.suppliedDemandFingerprint,
            )

    companion object {
        fun capture(
            occurrencePath: List<PathComponent>,
            field: FieldCoordinate,
            arguments: Arguments.Resolved,
            input: EngineObjectData.Sync,
            suppliedDemand: SelectionForest? = null,
            bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
        ): ResolverOccurrenceApplicationRecord =
            ResolverOccurrenceApplicationRecord(
                occurrencePath = occurrencePath.toList(),
                application =
                    ResolverApplicationRecord.capture(
                        field = field,
                        arguments = arguments,
                        input = input,
                        suppliedDemand = suppliedDemand,
                        bounds = bounds,
                    ),
            )
    }
}

/** Mutable bounded recorder intended to be snapshotted before an extensional oracle is run. */
class ResolutionApplicationLog(
    private val bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
) {
    private val lock = Any()
    private val records = mutableListOf<ResolverApplicationRecord>()
    @Volatile
    private var recording = true

    fun record(
        field: FieldCoordinate,
        arguments: Arguments.Resolved,
        input: EngineObjectData.Sync,
        suppliedDemand: SelectionForest? = null,
    ) {
        if (!recording) return
        val record =
            ResolverApplicationRecord.capture(
                field,
                arguments,
                input,
                suppliedDemand,
                bounds,
            )
        synchronized(lock) {
            if (!recording) return
            if (records.size >= bounds.maxApplications) {
                throw ResolutionWitnessBoundExceededException(
                    "application",
                    bounds.maxApplications,
                )
            }
            records += record
        }
    }

    fun <T> withoutRecording(block: () -> T): T {
        val previous: Boolean =
            synchronized(lock) {
                recording.also { recording = false }
            }
        return try {
            block()
        } finally {
            synchronized(lock) {
                recording = previous
            }
        }
    }

    fun snapshot(): ResolutionWitness =
        synchronized(lock) {
            ResolutionWitness(records.toList())
        }

    fun clear() {
        synchronized(lock) {
            records.clear()
        }
    }
}

/** Thread-safe bounded recorder for applications observed at exact OER occurrence paths. */
class ResolutionOccurrenceApplicationLog(
    private val bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
) {
    private val lock = Any()
    private val records = mutableListOf<ResolverOccurrenceApplicationRecord>()

    fun record(
        occurrencePath: List<PathComponent>,
        field: FieldCoordinate,
        arguments: Arguments.Resolved,
        input: EngineObjectData.Sync,
        suppliedDemand: SelectionForest? = null,
    ) {
        val record =
            ResolverOccurrenceApplicationRecord.capture(
                occurrencePath = occurrencePath,
                field = field,
                arguments = arguments,
                input = input,
                suppliedDemand = suppliedDemand,
                bounds = bounds,
            )
        synchronized(lock) {
            if (records.size >= bounds.maxApplications) {
                throw ResolutionWitnessBoundExceededException(
                    "application",
                    bounds.maxApplications,
                )
            }
            records += record
        }
    }

    fun snapshot(): ResolutionOccurrenceWitness =
        synchronized(lock) {
            ResolutionOccurrenceWitness(records.toList())
        }
}

data class ResolutionWitness(
    val applications: List<ResolverApplicationRecord>,
) {
    /** Coarse counts that deliberately combine applications with different inputs. */
    fun applicationCounts(): Map<ResolverApplicationKey, Int> =
        applications.groupingBy(ResolverApplicationRecord::key).eachCount()

    /** Exact observable counts for deterministic field-resolver applications. */
    fun applicationIdentityCounts(): Map<ResolverApplicationIdentity, Int> =
        applications.groupingBy(ResolverApplicationRecord::identity).eachCount()

    /** Exact application counts including the demand supplied at each application boundary. */
    fun applicationObservationCounts(): Map<ResolverApplicationObservation, Int> =
        applications.groupingBy(ResolverApplicationRecord::observation).eachCount()

    fun duplicateApplications(): Map<ResolverApplicationKey, Int> =
        applicationCounts().filterValues { count -> count > 1 }

    fun unrelatedApplications(allowed: AllowedResolverClosure): List<ResolverApplicationRecord> =
        applications.filter { application ->
            application.key.field !in allowed.canonicalFields
        }
}

data class ResolutionOccurrenceWitness(
    val applications: List<ResolverOccurrenceApplicationRecord>,
) {
    fun applicationIdentityCounts(): Map<ResolverOccurrenceApplicationIdentity, Int> =
        applications.groupingBy(ResolverOccurrenceApplicationRecord::identity).eachCount()

    fun applicationObservationCounts(): Map<ResolverOccurrenceApplicationObservation, Int> =
        applications.groupingBy(ResolverOccurrenceApplicationRecord::observation).eachCount()
}

/**
 * One registered resolver occurrence found by traversing a returned result independently of the
 * resolver constructors and correctness predicates. [occurrencePath] is the exact root-to-field
 * OER path: [ObjectEngineResult.GroundKey] components select object fields and [ListEngineResult.Index] components
 * select list elements, distinguishing equal fields at different list positions.
 */
data class RegisteredResolverOccurrence(
    val applicationKey: ResolverApplicationKey,
    val canonicalField: FieldCoordinate,
    val occurrencePath: List<PathComponent>,
    val containingObject: ObjectEngineResult,
)

fun EngineResult?.registeredResolverOccurrences(
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): List<RegisteredResolverOccurrence> {
    val result = mutableListOf<RegisteredResolverOccurrence>()
    var visitedNodes = 0

    fun visit(
        value: EngineResult?,
        path: List<PathComponent>,
    ) {
        visitedNodes += 1
        if (visitedNodes > bounds.maxResultNodes) {
            throw ResolutionWitnessBoundExceededException(
                "result-node",
                bounds.maxResultNodes,
            )
        }
        if (value == null || value == ErrorEngineResult) return

        when (value) {
            is ObjectEngineResult -> {
                value.keys
                    .sortedBy { key -> key.canonicalFingerprint(bounds).value }
                    .forEach { key ->
                        val canonicalField = key.field.fieldCoordinate()
                        val fieldPath = path + key
                        val arguments = key.arguments as? Arguments.Resolved
                        if (key.field in registry && arguments != null) {
                            result +=
                                RegisteredResolverOccurrence(
                                    applicationKey =
                                        ResolverApplicationKey(
                                            field = key.field.fieldCoordinate(),
                                            arguments = arguments,
                                        ),
                                    canonicalField = canonicalField,
                                    occurrencePath = fieldPath,
                                    containingObject = value,
                                )
                        }
                        visit(value.getCell(key).getValue().get(), fieldPath)
                    }
            }

            is ListEngineResult ->
                value.forEachIndexed { index, cell ->
                    visit(
                        cell.getValue().get(),
                        path + ListEngineResult.Index.of(index),
                    )
                }

            else -> Unit
        }
    }

    visit(this, emptyList())
    return result
}

fun EngineResult?.registeredResolverOccurrenceCounts(
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): Map<ResolverApplicationKey, Int> =
    registeredResolverOccurrences(registry, bounds)
        .groupingBy(RegisteredResolverOccurrence::applicationKey)
        .eachCount()

/** Resolver fields conservatively reachable from fields directly selected by an operation. */
data class AllowedResolverClosure(
    val directlySelectedFields: Set<Schema.ObjectField>,
    val canonicalFields: Set<FieldCoordinate>,
)

fun SelectionForest.allowedResolverClosure(
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): AllowedResolverClosure {
    val directlySelected = linkedSetOf<Schema.ObjectField>()
    var visitedSelections = 0

    fun collect(selections: SelectionForest) {
        selections.forEach { selection ->
            visitedSelections += 1
            if (visitedSelections > bounds.maxOperationSelections) {
                throw ResolutionWitnessBoundExceededException(
                    "operation-selection",
                    bounds.maxOperationSelections,
                )
            }
            selection.possibleTypes.forEach { possibleType ->
                val field = possibleType.field(selection.key.field.name)
                if (field is Schema.ObjectField && field in registry) {
                    directlySelected += field
                }
            }
            collect(selection.subselections)
        }
    }

    collect(this)
    val closure = linkedSetOf<Schema.ObjectField>()
    val pending = ArrayDeque<Schema.ObjectField>()
    directlySelected.forEach { site ->
        closure += site
        pending += site
    }
    if (closure.size > bounds.maxClosureSites) {
        throw ResolutionWitnessBoundExceededException(
            "closure-site",
            bounds.maxClosureSites,
        )
    }
    while (pending.isNotEmpty()) {
        val site = pending.removeFirst()
        registry.mayDemandFrom(site).forEach { dependency ->
            if (closure.add(dependency)) {
                if (closure.size > bounds.maxClosureSites) {
                    throw ResolutionWitnessBoundExceededException(
                        "closure-site",
                        bounds.maxClosureSites,
                    )
                }
                pending += dependency
            }
        }
    }

    return AllowedResolverClosure(
        directlySelectedFields = directlySelected,
        canonicalFields = closure.mapTo(linkedSetOf(), Schema.Field::fieldCoordinate),
    )
}

fun Arguments.Resolved.resolutionFingerprint(
    expectedField: Schema.Field,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): ResolutionFingerprint =
    ResolutionFingerprint(
        FingerprintBudget(bounds).arguments(this, expectedField),
    )

fun EngineObjectData.Sync.resolutionFingerprint(
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): ResolutionFingerprint =
    ResolutionFingerprint(
        FingerprintBudget(bounds).output(this),
    )

fun SelectionForest.resolutionFingerprint(
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): ResolutionFingerprint =
    ResolutionFingerprint(
        FingerprintBudget(bounds).forest(this),
    )

/**
 * A compact deterministic structural digest for potentially large occurrence-preserving demand.
 */
fun SelectionForest.resolutionDigest(
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): ResolutionFingerprint {
    val canonical = resolutionFingerprint(bounds).value.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
    return ResolutionFingerprint(
        "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) },
    )
}

private class FingerprintBudget(
    private val bounds: ResolutionWitnessBounds,
) {
    private var nodes = 0

    fun arguments(
        arguments: Arguments,
        expectedField: Schema.Field,
    ): String =
        when (arguments) {
            Arguments.Error -> node("error-args")
            is Arguments.Resolved ->
                node(
                    "args(" +
                        arguments.fieldValues.entries
                            .sortedBy { entry -> entry.key }
                            .joinToString(",") { (name, value) ->
                                atom(name) +
                                    "=" +
                                    input(value, expectedField.requireArg(name).type)
                            } +
                        ")",
                )
            else -> node("open-args:${arguments.hashCode()}")
        }

    fun output(value: EngineOutputData?): String =
        when {
            value == null -> node("null")
            value == EngineErrorData -> node("error")
            value is Int -> node("int:$value")
            value is Double -> node("float:${value.toBits()}")
            value is String -> node("string:${atom(value)}")
            value is Boolean -> node("boolean:$value")
            value is List<*> ->
                node(
                    "list[" +
                        value.joinToString(separator = ",", transform = ::output) +
                        "]",
                )
            value is EngineObjectData.Sync ->
                node(
                    "object:${atom(value.type.name)}{" +
                        value.getSelections()
                            .map { selection ->
                                atom(selection) + "=" + output(value.get(selection))
                            }.sorted()
                            .joinToString(",") +
                        "}",
                )
            else -> error("Unsupported output value $value")
        }

    fun forest(forest: SelectionForest): String {
        val selections = mutableListOf<String>()
        forest.forEach { selection -> selections += selection(selection) }
        return node("forest[" + selections.sorted().joinToString(",") + "]")
    }

    private fun selection(selection: Selection): String =
        node(
            "selection(" +
                canonicalKey(selection.key) +
                ";nominal=" + atom(selection.key.field.containingDef.name) +
                ";possible=" +
                selection.possibleTypes
                    .map { type -> atom(type.name) }
                    .sorted()
                    .joinToString(",", "[", "]") +
                ";children=" + forest(selection.subselections) +
                ")",
        )

    private fun canonicalKey(key: ObjectEngineResult.Key): String =
        node(
            "key(" +
                atom(key.field.containingDef.name) +
                "/" + atom(key.field.name) +
                ";" + arguments(key.arguments, key.field) +
                ")",
        )

    private fun input(
        value: EngineInputData?,
        expectedType: TypeExpr<Schema.InputTypeDef>,
    ): String {
        if (value == null) return node("null")

        val elementType = expectedType.unwrapList()
        if (elementType != null) {
            val values = requireType<EngineInputListData>(value)
            return node(
                "list:${typeExpr(elementType)}[" +
                    values.joinToString(separator = ",") { element ->
                        input(element, elementType)
                    } +
                    "]",
            )
        }
        return when (val expectedNamedType = expectedType.baseTypeDef) {
            is Schema.Scalar ->
                when (expectedNamedType.name) {
                    "Int" -> node("int:${value as Int}")
                    "Float" ->
                        node("float:${(value as Double).toBits()}")
                    "String" ->
                        node("string:${atom(value as String)}")
                    "Boolean" ->
                        node("boolean:${value as Boolean}")
                    "ID" -> node("id:${atom(value as String)}")
                    else -> error("Unsupported scalar: ${expectedNamedType.name}")
                }
            is Schema.Enum ->
                node(
                    "enum:${atom(expectedNamedType.name)}:" +
                        atom(value as String),
                )
            is Schema.Input -> {
                val fields = requireType<EngineInputObjectData>(value)
                node(
                    "input-object:${atom(expectedNamedType.name)}{" +
                        fields.entries
                            .sortedBy(Map.Entry<String, EngineInputData?>::key)
                            .joinToString(",") { (name, fieldValue) ->
                                atom(name) +
                                    "=" +
                                    input(
                                        fieldValue,
                                        expectedNamedType.requireField(name).type,
                                    )
                            } +
                        "}",
                )
            }
        }
    }

    private inline fun <reified T> requireType(value: EngineInputData): T {
        require(value is T)
        return value
    }

    private fun typeExpr(typeExpr: TypeExpr<Schema.TypeDef>): String {
        val elementType = typeExpr.unwrapList()
        return if (elementType == null) {
            "named(${atom(typeExpr.baseTypeDef.name)},nullable=${typeExpr.isNullable})"
        } else {
            "list(${typeExpr(elementType)},nullable=${typeExpr.isNullable})"
        }
    }

    private fun node(value: String): String {
        nodes += 1
        if (nodes > bounds.maxFingerprintNodes) {
            throw ResolutionWitnessBoundExceededException(
                "fingerprint-node",
                bounds.maxFingerprintNodes,
            )
        }
        if (value.length > bounds.maxFingerprintCharacters) {
            throw ResolutionWitnessBoundExceededException(
                "fingerprint-character",
                bounds.maxFingerprintCharacters,
            )
        }
        return value
    }

    private fun atom(value: String): String = "${value.length}:$value"
}

private fun ObjectEngineResult.GroundKey.canonicalFingerprint(
    bounds: ResolutionWitnessBounds,
): ResolutionFingerprint =
    ResolutionFingerprint(
        "${field.containingDef.name.length}:${field.containingDef.name}/" +
            "${field.name.length}:${field.name};" +
            FingerprintBudget(bounds).arguments(arguments, field),
    )

private fun Schema.Field.fieldCoordinate(): FieldCoordinate =
    FieldCoordinate(containingDef.name, name)
