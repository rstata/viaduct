------------------------ MODULE ReturnedResult ------------------------
EXTENDS OccurrenceFolds, ValueConstruction

(*
Source-to-judgment refinement for the value returned by Resolver01/02.
Each completed work item contributes the one cell returned by resolveKey;
BuiltCells is therefore the object-union fold result. Resolver outputs are
the finite Project operator from snipToDemand, typename cells are generated
from their containing object, and these stages store no execution variables.

The named ResultTree instance substitutes those constructed operators for
the abstract result carriers. This avoids assuming FoldCompleted,
ProjectionAlignment, GeneratedTypenames, or NoStoredVariables as independent
postconditions of the resolver.
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

EmptyStoredVariableNames ==
    [object \in Objects |-> {}]

EmptyBindingObject == [binding \in {} |-> Root]
EmptyBindingVariable == [binding \in {} |-> Root]
EmptyBindingValue == [binding \in {} |-> Root]

ReturnedTree ==
    INSTANCE ResultTree
        WITH PresentCells <- BuiltCells,
             ActualObservation <- ProjectedActualObservation,
             ExpectedObservation <- RawExpectedObservation,
             ActualCellValue <- GeneratedCellValue,
             StoredVariableNames <- EmptyStoredVariableNames,
             VariableBindings <- {},
             BindingObject <- EmptyBindingObject,
             BindingVariable <- EmptyBindingVariable,
             StoredBindingValue <- EmptyBindingValue,
             ExpectedBindingValue <- EmptyBindingValue

ReturnedResultWorld ==
    /\ FoldWorld
    /\ ProjectionWorld
    /\ RootedAndWellTyped
    /\ ObservationValues = Values
    /\ ResultObservation
           \in [Observations -> OutputObservations]
    /\ SuppliedDemand \in [Cells -> SUBSET DemandTokens]
    /\ \A observation \in Observations :
           /\ ResultObservation[observation]
                  \in PassiveObservations
           /\ ResultObservation[observation]
                  \in DOMAIN
                        Project(
                            SuppliedDemand[
                                ObservationResolver[observation]])

ReturnedCorrect == ReturnedTree!CorrectResolution

ReturnedResultTermination ==
    <> (AllFoldsCompleted /\ ReturnedCorrect)

=============================================================================
