package model.spec

import model.Assumptions
import model.Schema

/**
 * A post-validation selection in a GraphQL-spec selection set.
 *
 * This model retains the recursive shape of GraphQL selections: fields descend into their result
 * values, while inline fragments add nested type conditions without descending. Named fragment
 * spreads are absent because modeled inputs have already inlined them. Applied directives,
 * including `@skip` and `@include`, are in the project's eventual scope but deferred from this
 * current selection model.
 *
 * ### Invariant: spec-selection-well-foundedness
 *
 * A selection and its nested selections form a finite, well-founded value.
 *
 * ### Equality
 *
 * Kotlin `equals` is currently undefined for [SpecSelection]. The model does not yet assume that
 * spec selections can or need to be compared.
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
         * ### Invariant: spec-field-arguments
         *
         * Every key names an argument of [fieldName] in the surrounding validated type context.
         * Declared defaults have been applied, every required argument is present, omitted optional
         * arguments without defaults are absent, and every present non-variable value conforms
         * recursively to its argument type.
         *
         * ### Representation
         *
         * Non-variable values are in coerced form. A value may contain a [Schema.VariableValue]
         * when that variable is unbound.
         */
        val arguments: Map<String, Schema.InputValue?>

        /**
         * The selection set on this field's result.
         *
         * ### Invariant: spec-field-shape
         *
         * This is null exactly when the field's base type is a [Schema.SimpleType]. When the base
         * type is a [Schema.CompositeType], this is non-null and non-empty as required by GraphQL.
         */
        val subselections: List<SpecSelection>?

        companion object {
            /**
             * Constructs a field selection whose subselection shape matches [field]'s base type.
             *
             * @throws IllegalArgumentException when a simple field has subselections or a composite
             * field lacks a non-empty selection set
             */
            @JvmStatic
            fun of(
                alias: String?,
                field: Schema.OutputField,
                arguments: Map<String, Schema.InputValue?>,
                subselections: List<SpecSelection>?,
            ): Field {
                val defensiveArguments = arguments.toMap()
                val defensiveSubselections = subselections?.toList()
                when (field.type.baseType) {
                    is Schema.SimpleType ->
                        require(defensiveSubselections == null) {
                            "Simple field ${field.containingType.typeName}.${field.fieldName} " +
                                "must not have subselections"
                        }

                    is Schema.CompositeType ->
                        require(!defensiveSubselections.isNullOrEmpty()) {
                            "Composite field ${field.containingType.typeName}.${field.fieldName} " +
                                "requires a non-empty selection set"
                        }
                }
                return object : Field {
                    override val alias = alias
                    override val fieldName = field.fieldName
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
         * ### Invariant: spec-inline-fragment-applicability
         *
         * A non-null condition belongs to [Assumptions.schema] and is valid in the surrounding
         * post-validation type context.
         *
         * ### Interpretation
         *
         * A null condition leaves the surrounding type condition unchanged. A non-null condition is
         * a definition in [Assumptions.schema].
         */
        val typeCondition: Schema.CompositeType?

        /**
         * ### Invariant: spec-inline-fragment-shape
         *
         * The non-empty, ordered selection set contained by this inline fragment.
         */
        val selections: List<SpecSelection>

        companion object {
            /**
             * Constructs an inline fragment with a non-empty selection set.
             *
             * @throws IllegalArgumentException when [selections] is empty
             */
            @JvmStatic
            fun of(
                typeCondition: Schema.CompositeType?,
                selections: List<SpecSelection>,
            ): InlineFragment {
                val defensiveSelections = selections.toList()
                require(defensiveSelections.isNotEmpty()) {
                    "Inline fragment requires a non-empty selection set"
                }
                return object : InlineFragment {
                    override val typeCondition = typeCondition
                    override val selections = defensiveSelections
                }
            }
        }
    }
}
