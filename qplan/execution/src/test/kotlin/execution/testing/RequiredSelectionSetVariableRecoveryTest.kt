package execution.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import model.fragmentFrom
import model.requireObjectField
import model.testing.FromObjectField
import model.testing.TestWorld
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.select.SelectionsParser

class RequiredSelectionSetVariableRecoveryTest {
    private val world =
        TestWorld.fromSDL(
            """
            type Query {
              foo(y: Int!, other: Int): Int!
              bar(x: Int!): Int!
              source(scale: Int): Int!
            }
            """.trimIndent(),
        )
    private val schema = world.schema
    private val field = schema.requireObjectField("Query", "foo")
    private val recovery = RequiredSelectionSetVariableRecovery(schema)

    @Test
    fun `recovers renamed top-level argument through validated wrappers`() {
        val fragment =
            schema.fragmentFrom(
                "fragment _ on Query { bar(x: \$vary) }",
                variableField = field,
            )
        val requiredSelectionSet =
            requiredSelectionSet(
                "bar(x: \$vary)",
                FromArgument("vary", listOf("y")).validated().validated(),
            )

        val recovered =
            recovery.recoverConfigurations(
                field,
                fragment,
                requiredSelectionSet,
            )

        assertEquals(1, recovered.size)
        val configuration =
            assertIs<RequiredSelectionSetVariableRecovery.RecoveredFromArgument>(
                recovered.single(),
            )
        assertEquals("vary", configuration.variable.variableName)
        assertEquals(field, configuration.variable.field)
        assertEquals("y", configuration.argumentName)
    }

    @Test
    fun `rejects nested input argument path`() {
        val fragment =
            schema.fragmentFrom(
                "fragment _ on Query { bar(x: \$vary) }",
                variableField = field,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                recovery.recoverConfigurations(
                    field,
                    fragment,
                    requiredSelectionSet(
                        "bar(x: \$vary)",
                        FromArgument("vary", listOf("input", "value")),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("nested FromArgument path input.value"))
    }

    @Test
    fun `recovers aliased object path through nested RSS`() {
        val selections = "bar(x: \$vary), selected: source"
        val parsedSelections = SelectionsParser.parse("Query", selections)
        val variablesResolvers =
            VariablesResolver
                .fromSelectionSetVariables(
                    objectSelections = parsedSelections,
                    querySelections = null,
                    variables = listOf(FromObjectFieldVariable("vary", "selected")),
                    forChecker = false,
                ).map(VariablesResolver::validated)
        val fragment =
            schema.fragmentFrom(
                "fragment _ on Query { $selections }",
                variableField = field,
            )

        val recovered =
            recovery.recover(
                field,
                fragment,
                RequiredSelectionSet(
                    parsedSelections,
                    variablesResolvers,
                    forChecker = false,
                ),
            )

        val declaration = recovered.values.single() as FromObjectField
        assertEquals(listOf("selected"), declaration.responsePath)
    }

    @Test
    fun `recovers provider dependencies against root object RSS`() {
        val selections = "bar(x: \$value), scaled: source(scale: \$scale)"
        val parsedSelections = SelectionsParser.parse("Query", selections)
        val variablesResolvers =
            VariablesResolver.fromSelectionSetVariables(
                objectSelections = parsedSelections,
                querySelections = null,
                variables =
                    listOf(
                        FromObjectFieldVariable("value", "scaled"),
                        FromArgumentVariable("scale", "y"),
                    ),
                forChecker = false,
            )
        val fragment =
            schema.fragmentFrom(
                "fragment _ on Query { $selections }",
                variableField = field,
            )

        val recovered =
            recovery.recoverConfigurations(
                field,
                fragment,
                RequiredSelectionSet(
                    parsedSelections,
                    variablesResolvers,
                    forChecker = false,
                ),
            )

        assertEquals(setOf("value", "scale"), recovered.map { it.variable.variableName }.toSet())
    }

    @Test
    fun `rejects object path whose nested RSS does not match root selection`() {
        val fragment =
            schema.fragmentFrom(
                "fragment _ on Query { bar(x: \$vary), source }",
                variableField = field,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                recovery.recoverConfigurations(
                    field,
                    fragment,
                    requiredSelectionSet(
                        "bar(x: \$vary), source",
                        FromFieldVariablesResolver(
                            "vary",
                            listOf("source"),
                            requiredSelectionSet("bar(x: 1)"),
                        ),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("nested RSS that does not match object path"))
    }

    @Test
    fun `rejects inconsistent provider repeated in nested RSS`() {
        val selections = "bar(x: \$value), source(scale: \$scale)"
        val nestedRequiredSelectionSet =
            requiredSelectionSet(
                "source(scale: \$scale)",
                FromArgument("scale", listOf("y")),
            )
        val fragment =
            schema.fragmentFrom(
                "fragment _ on Query { $selections }",
                variableField = field,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                recovery.recoverConfigurations(
                    field,
                    fragment,
                    requiredSelectionSet(
                        selections,
                        FromFieldVariablesResolver(
                            "value",
                            listOf("source"),
                            nestedRequiredSelectionSet,
                        ),
                        FromArgument("scale", listOf("other")),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("inconsistent providers across nested RSSes"))
    }

    @Test
    fun `rejects raw variable provider`() {
        val fragment =
            schema.fragmentFrom(
                "fragment _ on Query { bar(x: \$vary) }",
                variableField = field,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                recovery.recoverConfigurations(
                    field,
                    fragment,
                    requiredSelectionSet(
                        "bar(x: \$vary)",
                        VariablesResolver.const(mapOf("vary" to 1)),
                    ),
                )
            }

        assertTrue(
            error.message
                .orEmpty()
                .contains("support only FromArgument and object-path variable providers"),
        )
    }

    @Test
    fun `rejects fragment variable without provider`() {
        val fragment =
            schema.fragmentFrom(
                "fragment _ on Query { bar(x: \$vary) }",
                variableField = field,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                recovery.recoverConfigurations(
                    field,
                    fragment,
                    requiredSelectionSet("bar(x: 1)"),
                )
            }

        assertTrue(error.message.orEmpty().contains("variables without providers: \$vary"))
    }

    @Test
    fun `rejects provider unused by fragment`() {
        val fragment = schema.fragmentFrom("fragment _ on Query { source }")

        val error =
            assertFailsWith<IllegalArgumentException> {
                recovery.recoverConfigurations(
                    field,
                    fragment,
                    requiredSelectionSet(
                        "bar(x: \$vary)",
                        FromArgument("vary", listOf("y")),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("unused variable providers: \$vary"))
    }

    private fun requiredSelectionSet(
        selections: String,
        vararg variablesResolvers: VariablesResolver,
    ): RequiredSelectionSet =
        RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", selections),
            variablesResolvers = variablesResolvers.toList(),
            forChecker = false,
        )
}
