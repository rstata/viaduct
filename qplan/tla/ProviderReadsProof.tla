--------------------- MODULE ProviderReadsProof ---------------------
EXTENDS ProviderReads, MaterializationProof

ASSUME ProviderReadAssumptions == ProviderReadWorld

LEMMA ProviderRootCellIsPresent ==
    \A binding \in VariableBindings :
        ProviderPrefixCell(binding) \in PresentCells
<1>. SUFFICES
        ASSUME NEW binding \in VariableBindings
        PROVE ProviderPrefixCell(binding) \in PresentCells
    OBVIOUS
<1>1. ProviderRootWork[binding] \in WorkItems
    BY ProviderReadAssumptions DEF ProviderReadWorld
<1>. QED BY <1>1, ProviderReadAssumptions
          DEF ProviderPrefixCell, ProviderReadWorld,
              MaterializationWorld, FoldWorld

LEMMA ProviderFinalCellCoordinates ==
    \A binding \in VariableBindings :
        /\ ProviderFinalCell(binding) \in PresentCells
        /\ CellObject[ProviderFinalCell(binding)] =
               WorkObject(ProviderRootWork[binding])
        /\ CellKey[ProviderFinalCell(binding)] =
               WorkKey(ProviderRootWork[binding])
<1>. SUFFICES
        ASSUME NEW binding \in VariableBindings
        PROVE
            /\ ProviderFinalCell(binding) \in PresentCells
            /\ CellObject[ProviderFinalCell(binding)] =
                   WorkObject(ProviderRootWork[binding])
            /\ CellKey[ProviderFinalCell(binding)] =
                   WorkKey(ProviderRootWork[binding])
    OBVIOUS
<1>1. ProviderPrefixCell(binding) \in PresentCells
    BY ProviderRootCellIsPresent
<1>2. /\ CellObject[ProviderPrefixCell(binding)] =
             WorkObject(ProviderRootWork[binding])
      /\ CellKey[ProviderPrefixCell(binding)] =
             WorkKey(ProviderRootWork[binding])
    BY <1>1, ProviderReadAssumptions
       DEF ProviderPrefixCell, ProviderReadWorld,
           MaterializationWorld, FoldWorld
<1>3. \E cell \in PresentCells :
          /\ CellObject[cell] =
                 WorkObject(ProviderRootWork[binding])
          /\ CellKey[cell] =
                 WorkKey(ProviderRootWork[binding])
    BY <1>1, <1>2
<1>. QED BY <1>3 DEF ProviderFinalCell, CellForKey

LEMMA ProviderPrefixAndFinalCellAgree ==
    \A binding \in VariableBindings :
        ProviderPrefixCell(binding) =
            ProviderFinalCell(binding)
<1>. SUFFICES
        ASSUME NEW binding \in VariableBindings
        PROVE
            ProviderPrefixCell(binding) =
                ProviderFinalCell(binding)
    OBVIOUS
<1>1. /\ ProviderPrefixCell(binding) \in PresentCells
      /\ ProviderFinalCell(binding) \in PresentCells
    BY ProviderRootCellIsPresent, ProviderFinalCellCoordinates
<1>2. /\ CellObject[ProviderPrefixCell(binding)] =
             CellObject[ProviderFinalCell(binding)]
      /\ CellKey[ProviderPrefixCell(binding)] =
             CellKey[ProviderFinalCell(binding)]
    BY ProviderFinalCellCoordinates, ProviderReadAssumptions
       DEF ProviderPrefixCell, ProviderReadWorld,
           MaterializationWorld, FoldWorld
<1>3. WorldUniqueKeys
    BY ProviderReadAssumptions
       DEF ProviderReadWorld, MaterializationWorld,
           FoldWorld, ConstructionWorld, World, WorldTree
<1>. QED BY <1>1, <1>2, <1>3 DEF WorldUniqueKeys

THEOREM PrefixProviderReadsEqualFinalReads ==
    ProviderReadsStable
BY ProviderPrefixAndFinalCellAgree
   DEF ProviderReadsStable, PrefixProviderValue,
       FinalProviderValue

THEOREM PrefixProviderReadsFollowStructuralPaths ==
    \A binding \in VariableBindings :
        PrefixProviderValue(binding) =
            ProviderPaths!ProviderPathValue(binding)
BY ProviderReadAssumptions
   DEF ProviderReadWorld, StructuralProviderReads,
       PrefixProviderValue, ProviderRootValue

LEMMA ConstructedNamesMatchBindingNames ==
    \A object \in Objects :
        ConstructedStoredVariableNames[object] =
            BindingNamesAt(object)
BY DEF ConstructedStoredVariableNames,
       BindingNamesAt, BindingsAt

THEOREM ConstructedBindingsConformToFinalReads ==
    \A object \in ReachableObjects :
        /\ ConstructedStoredVariableNames[object] =
               BindingNamesAt(object)
        /\ \A binding \in BindingsAt(object) :
               ConstructedStoredBindingValue[binding] =
                   FinalExpectedBindingValue[binding]
<1>. SUFFICES
        ASSUME NEW object \in ReachableObjects
        PROVE
            /\ ConstructedStoredVariableNames[object] =
                   BindingNamesAt(object)
            /\ \A binding \in BindingsAt(object) :
                   ConstructedStoredBindingValue[binding] =
                       FinalExpectedBindingValue[binding]
    OBVIOUS
<1>1. ConstructedStoredVariableNames[object] =
          BindingNamesAt(object)
    <2>1. object \in Objects
        BY DEF ReachableObjects
    <2>2. ConstructedStoredVariableNames[object] =
              {BindingVariable[binding] :
                  binding \in BindingsAt(object)}
        BY <2>1
           DEF ConstructedStoredVariableNames, BindingsAt
    <2>. QED BY <2>2 DEF BindingNamesAt
<1>2. ASSUME NEW binding \in BindingsAt(object)
      PROVE
          ConstructedStoredBindingValue[binding] =
              FinalExpectedBindingValue[binding]
    <2>1. binding \in VariableBindings
        BY <1>2 DEF BindingsAt
    <2>2. PrefixProviderValue(binding) =
              FinalProviderValue(binding)
        BY <2>1, PrefixProviderReadsEqualFinalReads
           DEF ProviderReadsStable
    <2>. QED BY <2>1, <2>2, Isa
              DEF ConstructedStoredBindingValue,
                  FinalExpectedBindingValue
<1>. QED BY <1>1, <1>2

=============================================================================
