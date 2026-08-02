---------------- MODULE ProviderPathEvaluationProof ----------------
EXTENDS ProviderPathEvaluation, TLAPS, NaturalsInduction

ASSUME ProviderPathAssumptions == ProviderPathWorld

THEOREM PathTraceHasExactFiniteShape ==
    \A binding \in Bindings :
        /\ PathTrace[binding] \in Seq(ReadValues)
        /\ Len(PathTrace[binding]) =
               Len(ProviderTail[binding]) + 1
        /\ PathTrace[binding][1] =
               ProviderRootValue[binding]
BY ProviderPathAssumptions
   DEF ProviderPathWorld, TraceConforms

THEOREM EveryPathStepMatchesReadVariable ==
    \A binding \in Bindings :
        \A position \in 1..Len(ProviderTail[binding]) :
            LET current == PathTrace[binding][position]
                next == PathTrace[binding][position + 1]
            IN
            IF current \in TerminalValues
            THEN next = current
            ELSE
                /\ current \in ObjectValues
                /\ next =
                       ObjectFieldValue[current][
                           ProviderTail[binding][position]]
BY ProviderPathAssumptions
   DEF ProviderPathWorld, TraceConforms

THEOREM NullAndErrorAbsorbTheRemainingPath ==
    \A binding \in Bindings :
        \A start \in 1..Len(PathTrace[binding]) :
            PathTrace[binding][start] \in TerminalValues
                => \A finish \in start..Len(PathTrace[binding]) :
                       PathTrace[binding][finish] =
                           PathTrace[binding][start]
<1>. SUFFICES
        ASSUME NEW binding \in Bindings,
               NEW start \in 1..Len(PathTrace[binding]),
               PathTrace[binding][start] \in TerminalValues,
               NEW finish \in start..Len(PathTrace[binding])
        PROVE
            PathTrace[binding][finish] =
                PathTrace[binding][start]
    OBVIOUS
<1> DEFINE
        P(offset) ==
            start + offset <= Len(PathTrace[binding])
                => PathTrace[binding][start + offset] =
                       PathTrace[binding][start]
<1> HIDE DEF P
<1>1. /\ start \in 1..Len(PathTrace[binding])
      /\ finish \in start..Len(PathTrace[binding])
    OBVIOUS
<1>2. Len(PathTrace[binding]) =
          Len(ProviderTail[binding]) + 1
    BY PathTraceHasExactFiniteShape
<1>3. P(0)
    BY DEF P
<1>4. \A offset \in Nat : P(offset) => P(offset + 1)
    <2>. SUFFICES
            ASSUME NEW offset \in Nat,
                   P(offset),
                   start + offset + 1 <=
                       Len(PathTrace[binding])
            PROVE
                PathTrace[binding][start + offset + 1] =
                    PathTrace[binding][start]
        BY DEF P
    <2>1. start + offset \in
              1..Len(ProviderTail[binding])
        BY <1>1, <1>2, SMT
    <2>2. PathTrace[binding][start + offset] =
              PathTrace[binding][start]
        BY DEF P
    <2>3. PathTrace[binding][start + offset]
              \in TerminalValues
        BY <2>2
    <2>4. PathTrace[binding][start + offset + 1] =
              PathTrace[binding][start + offset]
        BY <2>1, <2>3, EveryPathStepMatchesReadVariable
    <2>. QED BY <2>2, <2>4
<1>5. \A offset \in Nat : P(offset)
    BY <1>3, <1>4, NatInduction
<1>6. /\ start \in Nat
      /\ finish \in Nat
      /\ start <= finish
      /\ finish <= Len(PathTrace[binding])
    BY <1>1, SMT
<1>7. finish - start \in Nat
    BY <1>6, SMT
<1>8. P(finish - start)
    BY <1>5, <1>7
<1>9. start + (finish - start) = finish
    BY <1>6, SMT
<1>. QED BY <1>6, <1>8, <1>9 DEF P

THEOREM CompletedProviderPathsYieldInputs ==
    \A binding \in Bindings :
        ProviderPathValue(binding) \in InputValues
BY ProviderPathAssumptions
   DEF ProviderPathWorld, ProviderPathValue

THEOREM NullErrorAndSimpleConversionIsIdentity ==
    \A value \in TerminalValues \cup SimpleValues :
        ToInput[value] = value
BY ProviderPathAssumptions
   DEF ProviderPathWorld

THEOREM ListConversionPreservesEveryPosition ==
    \A list \in ListValues :
        /\ ToInput[list] = ConvertedList[list]
        /\ Len(InputListElements[ToInput[list]]) =
               Len(ListElements[list])
        /\ \A position \in 1..Len(ListElements[list]) :
               InputListElements[ToInput[list]][position] =
                   ToInput[ListElements[list][position]]
BY ProviderPathAssumptions
   DEF ProviderPathWorld

THEOREM NestedListConversionHasFiniteRank ==
    \A list \in ListValues :
        \A position \in 1..Len(ListElements[list]) :
            ListElements[list][position] \in ListValues
                => ListRank[ListElements[list][position]]
                       < ListRank[list]
BY ProviderPathAssumptions
   DEF ProviderPathWorld

=============================================================================
