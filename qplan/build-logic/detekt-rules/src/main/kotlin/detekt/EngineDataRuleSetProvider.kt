package detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Provides the engine data Detekt rules to the Detekt framework.
 */
class EngineDataRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "engine-data-rules"

    override fun instance(config: Config) =
        RuleSet(
            ruleSetId,
            listOf(
                ArgumentExpressionAliasRule(config),
                EngineOutputDataOverrideRule(config),
                EngineUnionMembershipRule(config)
            )
        )
}
