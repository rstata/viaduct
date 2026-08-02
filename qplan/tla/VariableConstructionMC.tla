------------------- MODULE VariableConstructionMC -------------------
EXTENDS ValueConstructionMC, VariableConstruction

MC04Variables == {"viewerId"}
MC04VariableBindings == {"query/viewerId"}

MC04StoredVariableNames ==
    [object \in MCObjects |->
        IF object = "query" THEN {"viewerId"} ELSE {}]

MC04BindingObject ==
    [binding \in MC04VariableBindings |-> "query"]

MC04BindingVariable ==
    [binding \in MC04VariableBindings |-> "viewerId"]

MC04StoredBindingValue ==
    [binding \in MC04VariableBindings |-> "Raymie"]

MC04ExpectedBindingValue == MC04StoredBindingValue

=============================================================================
