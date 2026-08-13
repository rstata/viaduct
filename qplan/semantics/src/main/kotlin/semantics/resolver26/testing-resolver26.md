# Testing Resolver26

## Thread Count

Every Resolver26 test uses one externally configurable resolution thread count, including static contracts, generated properties, coordinate replays, deep stress, broad stress, and multithreaded campaigns. The default is one.

Set it with the Gradle property `-Presolver26ThreadCount=N`, the JVM property `-Dresolver26.thread.count=N`, or the environment variable `RESOLVER26_THREAD_COUNT=N`; the Gradle property is preferred in commands in this guide. The value must be a positive integer.

The setting controls the fixed dispatcher inherited by all Resolver26 coroutines within a request. It does not make separate generated cases concurrent: cases are generated, resolved, and validated one at a time so a failure retains an exact seed and `S:R:Q` coordinate.

Resolver26 caches one process-scoped daemon pool per configured count. Workers are named `resolver26-N-M`, where `N` is the configured pool size and `M` identifies a worker in that pool.

## Concurrency Boundary

Everything invoked while a resolution is running must tolerate concurrent resolver applications. The application witness recorder uses a short synchronized append after constructing each immutable record, application counts and mutation-fixture caches use concurrent maps, and application ordinals use atomics.

Post-resolution validation is intentionally single-threaded. After `resolve` returns, the calling test coroutine snapshots instrumentation and serially evaluates application identities, structural coverage, `correctResolution`, object-path bindings, and any metamorphic comparison. Do not add synchronization to these pure snapshot consumers merely because resolution itself is concurrent.

Keep this division strict when adding instrumentation: capture concurrent events safely and cheaply during resolution, freeze or snapshot them after request quiescence, and perform expensive oracle work serially from the immutable snapshot. Never let a test-only recorder impose a scheduling dependency on Resolver26.

## Ordinary Runs

Run all non-stress Resolver26 tests on the default single worker:

```shell
./gradlew :semantics:test --tests 'semantics.resolver26.*'
```

Run the same static, generated, witness, and mutation suite with five resolution workers:

```shell
./gradlew :semantics:test --tests 'semantics.resolver26.*' -Presolver26ThreadCount=5
```

Run one class or test method by using the normal Gradle test filter and the same thread-count property:

```shell
./gradlew :semantics:test --tests 'semantics.resolver26.ArgumentStampingTest' -Presolver26ThreadCount=2
```

Run the generated Resolver26 contracts under a fixed property seed:

```shell
./gradlew :semantics:test --tests 'semantics.resolver26.ResolverGeneratedTest' -PresolverPropertySeed=424242 -Presolver26ThreadCount=5
```

Replay one exact generated coordinate with the same concurrency:

```shell
./gradlew :semantics:resolverPropertyReplay -PresolverPropertyClass=semantics.resolver26.ResolverGeneratedTest -PresolverPropertyProfile=feature-interaction -PresolverPropertySeed=424242 -PresolverPropertyCase=2:2:1 -Presolver26ThreadCount=5
```

## Stress Runs

Run the recursive deep stress property with a fixed seed and optional case count:

```shell
RESOLVER26_STRESS_CASES=100000 ./gradlew :semantics:resolver26Stress -Presolver26StressSeed=424242 -Presolver26ThreadCount=5
```

Run one unfiltered broad product by choosing a directed profile, seed, and `S:R:Q` dimensions:

```shell
./gradlew :semantics:resolver26BroadStress -Presolver26BroadStressProfile=multiple-owners -Presolver26BroadStressSeed=424242 -Presolver26BroadStressSize=20:10:50 -Presolver26ThreadCount=5
```

Run one persisted five-profile campaign round:

```shell
./gradlew :semantics:resolver26BroadStressCampaign -Presolver26BroadStressCampaignRound=81 -Presolver26ThreadCount=5
```

Run the dispatcher-instrumented campaign with selected rounds and either each round's recorded dimensions or one overriding size:

```shell
./gradlew :semantics:resolver26MultithreadedStress -Presolver26MultithreadedStressRounds=1,46,81,95 -Presolver26MultithreadedStressSize=campaign -Presolver26ThreadCount=10
```

The dedicated multithreaded task records continuation overlap and worker names. Its assertions are useful scheduling evidence, but external OS observation is the stronger check that those workers actually execute on multiple CPUs.

## CPU Parallelism Probe

Use a sufficiently deep run and at least two Resolver26 workers; very small cases can finish before sampling or offer too little runnable work. Run Gradle in the background, wait for its test worker, and sample that JVM from a second shell:

