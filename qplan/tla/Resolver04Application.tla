-------------------- MODULE Resolver04Application --------------------
EXTENDS ResolverApplication, Resolver04Projection, ProviderReads

CONSTANTS BindingSite, BindingField

Resolver04Tree ==
    INSTANCE ResultTree
        WITH PresentCells <- BuiltCells,
             ActualObservation <- ProjectedActualObservation,
             ExpectedObservation <- FinalExpectedObservation,
             ActualCellValue <- GeneratedCellValue,
             StoredVariableNames <-
                 ConstructedStoredVariableNames,
             StoredBindingValue <-
                 ConstructedStoredBindingValue,
             ExpectedBindingValue <-
                 FinalExpectedBindingValue

BindingsPrecedeDefiningFields ==
    \A binding \in VariableBindings :
        \E fieldPosition \in 1..Len(SiteOrder) :
            /\ SiteOrder[fieldPosition] = BindingField[binding]
            /\ \E variablePosition \in 1..(fieldPosition - 1) :
                   SiteOrder[variablePosition] = BindingSite[binding]

Resolver04ApplicationWorld ==
    /\ ResolverApplicationBaseWorld
    /\ Resolver04ProjectionWorld
    /\ ProviderReadWorld
    /\ BindingSite
           \in [VariableBindings -> VariableSites]
    /\ BindingField
           \in [VariableBindings -> FieldSites]
    /\ \A binding \in VariableBindings :
           /\ BindingField[binding] \in RequiredSites
           /\ BindingSite[binding]
                  \in VariablesInFragment[BindingField[binding]]
           /\ BindingField[binding] =
                  WorkKey(BindingOwnerWork[binding])

Resolver04AppliedCorrect ==
    Resolver04Tree!CorrectResolution

Resolver04ResultTermination ==
    <> (AllFoldsCompleted /\ Resolver04AppliedCorrect)

=============================================================================
