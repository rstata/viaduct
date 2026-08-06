package semantics.resolver04

import model.Schema
import model.testing.FromObjectField
import model.testing.fromObjectField

internal fun Schema.provider(
    objectFragmentSource: String,
    vararg responsePath: String,
): FromObjectField =
    fromObjectField(
        objectFragmentSource = objectFragmentSource,
        responsePath = responsePath.toList(),
    )
