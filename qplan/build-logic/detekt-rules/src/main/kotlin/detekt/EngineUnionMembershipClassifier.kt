package detekt

import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType

private const val KOTLIN_ANY = "kotlin.Any"
private const val KOTLIN_BOOLEAN = "kotlin.Boolean"
private const val KOTLIN_DOUBLE = "kotlin.Double"
private const val KOTLIN_INT = "kotlin.Int"
private const val KOTLIN_LIST = "kotlin.collections.List"
private const val KOTLIN_MAP = "kotlin.collections.Map"
private const val KOTLIN_ARRAY = "kotlin.Array"
private const val KOTLIN_NOTHING = "kotlin.Nothing"
private const val KOTLIN_STRING = "kotlin.String"

// internal for testing
internal data class DomainContract(
    val aliasFqName: String,
    val displayName: String,
    val scalarMembers: Set<String> = emptySet(),
    val namedMembers: Set<String> = emptySet(),
    val allowsLists: Boolean = false,
    val allowsMaps: Boolean = false,
)

private val SIMPLE_MEMBERS = setOf(KOTLIN_INT, KOTLIN_DOUBLE, KOTLIN_BOOLEAN, KOTLIN_STRING)

// internal for testing
internal fun engineDataContracts(): List<DomainContract> = listOf(
    DomainContract(
        aliasFqName = "model.EngineSimpleData",
        displayName = "EngineSimpleData",
        scalarMembers = SIMPLE_MEMBERS,
    ),
    DomainContract(
        aliasFqName = "model.EngineInputData",
        displayName = "EngineInputData",
        scalarMembers = SIMPLE_MEMBERS,
        allowsLists = true,
        allowsMaps = true,
    ),
    DomainContract(
        aliasFqName = "model.EngineOutputData",
        displayName = "EngineOutputData",
        scalarMembers = SIMPLE_MEMBERS,
        namedMembers = setOf(
            "model.EngineErrorData",
            "viaduct.engine.api.EngineObjectData.Sync",
        ),
        allowsLists = true,
    ),
    DomainContract(
        aliasFqName = "model.ArgumentExpression",
        displayName = "ArgumentExpression",
        scalarMembers = SIMPLE_MEMBERS,
        namedMembers = setOf(
            "model.ArgumentResolutionError",
            "model.Arguments.Variable",
        ),
        allowsLists = true,
        allowsMaps = true,
    ),
)

/**
 * Best-effort classifier that maps a Kotlin type reference to its domain contract.
 *
 * The central lookup is:
 *
 * ```
 * KtTypeReference -> DomainContract?
 * ```
 *
 * [bindingContextProvider] supplies the compiler information collected by Detekt's type-resolution
 * enabled rule. This allows the classifier to follow type aliases and distinguish the canonical
 * aliases from similarly named types. When compiler information is incomplete, it preserves that
 * uncertainty instead of guessing. The same information is used to classify expressions
 * recursively against the contract.
 */
