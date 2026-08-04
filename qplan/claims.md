# Claims

**[flattened-equivalence]** Flattened selection sets are operationally equivalent to the GraphQL spec's nested ones.

**[field-only-node-lowering]** Within the fixture-supported node domain, synthetic `$id` bridge fields and generated field resolvers preserve the field-resolution obligations of external node-valued field and node-resolver inputs.

**[resolver03-one-shot-construction]** Within Resolver03's variable-free acyclic domain, every resolver-bearing OER occurrence is constructed by one field-resolver application after all guarded transitive demand for that occurrence has been aggregated.

**[resolver-provider-containment-construction]** Every canonical Kotlin registry constructed by `TestWorld` contains each field-relative variable provider path in its defining resolver's representative and exact fixed-shape object fragments.

**[resolver-local-construction-proof]** Under the finite exact-key world assumptions in `tla/ResolverCore.tla`, TLAPS proves that least demand closure followed by a valid dependency-first fold terminates, supplies every exact direct resolver input first, and gives each activated resolver key one unique application position per concrete OER occurrence.

**[dependency-order-worklist-proof]** Under the finite acyclic dependency assumption in `tla/DependencyOrder.tla`, TLAPS proves that the shared dependency-order worklist terminates, resolves every key only after its dependencies, and never reapplies a resolved key.

**[resolver03-guarded-producer-completeness-proof]** Under the exact registry-extension assumption in `tla/Resolver03.tla`, TLAPS proves that each activated nested resolver occurrence's guarded transitive requirement tokens are included in its owning producer's supplied demand before that producer's unique application.

**[resolver03-composed-application-proof]** Under the finite extensional result-tree, deterministic materialization, observation-alignment, and exact registry-extension assumptions in `tla/Resolver03Application.tla`, TLAPS proves that the occurrence product fold terminates in a Resolver03 result satisfying every modeled `correctResolution` conjunct.

**[resolver04-variable-order-and-ambient-seal-proof]** Under the unified acyclic site-order and complete ambient-contribution assumptions in `tla/Resolver04.tla`, TLAPS proves that provider recursion has finite rank, fragment variables are bound from their providers before the defining field is applied, and all modeled converging operation, provider, and sibling demand is sealed into that field's supplied demand.

**[resolver04-provider-path-evaluation-proof]** Under the finite validated-path and ranked-list assumptions in `tla/ProviderPathEvaluation.tla`, TLAPS proves exact object-field traversal, null/error suffix absorption, and structural terminal conversion to an input value, and `tla/ProviderReads.tla` requires the Resolver04 provider read at its actual root cell to equal that result.

**[resolver04-composed-application-proof]** Under the finite extensional Resolver03 assumptions plus validated provider-read, variable-site, and ambient-contribution assumptions in `tla/Resolver04Application.tla`, TLAPS proves that the occurrence product fold terminates in a Resolver04 result satisfying every modeled `correctResolution` conjunct, including final provider-read variable conformance.

**[resolver-result-tree-refinement-proof]** Under the finite extensional carrier and observation-alignment assumptions in `tla/ResultTree.tla` through `tla/ValueConstruction.tla`, TLAPS proves that Resolver01 and Resolver02 construction postconditions imply every conjunct of the modeled `correctResolution` judgment.

**[resolver-occurrence-product-fold-proof]** Under finite dependency-first construction orders for every reachable OER object occurrence, TLAPS proves that their interleaved product fold terminates with every occurrence's built keys equal to its least closed demand.
