------------------ MODULE ProviderPathEvaluationMC ------------------
EXTENDS ProviderPathEvaluation

MCBindings == {"direct", "nested", "null-path", "list"}
MCKeys == {"leaf", "missing"}

MCNull == "null"
MCError == "error"
MCSimple == "simple"
MCObject == "object"
MCInnerList == "inner-list"
MCOuterList == "outer-list"
MCInnerInputList == "inner-input-list"
MCOuterInputList == "outer-input-list"

MCValues ==
    {MCNull, MCError, MCSimple, MCObject,
     MCInnerList, MCOuterList,
     MCInnerInputList, MCOuterInputList}
MCReadValues ==
    {MCNull, MCError, MCSimple, MCObject,
     MCInnerList, MCOuterList}
MCInputValues ==
    {MCNull, MCError, MCSimple,
     MCInnerInputList, MCOuterInputList}

MCNullValues == {MCNull}
MCErrorValues == {MCError}
MCSimpleValues == {MCSimple}
MCObjectValues == {MCObject}
MCListValues == {MCInnerList, MCOuterList}
MCInputListValues == {MCInnerInputList, MCOuterInputList}

MCProviderTail ==
    [binding \in MCBindings |->
        CASE binding = "nested"    -> <<"leaf">>
          [] binding = "null-path" -> <<"missing", "leaf">>
          [] OTHER                  -> <<>>]

MCProviderRootValue ==
    [binding \in MCBindings |->
        CASE binding = "nested"    -> MCObject
          [] binding = "null-path" -> MCObject
          [] binding = "list"      -> MCOuterList
          [] OTHER                  -> MCSimple]

MCObjectFieldValue ==
    [object \in MCObjectValues |->
        [key \in MCKeys |->
            IF key = "missing" THEN MCNull ELSE MCSimple]]

MCPathTrace ==
    [binding \in MCBindings |->
        CASE binding = "nested"    -> <<MCObject, MCSimple>>
          [] binding = "null-path" -> <<MCObject, MCNull, MCNull>>
          [] binding = "list"      -> <<MCOuterList>>
          [] OTHER                  -> <<MCSimple>>]

MCListElements ==
    [list \in MCListValues |->
        IF list = MCInnerList
        THEN <<MCError, MCSimple>>
        ELSE <<MCSimple, MCInnerList, MCNull>>]

MCConvertedList ==
    [list \in MCListValues |->
        IF list = MCInnerList
        THEN MCInnerInputList
        ELSE MCOuterInputList]

MCInputListElements ==
    [list \in MCInputListValues |->
        IF list = MCInnerInputList
        THEN <<MCError, MCSimple>>
        ELSE <<MCSimple, MCInnerInputList, MCNull>>]

MCListRank ==
    [list \in MCListValues |->
        IF list = MCInnerList THEN 0 ELSE 1]

MCToInput ==
    [value \in
        MCNullValues \cup MCErrorValues \cup MCSimpleValues
            \cup MCListValues |->
        CASE value = MCInnerList -> MCInnerInputList
          [] value = MCOuterList -> MCOuterInputList
          [] OTHER                -> value]

MCExpected ==
    /\ ProviderPathValue("direct") = MCSimple
    /\ ProviderPathValue("nested") = MCSimple
    /\ ProviderPathValue("null-path") = MCNull
    /\ ProviderPathValue("list") = MCOuterInputList

VARIABLE checked

MCInit == checked = TRUE
MCNext == UNCHANGED checked
MCSpec == MCInit /\ [][MCNext]_<<checked>>

=============================================================================
