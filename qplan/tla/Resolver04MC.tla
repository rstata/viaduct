-------------------------- MODULE Resolver04MC --------------------------
EXTENDS Resolver03MC

MCSites == {"provider-resolver", "variable-v", "bridge", "producer"}
MCFieldSites == {"provider-resolver", "bridge", "producer"}
MCVariableSites == {"variable-v"}
MCRequiredSites == MCSites
MCSiteDependencies ==
    [site \in MCSites |->
        CASE site = "variable-v" -> {"provider-resolver"}
          [] site = "producer"   -> {"variable-v", "bridge"}
          [] OTHER               -> {}]
MCSiteOrder == <<"provider-resolver", "variable-v", "bridge", "producer">>
MCVariablesInFragment ==
    [field \in MCFieldSites |->
        IF field = "producer" THEN {"variable-v"} ELSE {}]

MCInputValues == {"value-7"}
MCProviderValues == [variable \in MCVariableSites |-> "value-7"]
MCResolvedBindings ==
    [variable \in MCVariableSites |-> MCProviderValues[variable]]

MCDemandTokens == {"operation-child", "provider-child", "sibling-child"}
MCContributions == {"operation", "provider", "sibling"}
MCContributionField ==
    [contribution \in MCContributions |-> "producer"]
MCContributionDemand ==
    [contribution \in MCContributions |->
        CASE contribution = "operation" -> {"operation-child"}
          [] contribution = "provider"  -> {"provider-child"}
          [] OTHER                      -> {"sibling-child"}]

SitePrefix(count) ==
    {MCSiteOrder[position] : position \in 1..count}

AmbientDemand(field) ==
    {token \in MCDemandTokens :
        \E contribution \in MCContributions :
            /\ MCContributionField[contribution] = field
            /\ token \in MCContributionDemand[contribution]}

ProviderDependencyRanked ==
    \A position \in 1..Len(MCSiteOrder) :
        \A dependency \in MCSiteDependencies[MCSiteOrder[position]] :
            \E earlier \in 1..(position - 1) :
                MCSiteOrder[earlier] = dependency

BindingsConformToProviders ==
    \A variable \in MCVariableSites :
        MCResolvedBindings[variable] = MCProviderValues[variable]

VariablesBoundBeforeField ==
    \A field \in MCFieldSites \cap MCRequiredSites :
        \A variable \in MCVariablesInFragment[field] :
            \E fieldPosition \in 1..Len(MCSiteOrder) :
                /\ MCSiteOrder[fieldPosition] = field
                /\ \E variablePosition \in 1..(fieldPosition - 1) :
                       MCSiteOrder[variablePosition] = variable

AmbientDemandSealed ==
    \A contribution \in MCContributions :
        MCContributionDemand[contribution]
            \subseteq AmbientDemand(MCContributionField[contribution])

VariableResolutionCorrect ==
    /\ ProviderDependencyRanked
    /\ BindingsConformToProviders
    /\ VariablesBoundBeforeField
    /\ AmbientDemandSealed

=============================================================================
