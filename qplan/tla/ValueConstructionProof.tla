-------------------- MODULE ValueConstructionProof --------------------
EXTENDS ValueConstruction, TreeConstructionProof, ProjectionProof

ASSUME ValueConstructionAssumptions == ValueConstructionWorld

THEOREM ProjectionAlignmentImpliesResolverConformance ==
    ProjectionAlignment => ConformsToResolvers
<1>. SUFFICES
        ASSUME ProjectionAlignment,
               NEW observation \in Observations
        PROVE ActualObservation[observation] =
                  ExpectedObservation[observation]
    BY DEF ConformsToResolvers
<1>1. LET cell == ObservationResolver[observation]
          outputObservation == ResultObservation[observation]
          demand == SuppliedDemand[cell]
      IN
      /\ outputObservation \in DOMAIN Project(demand)
      /\ ActualObservation[observation] =
             Project(demand)[outputObservation]
      /\ ExpectedObservation[observation] =
             RawObservationValue[outputObservation]
    BY DEF ProjectionAlignment
<1>2. Project(SuppliedDemand[ObservationResolver[observation]])[
           ResultObservation[observation]]
       = RawObservationValue[ResultObservation[observation]]
    BY <1>1 DEF Project
<1>. QED BY <1>1, <1>2

LEMMA NoStoredVariablesConform ==
    NoStoredVariables => ConformsToVariables
<1>. SUFFICES
        ASSUME NoStoredVariables,
               NEW object \in ReachableObjects
        PROVE
            /\ StoredVariableNames[object] = BindingNamesAt(object)
            /\ \A binding \in BindingsAt(object) :
                   StoredBindingValue[binding] =
                       ExpectedBindingValue[binding]
    BY DEF ConformsToVariables
<1>1. BindingsAt(object) = {}
    BY DEF NoStoredVariables, BindingsAt
<1>2. BindingNamesAt(object) = {}
    BY <1>1 DEF BindingNamesAt
<1>. QED BY <1>1, <1>2 DEF NoStoredVariables

THEOREM Resolver01ValueConstructionIsCorrect ==
    Resolver01ValueConstruction => ValueConstructionCorrect
BY ProjectionAlignmentImpliesResolverConformance,
   NoStoredVariablesConform
   DEF Resolver01ValueConstruction, ValueConstructionCorrect,
       GeneratedTypenames, ConformsToTypename,
       NoStoredVariables

THEOREM Resolver01FullConstructionRefinesPostcondition ==
    Resolver01FullConstruction => Resolver01Constructed
BY Resolver01ValueConstructionIsCorrect
   DEF Resolver01FullConstruction, Resolver01Constructed

THEOREM Resolver01FullCorrectness ==
    Resolver01FullConstruction => CorrectResolution
BY Resolver01FullConstructionRefinesPostcondition,
   Resolver01TreeCorrectness

THEOREM Resolver02FullConstructionRefinesPostcondition ==
    Resolver02FullConstruction => Resolver02Constructed
BY Resolver01ValueConstructionIsCorrect
   DEF Resolver02FullConstruction, Resolver02Constructed

THEOREM Resolver02FullCorrectness ==
    Resolver02FullConstruction => CorrectResolution
BY Resolver02FullConstructionRefinesPostcondition,
   Resolver02TreeCorrectness

THEOREM Resolver03FullCorrectness ==
    Resolver03FullConstruction => CorrectResolution
BY Resolver02FullCorrectness
   DEF Resolver03FullConstruction

=============================================================================
