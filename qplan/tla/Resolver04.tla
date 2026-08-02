-------------------------- MODULE Resolver04 --------------------------
EXTENDS Resolver03

(*
Resolver04 adds variable-provider sites to the registry DAG. SiteOrder is the
dependency-first order produced by registry assembly. Its sequence position is
a natural-number rank for provider recursion.
*)

CONSTANTS Sites, FieldSites, VariableSites, RequiredSites,
          SiteDependencies, SiteOrder, VariablesInFragment,
          ProviderValues, InputValues,
          DemandTokens, Contributions, ContributionField,
          ContributionDemand

SitePrefix(count) ==
    {SiteOrder[position] : position \in 1..count}

ResolvedBindings ==
    [variable \in VariableSites |-> ProviderValues[variable]]

AmbientDemand(field) ==
    {token \in DemandTokens :
        \E contribution \in Contributions :
            /\ ContributionField[contribution] = field
            /\ token \in ContributionDemand[contribution]}

Resolver04World ==
    /\ IsFiniteSet(Sites)
    /\ FieldSites = ResolverKeys
    /\ FieldSites \cap VariableSites = {}
    /\ Sites = FieldSites \cup VariableSites
    /\ RequiredSites \subseteq Sites
    /\ SiteDependencies \in [Sites -> SUBSET Sites]
    /\ SiteOrder \in Seq(Sites)
    /\ SitePrefix(Len(SiteOrder)) = RequiredSites
    /\ \A first, second \in 1..Len(SiteOrder) :
           SiteOrder[first] = SiteOrder[second] => first = second
    /\ \A position \in 1..Len(SiteOrder) :
           \A dependency \in SiteDependencies[SiteOrder[position]] :
               \E earlier \in 1..(position - 1) :
                   SiteOrder[earlier] = dependency
    /\ VariablesInFragment \in [FieldSites -> SUBSET VariableSites]
    /\ \A field \in FieldSites :
           VariablesInFragment[field] \subseteq SiteDependencies[field]
    /\ ProviderValues \in [VariableSites -> InputValues]
    /\ IsFiniteSet(DemandTokens)
    /\ IsFiniteSet(Contributions)
    /\ ContributionField \in [Contributions -> FieldSites]
    /\ ContributionDemand \in [Contributions -> SUBSET DemandTokens]

ASSUME Resolver04Assumptions == Resolver04World

ProviderDependencyRanked ==
    \A position \in 1..Len(SiteOrder) :
        \A dependency \in SiteDependencies[SiteOrder[position]] :
            \E earlier \in 1..(position - 1) :
                SiteOrder[earlier] = dependency

BindingsConformToProviders ==
    \A variable \in VariableSites :
        ResolvedBindings[variable] = ProviderValues[variable]

VariablesBoundBeforeField ==
    \A field \in FieldSites \cap RequiredSites :
        \A variable \in VariablesInFragment[field] :
            \E fieldPosition \in 1..Len(SiteOrder) :
                /\ SiteOrder[fieldPosition] = field
                /\ \E variablePosition \in 1..(fieldPosition - 1) :
                       SiteOrder[variablePosition] = variable

AmbientDemandSealed ==
    \A contribution \in Contributions :
        ContributionDemand[contribution]
            \subseteq AmbientDemand(ContributionField[contribution])

VariableResolutionCorrect ==
    /\ ProviderDependencyRanked
    /\ BindingsConformToProviders
    /\ VariablesBoundBeforeField
    /\ AmbientDemandSealed

THEOREM ProviderRecursionHasFiniteRank ==
    ProviderDependencyRanked
BY Resolver04Assumptions DEF Resolver04World, ProviderDependencyRanked

THEOREM StoredBindingsEqualProviderReads ==
    BindingsConformToProviders
BY DEF BindingsConformToProviders, ResolvedBindings

THEOREM FragmentVariablesPrecedeTheirField ==
    VariablesBoundBeforeField
<1>1. ASSUME NEW field \in FieldSites \cap RequiredSites,
             NEW variable \in VariablesInFragment[field]
      PROVE
        \E fieldPosition \in 1..Len(SiteOrder) :
            /\ SiteOrder[fieldPosition] = field
            /\ \E variablePosition \in 1..(fieldPosition - 1) :
                   SiteOrder[variablePosition] = variable
    <2>1. PICK fieldPosition \in 1..Len(SiteOrder) :
               SiteOrder[fieldPosition] = field
        BY Resolver04Assumptions DEF Resolver04World, SitePrefix
    <2>2. variable \in SiteDependencies[field]
        BY Resolver04Assumptions DEF Resolver04World
    <2>3. \E variablePosition \in 1..(fieldPosition - 1) :
               SiteOrder[variablePosition] = variable
        BY <2>1, <2>2, Resolver04Assumptions DEF Resolver04World
    <2>. QED BY <2>1, <2>3
<1>. QED BY <1>1 DEF VariablesBoundBeforeField

THEOREM AmbientContributionsAreSealed ==
    AmbientDemandSealed
<1>1. ASSUME NEW contribution \in Contributions,
             NEW token \in ContributionDemand[contribution]
      PROVE token \in AmbientDemand(ContributionField[contribution])
    <2>1. token \in DemandTokens
        BY Resolver04Assumptions DEF Resolver04World
    <2>. QED BY <2>1 DEF AmbientDemand
<1>. QED BY <1>1 DEF AmbientDemandSealed

THEOREM Resolver04VariableCorrectness ==
    VariableResolutionCorrect
BY ProviderRecursionHasFiniteRank, StoredBindingsEqualProviderReads,
   FragmentVariablesPrecedeTheirField, AmbientContributionsAreSealed
   DEF VariableResolutionCorrect

Resolver04Correct ==
    /\ Resolver03Correct
    /\ VariableResolutionCorrect

THEOREM Resolver04LocalCorrectness ==
    Spec => <>Resolver04Correct
BY Resolver03LocalCorrectness, Resolver04VariableCorrectness, PTL
   DEF Resolver04Correct

=============================================================================
