# Resolver Benchmarks

Resolver25 and Resolver26 implement the current benchmark profile: selective node and field resolvers, resolver object fragments, `FromArgument` and `FromObjectField` variables, and no query fragments.

## Full Benchmark

`resolver25FullBenchmark` and `resolver26FullBenchmark` run the complete generated property-testing workflow. One JMH operation generates and validates 100 schemas by two registries by five queries, for 1,000 resolver calls. Generation, world assembly, resolution-witness capture, resolution, and post-resolution validation are all timed.

## Overhead Benchmark

`resolver25OverheadBenchmark` and `resolver26OverheadBenchmark` load one checked-in schema/registry pair. Before each measured invocation, JMH setup parses the schema and registry once, generates and parses a seeded query batch against those shared static objects, creates fresh request-local `Assumptions` and root values for every resolution, and stores the prepared calls in an array. JMH excludes that setup; the measured method only iterates the array, invokes the resolver, and consumes each result.

Control the random query batch with `-PresolverBenchmarkQueryCount=N` and `-PresolverBenchmarkQuerySeed=S`, and its repetition count with `-PresolverBenchmarkLoopCount=M`. One overhead JMH operation contains exactly `N * M` resolver calls.

After the measured trial, an untimed reporting pass resolves the `N` queries once more with application observation enabled. It reports average, percentile, and maximum statistics for fields returned, resolvers executed, result depth, and three variable-workload measurements:

- Resolver executions with any variable-bearing arguments, using P50 because the corpus target is a median of at least 10 per query. Higher medians are preferred.
- Variable-bearing arguments per such resolver execution, using P90. An argument is variable-bearing when its open value recursively contains at least one variable.
- Maximum variable stack depth per query, using P50. A dependency edge connects an executed resolver application to another executed resolver application whose result supplies one of the first application's argument variables. Stack depth is the longest such chain, counted in dependency edges; independent or `FromArgument`-only applications have depth zero.

The report also separates active from passive result fields, reports their ratio, and describes the fixed registry's active/passive fields per non-Query object plus object-fragment recursive selection counts and depths.

For Resolver26, each measured call includes `runBlocking` on the process-scoped configured dispatcher, the 15-second request timeout, coroutine launch/join work, successor-demand computation, result and Cell allocation, promise and access checks, and request-local cycle protection. Cycle protection registers each Cell writer and records reader-to-writer edges in concurrent maps before a potentially blocking read; it throws on a detected dependency cycle. The ordinary benchmark passes a no-op application observer, but Resolver26 still constructs and submits each observation to that no-op. Query generation, parsing, `Assumptions` and root construction, resolution-witness capture, correctness validation, and statistics traversal are outside the measured method.

## Corpus Search

Run `./gradlew :semantics:generateResolverBenchmarkCorpus -PresolverBenchmarkCorpusSeed=S -PresolverBenchmarkCorpusSize=Schemas:Registries:Queries`.

The default search evaluates 10 schemas, 5 registries per schema, and 10 random queries per pair. Search generation exposes controls for object-output frequency, scalar-biased nested query breadth, ordinary and long-tail object-fragment selection counts, and argument-field preference inside object fragments.

The search uses Resolver26 to measure actual expanded result size, depth, resolver applications, variable-bearing resolver activation, variable stack depth, owner dependencies, and query diversity. Registry eligibility targets roughly two active and fourteen passive fields per non-Query object, an overall passive/active field ratio between 4:1 and 7:1, object fragments averaging 3.5–5 recursive selections with P90 at least 10 and maximum at least 30, and both variable-source kinds. Workload eligibility requires at least 1,000 average result fields, at least 100 average resolver executions, activation of both variable-source kinds, and nonzero stacking; scoring then targets roughly 2,500 fields and 300 resolver executions while strongly rewarding a median of at least 10 variable-bearing resolver executions. Resolver26 timeouts and resolution-witness bound overflows disqualify a candidate. The winner is written to `src/jmh/resources/semantics/benchmark/current-profile/schema.graphqls` and `registry.json`; the SDL is human-readable, while Jackson stores the executable registry and query-generation recipe as explicit tree DTOs.

