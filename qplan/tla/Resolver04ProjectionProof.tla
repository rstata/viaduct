------------------ MODULE Resolver04ProjectionProof ------------------
EXTENDS Resolver04Projection, ProjectionProof

ASSUME Resolver04ProjectionAssumptions ==
    Resolver04ProjectionWorld

LEMMA Resolver03RequirementIsSupplied ==
    \A producer \in ResolverKeys :
        \A requirement \in Requirements :
            (\/ requirement \in DirectOutputDemand[producer]
             \/ \E occurrence \in ActivatedNested :
                   /\ OwnerProducer[occurrence] = producer
                   /\ requirement
                         \in RequiredByOccurrence[occurrence])
            =>
            requirement
                \in Core04!ProducerSuppliedDemand(producer)
<1>. SUFFICES
        ASSUME NEW producer \in ResolverKeys,
               NEW requirement \in Requirements,
               (\/ requirement
                       \in DirectOutputDemand[producer]
                   \/ \E occurrence \in ActivatedNested :
                         /\ OwnerProducer[occurrence] = producer
                         /\ requirement
                               \in RequiredByOccurrence[occurrence])
        PROVE
            requirement
                \in Core04!ProducerSuppliedDemand(producer)
    OBVIOUS
<1> CASE requirement \in DirectOutputDemand[producer]
    <2>. QED BY DEF Core04!ProducerSuppliedDemand
<1> CASE
        \E occurrence \in ActivatedNested :
            /\ OwnerProducer[occurrence] = producer
            /\ requirement
                  \in RequiredByOccurrence[occurrence]
    <2>1. PICK occurrence \in ActivatedNested :
              /\ OwnerProducer[occurrence] = producer
              /\ requirement
                    \in RequiredByOccurrence[occurrence]
        OBVIOUS
    <2>2. requirement
              \in ExtendedByOccurrence[occurrence]
        BY <2>1, Resolver04ProjectionAssumptions
           DEF Resolver04ProjectionWorld,
               Core04!Resolver03World
    <2>3. \E nested \in ActivatedNested :
              /\ OwnerProducer[nested] = producer
              /\ requirement
                    \in ExtendedByOccurrence[nested]
        BY <2>1, <2>2
    <2>4. requirement
              \in Core04!AggregatedExtendedDemand(producer)
        BY <2>3
           DEF Core04!AggregatedExtendedDemand
    <2>. QED BY <2>4
                DEF Core04!ProducerSuppliedDemand
<1>. QED

LEMMA AmbientRequirementIsSupplied ==
    \A producer \in ResolverKeys :
        \A requirement \in Requirements :
            (\E contribution \in Contributions :
                /\ ContributionField[contribution] = producer
                /\ requirement
                      \in ContributionDemand[contribution])
            =>
            requirement \in Core04!AmbientDemand(producer)
<1>. SUFFICES
        ASSUME NEW producer \in ResolverKeys,
               NEW requirement \in Requirements,
               \E contribution \in Contributions :
                   /\ ContributionField[contribution] = producer
                   /\ requirement
                         \in ContributionDemand[contribution]
        PROVE requirement \in Core04!AmbientDemand(producer)
    OBVIOUS
<1>1. PICK contribution \in Contributions :
          /\ ContributionField[contribution] = producer
          /\ requirement
                \in ContributionDemand[contribution]
    OBVIOUS
<1>2. requirement \in DemandTokens
    BY <1>1, Resolver04ProjectionAssumptions
       DEF Resolver04ProjectionWorld,
           Core04!Resolver04World
<1>. QED BY <1>1, <1>2 DEF Core04!AmbientDemand

THEOREM Resolver04SuppliesObservedDemand ==
    Resolver04ProjectionCoverage
<1>. SUFFICES
        ASSUME NEW observation \in Observations
        PROVE
            ResultObservation[observation]
                \in DOMAIN
                      Project(
                          SuppliedDemand[
                              ObservationResolver[observation]])
    BY DEF Resolver04ProjectionCoverage
<1> DEFINE
        cell == ObservationResolver[observation]
        producer == CellProducer[cell]
        requirement == ObservationRequirement[observation]
        outputObservation == ResultObservation[observation]
<1>1. /\ requirement
             \in SelectionCoverage[outputObservation]
      /\ (\/ requirement \in DirectOutputDemand[producer]
          \/ \E occurrence \in ActivatedNested :
                /\ OwnerProducer[occurrence] = producer
                /\ requirement
                      \in RequiredByOccurrence[occurrence]
          \/ \E contribution \in Contributions :
                /\ ContributionField[contribution] = producer
                /\ requirement
                      \in ContributionDemand[contribution])
    BY Resolver04ProjectionAssumptions
       DEF Resolver04ProjectionWorld
<1>2. /\ producer \in ResolverKeys
      /\ requirement \in Requirements
    <2>1. cell \in ActiveResolverCells
        BY Resolver04ProjectionAssumptions
           DEF Resolver04ProjectionWorld,
               ValueConstructionWorld, ConstructionWorld,
               World, WorldObservations, cell
    <2>2. cell \in Cells
        BY <2>1, Resolver04ProjectionAssumptions
           DEF Resolver04ProjectionWorld,
               ValueConstructionWorld, ConstructionWorld,
               World, WorldCarriers, ActiveResolverCells
    <2>3. producer \in ResolverKeys
        BY <2>2, Resolver04ProjectionAssumptions
           DEF Resolver04ProjectionWorld, producer
    <2>4. requirement \in Requirements
        BY Resolver04ProjectionAssumptions
           DEF Resolver04ProjectionWorld, requirement
    <2>. QED BY <2>3, <2>4
<1>3. requirement
          \in Resolver04SuppliedDemand(producer)
    <2> CASE
            \/ requirement \in DirectOutputDemand[producer]
            \/ \E occurrence \in ActivatedNested :
                  /\ OwnerProducer[occurrence] = producer
                  /\ requirement
                        \in RequiredByOccurrence[occurrence]
        <3>1. requirement
                  \in Core04!ProducerSuppliedDemand(producer)
            BY <1>2, Resolver03RequirementIsSupplied
        <3>. QED BY <3>1 DEF Resolver04SuppliedDemand
    <2> CASE
            \E contribution \in Contributions :
                /\ ContributionField[contribution] = producer
                /\ requirement
                      \in ContributionDemand[contribution]
        <3>1. requirement
                  \in Core04!AmbientDemand(producer)
            BY <1>2, AmbientRequirementIsSupplied
        <3>. QED BY <3>1 DEF Resolver04SuppliedDemand
    <2>. QED BY <1>1
<1>4. cell \in ActiveResolverCells
    BY Resolver04ProjectionAssumptions
       DEF Resolver04ProjectionWorld, ValueConstructionWorld,
           ConstructionWorld, World, WorldObservations, cell
<1>5. SuppliedDemand[cell] =
          Resolver04SuppliedDemand(producer)
    BY <1>4, Resolver04ProjectionAssumptions
       DEF Resolver04ProjectionWorld, cell, producer
<1>6. requirement
          \in SelectionCoverage[outputObservation]
                 \cap SuppliedDemand[cell]
    BY <1>1, <1>3, <1>5
<1>7. outputObservation \in PassiveObservations
    BY Resolver04ProjectionAssumptions
       DEF Resolver04ProjectionWorld, ValueConstructionWorld
<1>. QED BY <1>6, <1>7
          DEF Project, RetainedObservations,
              cell, outputObservation, requirement

=============================================================================
