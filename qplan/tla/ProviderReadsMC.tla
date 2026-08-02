----------------------- MODULE ProviderReadsMC -----------------------
EXTENDS MaterializationMC, ProviderReads

MCProviderVariables == {"viewerType"}
MCProviderBindings == {"query/viewerType"}

MCProviderStoredNames ==
    [object \in MCObjects |->
        IF object = "query" THEN {"viewerType"} ELSE {}]

MCProviderBindingObject ==
    [binding \in MCProviderBindings |-> "query"]

MCProviderBindingVariable ==
    [binding \in MCProviderBindings |-> "viewerType"]

MCProviderBindingValue ==
    [binding \in MCProviderBindings |-> "Query"]

MCBindingOwnerWork ==
    [binding \in MCProviderBindings |-> <<"query", 2>>]

MCProviderRootWork ==
    [binding \in MCProviderBindings |-> <<"query", 1>>]

MCProviderReadFunction ==
    [binding \in MCProviderBindings |->
        [value \in MCValues |-> value]]

MCProviderReadValues == MCValues
MCProviderInputValues == MCValues
MCProviderNullValues == {}
MCProviderErrorValues == {}
MCProviderSimpleValues == MCValues
MCProviderObjectValues == {}
MCProviderListValues == {}
MCProviderInputListValues == {}

MCProviderTail ==
    [binding \in MCProviderBindings |-> <<>>]

MCProviderObjectFieldValue ==
    [object \in MCProviderObjectValues |->
        [key \in MCKeys |-> "Query"]]

MCProviderPathTrace ==
    [binding \in MCProviderBindings |-> <<"Query">>]

MCProviderListElements ==
    [list \in MCProviderListValues |-> <<>>]

MCProviderConvertedList ==
    [list \in MCProviderListValues |-> "Query"]

MCProviderInputListElements ==
    [list \in MCProviderInputListValues |-> <<>>]

MCProviderListRank ==
    [list \in MCProviderListValues |-> 0]

MCProviderToInput ==
    [value \in MCProviderReadValues |-> value]

=============================================================================
