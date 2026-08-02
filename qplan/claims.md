# Claims

**[flattened-equivalence]** Flattened selection sets are operationally equivalent to the GraphQL spec's nested ones.

**[field-only-node-lowering]** Within the fixture-supported node domain, synthetic `$id` bridge fields and generated field resolvers preserve the field-resolution obligations of external node-valued field and node-resolver inputs.

**[resolver03-one-shot-construction]** Within Resolver03's variable-free acyclic domain, every resolver-bearing OER occurrence is constructed by one field-resolver application after all guarded transitive demand for that occurrence has been aggregated.
