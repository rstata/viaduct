-------------------------- MODULE ResultTree --------------------------
EXTENDS FiniteSets

(*
Finite extensional encoding of an EngineResult tree. Object and cell atoms
carry their complete occurrence identity, including list positions. This
turns the inductively defined Kotlin judgments into quantification over the
least set of object occurrences reachable from Root without using a
RECURSIVE operator, which TLAPS 1.5.0 does not support.

OperationDemand and ResolverDemand contain exact concrete keys after runtime
type specialization and variable instantiation. Resolver observations flatten
the recursive comparison performed by conformsToResolvers: each observation
records one shape, scalar, list-position, or passive descendant fact, and
observations stop at behavioral field boundaries.
*)

CONSTANTS
    Objects, Cells, Keys, Types, Values, Variables, Observations,
    VariableBindings,
    Root, QueryType, FragmentRootType,
    PresentCells, CellObject, CellKey, CellChildren, ObjectType,
    OperationDemand, ResolverCells, ErrorCells, ResolverDemand,
    ObservationResolver, ActualObservation, ExpectedObservation,
    TypenameCells, ActualCellValue, TypeNameValue,
    StoredVariableNames, BindingObject, BindingVariable,
    StoredBindingValue, ExpectedBindingValue

ObjectClosedSets ==
    {objects \in SUBSET Objects :
        /\ Root \in objects
        /\ \A cell \in PresentCells :
               CellObject[cell] \in objects
                   => CellChildren[cell] \subseteq objects}

ReachableObjects ==
    {object \in Objects :
        \A objects \in ObjectClosedSets : object \in objects}

ActiveResolverCells ==
    (PresentCells \cap ResolverCells) \ ErrorCells

PresentKeys(object) ==
    {CellKey[cell] :
        cell \in {candidate \in PresentCells :
            CellObject[candidate] = object}}

ObservationsOf(cell) ==
    {observation \in Observations :
        ObservationResolver[observation] = cell}

BindingsAt(object) ==
    {binding \in VariableBindings :
        BindingObject[binding] = object}

BindingNamesAt(object) ==
    {BindingVariable[binding] :
        binding \in BindingsAt(object)}

WorldCarriers ==
    /\ IsFiniteSet(Objects)
    /\ IsFiniteSet(Cells)
    /\ IsFiniteSet(Keys)
    /\ IsFiniteSet(Observations)
    /\ IsFiniteSet(VariableBindings)
    /\ Root \in Objects
    /\ QueryType \in Types
    /\ FragmentRootType \in Types
    /\ PresentCells \subseteq Cells
    /\ ResolverCells \subseteq Cells
    /\ ErrorCells \subseteq PresentCells
    /\ TypenameCells \subseteq Cells

WorldFunctions ==
    /\ CellObject \in [Cells -> Objects]
    /\ CellKey \in [Cells -> Keys]
    /\ CellChildren \in [Cells -> SUBSET Objects]
    /\ ObjectType \in [Objects -> Types]
    /\ OperationDemand \in [Objects -> SUBSET Keys]
    /\ ResolverDemand \in [Cells -> SUBSET Keys]
    /\ ObservationResolver \in [Observations -> Cells]
    /\ ActualObservation \in [Observations -> Values]
    /\ ExpectedObservation \in [Observations -> Values]
    /\ ActualCellValue \in [Cells -> Values]
    /\ TypeNameValue \in [Objects -> Values]
    /\ StoredVariableNames \in [Objects -> SUBSET Variables]
    /\ BindingObject \in [VariableBindings -> Objects]
    /\ BindingVariable \in [VariableBindings -> Variables]
    /\ StoredBindingValue \in [VariableBindings -> Values]
    /\ ExpectedBindingValue \in [VariableBindings -> Values]

WorldChildren ==
    \A cell \in Cells : CellChildren[cell] \subseteq Objects

WorldConnected ==
    \A cell \in PresentCells :
        CellObject[cell] \in ReachableObjects

WorldUniqueKeys ==
    \A first, second \in PresentCells :
        (/\ CellObject[first] = CellObject[second]
         /\ CellKey[first] = CellKey[second])
            => first = second

WorldTree ==
    /\ WorldChildren
    /\ WorldConnected
    /\ WorldUniqueKeys

WorldObservations ==
    /\ \A observation \in Observations :
           ObservationResolver[observation] \in ActiveResolverCells
    /\ \A cell \in ActiveResolverCells :
           ObservationsOf(cell) # {}

WorldBindings ==
    /\ \A binding \in VariableBindings :
           BindingObject[binding] \in ReachableObjects
    /\ \A first, second \in VariableBindings :
           (/\ BindingObject[first] = BindingObject[second]
            /\ BindingVariable[first] = BindingVariable[second])
               => first = second

World ==
    /\ WorldCarriers
    /\ WorldFunctions
    /\ WorldTree
    /\ WorldObservations
    /\ WorldBindings

RootedAndWellTyped ==
    /\ FragmentRootType = QueryType
    /\ ObjectType[Root] = QueryType

ConformsToFragment ==
    \A object \in ReachableObjects :
        OperationDemand[object] \subseteq PresentKeys(object)

IsClosedUnderResolverDemand ==
    \A cell \in ActiveResolverCells :
        ResolverDemand[cell] \subseteq PresentKeys(CellObject[cell])

ConformsToVariables ==
    \A object \in ReachableObjects :
        /\ StoredVariableNames[object] = BindingNamesAt(object)
        /\ \A binding \in BindingsAt(object) :
               StoredBindingValue[binding] =
                   ExpectedBindingValue[binding]

ConformsToResolvers ==
    \A observation \in Observations :
        ActualObservation[observation] = ExpectedObservation[observation]

ConformsToTypename ==
    \A cell \in PresentCells \cap TypenameCells :
        ActualCellValue[cell] = TypeNameValue[CellObject[cell]]

CorrectResolution ==
    /\ RootedAndWellTyped
    /\ ConformsToFragment
    /\ IsClosedUnderResolverDemand
    /\ ConformsToVariables
    /\ ConformsToResolvers
    /\ ConformsToTypename

OccurrenceCorrect(object) ==
    /\ OperationDemand[object] \subseteq PresentKeys(object)
    /\ \A cell \in ActiveResolverCells :
           CellObject[cell] = object
               => ResolverDemand[cell] \subseteq PresentKeys(object)
    /\ StoredVariableNames[object] = BindingNamesAt(object)
    /\ \A binding \in BindingsAt(object) :
           StoredBindingValue[binding] =
               ExpectedBindingValue[binding]
    /\ \A observation \in Observations :
           CellObject[ObservationResolver[observation]] = object
               => ActualObservation[observation] =
                      ExpectedObservation[observation]
    /\ \A cell \in PresentCells \cap TypenameCells :
           CellObject[cell] = object
               => ActualCellValue[cell] = TypeNameValue[object]

AllReachableOccurrencesCorrect ==
    \A object \in ReachableObjects : OccurrenceCorrect(object)

(*
Postcondition established by Resolver01 once its empty-fragment local set
construction and value-construction cases have been refined into this
carrier. The equality on PresentKeys is stronger than fragment conformance;
Resolver01 has no stored execution variables or resolver input demand.
*)
Resolver01Postcondition ==
    /\ RootedAndWellTyped
    /\ \A object \in ReachableObjects :
           PresentKeys(object) = OperationDemand[object]
    /\ \A cell \in ActiveResolverCells :
           ResolverDemand[cell] = {}
    /\ ConformsToVariables
    /\ ConformsToResolvers
    /\ ConformsToTypename

=============================================================================
