----------------- MODULE Resolver04ApplicationProof -----------------
EXTENDS Resolver04Application, ResolverApplicationProof,
        Resolver04ProjectionProof, ProviderReadsProof

ASSUME Resolver04ApplicationAssumptions ==
    Resolver04ApplicationWorld

LEMMA Resolver04CoverageIsReturnedCoverage ==
    Resolver04ProjectionCoverage =>
        ReturnedProjectionCoverage
BY DEF Resolver04ProjectionCoverage,
       ReturnedProjectionCoverage

THEOREM ConstructedBindingsPrecedeDefiningFields ==
    BindingsPrecedeDefiningFields
<1>. SUFFICES
        ASSUME NEW binding \in VariableBindings
        PROVE
            \E fieldPosition \in 1..Len(SiteOrder) :
                /\ SiteOrder[fieldPosition] = BindingField[binding]
                /\ \E variablePosition \in 1..(fieldPosition - 1) :
                       SiteOrder[variablePosition] =
                           BindingSite[binding]
    BY DEF BindingsPrecedeDefiningFields
<1>1. /\ BindingField[binding] \in FieldSites \cap RequiredSites
      /\ BindingSite[binding]
             \in VariablesInFragment[BindingField[binding]]
    BY Resolver04ApplicationAssumptions
       DEF Resolver04ApplicationWorld
<1>2. PICK fieldPosition \in 1..Len(SiteOrder) :
           SiteOrder[fieldPosition] = BindingField[binding]
    BY <1>1, Resolver04ApplicationAssumptions
       DEF Resolver04ApplicationWorld,
           Resolver04ProjectionWorld,
           Core04!Resolver04World,
           Core04!SitePrefix
<1>3. BindingSite[binding]
           \in SiteDependencies[BindingField[binding]]
    BY <1>1, Resolver04ApplicationAssumptions
       DEF Resolver04ApplicationWorld,
           Resolver04ProjectionWorld,
           Core04!Resolver04World
<1>4. \E variablePosition \in 1..(fieldPosition - 1) :
           SiteOrder[variablePosition] = BindingSite[binding]
    BY <1>2, <1>3, Resolver04ApplicationAssumptions
       DEF Resolver04ApplicationWorld,
           Resolver04ProjectionWorld,
           Core04!Resolver04World
<1>. QED BY <1>2, <1>4

LEMMA Resolver04ReachabilityIsFinal ==
    AllFoldsCompleted =>
        Resolver04Tree!ReachableObjects = ReachableObjects
BY ReturnedCellsAreFinal, Isa
   DEF Resolver04Tree!ReachableObjects,
       Resolver04Tree!ObjectClosedSets,
       ReachableObjects, ObjectClosedSets

LEMMA Resolver04ConformsToVariables ==
    AllFoldsCompleted =>
        Resolver04Tree!ConformsToVariables
<1>. SUFFICES
        ASSUME AllFoldsCompleted,
               NEW object \in Resolver04Tree!ReachableObjects
        PROVE
            /\ ConstructedStoredVariableNames[object] =
                   Resolver04Tree!BindingNamesAt(object)
            /\ \A binding \in Resolver04Tree!BindingsAt(object) :
                   ConstructedStoredBindingValue[binding] =
                       FinalExpectedBindingValue[binding]
    BY DEF Resolver04Tree!ConformsToVariables
<1>1. object \in ReachableObjects
    BY Resolver04ReachabilityIsFinal
<1>2. /\ ConstructedStoredVariableNames[object] =
             BindingNamesAt(object)
      /\ \A binding \in BindingsAt(object) :
             ConstructedStoredBindingValue[binding] =
                 FinalExpectedBindingValue[binding]
    BY <1>1, ConstructedBindingsConformToFinalReads
<1>. QED BY <1>2
          DEF Resolver04Tree!BindingNamesAt,
              Resolver04Tree!BindingsAt,
              BindingNamesAt, BindingsAt

