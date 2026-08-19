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
import model.OpenArguments
import model.PathComponent
import model.Schema
import model.Selection
import model.SelectionForest
import model.TypeExpr
import model.Value
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
    val arguments: Value.Arguments,
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
            arguments: Value.Arguments,
            input: Value.Object,
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
            arguments: Value.Arguments,
            input: Value.Object,
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
        arguments: Value.Arguments,
        input: Value.Object,
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
        arguments: Value.Arguments,
        input: Value.Object,
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
                        val arguments = key.arguments as? Value.Arguments
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
                val field = possibleType.fields[selection.key.field.fieldName]
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
        canonicalFields = closure.mapTo(linkedSetOf(), Schema.OutputField::fieldCoordinate),
    )
}

fun Value.Arguments.resolutionFingerprint(
    expectedType: Schema.FieldArguments,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): ResolutionFingerprint =
    ResolutionFingerprint(
        FingerprintBudget(bounds).arguments(this, expectedType),
    )

fun Value.Object.resolutionFingerprint(
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
        arguments: OpenArguments,
        expectedType: Schema.FieldArguments,
    ): String =
        when (arguments) {
            OpenArguments.Ground.Error -> node("error-args")
            is Value.Arguments ->
                node(
                    "args(" +
                        arguments.fieldValues.entries
                            .sortedBy { entry -> entry.key }
                            .joinToString(",") { (name, value) ->
                                atom(name) +
                                    "=" +
                                    input(value, expectedType.fields.getValue(name).typeExpr)
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
            value is Value.Object ->
                node(
                    "object:${atom(value.type.typeName)}{" +
                        value.fieldValues.entries
                            .map { (key, fieldValue) ->
                                atom(key) + "=" + output(fieldValue)
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
                ";nominal=" + atom(selection.key.field.containingType.typeName) +
                ";possible=" +
                selection.possibleTypes
                    .map { type -> atom(type.typeName) }
                    .sorted()
                    .joinToString(",", "[", "]") +
                ";children=" + forest(selection.subselections) +
                ")",
        )

    private fun canonicalKey(key: ObjectEngineResult.Key): String =
        node(
            "key(" +
                atom(key.field.containingType.typeName) +
                "/" + atom(key.field.fieldName) +
                ";" + arguments(key.arguments, key.field.arguments) +
                ")",
        )

    private fun input(
        value: EngineInputData?,
        expectedType: TypeExpr<Schema.InputType>,
    ): String {
        if (value == null) return node("null")

        return when (expectedType) {
            is TypeExpr.List -> {
                val values = requireType<EngineInputListData>(value)
                node(
                    "list:${typeExpr(expectedType.elementType)}[" +
                        values.joinToString(separator = ",") { element ->
                            input(element, expectedType.elementType)
                        } +
                        "]",
                )
            }
            is TypeExpr.Named ->
                when (val expectedNamedType = expectedType.baseType) {
                    Schema.IntType -> node("int:${value as Int}")
                    Schema.FloatType ->
                        node("float:${(value as Double).toBits()}")
                    Schema.StringType ->
                        node("string:${atom(value as String)}")
                    Schema.BooleanType ->
                        node("boolean:${value as Boolean}")
                    Schema.IDType -> node("id:${atom(value as String)}")
                    is Schema.EnumType ->
                        node(
                            "enum:${atom(expectedNamedType.typeName)}:" +
                                atom(value as String),
                        )
                    is Schema.InputObjectType -> {
                        val fields = requireType<EngineInputObjectData>(value)
                        node(
                            "input-object:${atom(expectedNamedType.typeName)}{" +
                                fields.entries
                                    .sortedBy(Map.Entry<String, EngineInputData?>::key)
                                    .joinToString(",") { (name, fieldValue) ->
                                        atom(name) +
                                            "=" +
                                            input(
                                                fieldValue,
                                                expectedNamedType.fields.getValue(name).typeExpr,
                                            )
                                    } +
                                "}",
                        )
                    }
                }
        }
    }

    private inline fun <reified T> requireType(value: EngineInputData): T {
        require(value is T)
        return value
    }

    private fun typeExpr(typeExpr: TypeExpr<Schema.Type>): String =
        when (typeExpr) {
            is TypeExpr.Named ->
                "named(${atom(typeExpr.baseType.typeName)},nullable=${typeExpr.isNullable})"
            is TypeExpr.List ->
                "list(${typeExpr(typeExpr.elementType)},nullable=${typeExpr.isNullable})"
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
        "${field.containingType.typeName.length}:${field.containingType.typeName}/" +
            "${field.fieldName.length}:${field.fieldName};" +
            FingerprintBudget(bounds).arguments(arguments, field.arguments),
    )

private fun Schema.OutputField.fieldCoordinate(): FieldCoordinate =
    FieldCoordinate(containingType.typeName, fieldName)
