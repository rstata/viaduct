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

## Preserving A Profiling Round

Run performance benchmarks and profiles from a clean committed runtime tree. Record the exact commit SHA and confirm that `git status --short` is empty before the first run. Documentation and generated profile reports may be added after measurement, but runtime, benchmark, and corpus changes must be committed first. If exceptional circumstances require profiling a dirty tree, preserve its complete patch and state explicitly that the recorded commit is not sufficient to reproduce the run.

Create one checked-in directory under [`profiles`](./profiles) for each profiling round and link it from the performance-log entry. Its README must record the commands, tested revision, host and JVM, benchmark parameters, corpus hashes, and raw benchmark iterations or point to the log entry containing them. Export each JFR with [`../export-resolver-profile.sh`](../export-resolver-profile.sh) so the bundle retains phase events, hot methods, allocation sites, GC pauses, aggregated execution and allocation stacks, and the raw recording checksum. Raw JFR files are optional; retain them outside Git until the investigation closes in case additional views are needed.

Workload changes caused by legitimate semantic corrections are valid performance changes. Preserve all emitted workload statistics and identify the responsible semantic commit when known so timing discontinuities can be interpreted without requiring an old recording.

## Performance Log

Update this log whenever a resolver performance investigation concludes. Add the newest entry immediately below these instructions so entries remain in reverse chronological order.

Each entry must record the UTC date and time, host name and relevant hardware or instance configuration, Codex session ID, tested revision, profiling targets added or used, findings, changes made, and any controlled before/after result. At closeout, run the profiling-related benchmarks serially on an otherwise idle host with default parameters unless the entry explicitly records its overrides: Resolver25 overhead, Resolver26 overhead, `correctResolution`, and the frozen property test. The full generated-workflow benchmarks are deliberately excluded because they exercise a different workload and are not controls for the profiling targets. Report every measured iteration, JMH score and error, units, work per operation, and mean time per resolution, property case, or correctness judgment. Include all emitted fixed-corpus statistics with their actual percentile labels. Do not compare results across different hosts, JVMs, benchmark parameters, or corpus revisions without calling out that difference.

### 2026-08-22 17:49:08 UTC

Host: `raymie-stata-codex`; KVM guest with one Intel Xeon 6975P-C socket, 48 physical cores / 96 vCPUs, 371 GiB RAM, no swap, and two NUMA nodes.

Session: `01a02a6b-c8a2-75c3-a427-76c799e8d325`

Tested revision: `5193dec7ba656b6c748d8a39bd28a431b31d60e2`

Profile evidence: [`profiles/2026-08-22-5193dec7`](./profiles/2026-08-22-5193dec7)

The runtime tree was clean and committed before the final test, benchmark, and profile sequence. The final default benchmarks ran serially on an otherwise idle host with Corretto 21.0.4 and JMH 1.36. The evidence README records exact commands and corpus hashes. The final JFR profiles repeated each prepared workload three times and retain phase events, hot methods, allocation sites, GC pauses, aggregated execution and allocation stacks, and raw-recording checksums; the raw recordings remain outside Git and can be regenerated from the tested SHA.

| Benchmark | Measured iterations | JMH score | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolver25 overhead | 2.508, 2.495, 2.577 s/op | 2.527 +/- 0.808 s/op | 100 resolutions | 25.270 ms/resolution |
| Resolver26 overhead | 1.760, 1.748, 1.747 s/op | 1.752 +/- 0.130 s/op | 100 resolutions | 17.520 ms/resolution |
| `correctResolution` | 0.740, 0.745, 0.742 s/op | 0.743 +/- 0.043 s/op | 50 judgments | 14.860 ms/judgment |
| Frozen property test | 0.547, 0.551, 0.653, 0.599, 0.531 s/op | 0.576 +/- 0.193 s/op | 1 property case / 12,763 expected resolver applications | 0.576 s/case |

The closeout Resolver26 result is 24.6% faster than the session baseline of 2.325 s/op, and the frozen property result is 9.3% faster than the ten-iteration baseline average of 0.635 s/case. The property closeout had two slower iterations; the immediately preceding retained-revision control was tighter at 0.547 +/- 0.020 s/op from 0.550, 0.546, 0.554, 0.546, and 0.540. The closeout `correctResolution` result is 25.5% faster than the session's 0.997 s/op diagnostic baseline. These comparisons all use the same host, JVM, corpus revisions, default benchmark parameters, and post-`86b9683a7` corrected resolver semantics.

