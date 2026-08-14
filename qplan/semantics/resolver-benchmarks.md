# Resolver Benchmarks

Resolver25 and Resolver26 implement the current benchmark profile: selective node and field resolvers, resolver object fragments, `FromArgument` and `FromObjectField` variables, and no query fragments.

## Full Benchmark

`resolver25FullBenchmark` and `resolver26FullBenchmark` run the complete generated property-testing workflow. One JMH operation generates and validates 100 schemas by two registries by five queries, for 1,000 resolver calls. Generation, world assembly, resolution-witness capture, resolution, and post-resolution validation are all timed.

## Overhead Benchmark

`resolver25OverheadBenchmark` and `resolver26OverheadBenchmark` load one checked-in schema/registry pair. Before each measured invocation, JMH setup generates a seeded query batch, parses every query, creates fresh request assumptions and root values for every repetition, and stores the prepared calls in an array. JMH excludes that setup; the measured method only iterates the array, invokes the resolver, and consumes each result.

Control the random query batch with `-PresolverBenchmarkQueryCount=N` and `-PresolverBenchmarkQuerySeed=S`, and its repetition count with `-PresolverBenchmarkLoopCount=M`. One overhead JMH operation contains exactly `N * M` resolver calls.

After the measured trial, an untimed reporting pass resolves the `N` queries once more with application observation enabled. It reports average, percentile, and maximum statistics for fields returned, resolvers executed, result depth, and three variable-workload measurements:

- Resolver executions with any variable-bearing arguments, using P50 because the corpus target is a median of at least 10 per query. Higher medians are preferred.
- Variable-bearing arguments per such resolver execution, using P90. An argument is variable-bearing when its open value recursively contains at least one variable.
- Maximum variable stack depth per query, using P50. A dependency edge connects an executed resolver application to another executed resolver application whose result supplies one of the first application's argument variables. Stack depth is the longest such chain, counted in dependency edges; independent or `FromArgument`-only applications have depth zero.

## Corpus Search

Run `./gradlew :semantics:generateResolverBenchmarkCorpus -PresolverBenchmarkCorpusSeed=S -PresolverBenchmarkCorpusSize=Schemas:Registries:Queries`. The default search evaluates 20 schemas, 10 registries per schema, and 100 random queries per pair.

The search uses Resolver26 to measure actual expanded result size, depth, resolver applications, variable-bearing resolver activation, variable stack depth, owner dependencies, and query diversity. Its scoring strongly rewards reaching the minimum median of 10 variable-bearing resolver executions per query, continues to reward higher medians, and targets roughly 2,000 total resolver executions per query. It writes the highest-scoring pair to `src/jmh/resources/semantics/benchmark/current-profile/schema.graphqls` and `registry.json`. The SDL is human-readable; Jackson stores the executable registry and query-generation recipe as explicit tree DTOs.
