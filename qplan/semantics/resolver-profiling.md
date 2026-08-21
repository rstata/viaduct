# Resolver Profiling

This document is the profiling companion to [`resolver-benchmarks.md`](./resolver-benchmarks.md). That document defines the workloads, benchmark controls, and reporting requirements; this document describes the narrow JFR targets used to explain where those workloads spend their time.

## Choosing A Target

Use the narrowest target that still contains the behavior under investigation:

| Target | Use it to investigate | Deliberately excluded |
| --- | --- | --- |
| `resolver26OverheadProfile` | Resolver26 itself over the fixed current-profile corpus | Query-resource loading and parsing, request preparation, correctness oracles, and statistics reporting |
| `correctResolutionProfile` | The `correctResolution` judgment over 50 prepared, diverse inputs | Resolver26 execution, query generation and parsing, witness preparation, fragment merging, and binding instantiation |
| `propertyTestProfile` | One frozen end-to-end Resolver26 property case, including its correctness oracles | Resource decoding and `TestWorld` assembly |

The full Resolver25 and Resolver26 benchmarks intentionally have no dedicated profiling task. They include generation and validation and are useful as end-to-end performance indicators, but they are usually too broad to explain a hotspot. Start with the property-test profile when the expensive phase is not yet known, then move to the Resolver26-overhead or `correctResolution` profile when its phase events identify one of those components.

## Resolver26 Overhead

Run:

```shell
./gradlew :semantics:resolver26OverheadProfile --console=plain
```

The task prepares the fixed query corpus before each invocation, runs one unrecorded warmup iteration, and records one measured iteration. Recording begins after invocation setup and ends immediately after the measured Resolver26 body. The default recording is `semantics/build/reports/resolver-benchmarks/resolver26-overhead.jfr`.

The profile uses the same controls as the overhead benchmark:

```shell
./gradlew :semantics:resolver26OverheadProfile \
  -PresolverBenchmarkLoopCount=3 \
  -Presolver26OverheadProfileOutput=/tmp/resolver26-overhead.jfr \
  --console=plain
```

Increasing `resolverBenchmarkLoopCount` repeats the already prepared corpus inside the recording and is the preferred way to obtain more samples without admitting setup noise.

## Correct Resolution

Run:

```shell
./gradlew :semantics:correctResolutionProfile --console=plain
```

Trial setup creates the prepared corpus and verifies every judgment. The task then runs one unrecorded warmup iteration and records one measured iteration containing only calls to `correctResolution`. The default recording is `semantics/build/reports/resolver-benchmarks/correct-resolution.jfr`.

The profile uses the same controls as the benchmark:

```shell
./gradlew :semantics:correctResolutionProfile \
  -PcorrectResolutionBenchmarkInputCount=50 \
  -PcorrectResolutionBenchmarkQuerySeed=1 \
  -PcorrectResolutionBenchmarkLoopCount=3 \
  -PcorrectResolutionProfileOutput=/tmp/correct-resolution.jfr \
  --console=plain
```

Resolver and object-materialization frames are legitimate in this recording because `conformsToResolvers` invokes activated field resolvers as part of the correctness judgment.

## Property Test

Run:

```shell
./gradlew :semantics:propertyTestProfile --console=plain
```

The task loads the frozen Resolver26 broad-campaign case, runs one unrecorded warmup case, and records one measured case. The recording includes request preparation, Resolver26, witness snapshotting, application-identity reconstruction and comparison, `correctResolution`, and object-path binding validation. Resource decoding and `TestWorld` assembly occur during trial setup and are excluded. The default recording is `semantics/build/reports/resolver-benchmarks/property-test.jfr`.

Repeat the frozen case inside the recording or preserve the result at another location with:

```shell
./gradlew :semantics:propertyTestProfile \
  -PpropertyTestBenchmarkLoopCount=3 \
  -PpropertyTestProfileOutput=/tmp/property-test.jfr \
  --console=plain
```

This recording contains `qplan.PropertyTestPhase` duration events for each major phase. Use those events first to decide whether the next investigation belongs in Resolver26, `correctResolution`, or one of the property-test oracles.

## Inspecting Recordings

The JDK `jfr` command provides useful first-pass reports:

```shell
jfr summary semantics/build/reports/resolver-benchmarks/property-test.jfr
jfr view hot-methods semantics/build/reports/resolver-benchmarks/property-test.jfr
jfr view allocation-by-site semantics/build/reports/resolver-benchmarks/property-test.jfr
jfr view gc-pauses semantics/build/reports/resolver-benchmarks/property-test.jfr
jfr print --events qplan.PropertyTestPhase semantics/build/reports/resolver-benchmarks/property-test.jfr
```

Replace the path with the Resolver26-overhead or `correctResolution` recording as appropriate. Java Mission Control can be used when call-tree, allocation, or timeline exploration needs more context than the command-line views provide.

Each profiling task deletes its configured output before recording. Set the corresponding output property to a unique path before a comparison run when the previous recording must be retained. Profile timings include JFR overhead and use only one measured iteration, so use the matching JMH benchmark rather than the profile duration to report an improvement.