Resolver25 overhead corpus statistics for 100 queries:

```text
fields returned: average=294.37, p90=448, max=678
active fields returned: average=93.09, p90=155, max=241
passive fields returned: average=201.28, p90=297, max=437
passive fields per active field: average=2.38, p90=3.04, max=4.84
resolvers executed: average=68.59, p90=107, max=165
resolver executions with variable-bearing arguments: average=5.72, p50=9, max=20
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.59, p50=1, max=1
result depth: average=8.58, p90=9, max=9
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```

Resolver26 overhead corpus statistics for 100 queries:

```text
fields returned: average=301.52, p90=448, max=732
active fields returned: average=100.24, p90=169, max=295
passive fields returned: average=201.28, p90=297, max=437
passive fields per active field: average=2.25, p90=3.00, max=4.84
resolvers executed: average=68.73, p90=107, max=165
resolver executions with variable-bearing arguments: average=5.72, p50=9, max=20
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.59, p50=1, max=1
result depth: average=8.58, p90=9, max=9
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```

Four retained changes account for the recovery. `f64af7878` caches stable `ObjectCellStore.keys` snapshots; its immediate property control was effectively flat at 0.631 s/op, but it removed the prior repeated key-set copies. `0b24f4a5f` removes duplicate fixture output validation and improved Resolver26 from 2.325 to 1.962 s/op (15.6%) and the property case from 0.635 to 0.600 s/op (5.5%). `5435d107a` reuses one empty resolved-argument value and bypasses coercion for empty, default-free, optional argument definitions; its controls improved Resolver26 from 1.962 to 1.836 s/op (6.4%) and the property case from 0.600 to 0.556 s/op (7.3%). `5193dec7b` constructs a missing-cell exception only when freeze encounters an unclaimed reader placeholder; its Resolver26 control improved from 1.836 to 1.803 s/op, while the stable property repeat improved from 0.556 to 0.547 s/op.

One attempted optimization was rejected. `5bbfce6ad` eagerly cached each immutable resolver-input selection's first nested error; Resolver26 regressed from 1.962 to 2.190 s/op (11.6%) and the property case remained flat at 0.605 s/op, so `55c2c424f` reverted it. Recursive error scans did not rank highly enough to justify eager cache construction.

The final profiles confirm the targeted allocation changes. In Resolver26, `LinkedHashMap.sequencedEntrySet` fell from 50.24% of allocation pressure before empty-argument reuse to 0.52%; the remaining instances come from genuinely populated maps. Missing-cell `NoSuchElementException` construction no longer appears in any final top-100 allocation stack. Repeated `ObjectCellStore.keys` copies likewise remain absent.

The remaining Resolver26 CPU samples are led by `conformsToOutputSchemaType` at 20.35%, `HashMap.getNode` at 12.66%, and `GJSchema.lowerOrdinaryOutput` at 11.66%. The correctness profile is similarly led by output conformance at 16.75%, lowering at 16.26%, and source-coordinate lowering at 9.36%, because `conformsToResolvers` legitimately invokes fixture resolvers. These are broader fixture-output construction costs, not a newly isolated redundant pass after `0b24f4a5f`; changing them safely would require a stronger canonical-output construction boundary and is deferred. GC does not dominate: final recorded pause totals were 49.5 ms for Resolver26, 18.8 ms for the property case, and 41.2 ms for `correctResolution`.

The property profile's three repetitions averaged 397.491 ms in Resolver26, 128.353 ms in the application-identity oracle, 224.554 ms in `correctResolution`, 16.436 ms in object-path validation, 18.773 ms in request preparation, and 0.034 ms in witness snapshotting. The first repetition includes recording-start effects and was materially slower than the next two, so benchmark controls, not profile durations, remain the evidence for the retained speedups.

The full `:model:test :semantics:test` gate passed at the tested revision. Commit `86b9683a7` remains the semantic breadcrumb for the legitimate workload reduction caused by corrected resolver-input error propagation; this round does not treat that correction as a benchmark defect.

### 2026-08-22 17:12:04 UTC

Host: `raymie-stata-codex`; KVM guest with one Intel Xeon 6975P-C socket, 48 physical cores / 96 vCPUs, 371 GiB RAM, no swap, two NUMA nodes. The cloud instance type was not available from the guest.

Session: `01a02a6b-c8a2-75c3-a427-76c799e8d325`

