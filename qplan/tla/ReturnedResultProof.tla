--------------------- MODULE ReturnedResultProof ---------------------
EXTENDS ReturnedResult, OccurrenceFoldsProof, ProjectionProof,
        TreeConstructionProof

ASSUME ReturnedResultAssumptions == ReturnedResultWorld

LEMMA ReturnedCellsAreFinal ==
    AllFoldsCompleted => BuiltCells = PresentCells
BY CompletedFoldAlignsWithOutput
   DEF AllFoldsCompleted, OutputAlignment

LEMMA ReturnedReachabilityIsFinal ==
    AllFoldsCompleted =>
        ReturnedTree!ReachableObjects = ReachableObjects
BY ReturnedCellsAreFinal, Isa
   DEF ReturnedTree!ReachableObjects,
       ReturnedTree!ObjectClosedSets,
       ReachableObjects, ObjectClosedSets

LEMMA ReturnedConformsToFragment ==
    AllFoldsCompleted => ReturnedTree!ConformsToFragment
BY ReturnedCellsAreFinal, ReturnedReachabilityIsFinal,
   CompletedResultRefinesFoldCompleted,
   CompletedFoldsConformToFragment
   DEF ReturnedTree!ConformsToFragment,
       ReturnedTree!PresentKeys, ConformsToFragment, PresentKeys

LEMMA ReturnedClosesResolverDemand ==
    AllFoldsCompleted =>
        ReturnedTree!IsClosedUnderResolverDemand
BY ReturnedCellsAreFinal, CompletedResultRefinesFoldCompleted,
   CompletedFoldsCloseResolverDemand
   DEF ReturnedTree!IsClosedUnderResolverDemand,
       ReturnedTree!ActiveResolverCells,
       ReturnedTree!PresentKeys,
       IsClosedUnderResolverDemand, ActiveResolverCells,
       PresentKeys

LEMMA ReturnedConformsToVariables ==
    ReturnedTree!ConformsToVariables
BY Isa
   DEF ReturnedTree!ConformsToVariables,
       ReturnedTree!ReachableObjects,
       ReturnedTree!ObjectClosedSets,
       ReturnedTree!BindingsAt,
       ReturnedTree!BindingNamesAt,
       EmptyStoredVariableNames, EmptyBindingObject,
       EmptyBindingVariable, EmptyBindingValue

LEMMA ReturnedConformsToResolvers ==
    ReturnedTree!ConformsToResolvers
<1>. SUFFICES
        ASSUME NEW observation \in Observations
        PROVE
            ProjectedActualObservation[observation] =
                RawExpectedObservation[observation]
    BY DEF ReturnedTree!ConformsToResolvers
<1>1. ResultObservation[observation]
          \in DOMAIN
                Project(
                    SuppliedDemand[
                        ObservationResolver[observation]])
    BY ReturnedResultAssumptions DEF ReturnedResultWorld
<1>. QED BY <1>1 DEF ProjectedActualObservation,
                    RawExpectedObservation, Project

LEMMA ReturnedConformsToTypename ==
    ReturnedTree!ConformsToTypename
<1>. SUFFICES
        ASSUME NEW cell \in BuiltCells \cap TypenameCells
        PROVE GeneratedCellValue[cell] =
                  TypeNameValue[CellObject[cell]]
    BY DEF ReturnedTree!ConformsToTypename
<1>1. cell \in Cells
    BY ReturnedResultAssumptions
       DEF ReturnedResultWorld, FoldWorld, ConstructionWorld,
           World, WorldCarriers, BuiltCells, CompletedWork
<1>. QED BY <1>1 DEF GeneratedCellValue

THEOREM CompletedReturnedResultIsCorrect ==
    AllFoldsCompleted => ReturnedCorrect
BY ReturnedConformsToFragment, ReturnedClosesResolverDemand,
   ReturnedConformsToVariables, ReturnedConformsToResolvers,
   ReturnedConformsToTypename, ReturnedResultAssumptions
   DEF ReturnedCorrect, ReturnedTree!CorrectResolution,
       ReturnedTree!RootedAndWellTyped, ReturnedResultWorld,
       FoldWorld, ConstructionWorld, World,
       RootedAndWellTyped

THEOREM Resolver01And02ReturnedResultCorrect ==
    FoldSpec => ReturnedResultTermination
BY AllOccurrenceFoldsComplete, CompletedReturnedResultIsCorrect, PTL
   DEF FoldTermination, ReturnedResultTermination

=============================================================================
