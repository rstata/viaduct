package semantics.spec

import jakarta.inject.Inject
import model.Assumptions
import model.ObjectEngineResult
import model.Schema
import model.Selection
import model.spec.SpecSelection

/**
 * Flattens a spec selection set interpreted with [typeInScope].
 *
 * Fields become [Selection] values, while inline fragments are removed after contributing their
 * type conditions to the fields they contain. The returned list is unordered.
 */
class SpecSelectionFlattener
    @Inject
    constructor(
        private val assumptions: Assumptions,
    ) {
        fun flatten(
            typeInScope: Schema.CompositeType,
            selectionSet: List<SpecSelection>,
        ): List<Selection> {
            val initialContext =
                SelectionContext(
                    nominalType = typeInScope,
                    possibleTypes = typeInScope.possibleTypes,
                )
            return flattenSelectionSet(selectionSet, initialContext)
        }

        private fun flattenSelectionSet(
            selections: List<SpecSelection>,
            context: SelectionContext,
        ): List<Selection> =
            selections.flatMap { selection ->
                when (selection) {
                    is SpecSelection.Field -> listOf(selection.flattenField(context))
                    is SpecSelection.InlineFragment -> {
                        val fragmentContext =
                            selection.typeCondition?.let { typeCondition ->
                                SelectionContext(
                                    nominalType = typeCondition,
                                    possibleTypes =
                                        context.possibleTypes intersect typeCondition.possibleTypes,
                                )
                            } ?: context
                        flattenSelectionSet(selection.selections, fragmentContext)
                    }
                }
            }

        private fun SpecSelection.Field.flattenField(context: SelectionContext): Selection {
            val field =
                assumptions.schema.field(
                    context.nominalType.typeName,
                    fieldName,
                )
            val flattenedSubselections =
                when (val resultType = field.type.baseType) {
                    is Schema.SimpleType -> {
                        check(subselections == null) {
                            "Simple field ${context.nominalType.typeName}.$fieldName has subselections"
                        }
                        null
                    }

                    is Schema.CompositeType -> {
                        val sourceSubselections =
                            checkNotNull(subselections) {
                                "Composite field ${context.nominalType.typeName}.$fieldName lacks subselections"
                            }
                        flattenSelectionSet(
                            sourceSubselections,
                            SelectionContext(
                                nominalType = resultType,
                                possibleTypes = resultType.possibleTypes,
                            ),
                        )
                    }
                }

            return FlattenedSelection(
                key =
                    ObjectEngineResult.Key(
                        fieldName = fieldName,
                        arguments =
                            assumptions.schema.argumentsValue(
                                field = field,
                                fields = arguments,
                            ),
                    ),
                nominalType = context.nominalType,
                possibleTypes = context.possibleTypes,
                subselections = flattenedSubselections,
            )
        }

        private class SelectionContext(
            val nominalType: Schema.CompositeType,
            val possibleTypes: Set<Schema.ObjectType>,
        )

        private class FlattenedSelection(
            override val key: ObjectEngineResult.Key,
            override val nominalType: Schema.CompositeType,
            override val possibleTypes: Set<Schema.ObjectType>,
            override val subselections: List<Selection>?,
        ) : Selection
    }
