------------------- MODULE Resolver04ApplicationMC -------------------
EXTENDS ResolverApplicationMC, ProviderReadsMC,
        Resolver04Application

MCR4ResolverKeys == {"user"}
MCR4InitialDemand == {"user"}
MCR4DirectDemand ==
    [key \in MCKeys |->
        IF key = "user" THEN {"__typename"} ELSE {}]
MCR4ConstructionOrder == <<"__typename", "user">>

MCR4Requirements == MCDemandTokens
MCR4NestedOccurrences == {}
MCR4ActivatedNested == {}
MCR4OwnerProducer ==
    [occurrence \in MCR4NestedOccurrences |-> "user"]
MCR4RequiredByOccurrence ==
    [occurrence \in MCR4NestedOccurrences |-> {}]
MCR4ExtendedByOccurrence == MCR4RequiredByOccurrence
MCR4DirectOutputDemand ==
    [producer \in MCR4ResolverKeys |-> {"select-user"}]
MCR4OEROccurrences == MCObjects
MCR4LocalOneApplication ==
    [object \in MCR4OEROccurrences |-> TRUE]

MCR4VariableSite == "variable-viewerType"
MCR4Sites == {MCR4VariableSite, "user"}
MCR4FieldSites == {"user"}
MCR4VariableSites == {MCR4VariableSite}
MCR4RequiredSites == MCR4Sites
MCR4SiteDependencies ==
    [site \in MCR4Sites |->
        IF site = "user" THEN {MCR4VariableSite} ELSE {}]
MCR4SiteOrder == <<MCR4VariableSite, "user">>
MCR4VariablesInFragment ==
    [field \in MCR4FieldSites |-> {MCR4VariableSite}]
MCR4InputValues == MCValues
MCR4ProviderValues ==
    [variable \in MCR4VariableSites |-> "Query"]

MCR4Contributions == {"provider-name"}
MCR4ContributionField ==
    [contribution \in MCR4Contributions |-> "user"]
MCR4ContributionDemand ==
    [contribution \in MCR4Contributions |-> {"select-name"}]

MCR4CellProducer ==
    [cell \in MCCells |-> "user"]

MCR4ObservationRequirement ==
    [observation \in MCObservations |->
        IF observation = "user-shape"
        THEN "select-user"
        ELSE "select-name"]

MCR4BindingSite ==
    [binding \in MCProviderBindings |-> MCR4VariableSite]

MCR4BindingField ==
    [binding \in MCProviderBindings |-> "user"]

R4AppVars == <<remainingWork, n, checked, coreN>>

R4AppInit ==
    /\ MaterialMCInit
    /\ Core04!Init

R4AppNext ==
    \/ /\ MaterialMCNext
       /\ UNCHANGED coreN
    \/ /\ Core04!Next
       /\ UNCHANGED MaterialMCvars

R4AppSpec ==
    R4AppInit
        /\ [][R4AppNext]_R4AppVars
        /\ WF_R4AppVars(R4AppNext)

=============================================================================
