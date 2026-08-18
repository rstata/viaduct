package semantics.contract

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import model.Schema
import model.Value
import model.testing.ResolverApplicationArguments
import kotlin.test.assertEquals

internal fun ResolverApplicationArguments.assertApplicationCount(
    field: Schema.OutputField,
    expected: Int,
) {
    assertEquals(expected, arguments(field).size)
}

/** Asserts the complete field-to-arguments application ledger. */
internal fun ResolverApplicationArguments.assertApplications(
    expected: Map<Schema.OutputField, List<Map<String, Any?>>>,
) {
    assertEquals(
        expected.mapValues { (field, applications) ->
            applications.map { arguments -> Value.Arguments.of(field, arguments) }
        },
        all(),
    )
}

internal fun ResolverApplicationArguments.assertArguments(
    field: Schema.OutputField,
    vararg expected: Map<String, Any?>,
) {
    arguments(field).shouldContainExactlyInAnyOrder(
        expected.map { arguments -> Value.Arguments.of(field, arguments) },
    )
}

/**
 * Asserts which argument tuples occurred without constraining duplicate application counts.
 */
internal fun ResolverApplicationArguments.assertDistinctArguments(
    field: Schema.OutputField,
    vararg expected: Map<String, Any?>,
) {
    assertEquals(
        expected.mapTo(linkedSetOf()) { arguments ->
            Value.Arguments.of(field, arguments)
        },
        arguments(field).toSet(),
    )
}