## Performance Log

Update this log whenever a resolver performance investigation concludes. Add the newest entry immediately below these instructions so entries remain in reverse chronological order.

Each entry must record the UTC date and time, host name and relevant hardware or instance configuration, Codex session ID, tested revision, profiling targets added or used, findings, changes made, and any controlled before/after result. At closeout, run the profiling-related benchmarks serially on an otherwise idle host with default parameters unless the entry explicitly records its overrides: Resolver25 overhead, Resolver26 overhead, `correctResolution`, and the frozen property test. The full generated-workflow benchmarks are deliberately excluded because they exercise a different workload and are not controls for the profiling targets. Report every measured iteration, JMH score and error, units, work per operation, and mean time per resolution, property case, or correctness judgment. Include all emitted fixed-corpus statistics with their actual percentile labels. Do not compare results across different hosts, JVMs, benchmark parameters, or corpus revisions without calling out that difference.

### 2026-08-21 14:33:31 UTC

Host: `raymie-stata-codex`; KVM guest with one Intel Xeon 6975P-C socket, 48 physical cores / 96 vCPUs, 371 GiB RAM, no swap, two NUMA nodes. The cloud instance type was not available from the guest.

Session: `01a0221d-5b61-76d2-9afc-13a06668c652`

Base revision: `568dbc95d6b93342ed94b770814c95624d5fd291`; the profiling documentation and query-corpus changes described here were in the worktree.

This session added the three narrow JFR targets documented above and the isolated `correctResolution` and frozen property-test JMH benchmarks. The frozen property workload serializes Resolver26 broad-campaign round 46's `stamp-collisions` case at historical coordinate `S=10 R=4 Q=3`, so its 12,763 expected resolver applications remain stable as generators evolve. The property profile's phase events made Resolver26, application-identity reconstruction, `correctResolution`, and object-path binding validation independently visible.

Initial profiles identified repeated schema-coordinate recovery, output-type conformance checks, temporary required-argument sets, structural map hashing, and repeated property-oracle materialization as useful targets. Changes made during the session use direct required-argument checks, canonical qplan type identity when available, deterministic source-to-lowered coordinate navigation, direct `Holder` membership checks, and a streaming registered-resolver-occurrence traversal that carries canonical fields and accumulates identity counts without intermediate occurrence and grouping collections. Focused equivalence tests compare the streaming traversal with the prior sorted traversal, including complete paths, canonical fields, application keys, containing-object identity, binding outcomes, and fingerprint-bound behavior.

The frozen property case began the session at approximately 1.50 s per case and closed at 0.589 s per case, approximately 61% less elapsed time or 2.5 times the original throughput. That comparison uses the same serialized workload and host, but the original control was recorded earlier in the session rather than in the closing run below.

The overhead corpus now also checks in the exact ordered batch of 100 query sources generated with seed 1. Both post-serialization runs reproduced every pre-serialization corpus statistic exactly. Resolver25 moved from 5.928 to 5.905 s/op (0.4% faster), and Resolver26 moved from 3.664 to 3.637 s/op (0.7% faster); these sub-1% differences are not material and are consistent with run-to-run noise. Ordinary benchmark and profile tasks now load this snapshot, while explicit generation tasks own deliberate corpus replacement.

Closing benchmarks used JMH 1.36 on Corretto 21.0.4 and default parameters.

| Benchmark | Measured iterations | JMH score | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolver25 overhead | 5.947, 5.846, 5.922 s/op | 5.905 +/- 0.957 s/op | 100 resolutions | 59.050 ms/resolution |
| Resolver26 overhead | 3.612, 3.690, 3.611 s/op | 3.637 +/- 0.823 s/op | 100 resolutions | 36.370 ms/resolution |
| `correctResolution` | 1.577, 1.495, 1.517 s/op | 1.530 +/- 0.776 s/op | 50 judgments | 30.600 ms/judgment |
| Frozen property test | 0.583, 0.554, 0.684, 0.568, 0.559 s/op | 0.589 +/- 0.207 s/op | 1 property case / 12,763 resolver applications | 0.589 s/case |

Resolver25 overhead corpus statistics for 100 queries:

```text
fields returned: average=802.53, p90=1533, max=1990
active fields returned: average=246.13, p90=489, max=671
passive fields returned: average=556.40, p90=1034, max=1331
passive fields per active field: average=2.49, p90=3.23, max=4.73
resolvers executed: average=217.55, p90=444, max=595
resolver executions with variable-bearing arguments: average=11.22, p50=11, max=37
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.81, p50=1, max=1
result depth: average=15.04, p90=15, max=18
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```

Resolver26 overhead corpus statistics for 100 queries:

```text
fields returned: average=811.35, p90=1535, max=2112
active fields returned: average=255.67, p90=513, max=718
passive fields returned: average=555.68, p90=1044, max=1394
passive fields per active field: average=2.38, p90=3.13, max=4.64
resolvers executed: average=219.61, p90=455, max=607
resolver executions with variable-bearing arguments: average=27.24, p50=25, max=113
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.81, p50=1, max=1
result depth: average=15.04, p90=15, max=18
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```