## Appendix: Generator Next Steps

### Workload Contract

- The current profile is evaluated with Resolver26 and covers selective node and field resolvers, resolver object fragments, `FromArgument` and `FromObjectField` variables, and no query fragments.
- The expensive schema/registry search is offline and reproducible. It writes one winning GraphQL SDL schema and one JSON registry recipe as checked-in resources; benchmark invocations do not repeat that search.
- Queries are not checked in. Each invocation generates a configurable `N`-query corpus from a configurable seed, parses and prepares it outside the measured method, and executes the complete corpus `M` times.
- Every measured invocation reuses one parsed schema and resolver registry across its `N * M` resolutions, so static decoding and registry assembly are never part of per-resolution timing. Each resolution receives fresh request-local `Assumptions` and a fresh root object because variable bindings are monotonic per-request state and must not leak across resolutions.
- The timed loop contains resolution and Blackhole consumption, including Resolver26's ordinary runtime instrumentation described above. Query generation, parsing, setup, witness capture, correctness validation, and statistics reporting remain untimed.
- Queries should be deep, with paths reaching about ten layers, and large enough to return roughly a thousand or more fields on average with a long list-derived tail, without making one JMH operation excessively long.
- A representative non-Query object should have about two active fields and fourteen passive fields. The accepted overall schema ratio is approximately five passive fields per active field, with the search currently allowing 4:1 through 7:1.
- Resolver object fragments should average about four recursive selections and have a long tail: P90 at least 10 and maximum at least 30. Fragment depth is measured separately.
- The workload must activate both `FromArgument` and `FromObjectField` variables in complex combinations. At least 10 resolver instances in the median query should have one or more variable-bearing arguments; the number of variable-bearing arguments per such resolver remains a reported characteristic rather than a hard target.
- The workload should execute a few hundred resolver instances per query rather than the earlier roughly 2,000-instance shape. Field count remains mostly list-derived, while non-list fields and result depth are reported to make that expansion legible.
- The report must include average, requested percentile, and maximum values for result fields, active and passive fields, passive/active ratio, resolver executions, variable-bearing resolver executions, variable-bearing arguments per such execution, stack depth, result depth, schema active/passive fields, and object-fragment selection count and depth.

### TODO: Deeper Variable Stacking

The current fixed corpus reaches variable stack depth one but does not exercise a longer executed chain. A depth-two stack requires an application whose argument reads a value supplied by a second resolver application whose own argument, in turn, reads a value supplied by a third; merely having many variable-bearing arguments or large object fragments does not create this dependency topology.

Several current constraints make longer stacks uncommon. Keeping roughly two active fields among fourteen passive fields reduces possible resolver-to-resolver edges; scalar-biased query breadth preserves the passive-field ratio but activates fewer composite resolver chains; `FromArgument` variables improve variable coverage without adding a resolver-supplied dependency edge; and the registry generator's acyclic rank ordering prevents cycles but makes each additional dependency step progressively harder to place. A static owner dependency also helps only when one random query activates every resolver occurrence in the chain with compatible localized selections and stamps.

Blindly increasing resolver density, object-fragment size, query branching, list size, or depth works against the other workload targets. Those changes distort the active/passive ratio and resolver count, and pathological combinations have already exceeded Resolver26's 15-second bound, the resolution-witness fingerprint budget, or the corpus-search heap.

The next generator improvement should therefore construct an explicit acyclic owner-dependency chain of configurable length and make runtime activation of that chain an eligibility condition. It should preserve the existing active/passive, object-fragment distribution, result-size, resolver-count, variable-source, and bounded-generation constraints rather than trying to obtain deeper stacking through broader random generation.
