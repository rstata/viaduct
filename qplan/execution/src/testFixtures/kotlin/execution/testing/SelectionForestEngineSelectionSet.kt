package execution.testing

import graphql.GraphQLContext
import graphql.execution.ValuesResolver
import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.language.TypeName
import graphql.schema.InputValueWithState
import java.util.Locale
import model.Arguments
import model.Selection
import model.SelectionForest
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ViaductSchema as EngineSchema
import viaduct.engine.api.gj
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.graphql.schema.ViaductSchema as QPlanSchema
import viaduct.graphql.utils.ParsedSelections

/**
 * Exposes qplan output demand through the Engine API selection-set surface.
 *
 * [SelectionForest] is already a flattened semantic representation: it retains concrete
 * [Selection.possibleTypes] but not the inline-fragment nesting that produced those types. This
 * adapter therefore renders a canonical GraphQL-Java form with one inline fragment per applicable
 * concrete object type and lets the existing Engine API implementation provide its convenience
 * operations.
 *
 * This conversion preserves field coordinates, concrete applicability, nested demand, and resolved
 * arguments. It cannot recover source aliases, directives, named fragments, or their spelling;
 * those are not carried by [SelectionForest].
 */
internal fun SelectionForest.toEngineSelectionSet(
    type: QPlanSchema.CompositeTypeDef,
    schema: EngineSchema,
): EngineSelectionSet {
    require(schema.schema.getType(type.name) != null) {
        "Qplan selection type ${type.name} is absent from the Engine schema"
    }
    return createEngineSelectionSet(
        parsedSelections =
            ParsedSelections(
                typeName = type.name,
                selections = toConcreteSelectionSet(schema),
                fragmentMap = emptyMap(),
            ),
        viaductSchema = schema,
        variables = emptyMap(),
    )
}

private fun SelectionForest.toConcreteSelectionSet(schema: EngineSchema): SelectionSet {
    val fieldsByConcreteType = linkedMapOf<String, MutableList<Field>>()
    forEach { selection ->
        selection.possibleTypes
            .sortedBy(QPlanSchema.Object::name)
            .forEach { concreteType ->
                fieldsByConcreteType
                    .getOrPut(concreteType.name, ::mutableListOf)
                    .add(selection.toField(concreteType, schema))
            }
    }

    val fragments =
        fieldsByConcreteType
            .toSortedMap()
            .map { (typeName, fields) ->
                InlineFragment.newInlineFragment()
                    .typeCondition(TypeName(typeName))
                    .selectionSet(
                        SelectionSet(
                            fields.sortedBy(AstPrinter::printAstCompact),
                        ),
                    ).build()
            }
    return SelectionSet(fragments)
}

private fun Selection.toField(
    concreteType: QPlanSchema.Object,
    schema: EngineSchema,
): Field {
    val fieldName = key.field.name
    val sourceField =
        schema.schema.getFieldDefinition((concreteType.name to fieldName).gj)
            ?: throw IllegalArgumentException(
                "Qplan field ${concreteType.name}.$fieldName is absent from the Engine schema",
            )
    val arguments =
        key.arguments as? Arguments.Resolved
            ?: throw IllegalArgumentException(
                "EngineSelectionSet demand requires resolved arguments for " +
                    "${key.field.containingDef.name}.$fieldName",
            )
    val field =
        Field.newField(fieldName)
            .arguments(
                arguments.fieldValues
                    .toSortedMap()
                    .map { (name, value) ->
                        val sourceArgument =
                            sourceField.getArgument(name)
                                ?: throw IllegalArgumentException(
                                    "Qplan argument ${concreteType.name}.$fieldName($name:) " +
                                        "is absent from the Engine schema",
                                )
                        Argument.newArgument()
                            .name(name)
                            .value(
                                ValuesResolver.valueToLiteral(
                                    InputValueWithState.newInternalValue(value),
                                    sourceArgument.type,
                                    GraphQLContext.getDefault(),
                                    Locale.getDefault(),
                                ),
                            ).build()
                    },
            )
    val children = subselections.toConcreteSelectionSet(schema)
    if (children.selections.isNotEmpty()) {
        field.selectionSet(children)
    }
    return field.build()
}
