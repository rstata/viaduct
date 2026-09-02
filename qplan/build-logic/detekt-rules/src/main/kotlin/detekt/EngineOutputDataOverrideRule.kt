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
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

private val TARGET_FUNCTION_OWNERS = setOf(
    FqName("viaduct.engine.api.EngineObjectData"),
    FqName("viaduct.engine.api.EngineObjectData.Sync")
)
private val TARGET_FUNCTION_NAMES = setOf("fetch", "fetchOrNull", "get", "getOrNull")
private const val TARGET_ALIAS_NAME = "EngineOutputData?"
private const val TARGET_ALIAS_FQ_NAME = "model.EngineOutputData?"

/**
 * Flags an override that doesn't use the appropriate typealias instead of a bare `Any?`.
 *
 * Resolves via the compiler's binding context (`@RequiresTypeResolution`), so it also catches
 * implementations declared outside the target interface's own file.
 */
@RequiresTypeResolution
class EngineOutputDataOverrideRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "EngineOutputDataOverride",
        severity = Severity.Warning,
        description = "An EngineObjectData.Sync fetch/get override's return type must be spelled 'EngineOutputData?'.",
        debt = Debt.FIVE_MINS
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        if (function.name !in TARGET_FUNCTION_NAMES) return
        if (!function.overridesTargetFunction()) return

        val declaredType = function.typeReference
        if (declaredType != null && declaredType.isTargetAlias()) return

        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(declaredType ?: function),
                message = "EngineObjectData.Sync override's return type must be 'EngineOutputData?'" +
                    if (declaredType != null) "; found '${declaredType.text}'" else "; type is inferred"
            )
        )
    }

    private fun KtTypeReference.isTargetAlias(): Boolean {
        val text = text.trim()
        return text == TARGET_ALIAS_NAME || text == TARGET_ALIAS_FQ_NAME
    }

    private fun KtNamedFunction.overridesTargetFunction(): Boolean {
        if (!hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false

        val root = bindingContext.get(BindingContext.FUNCTION, this) ?: return false

        val visited = HashSet<CallableMemberDescriptor>()
        val queue = ArrayDeque<CallableMemberDescriptor>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val containingClass = current.containingDeclaration as? ClassDescriptor
            if (containingClass != null && containingClass.fqNameSafe in TARGET_FUNCTION_OWNERS) return true
            queue.addAll(current.overriddenDescriptors)
        }
        return false
    }
}
