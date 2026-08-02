package semantics.arbitrary

import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Field
import graphql.language.FloatValue
import graphql.language.FragmentDefinition
import graphql.language.InlineFragment
import graphql.language.IntValue
import graphql.language.Selection
import graphql.language.SelectionSet
import graphql.language.StringValue
import graphql.language.TypeName
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import java.math.BigDecimal
import java.math.BigInteger

class ArbitraryQuery internal constructor(
    val source: String,
    val selectionDepth: Int,
) {
    override fun toString(): String = source
}

fun ArbitrarySchema.query(config: Config = Config.default): Arb<ArbitraryQuery> {
    val generatedSchema = this
    return arbitrary { random ->
        QueryGenerator(generatedSchema, config, random).generate()
    }
}

private class QueryGenerator(
    private val schema: ArbitrarySchema,
    private val config: Config,
    private val random: RandomSource,
) {
    fun generate(): ArbitraryQuery {
        val selectionSet = selectionSet("Query", depth = 0)
        val fragment =
            FragmentDefinition
                .newFragmentDefinition()
                .name("Generated")
                .typeCondition(TypeName("Query"))
                .selectionSet(selectionSet)
                .build()
        return ArbitraryQuery(
            source = AstPrinter.printAst(fragment).trim(),
            selectionDepth = selectionSet.maximumFieldDepth(),
        )
    }

    private fun selectionSet(
        typeName: String,
        depth: Int,
    ): SelectionSet {
        val objectType = schema.allObjects.singleOrNull { it.name == typeName }
        val candidates =
            schema.fieldsOn(typeName) +
                syntheticFields(typeName, objectType?.implementsNode == true)
        if (depth >= config[MaxSelectionDepth] - 1) {
            return SelectionSet.newSelectionSet()
                .selection(Field.newField("__typename").build())
                .build()
        }

        val count = Arb.int(1..minOf(3, candidates.size)).next(random)
        val requiredField =
            schema.deepFields[typeName]
                ?.takeIf { depth < config[MinimumSelectionDepth] }
                ?.let { fieldName -> candidates.single { it.name == fieldName } }
        val selectedFields =
            listOfNotNull(requiredField) +
                candidates
                    .filterNot { it == requiredField }
                    .shuffled(random)
                    .take(count - if (requiredField == null) 0 else 1)
        val directSelections =
            selectedFields
                .flatMap { field ->
                    val selection = fieldSelection(field, depth)
                    buildList {
                        add(selection)
                        if (chance(config[DuplicateSelectionWeight])) {
                            add(selection)
                        }
                        if (
                            field.arguments.isNotEmpty() &&
                            chance(config[DuplicateSelectionWeight])
                        ) {
                            add(fieldSelection(field, depth, forceAlias = true))
                        }
                    }
                }
        val possibleObjects =
            if (objectType == null) schema.possibleObjects(typeName) else emptyList()
        val fragmentSelections =
            if (
                config[QueryFragmentsEnabled] &&
                possibleObjects.isNotEmpty() &&
                chance(0.75)
            ) {
                val concrete = Arb.element(possibleObjects).next(random)
                listOf(
                    InlineFragment
                        .newInlineFragment()
                        .typeCondition(TypeName(concrete.name))
                        .selectionSet(selectionSet(concrete.name, depth + 1))
                        .build(),
                )
            } else {
                emptyList()
            }
        return SelectionSet
            .newSelectionSet()
            .selections(directSelections + fragmentSelections)
            .build()
    }

    private fun fieldSelection(
        field: FieldDefinitionSpec,
        depth: Int,
        forceAlias: Boolean = false,
    ): Selection<*> {
        val alias =
            if (forceAlias || chance(config[AliasWeight])) {
                "alias${Arb.int(0..10_000).next(random)}"
            } else {
                null
            }
        val builder =
            Field
                .newField(field.name)
                .alias(alias)
                .arguments(field.arguments.map(::argument))
        val objectType =
            field.type.namedType.takeIf(schema::isComposite)
        if (objectType != null) {
            builder.selectionSet(selectionSet(objectType, depth + 1))
        }
        val selection: Selection<*> = builder.build()
        return if (
            config[QueryFragmentsEnabled] &&
            field.ownerName != "Query" &&
            chance(0.3)
        ) {
            InlineFragment
                .newInlineFragment()
                .typeCondition(TypeName(field.ownerName))
                .selectionSet(
                    SelectionSet.newSelectionSet().selection(selection).build(),
                ).build()
        } else {
            selection
        }
    }

    private fun argument(definition: ArgumentDefinitionSpec): Argument {
        val salt = Arb.int(0..10_000).next(random)
        val value =
            when (definition.scalar) {
                ScalarKind.BOOLEAN -> BooleanValue.newBooleanValue(salt % 2 == 0).build()
                ScalarKind.FLOAT -> FloatValue.newFloatValue(BigDecimal("$salt.5")).build()
                ScalarKind.ID -> StringValue.newStringValue("id-$salt").build()
                ScalarKind.INT -> IntValue.newIntValue(BigInteger.valueOf(salt.toLong())).build()
                ScalarKind.STRING -> StringValue.newStringValue("value-$salt").build()
            }
        return Argument.newArgument().name(definition.name).value(value).build()
    }

    private fun syntheticFields(
        typeName: String,
        implementsNode: Boolean,
    ): List<FieldDefinitionSpec> =
        buildList {
            add(
                FieldDefinitionSpec(
                    ownerName = typeName,
                    name = "__typename",
                    type =
                        OutputTypeSpec(
                            namedType = "String",
                            nullable = false,
                            list = false,
                            elementNullable = false,
                        ),
                    arguments = emptyList(),
                ),
            )
            if (implementsNode) {
                add(
                    FieldDefinitionSpec(
                        ownerName = typeName,
                        name = "id",
                        type =
                            OutputTypeSpec(
                                namedType = "ID",
                                nullable = false,
                                list = false,
                                elementNullable = false,
                            ),
                        arguments = emptyList(),
                    ),
                )
            }
        }

    private fun <T> List<T>.shuffled(random: RandomSource): List<T> {
        val remaining = toMutableList()
        val result = mutableListOf<T>()
        while (remaining.isNotEmpty()) {
            result += remaining.removeAt(Arb.int(0 until remaining.size).next(random))
        }
        return result
    }

    private fun chance(weight: Double): Boolean =
        Arb.double(0.0, 1.0).next(random) < weight

    private fun SelectionSet.maximumFieldDepth(): Int =
        selections.maxOfOrNull { selection ->
            when (selection) {
                is Field -> 1 + (selection.selectionSet?.maximumFieldDepth() ?: 0)
                is InlineFragment -> selection.selectionSet.maximumFieldDepth()
                else -> 0
            }
        } ?: 0
}
