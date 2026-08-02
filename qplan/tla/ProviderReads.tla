------------------------ MODULE ProviderReads ------------------------
EXTENDS Materialization

(*
A variable provider is one path rooted at a cell in the defining field's
containing OER. ProviderReadFunction is the finite extensional interpretation
of that validated path, including null/error propagation and terminal-list
conversion. The root cell is completed before the defining field work item,
so its immutable structural value is identical in the prefix and final OER.
*)

CONSTANTS
    BindingOwnerWork, ProviderRootWork, ProviderReadFunction,
    ProviderReadValues, ProviderInputValues,
    ProviderNullValues, ProviderErrorValues,
    ProviderSimpleValues, ProviderObjectValues,
    ProviderListValues, ProviderInputListValues,
    ProviderTail, ProviderObjectFieldValue, ProviderPathTrace,
    ProviderListElements, ProviderConvertedList,
    ProviderInputListElements, ProviderListRank, ProviderToInput

ProviderPrefixCell(binding) ==
    WorkCell[ProviderRootWork[binding]]

ProviderFinalCell(binding) ==
    CellForKey(
        PresentCells,
        WorkObject(ProviderRootWork[binding]),
        WorkKey(ProviderRootWork[binding]))

ProviderRootValue ==
    [binding \in VariableBindings |->
        CellValue[ProviderPrefixCell(binding)]]

ProviderPaths ==
    INSTANCE ProviderPathEvaluation
        WITH Bindings <- VariableBindings,
             Keys <- Keys,
             Values <- Values,
             ReadValues <- ProviderReadValues,
             InputValues <- ProviderInputValues,
             NullValues <- ProviderNullValues,
             ErrorValues <- ProviderErrorValues,
             SimpleValues <- ProviderSimpleValues,
             ObjectValues <- ProviderObjectValues,
             ListValues <- ProviderListValues,
             InputListValues <- ProviderInputListValues,
             ProviderTail <- ProviderTail,
             ProviderRootValue <- ProviderRootValue,
             ObjectFieldValue <- ProviderObjectFieldValue,
             PathTrace <- ProviderPathTrace,
             ListElements <- ProviderListElements,
             ConvertedList <- ProviderConvertedList,
             InputListElements <- ProviderInputListElements,
             ListRank <- ProviderListRank,
             ToInput <- ProviderToInput

PrefixProviderValue(binding) ==
    ProviderReadFunction[binding][
        CellValue[ProviderPrefixCell(binding)]]

FinalProviderValue(binding) ==
    ProviderReadFunction[binding][
        CellValue[ProviderFinalCell(binding)]]

ConstructedStoredVariableNames ==
    [object \in Objects |->
        {BindingVariable[binding] :
            binding \in {candidate \in VariableBindings :
                BindingObject[candidate] = object}}]

ConstructedStoredBindingValue ==
    [binding \in VariableBindings |->
        PrefixProviderValue(binding)]

FinalExpectedBindingValue ==
    [binding \in VariableBindings |->
        FinalProviderValue(binding)]

StructuralProviderReads ==
    \A binding \in VariableBindings :
        ProviderReadFunction[binding][ProviderRootValue[binding]] =
            ProviderPaths!ProviderPathValue(binding)

ProviderReadWorld ==
    /\ MaterializationWorld
    /\ BindingOwnerWork
           \in [VariableBindings -> WorkItems]
    /\ ProviderRootWork
           \in [VariableBindings -> WorkItems]
    /\ ProviderReadFunction
           \in [VariableBindings -> [Values -> Values]]
    /\ ProviderPaths!ProviderPathWorld
    /\ StructuralProviderReads
    /\ \A binding \in VariableBindings :
           /\ BindingObject[binding] =
                  WorkObject(BindingOwnerWork[binding])
           /\ BindingObject[binding] =
                  WorkObject(ProviderRootWork[binding])
           /\ ProviderRootWork[binding]
                  \in EarlierWork(BindingOwnerWork[binding])

ProviderReadsStable ==
    \A binding \in VariableBindings :
        PrefixProviderValue(binding) =
            FinalProviderValue(binding)

=============================================================================