Tested revision: `91303870b87fe08cbb030ac4f28f4f7b0edbbe24`

Profile evidence: [`profiles/2026-08-22-91303870`](./profiles/2026-08-22-91303870)

This investigation used the existing frozen property-test and Resolver26-overhead JMH benchmarks and the `propertyTestProfile`, `resolver26OverheadProfile`, and diagnostic `correctResolutionProfile` JFR targets. Profiles repeated their prepared workload three times with `propertyTestBenchmarkLoopCount=3`, `resolverBenchmarkLoopCount=3`, or `correctResolutionBenchmarkLoopCount=3` and were written to `/tmp/1rv-property-test-20260822.jfr`, `/tmp/1rv-resolver26-overhead-20260822.jfr`, and `/tmp/1rv-correct-resolution-20260822.jfr`. The requested JMH benchmarks ran serially on an otherwise idle host with their default parameters on Corretto 21.0.4 and JMH 1.36. The property benchmark was repeated because its first result had wider iteration variance. The full four-benchmark closeout suite was not run because this investigation was scoped to the requested frozen property and Resolver26 benchmarks; `correctResolution` was added only as a diagnostic target.

| Benchmark | Measured iterations | JMH score | Work per operation | Mean per unit |
| --- | --- | --- | --- | --- |
| Resolver26 overhead | 2.305, 2.296, 2.375 s/op | 2.325 +/- 0.786 s/op | 100 resolutions | 23.250 ms/resolution |
| Frozen property test, run 1 | 0.610, 0.576, 0.750, 0.660, 0.574 s/op | 0.634 +/- 0.284 s/op | 1 property case / 12,763 expected resolver applications | 0.634 s/case |
| Frozen property test, run 2 | 0.593, 0.721, 0.631, 0.646, 0.592 s/op | 0.636 +/- 0.202 s/op | 1 property case / 12,763 expected resolver applications | 0.636 s/case |
| `correctResolution`, diagnostic | 0.976, 0.969, 1.047 s/op | 0.997 +/- 0.788 s/op | 50 judgments | 19.940 ms/judgment |

The two frozen-property runs average 0.635 s/case across their ten measured iterations, 7.8% slower than the last reported 0.589 s/case. The slowdown is reproducible, but the nominal Resolver26 improvement from 3.637 to 2.325 s/op is not comparable: error-propagation semantics shortened the fixed query workload from an average of 219.61 to 68.73 executed resolvers per query, a 68.7% reduction, while fields returned fell from 811.35 to 301.52. The diagnostic `correctResolution` result is likewise not comparable with its prior 1.530 s/op baseline because its prepared results are shortened by the same behavior.

Current Resolver26 overhead corpus statistics for 100 queries:

```text
fields returned: average=301.52, p90=448, max=732
active fields returned: average=100.24, p90=169, max=295
passive fields returned: average=201.28, p90=297, max=437
passive fields per active field: average=2.25, p90=3.00, max=4.84
resolvers executed: average=68.73, p90=107, max=165
resolver executions with variable-bearing arguments: average=5.72, p50=9, max=20
variable-bearing arguments per such resolver execution: average=1.00, p90=1, max=1
maximum variable stack depth: average=0.59, p50=1, max=1
result depth: average=8.58, p90=9, max=9
active fields per non-Query object: average=1.88, p90=4, max=5
passive fields per non-Query object: average=14.31, p90=18, max=18
selections per object fragment: average=4.48, p90=18, max=39
object fragment depth: average=1.63, p90=5, max=9
```

A controlled commit comparison on the same host and JVM located both changes at `86b9683a7287aabf73c10c1c98a8b57641d8955b` (`Propagate errors read from resolver inputs`). At `ba3901a08fc5a3845fed3d80509dd7898996401e`, the original Resolver26 workload scored 3.672 +/- 1.208 s/op from iterations 3.627, 3.748, and 3.642, matching the logged baseline. At `9bd8cf4af689516e974075ab369a7480c5e2185e`, the original workload scored 4.050 +/- 0.991 s/op from 4.008, 4.111, and 4.029, approximately 10.3% slower than `ba3901a08`; its frozen property case scored 0.595 +/- 0.095 s/op from 0.583, 0.568, 0.589, 0.601, and 0.633. At `86b9683a7`, Resolver26's workload changed to its current dimensions and scored 2.291 +/- 0.802 s/op from 2.274, 2.257, and 2.340, while the frozen property case regressed to 0.635 +/- 0.259 s/op from 0.613, 0.754, 0.593, 0.612, and 0.603. Propagated errors now terminate dependent resolver branches, which may be semantically correct, but the existing Resolver26 and `correctResolution` corpus timings no longer measure the workload represented by their logged baselines.

