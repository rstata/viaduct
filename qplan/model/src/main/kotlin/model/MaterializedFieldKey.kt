package model

/**
 * Returns the private string address of this visible materialized field.
 *
 * Argumentless fields use their canonical field name. Argument-bearing fields use a deterministic
 * encoding of the field name and ground arguments. Occurrence stamps do not participate.
 */
fun ObjectEngineResult.GroundKey.materializedFieldKey(): String {
    val arguments = arguments
    if (arguments is Value.Arguments && arguments.fieldValues.isEmpty()) {
        return field.fieldName
    }
    return buildString {
        append(field.fieldName)
        append('(')
        when (arguments) {
            OpenArguments.Ground.Error -> append('!')
            is Value.Arguments ->
                arguments.fieldValues
                    .toSortedMap()
                    .entries
                    .joinTo(this, separator = ",") { (name, value) ->
                        "$name=${value.encodeMaterializedInput()}"
                    }
        }
        append(')')
    }
}

private fun EngineInputData?.encodeMaterializedInput(): String =
    when (this) {
        null -> "n"
        is Int -> "i$this;"
        is Double -> "f${toRawBits().toULong().toString(16)};"
        is Boolean -> if (this) "b1" else "b0"
        is String -> "s${length}:$this"
        is EngineIDData -> "d${id.length}:$id"
        is EngineEnumValueData ->
            "e${type.typeName.length}:${type.typeName}${value.length}:$value"
        is List<*> ->
            joinToString(prefix = "l${size}:[", postfix = "]", separator = "") { value ->
                value.encodeMaterializedInput().lengthPrefixed()
            }
        is Map<*, *> -> {
            val fields =
                entries
                    .map { (name, value) ->
                        require(name is String) { "Engine input-object field names must be strings" }
                        name to value
                    }.sortedBy(Pair<String, Any?>::first)
            fields.joinToString(
                prefix = "o${fields.size}:{",
                postfix = "}",
                separator = "",
            ) { (name, value) ->
                name.lengthPrefixed() + value.encodeMaterializedInput().lengthPrefixed()
            }
        }
        else -> error("Unsupported engine input value ${this::class.qualifiedName}")
    }

private fun String.lengthPrefixed(): String = "$length:$this"