// internal for testing
internal class EngineUnionMembershipClassifier(
    private val bindingContextProvider: () -> BindingContext,
    private val contracts: List<DomainContract>,
) {
    private val bindingContext: BindingContext
        get() = bindingContextProvider()

    // internal for testing
    internal fun resolveContract(typeReference: KtTypeReference): DomainContract? {
        val sourceName = typeReference.text.trim().removeSuffix("?").substringAfterLast('.')
        val sourceContract = contracts.firstOrNull { it.displayName == sourceName }
        val userType = typeReference.typeElement as? KtUserType ?: return sourceContract
        val reference = userType.referenceExpression ?: return sourceContract
        val descriptor = bindingContext[BindingContext.REFERENCE_TARGET, reference]
        val aliasFqName = (descriptor as? TypeAliasDescriptor)?.fqNameSafe?.asString()
        if (aliasFqName != null) {
            return contracts.firstOrNull { it.aliasFqName == aliasFqName }
        }

        // Detekt's lightweight test environment can omit the reference target for an imported
        // typealias even though it resolves the expression's type. Only use the spelling fallback
        // when there is no descriptor; never reinterpret a resolved, unrelated alias as canonical.
        return if (descriptor == null) sourceContract else null
    }

    // internal for testing
    internal fun classify(expression: KtExpression, contract: DomainContract): Membership {
        val referenceTarget = (expression as? KtReferenceExpression)
            ?.let { bindingContext[BindingContext.REFERENCE_TARGET, it] }
        val variableDescriptor = referenceTarget as? VariableDescriptor
        val resolvedType = bindingContext
            .get(BindingContext.EXPRESSION_TYPE_INFO, expression)
            ?.type
            ?: variableDescriptor?.type
        val resolvedMembership = resolvedType?.let { classify(it, contract) }
        if (resolvedMembership is Membership.Allowed || resolvedMembership is Membership.Forbidden) {
            return resolvedMembership
        }

        if (variableDescriptor != null) {
            // Lightweight Detekt test contexts can leave an inferred property's type unresolved.
            // Its initializer is a safe fallback only when there is no explicit type annotation.
            val inferredProperty = DescriptorToSourceUtils
                .getSourceFromDescriptor(variableDescriptor) as? KtProperty
            if (inferredProperty?.typeReference == null) {
                inferredProperty?.initializer?.let { return classify(it, contract) }
            }
        }

        val call = expression as? KtCallExpression ?: return resolvedMembership
            ?: Membership.Unknown(expression.text)
        return when (call.calleeExpression?.text) {
            "listOf", "listOfNotNull" -> {
                if (!contract.allowsLists) {
                    Membership.Forbidden(KOTLIN_LIST)
                } else {
                    combine(call.valueArguments.mapNotNull { argument ->
                        argument.getArgumentExpression()?.let { classify(it, contract) }
                    })
                }
            }
            "mapOf" -> {
                if (!contract.allowsMaps) {
                    Membership.Forbidden(KOTLIN_MAP)
                } else {
                    combine(call.valueArguments.mapNotNull { argument ->
                        val entry = argument.getArgumentExpression()?.mapEntry() ?: return@mapNotNull null
                        val keyType = bindingContext
                            .get(BindingContext.EXPRESSION_TYPE_INFO, entry.first)
                            ?.type
                            ?: return@mapNotNull Membership.Unknown(entry.first.text)
                        if (!isString(keyType)) {
                            return@mapNotNull Membership.Forbidden(keyType.describe())
                        }
                        classify(entry.second, contract)
                    })
                }
            }
            else -> Membership.Unknown(expression.text)
        }
    }

    // internal for testing
    internal fun classifySpread(expression: KtExpression, contract: DomainContract): Membership {
        val resolvedType = bindingContext
            .get(BindingContext.EXPRESSION_TYPE_INFO, expression)
            ?.type
        if (resolvedType != null) {
            val typeName = resolvedType.constructor.declarationDescriptor
                ?.fqNameSafe
                ?.asString()
            if (typeName == KOTLIN_ARRAY) {
                val elementType = resolvedType.arguments.singleOrNull()?.type
                    ?: return Membership.Unknown(typeName)
                return classify(elementType, contract)
            }
        }

        val call = expression as? KtCallExpression
        if (call?.calleeExpression?.text == "arrayOf") {
            return combine(call.valueArguments.mapNotNull { argument ->
                argument.getArgumentExpression()?.let { classify(it, contract) }
            })
        }

        return classify(expression, contract)
    }

    private fun classify(type: KotlinType, contract: DomainContract): Membership {
        val descriptor = type.constructor.declarationDescriptor
        val typeName = descriptor?.fqNameSafe?.asString() ?: type.toString()

        if (
            typeName.isBlank() ||
                typeName.startsWith("{") ||
                typeName.startsWith("[Error type:") ||
                typeName.contains(" & ")
        ) {
            return Membership.Unknown(typeName.ifBlank { type.toString() })
        }

        if (typeName in contract.scalarMembers || typeName in contract.namedMembers) {
            return Membership.Allowed
        }

        if (typeName == KOTLIN_NOTHING) return Membership.Allowed

        if (typeName == KOTLIN_LIST) {
            if (!contract.allowsLists) return Membership.Forbidden(typeName)
            val elementType = type.arguments.singleOrNull()?.type
                ?: return Membership.Unknown(typeName)
            return classify(elementType, contract)
        }

        if (typeName == KOTLIN_MAP) {
            if (!contract.allowsMaps) return Membership.Forbidden(typeName)
            val arguments = type.arguments
            if (arguments.size != 2) return Membership.Unknown(typeName)
            val keyType = arguments[0].type
            if (!isString(keyType)) return Membership.Forbidden(keyType.describe())
            return classify(arguments[1].type, contract)
        }

        if (typeName == KOTLIN_ANY) return Membership.Unknown(typeName)
        return Membership.Forbidden(typeName)
    }

    private fun KtExpression.mapEntry(): Pair<KtExpression, KtExpression>? {
        return when (this) {
            is KtBinaryExpression -> left?.let { key -> right?.let { value -> key to value } }
            is KtCallExpression -> {
                if (calleeExpression?.text != "to" || valueArguments.size != 2) return null
                val key = valueArguments[0].getArgumentExpression() ?: return null
                val value = valueArguments[1].getArgumentExpression() ?: return null
                key to value
            }
            else -> null
        }
    }

    private fun combine(results: List<Membership>): Membership {
        results.firstOrNull { it is Membership.Forbidden }?.let { return it }
        results.firstOrNull { it is Membership.Unknown }?.let { return it }
        return Membership.Allowed
    }

    private fun isString(type: KotlinType): Boolean =
        type.constructor.declarationDescriptor?.fqNameSafe?.asString() == KOTLIN_STRING

    private fun KotlinType.describe(): String =
        constructor.declarationDescriptor?.fqNameSafe?.asString() ?: toString()
}

// internal for testing
internal fun DomainContract.summary(): String = buildList {
    addAll(scalarMembers)
    addAll(namedMembers)
    if (allowsLists) add("List<recursive member>")
    if (allowsMaps) add("Map<String, recursive member>")
}.sorted().joinToString(", ")

// internal for testing
internal sealed interface Membership {
    data object Allowed : Membership
    data class Forbidden(val typeName: String) : Membership
    data class Unknown(val typeName: String) : Membership
}
