package model.registry

import model.Arguments
import model.MaterializeSelectionForest
import model.SelectionForest
import model.selectionForestOf
import model.toCanonicalMaterializeSelectionForest
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Executes one field checker from its resolved arguments and named required-selection values. */
typealias FieldCheckerFunction =
    suspend (Arguments.Resolved, Map<String, EngineObjectData.Sync>) -> CheckerResult

/**
 * One named checker required selection set. Its root is determined by the map containing it.
 *
 * Equality is undefined because selection equality is undefined.
 */
sealed interface CheckerSelectionSet {
    val type: ViaductSchema.Object
    val materializeSelections: MaterializeSelectionForest
    val constructionSelections: SelectionForest

    companion object {
        fun of(
            type: ViaductSchema.Object,
            selections: MaterializeSelectionForest,
        ): CheckerSelectionSet {
            require(
                selections.all { selection ->
                    selection.key.field.containingDef == type &&
                        selection.possibleTypes == setOf(type)
                },
            ) {
                "Checker selection set must be specialized to ${type.name}"
            }
            selections.collect(type)
            return CheckerSelectionSetImpl(type, selections)
        }

        fun of(
            type: ViaductSchema.Object,
            selections: SelectionForest,
        ): CheckerSelectionSet =
            of(type, selections.toCanonicalMaterializeSelectionForest())
    }
}

private class CheckerSelectionSetImpl(
    override val type: ViaductSchema.Object,
    override val materializeSelections: MaterializeSelectionForest,
) : CheckerSelectionSet {
    override val constructionSelections: SelectionForest =
        materializeSelections.constructionSelections()
}

/**
 * Named object- and Query-rooted inputs for one field or type checker.
 *
 * Names share the single namespace exposed to the checker function. A null object selection
 * requests one empty value rooted at the checked object type. Query selections are non-null because
 * a null production RSS denotes that containing-object value rather than an empty Query value. Each
 * map retains its individual materialization selections while its construction selections are
 * unioned.
 */
class CheckerRequiredSelections private constructor(
    val objectSelectionSets: Map<String, CheckerSelectionSet?>,
    val querySelectionSets: Map<String, CheckerSelectionSet>,
) {
    val objectConstructionSelections: SelectionForest =
        objectSelectionSets.values
            .filterNotNull()
            .fold(selectionForestOf()) { demand, selectionSet ->
                demand + selectionSet.constructionSelections
            }

    val queryConstructionSelections: SelectionForest =
        querySelectionSets.values.fold(selectionForestOf()) { demand, selectionSet ->
            demand + selectionSet.constructionSelections
        }

    companion object {
        fun of(
            objectType: ViaductSchema.Object,
            queryType: ViaductSchema.Object,
            objectSelectionSets: Map<String, CheckerSelectionSet?> = emptyMap(),
            querySelectionSets: Map<String, CheckerSelectionSet> = emptyMap(),
        ): CheckerRequiredSelections {
            require(queryType.name == "Query") { "Checker Query type must be Query" }
            require((objectSelectionSets.keys intersect querySelectionSets.keys).isEmpty()) {
                "Checker object and Query selection names must be disjoint"
            }
            objectSelectionSets.values.filterNotNull().forEach { selectionSet ->
                require(selectionSet.type == objectType) {
                    "Checker object selection set must be rooted at ${objectType.name}"
                }
            }
            querySelectionSets.values.forEach { selectionSet ->
                require(selectionSet.type == queryType) {
                    "Checker Query selection set must be rooted at Query"
                }
            }
            return CheckerRequiredSelections(
                objectSelectionSets = objectSelectionSets.toMap(),
                querySelectionSets = querySelectionSets.toMap(),
            )
        }
    }
}

/** A field checker supplied by the reasoning world's external resolver registry. */
class FieldChecker private constructor(
    val field: ViaductSchema.ObjectField,
    val requiredSelections: CheckerRequiredSelections,
    private val function: FieldCheckerFunction,
) {
    suspend operator fun invoke(
        arguments: Arguments.Resolved,
        objectData: Map<String, EngineObjectData.Sync>,
    ): CheckerResult = function(arguments, objectData)

    companion object {
        fun of(
            field: ViaductSchema.ObjectField,
            queryType: ViaductSchema.Object,
            objectSelectionSets: Map<String, CheckerSelectionSet?> = emptyMap(),
            querySelectionSets: Map<String, CheckerSelectionSet> = emptyMap(),
            function: FieldCheckerFunction,
        ): FieldChecker {
            val requiredSelections =
                CheckerRequiredSelections.of(
                    objectType = field.containingDef,
                    queryType = queryType,
                    objectSelectionSets = objectSelectionSets,
                    querySelectionSets = querySelectionSets,
                )
            return FieldChecker(field, requiredSelections, function)
        }
    }
}
