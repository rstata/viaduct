package detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.internal.RequiresTypeResolution
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

private const val TARGET_PACKAGE = "model.testing"
private const val TARGET_ALIAS = "ArgumentExpression"

private val RETURN_TARGETS = setOf(
    "model.testing.decodeInputValue",
    "model.testing.decodeLiteral",
    "model.testing.decodeInputObjectFields",
    "model.testing.decodeObjectLiteral",
    "model.testing.decodeExternalInputValue",
)

private const val INPUT_OBJECT_FIELDS_FUNCTION = "model.testing.decodeInputObjectFields"
private const val SUPPLIED_DECODER_PARAMETER = "decodeSupplied"

/**
 * Flags decoder boundaries that carry checked argument expressions but spell the carrier as
 * Kotlin's broad `Any` type.
 *
 * Raw external values intentionally remain `Any?` at the input boundary. This rule covers only
 * values after the decoder has produced an [ArgumentExpression] and the private decoder callback
 * that supplies those values.
 */
@RequiresTypeResolution
class ArgumentExpressionAliasRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "ArgumentExpressionAlias",
        severity = Severity.Warning,
        description = "A decoded argument expression must use the ArgumentExpression typealias.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        val fqName = function.fqName() ?: return
        val returnIsTarget = fqName in RETURN_TARGETS || isArgumentDecoder(fqName)
        val parameterNames = when (fqName) {
            INPUT_OBJECT_FIELDS_FUNCTION -> setOf(SUPPLIED_DECODER_PARAMETER)
            else -> emptySet()
        }
        val reportedOffsets = mutableSetOf<Int>()

        if (!returnIsTarget && parameterNames.isEmpty()) return

        if (returnIsTarget) {
            function.typeReference?.let { typeReference ->
                checkTypeReference(typeReference, reportedOffsets)
            }
        }
        function.valueParameters
            .filter { it.name in parameterNames }
            .forEach { parameter ->
                parameter.typeReference?.let { typeReference ->
                    checkTypeReference(typeReference, reportedOffsets)
                }
            }
        if (returnIsTarget) {
            function.bodyExpression
                ?.collectDescendantsOfType<KtTypeReference>()
                ?.forEach { typeReference ->
                    checkTypeReference(typeReference, reportedOffsets)
                }
        }
    }

    private fun KtNamedFunction.fqName(): String? {
        return (bindingContext[BindingContext.FUNCTION, this] as? CallableMemberDescriptor)
            ?.fqNameSafe
            ?.asString()
    }

    private fun isArgumentDecoder(fqName: String): Boolean =
        fqName.startsWith("$TARGET_PACKAGE.GJSelectionParser.") &&
            fqName.endsWith(".decode")

    private fun checkTypeReference(
        typeReference: KtTypeReference,
        reportedOffsets: MutableSet<Int>,
    ) {
        typeReference
            .collectDescendantsOfType<KtUserType>()
            .distinctBy { userType -> userType.textOffset }
            .filter(::isKotlinAny)
            .filter { userType -> reportedOffsets.add(userType.textOffset) }
            .forEach { userType ->
                report(
                    CodeSmell(
                        issue = issue,
                        entity = Entity.from(userType),
                        message = "Use '$TARGET_ALIAS' instead of '${userType.text}'.",
                    ),
                )
            }
    }

    private fun isKotlinAny(userType: KtUserType): Boolean {
        if (userType.text.substringAfterLast('.') != "Any") return false
        val reference = userType.referenceExpression ?: return true
        val descriptor = bindingContext[BindingContext.REFERENCE_TARGET, reference]
        return descriptor == null || descriptor.fqNameSafe.asString() == "kotlin.Any"
    }
}