LEMMA Resolver04NonVariableConjuncts ==
    AppliedCorrect =>
        /\ Resolver04Tree!RootedAndWellTyped
        /\ Resolver04Tree!ConformsToFragment
        /\ Resolver04Tree!IsClosedUnderResolverDemand
        /\ Resolver04Tree!ConformsToResolvers
        /\ Resolver04Tree!ConformsToTypename
<1>. SUFFICES
        ASSUME AppliedCorrect
        PROVE
            /\ Resolver04Tree!RootedAndWellTyped
            /\ Resolver04Tree!ConformsToFragment
            /\ Resolver04Tree!IsClosedUnderResolverDemand
            /\ Resolver04Tree!ConformsToResolvers
            /\ Resolver04Tree!ConformsToTypename
    OBVIOUS
<1>1. /\ AppliedTree!RootedAndWellTyped
      /\ AppliedTree!ConformsToFragment
      /\ AppliedTree!IsClosedUnderResolverDemand
      /\ AppliedTree!ConformsToResolvers
      /\ AppliedTree!ConformsToTypename
    BY DEF AppliedCorrect, AppliedTree!CorrectResolution
<1>2. Resolver04Tree!RootedAndWellTyped
    BY <1>1
       DEF Resolver04Tree!RootedAndWellTyped,
           AppliedTree!RootedAndWellTyped
<1>3. Resolver04Tree!ConformsToFragment
    BY <1>1
       DEF Resolver04Tree!ConformsToFragment,
           Resolver04Tree!ReachableObjects,
           Resolver04Tree!ObjectClosedSets,
           Resolver04Tree!PresentKeys,
           AppliedTree!ConformsToFragment,
           AppliedTree!ReachableObjects,
           AppliedTree!ObjectClosedSets,
           AppliedTree!PresentKeys
<1>4. Resolver04Tree!IsClosedUnderResolverDemand
    BY <1>1
       DEF Resolver04Tree!IsClosedUnderResolverDemand,
           Resolver04Tree!ActiveResolverCells,
           Resolver04Tree!PresentKeys,
           AppliedTree!IsClosedUnderResolverDemand,
           AppliedTree!ActiveResolverCells,
           AppliedTree!PresentKeys
<1>5. Resolver04Tree!ConformsToResolvers
    BY <1>1
       DEF Resolver04Tree!ConformsToResolvers,
           AppliedTree!ConformsToResolvers
<1>6. Resolver04Tree!ConformsToTypename
    BY <1>1
       DEF Resolver04Tree!ConformsToTypename,
           AppliedTree!ConformsToTypename
<1>. QED BY <1>2, <1>3, <1>4, <1>5, <1>6

LEMMA Resolver04CompletedResultIsCorrect ==
    AllFoldsCompleted => Resolver04AppliedCorrect
<1>. SUFFICES
        ASSUME AllFoldsCompleted
        PROVE Resolver04AppliedCorrect
    OBVIOUS
<1>1. Resolver04ProjectionCoverage
    BY Resolver04SuppliesObservedDemand
<1>2. ReturnedProjectionCoverage
    BY <1>1, Resolver04CoverageIsReturnedCoverage
<1>3. AppliedCorrect
    BY <1>2, CompletedAppliedResultIsCorrect
<1>4. /\ Resolver04Tree!RootedAndWellTyped
      /\ Resolver04Tree!ConformsToFragment
      /\ Resolver04Tree!IsClosedUnderResolverDemand
      /\ Resolver04Tree!ConformsToResolvers
      /\ Resolver04Tree!ConformsToTypename
    BY <1>3, Resolver04NonVariableConjuncts
<1>5. Resolver04Tree!ConformsToVariables
    BY Resolver04ConformsToVariables
<1>. QED BY <1>4, <1>5
          DEF Resolver04AppliedCorrect,
              Resolver04Tree!CorrectResolution

THEOREM Resolver04ApplicationsAreCorrect ==
    FoldSpec => Resolver04ResultTermination
BY AllOccurrenceFoldsComplete,
   Resolver04CompletedResultIsCorrect, PTL
   DEF FoldTermination, Resolver04ResultTermination

=============================================================================