```shell
mkdir -p build/reports/resolver26-cpu-probe
./gradlew :semantics:resolver26MultithreadedStress -Presolver26MultithreadedStressRounds=81 -Presolver26MultithreadedStressSize=20:10:10 -Presolver26ThreadCount=10 --rerun-tasks --console=plain >build/reports/resolver26-cpu-probe/run.log 2>&1 &
gradle_pid=$!
while ! worker_pid=$(jps -lv | awk '/GradleWorkerMain/ { print $1; exit }') || [[ -z $worker_pid ]]; do sleep 1; done
pidstat -t -p "$worker_pid" 1 8 | tee build/reports/resolver26-cpu-probe/pidstat.log
wait "$gradle_pid"
```

Reasonable evidence consists of the Gradle worker process exceeding `100%` CPU while multiple `resolver26-10-*` rows report nonzero CPU in the same samples. Process CPU over `100%` indicates use of more than one core; the named thread rows distinguish Resolver26 work from JIT, GC, and Gradle activity.

If `pidstat` is unavailable, use `top -H -p "$worker_pid"` for live per-thread CPU or `ps -L -p "$worker_pid" -o pid,tid,pcpu,comm` for repeated snapshots. This is evidence rather than a proof: OS accounting is sampled, thread names may be truncated, and brief runs can evade observation.

Avoid selecting an unrelated Gradle worker when other builds are active. Stop other builds, inspect `jps -lv`, or correlate the worker's start time and command with the run being probed.

## Large Campaigns

A 100,000- to 1,000,000-case run should explore a broad state space rather than repeat one distribution. Split the budget across fresh JVM rounds, independent seeds, directed profiles, and different `S:R:Q` shapes; persist each round's command, seed, profile, dimensions, thread count, and log.

The checked-in million-case campaign varies schema breadth, registry diversity, query interaction count, and large/deep worlds. Its directed profiles emphasize balanced worlds, localized descendants, nullable and error providers, stamp collisions, and multiple object-path-variable owners. Keep all of those axes represented in future campaigns.

Favor cases that activate combinations of features, not registries that merely contain them. Important combinations include `FromObjectField` with `FromArgument`, nested provider paths, passive and resolver-bearing descendants, lists and list-localized stamps, node lowering and node arrays, many field resolvers with complex object fragments, nullable or error intermediates, equal grounded arguments from distinct stamps, multiple variable owners and owner dependencies, aliases, duplicate selections, deep selection sets, and high resolver density.

Bound list fanout and other multiplicative dimensions so large worlds do not collapse into a few resource explosions, but do not make the corpus shallow. Preserve registry diversity during query-heavy phases; many queries against one simple registry are not a substitute for varied resolver graphs.

Use low and high thread counts across the campaign. One worker preserves a deterministic baseline, two to ten workers exercise common interleavings, and a larger pool supplies additional scheduling pressure. The thread count changes scheduling, not the semantic corpus, so exact seeds and coordinates remain replayable at any count.

Audit both generated features and activated behavior. Track attempted and completed cases, resolver applications, variable-owner applications, provider-path depth, selection depth, list occurrences, stamp collisions, and required structural signatures. A green run that never activates its target interaction is not evidence for that interaction.

When a case fails, first replay its exact profile, seed, coordinate, and thread count. Then replay at one and several worker counts, classify the failure as resolver, generator, oracle, campaign, or resource-envelope behavior, and reduce a real Resolver26 defect to a deterministic regression before changing the implementation.

Do not import Resolver25 architecture or implementation into Resolver26 while diagnosing tests. Resolver25 is useful only as a source of testing lessons; its execution model is intentionally different.

## Improving The Corpus

Future million-case collections should spend cases according to information gained. Useful extensions include novelty-guided retention of rare structural fingerprints, pairwise or higher-order feature-interaction matrices, extra budget for rare activated signatures, and suppression of semantically duplicate generated cases.

Metamorphic variants can preserve a world while permuting selections, aliases, duplicate occurrences, and equivalent query structure. Keep extensional result, exact application witness, binding, and metamorphic oracles independent so agreement is not manufactured by shared implementation assumptions.

Record generated and activated feature vectors separately, then retain seeds that reach rare intersections or unusually deep paths. Stratify budgets over breadth, depth, registry count, query count, resolver density, and list fanout rather than maximizing one scalar size.

Scheduling perturbations such as deliberate yields may eventually expose additional races, but add them only with a reproducible seed and a reliable coordinate replay. A corpus whose failures cannot be localized is less useful than a slightly smaller one with exact forensic evidence.