The archived and current property profiles agree with the JMH regression. Average steady-state Resolver26 time was effectively flat at 302.658 ms before and 300.035 ms now, while application-identity reconstruction rose from 110.699 to 129.268 ms, `correctResolution` rose from 190.856 to 218.183 ms, object-path validation rose from 10.566 to 15.738 ms, and request preparation rose from 6.844 to 8.351 ms. These phases together rose approximately 8.0%. Garbage collection does not explain the change: recorded property-profile pause time fell from 20.9 to 18.9 ms.

The clearest low-hanging allocation target is `ObjectCellStore.keys`: it returns `cells.keys.toSet()` on every access and accounts for 31.47% of current property-profile allocation pressure. The earlier profile attributed 31.68% to the underlying `LinkedHashMap.sequencedEntrySet` path, so this is longstanding overhead rather than the new regression. Correctness and witness traversals repeatedly request this complete copy. Caching a stable key set after `freeze()`, or replacing caller-side `key in keys` checks with direct membership and iteration APIs, should remove substantial allocation without changing semantics.

The current shortened Resolver26 profile attributes approximately 40% of CPU samples to generated-output coercion and validation: `conformsToOutputSchemaType` 14.85%, `coerceOutputValue` 13.48%, and `GJSchema.lowerOrdinaryOutput` 11.43%, with overlapping structural `HashMap.getNode` work at 11.09%. Generated canonical outputs currently pass through coercion, source-to-lowered traversal, and conformance, each recursively processing lists. An internal canonical-output construction path that proves these invariants once is the next strongest optimization candidate for the current workload.

The regression-introducing commit also added `firstErrorDataOrNull()` to every `QPlanEngineObjectDataImpl.get()`. It recursively scans list values whenever resolver input is read. Caching each immutable selection's first error would avoid repeated list scans and is a tightly scoped hypothesis for clawing back some of the commit-local cost, but this helper did not rank among the top CPU frames in the narrow profile and therefore needs a controlled benchmark before being treated as a demonstrated hotspot. Structural map hashing and lookup remain visible but are lower priority than eliminating complete key-set copies and duplicate output traversals.

No runtime code changed during this investigation. The reduced Resolver26 work is a legitimate consequence of corrected error-propagation semantics, not a benchmark defect, but it prevents a direct timing comparison with earlier runs. Future log entries should preserve the emitted workload statistics and identify semantic commits that materially change them so apparent discontinuities have an explicit explanation.

### 2026-08-21 14:33:31 UTC

Host: `raymie-stata-codex`; KVM guest with one Intel Xeon 6975P-C socket, 48 physical cores / 96 vCPUs, 371 GiB RAM, no swap, two NUMA nodes. The cloud instance type was not available from the guest.

Session: `01a0221d-5b61-76d2-9afc-13a06668c652`

Base revision: `568dbc95d6b93342ed94b770814c95624d5fd291`; the profiling documentation and query-corpus changes described here were in the worktree.

Profile evidence: [`profiles/2026-08-21-568dbc95`](./profiles/2026-08-21-568dbc95)

This session added the three narrow JFR targets documented above and the isolated `correctResolution` and frozen property-test JMH benchmarks. The frozen property workload serializes Resolver26 broad-campaign round 46's `symbolic-identity` case at historical coordinate `S=10 R=4 Q=3`, so its 12,763 expected resolver applications remain stable as generators evolve. The property profile's phase events made Resolver26, application-identity reconstruction, `correctResolution`, and object-path binding validation independently visible.

Initial profiles identified repeated schema-coordinate recovery, output-type conformance checks, temporary required-argument sets, structural map hashing, and repeated property-oracle materialization as useful targets. Changes made during the session use direct required-argument checks, canonical qplan type identity when available, deterministic source-to-lowered coordinate navigation, direct `HMap` membership checks, and a streaming registered-resolver-occurrence traversal that carries canonical fields and accumulates identity counts without intermediate occurrence and grouping collections. Focused equivalence tests compare the streaming traversal with the prior sorted traversal, including complete paths, canonical fields, application keys, containing-object identity, binding outcomes, and fingerprint-bound behavior.

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
