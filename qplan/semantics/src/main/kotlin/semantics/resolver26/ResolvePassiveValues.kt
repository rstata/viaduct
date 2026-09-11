package semantics.resolver26

import kotlinx.coroutines.launch
import viaduct.graphql.schema.ViaductSchema

import model.EngineErrorData
import model.ResolverOutputData
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
import model.InclusionCondition
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.Selection
import model.SelectionForest
import model.RootFieldReferenceData
import model.invariants.conformsToResolverOutputSchemaType
import model.isParentField
import model.merge
import model.outputType
import model.outputValue
import model.requireField
import model.schemaType
import model.selectionForestOf
import model.toEngineResult
import viaduct.engine.api.EngineObjectData

// Builds one passive result value, launching an orchestration lifecycle for every object it creates.
context(operation: Resolver26OperationContext)
internal fun ResolverOutputData?.resolvePassiveValues(
    root: ObjectEngineResult,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    invocationDemand: SelectionForest,
    constructionDemand: SelectionForest,
    parent: OEROccurrenceContext? = null,
): EngineResult? {
    require(conformsToResolverOutputSchemaType(expectedType)) {
        "Resolver output does not conform to $expectedType"
    }
    return when (this) {
        null -> null
        is EngineErrorData -> ErrorEngineResult.of(this)
        is RootFieldReferenceData ->
            error("A direct root-field reference must be installed before passive resolution")
        is EngineObjectData.Sync ->
            resolvePassiveObjectValues(
                root = root,
                path = path,
                invocationDemand = invocationDemand,
                constructionDemand = constructionDemand,
                parent = parent,
            )
        is List<*> -> {
            requireNotNull(parent) {
                "List value has no containing object occurrence"
            }
            val elementType = checkNotNull(expectedType.unwrapList())
            val result = ListEngineResult.ofPendingValues(elementType, size)
            forEachIndexed { index, value ->
                val elementPath = path + ListEngineResult.Index.of(index)
                val elementCell = result[index]
                if (value is RootFieldReferenceData) {
                    val containingOccurrence = parent
                    launchListElementReference(
                        reference = value,
                        publicationCell = elementCell,
                        publicationPath = elementPath,
                        expectedType = elementType,
                        constructionDemand = constructionDemand,
                        parent = containingOccurrence,
                    )
                } else {
                    elementCell.getValue().complete(
                        value.resolvePassiveValues(
                            root = root,
                            expectedType = elementType,
                            path = elementPath,
                            invocationDemand = invocationDemand,
                            constructionDemand = constructionDemand,
                            parent = parent,
                        ),
                    )
                }
            }
            result
        }
        else ->
            toEngineResult(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
    }
}

context(operation: Resolver26OperationContext)
private fun launchListElementReference(
    reference: RootFieldReferenceData,
    publicationCell: EngineResultCell,
    publicationPath: List<PathComponent>,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    constructionDemand: SelectionForest,
    parent: OEROccurrenceContext,
) {
    val consumerKey = publicationPath.filterIsInstance<ObjectEngineResult.ObjectKey>().last()
    val selection =
        selectionForestOf(
            Selection.of(
                key = consumerKey,
                possibleTypes = setOf(parent.target.type),
                subselections = constructionDemand,
            ),
        ).merge(parent.target.type).byKey().getValue(consumerKey)
    operation.cycleChecker.registerWriter(publicationCell, publicationPath)
    val task =
        FieldResolverTask(
            operationContext = operation,
            oerOccurrenceContext = parent,
            resolverOccurrenceContext =
                RootFieldReferenceOccurrence(
                    selection = selection,
                    reference = reference,
                    publicationPath = publicationPath,
                    publicationExpectedType = expectedType,
                ),
            cell = publicationCell,
        )
    operation.requestScope.launch { task.run() }
}

// Creates one mutable OER and enters its orchestration lifecycle before descending into children.
context(operation: Resolver26OperationContext)
private fun EngineObjectData.Sync.resolvePassiveObjectValues(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    invocationDemand: SelectionForest,
    constructionDemand: SelectionForest,
    parent: OEROccurrenceContext?,
): ObjectEngineResult {
    val target =
        ObjectEngineResult.of(
            type = schemaType,
            mutable = true,
        )
    val occurrence =
        OEROccurrenceContext(
            root = root,
            path = path,
            target = target,
            parent = parent,
        )
    val orchestration =
        ObjectOrchestrationTask(
            operation = operation,
            occurrence = occurrence,
            source = this,
            initialDemand = constructionDemand,
        )
    val closedDemand = orchestration.prepare()
    materializePassiveFields(
        occurrence = occurrence,
        invocationDemand = invocationDemand,
        closedDemand = closedDemand,
    )
    orchestration.launch()
    return target
}

// Copies resolver-returned passive fields except engine-provided parent backedges.
context(operation: Resolver26OperationContext)
private fun EngineObjectData.Sync.materializePassiveFields(
    occurrence: OEROccurrenceContext,
    invocationDemand: SelectionForest,
    closedDemand: ObjectSelectionForest,
) {
    val invocationDemandByKey = invocationDemand.merge(schemaType).byKey()
    val passiveDemandByKey = (invocationDemand + closedDemand).merge(schemaType).byKey()
    if (operation.selectiveResolvers) {
        val selectedFieldNames =
            invocationDemandByKey.keys
                .mapTo(linkedSetOf()) { key -> key.field.name }
        val unselectedKeys = getSelections().toSet() - selectedFieldNames
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${schemaType.name} contains unselected fields: " +
                unselectedKeys.joinToString()
        }
    }

    val closedDemandByKey = closedDemand.byKey()
    getSelections().forEach { fieldName ->
        val field = schemaType.requireField(fieldName)
        if (field.isParentField()) return@forEach
        require(field.args.isEmpty()) {
            "Resolver output must not supply argument-bearing field " +
                "${schemaType.name}/$fieldName"
        }
        val output = outputValue(fieldName)
        if (output is RootFieldReferenceData) return@forEach
        // A reference is executable resolver work, so a list containing one cannot be materialized
        // speculatively like ordinary passive data. Gate the containing field before creating any
        // element tasks, and omit an undemanded reference-bearing list entirely.
        val containsListElementReference = output.containsListElementRootFieldReference()
        val demandedKeys =
            passiveDemandByKey.keys.mapNotNullTo(linkedSetOf()) { key ->
                if (key.field != field) {
                    null
                } else {
                    check(key is ObjectEngineResult.GroundKey) {
                        "Passive returned field has an open key: $key"
                    }
                    key
                }
            }
        if (demandedKeys.isEmpty()) {
            if (containsListElementReference) return@forEach
            demandedKeys += ObjectEngineResult.GroundKey.of(field, emptyMap())
        }
        demandedKeys.forEach { key ->
            val childInvocationDemand =
                invocationDemandByKey[key]
                    ?.subselections
                    ?: selectionForestOf()
            val childConstructionDemand =
                closedDemandByKey[key]
                    ?.subselections
                    ?: selectionForestOf()
            val inclusionCondition =
                passiveDemandByKey[key]
                    ?.inclusionCondition
                    ?: InclusionCondition.Always
            if (
                containsListElementReference &&
                inclusionCondition !== InclusionCondition.Always
            ) {
                if (inclusionCondition === InclusionCondition.Never) return@forEach
                occurrence.installAndLaunchResolver(
                    PassiveValueOccurrence(
                        selection = passiveDemandByKey.getValue(key),
                        value = output,
                        invocationDemand = childInvocationDemand,
                        publicationConstructionDemand = childConstructionDemand,
                        publicationPath = occurrence.coordinate(key),
                    ),
                )
            } else {
                val value =
                    output
                        .resolvePassiveValues(
                            root = occurrence.root,
                            expectedType = key.field.outputType,
                            path = occurrence.coordinate(key),
                            invocationDemand = childInvocationDemand,
                            constructionDemand = childConstructionDemand,
                            parent = occurrence,
                        )
                occurrence.target.setCellValue(key, value)
            }
        }
    }
}

private fun Any?.containsListElementRootFieldReference(): Boolean =
    this is List<*> &&
        any { value ->
            value is RootFieldReferenceData || value.containsListElementRootFieldReference()
        }
