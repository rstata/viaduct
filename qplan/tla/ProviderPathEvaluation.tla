------------------- MODULE ProviderPathEvaluation -------------------
EXTENDS Naturals, Sequences, FiniteSets

(*
Finite structural semantics of Variables.kt readVariable and
toVariableInput after the provider's root field cell has been selected.
ProviderTail contains the remaining exact keys in the validated path.
PathTrace records each intermediate EngineResult value. Null and error
values absorb the remaining path; otherwise every nonterminal step must
traverse an object. Ranked ListValues make terminal list conversion
well-founded without a RECURSIVE operator.
*)

CONSTANTS
    Bindings, Keys, Values, ReadValues, InputValues,
    NullValues, ErrorValues, SimpleValues, ObjectValues,
    ListValues, InputListValues,
    ProviderTail, ProviderRootValue, ObjectFieldValue, PathTrace,
    ListElements, ConvertedList, InputListElements, ListRank, ToInput

TerminalValues == NullValues \cup ErrorValues

ConvertibleReadValues ==
    NullValues \cup ErrorValues \cup SimpleValues \cup ListValues

ReadValuePartition ==
    /\ ReadValues =
           NullValues \cup ErrorValues \cup SimpleValues
               \cup ObjectValues \cup ListValues
    /\ NullValues \cap ErrorValues = {}
    /\ NullValues \cap SimpleValues = {}
    /\ NullValues \cap ObjectValues = {}
    /\ NullValues \cap ListValues = {}
    /\ ErrorValues \cap SimpleValues = {}
    /\ ErrorValues \cap ObjectValues = {}
    /\ ErrorValues \cap ListValues = {}
    /\ SimpleValues \cap ObjectValues = {}
    /\ SimpleValues \cap ListValues = {}
    /\ ObjectValues \cap ListValues = {}

TraceConforms(rootValues, traces) ==
    /\ rootValues \in [Bindings -> ReadValues]
    /\ traces \in [Bindings -> Seq(ReadValues)]
    /\ \A binding \in Bindings :
           /\ Len(traces[binding]) =
                  Len(ProviderTail[binding]) + 1
           /\ traces[binding][1] = rootValues[binding]
           /\ \A position \in 1..Len(ProviderTail[binding]) :
                  LET current == traces[binding][position]
                      next == traces[binding][position + 1]
                  IN
                  IF current \in TerminalValues
                  THEN next = current
                  ELSE
                      /\ current \in ObjectValues
                      /\ next =
                             ObjectFieldValue[current][
                                 ProviderTail[binding][position]]

ProviderPathValue(binding) ==
    ToInput[
        PathTrace[binding][Len(PathTrace[binding])]]

ProviderPathWorld ==
    /\ IsFiniteSet(Bindings)
    /\ IsFiniteSet(Keys)
    /\ IsFiniteSet(Values)
    /\ ReadValues \subseteq Values
    /\ InputValues \subseteq Values
    /\ ReadValuePartition
    /\ InputListValues \subseteq InputValues
    /\ (TerminalValues \cup SimpleValues) \subseteq InputValues
    /\ ProviderTail \in [Bindings -> Seq(Keys)]
    /\ ObjectFieldValue
           \in [ObjectValues -> [Keys -> ReadValues]]
    /\ TraceConforms(ProviderRootValue, PathTrace)
    /\ \A binding \in Bindings :
           PathTrace[binding][Len(PathTrace[binding])]
               \in ConvertibleReadValues
    /\ ListElements
           \in [ListValues -> Seq(ConvertibleReadValues)]
    /\ ConvertedList \in [ListValues -> InputListValues]
    /\ InputListElements
           \in [InputListValues -> Seq(InputValues)]
    /\ ListRank \in [ListValues -> Nat]
    /\ \A list \in ListValues :
           \A position \in 1..Len(ListElements[list]) :
               ListElements[list][position] \in ListValues
                   => ListRank[ListElements[list][position]]
                          < ListRank[list]
    /\ ToInput \in [ConvertibleReadValues -> InputValues]
    /\ \A value \in TerminalValues \cup SimpleValues :
           ToInput[value] = value
    /\ \A list \in ListValues :
           /\ ToInput[list] = ConvertedList[list]
           /\ Len(InputListElements[ConvertedList[list]]) =
                  Len(ListElements[list])
           /\ \A position \in 1..Len(ListElements[list]) :
                  InputListElements[ConvertedList[list]][position] =
                      ToInput[ListElements[list][position]]

=============================================================================
