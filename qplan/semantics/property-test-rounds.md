# Property-Test Rounds

## Model

A property-test run has two independent inputs. `testInputProfileId` names a versioned, fully resolved generator configuration, while `subjectProfileId` names executable behavior and oracles. The remaining run values are seed, `S:R:Q` counts, and required coverage signature IDs. A round is a versioned ID plus an ordered list of runs.

`PropertyTestCampaignConfigFile` is the compact serialized form for a complete campaign. It names one subject profile, declares its test-input profiles and coverage obligations, defines reusable phases, and assigns consecutive seed ranges to those phases. For round `n`, the range's `baseSeed` advances from its first round and each profile receives `(baseSeed + n - first) * seedMultiplier + seedOffset`. Expanding a campaign therefore produces the same ordinary `PropertyTestRoundConfigFile` consumed by the runner.

`arbitrary` defines `GeneratorConfigData`, a primitive-shaped data class plus conversion to and from `Config`; it has no serialization or resource-loading responsibility. The launcher layer defines the campaign and round data classes, JSON codec, resource index, and loaders in `semantics/propertytest`. Generator profile resources live under `semantics/src/test/resources/semantics/property-tests/generator-configs`, and the explicit `index.json` makes discovery work identically from directories and jars. Broad campaign resources live under `semantics/src/test/resources/semantics/property-tests/campaigns`.

Each generator document contains a partial `shared` object and one or both partial `resolver25` and `resolver26` objects. The loader parses these as JSON trees, recursively upserts the selected resolver object into `shared`, adds the `GeneratorConfigData` format version, and only then binds and validates the complete data class. The resulting complete profile contains every resolved value rather than relying on `Config` defaults. Every `ConfigKey` has an explicit stable wire name and primitive wire type.

## Shared Execution

`PropertyTestRoundRunner` resolves the input and subject profiles and calls `executeResolverTestCases`, the property generator entry point that does not inspect process properties. Resolver25 and Resolver26 broad correctness are subject profiles; Resolver26 required structural signatures are supplied by each run.

The campaign JUnit tests deserialize and expand the same campaign resources before calling the same runner. Their runtime-only `PropertyTestRoundExecution` may select one input profile and one `S:R:Q` coordinate. This preserves the existing JUnit replay interface without adding selected-case state to serialized launcher configuration.

## Launcher

Install the launcher once:

```shell
./gradlew :semantics:installPropertyTestRoundLauncher
```

Run one round from a checked-in or external campaign:

```shell
semantics/build/install/property-test-round/bin/property-test-round \
  --campaign classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json \
  --round 1
```

The launcher also accepts a standalone round configuration:

```shell
semantics/build/install/property-test-round/bin/property-test-round \
  /absolute/path/to/custom-round.json
```

Run a whole campaign or selected rounds with the generic shell driver:

```shell
./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json

./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json \
  1 21 46 81
```

The driver asks the Kotlin launcher for the configured round numbers, performs the install once, lets Gradle exit, and then invokes one fresh launcher JVM per round. The shell does not parse campaign JSON or assume a round range. Gradle configuration, task execution, and JUnit startup are outside each round's test-suite runtime; per-round and total wall-clock reports start after installation and measure only launcher processes.

## Resource Maintenance

Campaign JSON is authored directly. Generator profiles originate as typed Kotlin `Config` values in the Resolver25 and Resolver26 broad-stress profile definitions. After changing those profiles, regenerate their fully resolved layered resources with:

```shell
./gradlew :semantics:materializeGeneratorConfigs
```

Review the resulting generator JSON changes, then run the affected campaign and inspect its exercised coverage. Adjust the typed profile and repeat as needed. Format versions must be incremented deliberately when wire semantics change.
