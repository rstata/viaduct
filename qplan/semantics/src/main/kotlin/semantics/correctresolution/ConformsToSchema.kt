package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema

/**
 * Whether every present cell in this result recursively conforms to its schema output type.
 *
 * Null conforms exactly at a nullable outer layer, and [Schema.ErrorValue] conforms to every output
 * type as the bottom value. This predicate observes each cell's value but not its check component.
 */
context(world: Assumptions)
fun ObjectEngineResult.conformsToSchema(): Boolean = 
    keys.all { key -> fetch(key).value.engineResultConformsToSchema(key.field.type) }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToSchema(schemaType: Schema.TypeExpr<Schema.OutputType>): Boolean =
    when (this) {
        null -> schemaType.isNullable
        is Schema.ErrorValue -> true
        is Schema.SimpleValue -> schemaType is Schema.TypeExpr.Named && schemaType.baseType == this.type

        is ObjectEngineResult -> {
            ((schemaType as? Schema.TypeExpr.Named)?.baseType as? Schema.CompositeType)?.let { declaredType ->
                declaredType.possibleTypes.contains(this.type) && this.conformsToSchema()
            } ?: false
        }

        is ListEngineResult -> { 
            (schemaType as? Schema.TypeExpr.List)?.elementType?.let { elementType ->
                this.all { element -> element.engineResultConformsToSchema(elementType) }
            } ?: false
        }
    }
