package model.spec

import model.Assumptions
import model.Schema

/**
 * A post-validation selection in a GraphQL-spec selection set.
 *
 * This model retains the recursive shape of GraphQL selections: fields descend into their result
 * values, while inline fragments add nested type conditions without descending. Applied directives
 * and named fragment spreads are outside the model.
 *
 * A selection and its nested selections form a finite, well-founded value. Kotlin `equals` is
 * currently undefined for [SpecSelection]. The model does not yet assume that spec selections can
 * or need to be compared.
 */
sealed interface SpecSelection {
    /**
     * A GraphQL field selection.
     *
     * The field is valid in its surrounding post-validation type context. [fieldName] and
     * [arguments] identify the selected schema field invocation; [alias] affects its GraphQL
     * response key but not the field invocation.
     */
    interface Field : SpecSelection {
        /** The response alias, or null when the response key is [fieldName]. */
        val alias: String?

        /** The schema field name. */
        val fieldName: String

        /**
         * The field arguments as an unordered map from schema argument name to semantic value.
         *
         * Non-variable values are in coerced form. A value may contain a [Schema.VariableValue]
         * when that variable is unbound.
         */
        val arguments: Map<String, Schema.InputValue?>

        /**
         * The selection set on this field's result.
         *
         * This is null exactly when the field's base type is a [Schema.SimpleType]. When the base
         * type is a [Schema.CompositeType], this is non-null and non-empty as required by GraphQL.
         */
        val subselections: List<SpecSelection>?

        companion object {
            @JvmStatic
            fun of(
                alias: String?,
                fieldName: String,
                arguments: Map<String, Schema.InputValue?>,
                subselections: List<SpecSelection>?,
            ): Field {
                val defensiveArguments = arguments.toMap()
                val defensiveSubselections = subselections?.toList()
                return object : Field {
                    override val alias = alias
                    override val fieldName = fieldName
                    override val arguments = defensiveArguments
                    override val subselections = defensiveSubselections
                }
            }
        }
    }

    /**
     * A GraphQL inline fragment.
     *
     * This node does not descend into the object-value tree. It only nests [selections] beneath an
     * optional type condition. Applied directives are not represented.
     */
    interface InlineFragment : SpecSelection {
        /**
         * The fragment's canonical composite type condition, or null when it has no type condition.
         *
         * A null condition leaves the surrounding type condition unchanged. A non-null condition is
         * a definition in [Assumptions.schema].
         */
        val typeCondition: Schema.CompositeType?

        /** The non-empty, ordered selection set contained by this inline fragment. */
        val selections: List<SpecSelection>

        companion object {
            @JvmStatic
            fun of(
                typeCondition: Schema.CompositeType?,
                selections: List<SpecSelection>,
            ): InlineFragment {
                val defensiveSelections = selections.toList()
                return object : InlineFragment {
                    override val typeCondition = typeCondition
                    override val selections = defensiveSelections
                }
            }
        }
    }
}
