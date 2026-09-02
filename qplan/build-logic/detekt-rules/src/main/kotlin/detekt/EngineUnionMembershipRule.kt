package detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.internal.RequiresTypeResolution
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.calls.util.getParameterForArgument
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall

/**
 * Enforces these type-level engine-value contracts:
 *
 * ```
 * EngineSimpleData = Int | Double | Boolean | String
 * EngineInputData = EngineSimpleData
 *                 | List<EngineInputData?>
 *                 | Map<String, EngineInputData?>
 * EngineOutputData = EngineSimpleData
 *                  | List<EngineOutputData?>
 *                  | EngineErrorData
 *                  | EngineObjectData.Sync
 * ```
 *
 * The rule checks values assigned to, returned from, or passed to these aliases. It reports
 * statically known types outside the contract and can optionally report values whose membership
 * cannot be proven. Value-level constraints, such as requiring finite `Double` values, remain
 * runtime responsibilities.
 */
@RequiresTypeResolution
class EngineUnionMembershipRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "EngineUnionMembership",
        severity = Severity.Warning,
        description = "A canonical engine value must be statically compatible with its value-domain union.",
        debt = Debt.FIVE_MINS,
    )

    private val reportUnknown = config.valueOrDefault("reportUnknown", false)
    private val classifier = EngineUnionMembershipClassifier(
        bindingContextProvider = { bindingContext },
        contracts = engineDataContracts(),
    )

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        property.initializer?.let { checkValue(property.typeReference, it) }
        property.getter?.bodyExpression?.let { checkBody(property.typeReference, it) }
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        function.valueParameters.forEach { parameter ->
            parameter.defaultValue?.let { checkValue(parameter.typeReference, it) }
        }

        val returnType = function.typeReference ?: return
        val body = function.bodyExpression ?: return
        checkBody(returnType, body)
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken != KtTokens.EQ) return
        val left = expression.left as? KtReferenceExpression ?: return
        val right = expression.right ?: return
        val descriptor = bindingContext[BindingContext.REFERENCE_TARGET, left] as? VariableDescriptor
            ?: return
        val declaration = DescriptorToSourceUtils.getSourceFromDescriptor(descriptor)
        val declaredType = when (declaration) {
            is KtParameter -> declaration.typeReference
            is KtProperty -> declaration.typeReference
            else -> null
        }
        checkValue(declaredType, right)
    }

    private fun checkBody(returnType: KtTypeReference?, body: KtExpression) {
        if (returnType == null) return
        if (body is KtBlockExpression) {
            body.collectDescendantsOfType<KtReturnExpression>().forEach { returned ->
                returned.returnedExpression?.let { checkValue(returnType, it) }
            }
        } else {
            checkValue(returnType, body)
        }
    }

    override fun visitCallExpression(call: KtCallExpression) {
        super.visitCallExpression(call)
        val resolvedCall = call.getResolvedCall(bindingContext) ?: return

        call.valueArguments.forEach { argument ->
            val expression = argument.getArgumentExpression() ?: return@forEach
            val parameter = resolvedCall.getParameterForArgument(argument) ?: return@forEach
            val parameterSource = DescriptorToSourceUtils
                .getSourceFromDescriptor(parameter) as? KtParameter
                ?: return@forEach
            val result = if (argument.isSpread && parameterSource.isVarArg) {
                classifier.classifySpread(
                    expression,
                    parameterSource.typeReference?.let { classifier.resolveContract(it) }
                        ?: return@forEach,
                )
            } else {
                null
            }
            if (result == null) {
                checkValue(parameterSource.typeReference, expression)
            } else {
                reportResult(parameterSource.typeReference, expression, result)
            }
        }
    }

    private fun checkValue(declaredType: KtTypeReference?, expression: KtExpression) {
        val contract = declaredType?.let { classifier.resolveContract(it) } ?: return
        if (expression.text == "null") return

        reportResult(declaredType, expression, classifier.classify(expression, contract))
    }

    private fun reportResult(
        declaredType: KtTypeReference?,
        expression: KtExpression,
        result: Membership,
    ) {
        val contract = declaredType?.let { classifier.resolveContract(it) } ?: return
        when (result) {
            is Membership.Allowed -> Unit
            is Membership.Forbidden -> report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(expression),
                    message = "${contract.displayName} does not include statically known type " +
                        "'${result.typeName}'. Allowed members are ${contract.summary()}.",
                ),
            )
            is Membership.Unknown -> if (reportUnknown) {
                report(
                    CodeSmell(
                        issue = issue,
                        entity = Entity.from(expression),
                        message = "Cannot prove that '${result.typeName}' belongs to " +
                            "${contract.displayName}; validate the value before it enters this domain.",
                    ),
                )
            }
        }
    }

}
