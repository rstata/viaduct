------------------- MODULE VariableConstructionProof -------------------
EXTENDS VariableConstruction, ValueConstructionProof

ASSUME VariableConstructionAssumptions == ValueConstructionWorld

THEOREM ResolvedBindingsConformToVariables ==
    ResolvedVariableBindings => ConformsToVariables
BY DEF ResolvedVariableBindings, ConformsToVariables

THEOREM Resolver04ValueConstructionIsCorrect ==
    Resolver04ValueConstruction => ValueConstructionCorrect
BY ProjectionAlignmentImpliesResolverConformance,
   ResolvedBindingsConformToVariables
   DEF Resolver04ValueConstruction, ValueConstructionCorrect,
       GeneratedTypenames, ConformsToTypename

THEOREM Resolver04FullConstructionRefinesPostcondition ==
    Resolver04FullConstruction => Resolver02Constructed
BY Resolver04ValueConstructionIsCorrect
   DEF Resolver04FullConstruction, Resolver02Constructed

THEOREM Resolver04FullCorrectness ==
    Resolver04FullConstruction => CorrectResolution
BY Resolver04FullConstructionRefinesPostcondition,
   Resolver02TreeCorrectness

=============================================================================
