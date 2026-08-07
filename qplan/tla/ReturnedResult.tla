------------------------ MODULE ReturnedResult ------------------------
EXTENDS OccurrenceFolds, ValueConstruction

(*
Source-to-judgment refinement for the value returned by Resolver01/02.
Each completed work item contributes the one cell returned by resolveKey;
BuiltCells is therefore the object-union fold result. Resolver outputs are
the finite Project operator from snipToDemand, typename cells are generated
from their containing object.

The named ResultTree instance substitutes those constructed operators for
the abstract result carriers. This avoids assuming FoldCompleted,
ProjectionAlignment, or GeneratedTypenames as independent postconditions of
the resolver.
*)

ProjectedActualObservation ==
    [observation \in Observations |->
        LET cell == ObservationResolver[observation]
            outputObservation == ResultObservation[observation]
            demand == SuppliedDemand[cell]
        IN  Project(demand)[outputObservation]]

RawExpectedObservation ==
    [observation \in Observations |->
        RawObservationValue[ResultObservation[observation]]]

GeneratedCellValue ==
    [cell \in Cells |->
        IF cell \in TypenameCells
        THEN TypeNameValue[CellObject[cell]]
        ELSE ActualCellValue[cell]]

ReturnedTree ==
    INSTANCE ResultTree
        WITH PresentCells <- BuiltCells,
             ActualObservation <- ProjectedActualObservation,
             ExpectedObservation <- RawExpectedObservation,
             ActualCellValue <- GeneratedCellValue

ReturnedResultBaseWorld ==
    /\ FoldWorld
    /\ ProjectionWorld
    /\ RootedAndWellTyped
    /\ ObservationValues = Values
    /\ ResultObservation
           \in [Observations -> OutputObservations]
    /\ SuppliedDemand \in [Cells -> SUBSET DemandTokens]
    /\ \A observation \in Observations :
           ResultObservation[observation]
               \in PassiveObservations

ReturnedProjectionCoverage ==
    \A observation \in Observations :
        ResultObservation[observation]
            \in DOMAIN
                  Project(
                      SuppliedDemand[
                          ObservationResolver[observation]])

ReturnedResultWorld ==
    /\ ReturnedResultBaseWorld
    /\ ReturnedProjectionCoverage

ReturnedCorrect == ReturnedTree!CorrectResolution

ReturnedResultTermination ==
    <> (AllFoldsCompleted /\ ReturnedCorrect)

=============================================================================
