--------------------- MODULE Resolver04Projection ---------------------
EXTENDS ValueConstruction

(*
Resolver04 adds ambient demand discovered while resolving variable providers.
An observation requirement may be direct producer demand, guarded nested
demand, or one exact ambient contribution. The producer receives the union
of Resolver03's supplied demand and all ambient contributions for its field.
*)

CONSTANTS
    ResolverKeys, InitialDemand, DirectDemand, ConstructionOrder,
    Requirements, NestedOccurrences, ActivatedNested,
    OwnerProducer, RequiredByOccurrence, ExtendedByOccurrence,
    DirectOutputDemand, OEROccurrences, LocalOneApplication,
    Sites, FieldSites, VariableSites, RequiredSites,
    SiteDependencies, SiteOrder, VariablesInFragment,
    ProviderValues, InputValues, Contributions,
    ContributionField, ContributionDemand,
    CellProducer, ObservationRequirement

VARIABLE coreN

Core04 ==
    INSTANCE Resolver04
        WITH Keys <- Keys,
             ResolverKeys <- ResolverKeys,
             InitialDemand <- InitialDemand,
             DirectDemand <- DirectDemand,
             ConstructionOrder <- ConstructionOrder,
             n <- coreN,
             Requirements <- Requirements,
             NestedOccurrences <- NestedOccurrences,
             ActivatedNested <- ActivatedNested,
             OwnerProducer <- OwnerProducer,
             RequiredByOccurrence <- RequiredByOccurrence,
             ExtendedByOccurrence <- ExtendedByOccurrence,
             DirectOutputDemand <- DirectOutputDemand,
             OEROccurrences <- OEROccurrences,
             LocalOneApplication <- LocalOneApplication,
             Sites <- Sites,
             FieldSites <- FieldSites,
             VariableSites <- VariableSites,
             RequiredSites <- RequiredSites,
             SiteDependencies <- SiteDependencies,
             SiteOrder <- SiteOrder,
             VariablesInFragment <- VariablesInFragment,
             ProviderValues <- ProviderValues,
             InputValues <- InputValues,
             DemandTokens <- DemandTokens,
             Contributions <- Contributions,
             ContributionField <- ContributionField,
             ContributionDemand <- ContributionDemand

Resolver04SuppliedDemand(producer) ==
    Core04!ProducerSuppliedDemand(producer)
        \cup Core04!AmbientDemand(producer)

Resolver04ProjectionWorld ==
    /\ Core04!Resolver03World
    /\ Core04!Resolver04World
    /\ ValueConstructionWorld
    /\ Requirements = DemandTokens
    /\ CellProducer \in [Cells -> ResolverKeys]
    /\ ObservationRequirement
           \in [Observations -> Requirements]
    /\ \A cell \in ActiveResolverCells :
           SuppliedDemand[cell] =
               Resolver04SuppliedDemand(CellProducer[cell])
    /\ \A observation \in Observations :
           LET cell == ObservationResolver[observation]
               producer == CellProducer[cell]
               requirement ==
                   ObservationRequirement[observation]
           IN
           /\ requirement
                  \in SelectionCoverage[
                      ResultObservation[observation]]
           /\ (\/ requirement
                     \in DirectOutputDemand[producer]
               \/ \E occurrence \in ActivatedNested :
                     /\ OwnerProducer[occurrence] = producer
                     /\ requirement
                           \in RequiredByOccurrence[occurrence]
               \/ \E contribution \in Contributions :
                     /\ ContributionField[contribution] = producer
                     /\ requirement
                           \in ContributionDemand[contribution])

Resolver04ProjectionCoverage ==
    \A observation \in Observations :
        ResultObservation[observation]
            \in DOMAIN
                  Project(
                      SuppliedDemand[
                          ObservationResolver[observation]])

=============================================================================
