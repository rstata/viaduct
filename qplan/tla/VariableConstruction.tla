---------------------- MODULE VariableConstruction ----------------------
EXTENDS ValueConstruction

(*
Nonempty execution-variable specialization used by Resolver04. A binding atom
belongs to one concrete OER occurrence and one globally named variable.
ExpectedBindingValue is the value read from the instantiated registered
provider path; StoredBindingValue is the value retained in that OER.
*)

ResolvedVariableBindings ==
    \A object \in ReachableObjects :
        /\ StoredVariableNames[object] = BindingNamesAt(object)
        /\ \A binding \in BindingsAt(object) :
               StoredBindingValue[binding] =
                   ExpectedBindingValue[binding]

Resolver04ValueConstruction ==
    /\ RootedAndWellTyped
    /\ ProjectionAlignment
    /\ GeneratedTypenames
    /\ ResolvedVariableBindings

Resolver04FullConstruction ==
    /\ FoldCompleted
    /\ Resolver04ValueConstruction

=============================================================================
