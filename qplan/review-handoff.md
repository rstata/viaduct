# Review Handoff: Passive and Active Value Resolution

## Purpose

> **Superseded selection policy:** Resolver01 and Resolver02 now consume complete finite resolver outputs and call `resolveValue` with null passive selections. Resolver02 revisits existing passive keys when incoming closure introduces deeper demand and no longer uses `outputSelectionForest` or `successorDemand()`. Resolver03 remains selective and uses `successorDemand()` for both projection and passive construction. The remainder of this document describes the earlier reviewed change.

This change restructures value traversal in Resolver01, Resolver02, and Resolver03. The old
resolver-local `resolveValue` functions recursively called the ordinary object `resolve` operation
whenever they encountered an object. Passive field projection and active field resolution were
therefore interleaved, and each newly encountered object began resolution with an empty
`EngineResult.Object`.

The new design separates those concerns:

1. Construct the selected passive portion of a produced value as an OER tree.
2. Record the object paths at which selected registered field resolvers still need to run.
3. Resume active object resolution at those paths, passing the passive object already constructed
   there as the initial `resolved` prefix.

The shared implementation is in
[`semantics/ResolveValue.kt`](./semantics/src/main/kotlin/semantics/ResolveValue.kt). Resolver01-03
retain their own demand discovery, dependency ordering, materialization, and resolver-application
policies.

Resolver04 is unchanged.

## The New Result Type

`resolveValue` returns:

```kotlin
class ResolvedValue(
    val engineResult: EngineResult?,
    val pathsNeedingResolution: Map<List<Value.Key>, SelectionForest>,
)
```

`engineResult` is the selected passive result tree. A `pathsNeedingResolution` entry identifies an
object in that tree containing at least one selected field with a registered resolver. Its value is
the selection forest already collapsed through the fields leading to that object.

Paths contain concrete `Value.Key` values. They do not contain response aliases or list indices.
Lists are transparent: one path denotes every matching object occurrence at that path in the list.

## Passive Construction

`Value.Output?.resolveValue(selections, path)` handles values as follows:

- Null, `Value.Error`, and simple values terminate traversal and produce no active paths.
- Lists preserve element positions and recursively perform the same passive construction for every
  element.
- Objects filter selections by their concrete type and group them by
  `selection.concreteObjectKey(type)`.
- Unregistered selected fields are copied recursively from the source `Value.Object`.
- Registered selected fields are omitted from the passive OER.
- If an object has any selected registered field, its current concrete-key path and local selection
  forest are recorded for later active resolution.
- A selected `__typename` is constructed directly as `Value.String.of(type.typeName)`. It is not
  read from `fieldValues` and does not trigger recursive object resolution.

Traversal stops structurally at an active field because that field's value does not exist until its
resolver is applied. When that resolver later produces an output value, its resolver-specific
`resolveKey` invokes `resolveValue` again on the new output. Active boundaries below the new value
are discovered at that point.

## Active Resumption

`resolvePaths` sorts recorded paths by descending path length and folds over the passive result.
For each path, it walks the original source `Value.Output` and the current OER in parallel. At the
target object it calls a resolver-specific callback equivalent to:

```kotlin
value.resolve(
    selections = collapsedSelections,
    resolved = passiveObjectAtPath,
)
```

The callback is the previously existing object `resolve` overload that accepts an
`EngineResult.Object`. That overload computes its resolver-specific demand and orders only:

```kotlin
selectionsByKey.keys - resolved.keys
```

Thus passive cells already present in the prefix are preserved, while omitted active cells are
constructed by the ordinary dependency-ordered resolver fold.

Deepest-first order matters when a resolver at an ancestor object materializes an object fragment
that depends on active data inside a passive descendant. Resolving the descendant path first puts
that data in the OER before the ancestor resolver materializes its input. Once the ancestor is
resumed, its already-complete descendant key is excluded from reconstruction.

For a list, `resolvePath` applies the same path and collapsed selections independently to every
element. Concrete keys carry their containing object type, so a path step is ignored in elements
whose concrete type does not own that key.

## Resolver-Specific Selection Policy

The shared helper does not decide how demand is expanded. Each resolver supplies the selections
appropriate to its semantics.

### Resolver01

Resolver01 passes ordinary subselections to passive construction and resumption.

Its current generated domain gives ordinary source resolvers empty object fragments. The only
nonempty local dependencies are fixture-generated node-loader bridge requirements, whose passive
bridge values are scalar. Within that domain, no additional output-side expansion is required
before passive construction.

This assumption deserves scrutiny if Resolver01's supported domain is later widened.

### Resolver02

Resolver02 still gives a registered producer:

```kotlin
subselections + resolver.outputSelectionForest(subselections)
```

This preserves its deliberately non-selective producer model. For construction of the selected
OER, however, it passes `subselections.successorDemand()` to `resolveValue`.

The distinction is intentional. `outputSelectionForest` makes the source value available from the
producer, while `successorDemand` identifies the selected passive values and nested resolver
prerequisites that must be present in the resulting OER.

### Resolver03

Resolver03 computes `subselections.successorDemand()` once for a registered field and uses that
same forest both:

- as the selective producer's requested output, and
- as the input to passive construction and active-path discovery.

The registry-computed predecessor demand and local dependency order still prepare the producer's
input. Successor demand still prepares the producer's output for nested consumers.

## Why Successor Demand Is Needed Before Passive Construction

This was the most important implementation detail discovered during the change.

Suppose a selected passive object field is prepopulated in the partial OER. Active resumption later
expands the containing object's demand and discovers that a resolver also needs an additional
descendant beneath that passive field. The object `resolve(selections, resolved)` overload excludes
every key already present in `resolved`; it does not re-enter an existing passive key to deepen it.

Therefore the passive subtree must already include every descendant that same resolution pass can
require. Resolver02 and Resolver03 achieve that by passing `successorDemand()` to `resolveValue` for
registered producer outputs. This places nested resolver prerequisites in the passive tree before
the corresponding containing object is resumed.

A reviewer should look for a case where successor demand is still insufficient, excessive, or
applied at the wrong boundary. In particular, inspect combinations of:

- a directly selected passive object;
- a sibling resolver that introduces deeper demand under that same object key;
- nested resolver prerequisites under the newly demanded descendants;
- aliases or argument-distinct selections converging on one alias-free `Value.Key`; and
- polymorphic or list-valued passive fields.

## `Value.ObjectFields` Invariant

Concrete-key paths rely on every OER/source-object key being owned by the concrete object containing
it. `Value.ObjectFields` now documents this explicitly:

- `containingType` is a concrete `Schema.ObjectType`;
- every present key's `Schema.ObjectField` is owned by that type; and
- key arguments contain no unresolved variables.

The implementation already enforced this in `ObjectFieldValuesImpl`; this change did not add a
second enforcement mechanism. It added the explicit KDoc and a model test proving that a key owned
by another concrete object type is rejected.

This invariant lets path traversal compare `key.field.containingType` with the source object's
concrete type and skip paths that belong to another possible type.

## Why We Think the Construction Is Correct

The working argument is:

1. Every selected passive cell is recursively copied from the producer's source value.
2. Every selected `__typename` is supplied directly from the concrete object type.
3. Every selected active field is omitted from passive construction, so passive traversal cannot
   accidentally apply it or fabricate its value.
4. Every object containing selected active fields contributes a continuation path with selections
   already collapsed to that object.
5. Deepest-first continuation makes active descendants available before an ancestor resolver can
   materialize an input requiring them.
6. The object resolver receives the corresponding passive OER as `resolved`, preserves its cells,
   closes resolver demand according to that resolver generation, and constructs only missing keys.
7. Each newly produced resolver output repeats the same passive-then-active process.
8. List positions are preserved, and active work is performed separately for each matching object
   occurrence.
9. In Resolver03, the exact witness count equals the number of resolver-bearing OER cells, providing
   evidence that passive preconstruction neither loses required applications nor introduces repeat
   applications.

This is an extensional construction argument. It does not claim a JVM execution schedule, batching,
cross-occurrence deduplication, or a production query-plan implementation.

## Details We Stumbled On

### Direct `__typename`

An early design question was whether `__typename` should be handled by recursive object resolution.
It should not. `resolveValue` now emits it directly during passive construction. The focused test
would fail if the helper attempted to read a nonexistent `__typename` entry from source
`fieldValues`.

### List Paths

The requested path type contains only `Value.Key`, so list positions cannot be represented.
`resolvePath` consequently applies one path to all matching list elements. Merging path maps from
multiple elements can overwrite an equal path entry. We believe this is sound because every element
at a given list level receives the same incoming selection forest; deeper polymorphic paths use
different concrete keys.

This is an assumption worth attacking with heterogeneous lists, nested lists, null elements, and
different concrete possible types that expose similarly named fields.

### Registered Boundaries

Passive traversal cannot record paths inside a registered field's output because that output has not
been produced yet. Those paths are discovered when `resolveKey` invokes `resolveValue` on the
resolver output. A reviewer should verify that no correctness argument accidentally assumes all
paths are known in one global initial pass.

### Stress Oracle and Node Lowering

The first Resolver03 stress harness enabled fixture node resolvers and compared witnessed source
resolver applications with resolver-bearing OER cells. Fixture lowering normalizes bridge/loader
cells to source fields in a way that made this exact count oracle unsuitable: the observed cell
counts doubled even though ordinary correctness checks passed. The Resolver03 witness suite already
scopes its exact application oracle to `NodeResolversEnabled=false`, so the stress harness now uses
that same scope.

Node behavior remains covered by the ordinary deterministic and generated resolver tests, but not
by this exact-count stress oracle.

### Stress Breadth and Heap Use

The initial stress configuration combined 5-7 object types, 5-7 fields per object, 100% depth-4
resolver fragments, and batches of 100 cases per schema. It verified 674 cases and 73,040 resolver
applications before one pathological generated expansion exhausted the 2 GB test heap. This was an
overly broad harness rather than a semantic assertion failure, and it projected to many hours.

The final harness preserves selection depth 4-6 and dense resolver dependencies but reduces schema
and fragment breadth and uses smaller batches. The final seeded run completed all 10,000 cases with
496,873 resolver applications.

## Where to Review Most Deeply

### 1. Existing-Key Semantics

Inspect each private `Value.Object.resolve(selections, resolved)` overload and challenge the use of
`keys - resolved.keys`. Determine whether every way that demand can deepen an existing passive key
is covered before passive construction. Resolver02 and Resolver03 are the highest-risk variants.

### 2. Path Map Collisions

Inspect map union in `resolveValue`, especially across lists. Try to construct two object occurrences
that produce the same `List<Value.Key>` but legitimately require different collapsed selection
forests. If such a case exists, the map currently keeps only the later forest.

### 3. Deepest-First Sufficiency

Check whether path length is a sufficient dependency order. Sibling resolver dependencies within one
object are still handled by each resolver's `dependencyOrder`, but dependencies spanning passive
descendant paths rely on deepest-first traversal. Look for cross-branch demand that is not ordered by
ancestry.

### 4. Successor-Demand Boundaries

Compare the selections sent to the producer with those sent to `resolveValue`:

- Resolver01: ordinary subselections for both;
- Resolver02: broad private producer output forest, successor demand for OER construction;
- Resolver03: successor demand for both.

Challenge whether registered and unregistered parent fields need different handling and whether
applying successor demand only for registered producer outputs is always sufficient.

### 5. Source/OER Alignment

`resolvePath` walks the original `Value.Output` and current `EngineResult` together and uses
`require` checks for type, list-size, and key alignment. Look for legitimate states created by null,
error, polymorphic, list, argument-error, or checker behavior that violate those assumptions.

### 6. `__typename` and Error Arguments

Verify interactions among direct `__typename`, duplicate or aliased typename selections, and keys
whose arguments contain `Value.Error`. The ordinary object resolver still has its own `__typename`
branch, while passive construction may prepopulate it and cause the resumed fold to skip it.

### 7. One-Shot Witness Scope

The stress assertion compares `registeredResolverCellCounts` with the witness application counts.
Review whether both sides count the same notion of occurrence for lists, recursive outputs, errors,
and polymorphic values. Node lowering is deliberately excluded, and variables remain outside
Resolver03's domain.

## Files Changed

- [`semantics/ResolveValue.kt`](./semantics/src/main/kotlin/semantics/ResolveValue.kt)
- [`semantics/resolver01/Resolver.kt`](./semantics/src/main/kotlin/semantics/resolver01/Resolver.kt)
- [`semantics/resolver02/Resolver.kt`](./semantics/src/main/kotlin/semantics/resolver02/Resolver.kt)
- [`semantics/resolver03/Resolver.kt`](./semantics/src/main/kotlin/semantics/resolver03/Resolver.kt)
- [`model/Value.kt`](./model/src/main/kotlin/model/Value.kt)
- [`semantics/ResolveValueTest.kt`](./semantics/src/test/kotlin/semantics/ResolveValueTest.kt)
- [`model/ObjectConstructionTest.kt`](./model/src/test/kotlin/model/ObjectConstructionTest.kt)
- [`semantics/resolver03/ResolverStressTest.kt`](./semantics/src/test/kotlin/semantics/resolver03/ResolverStressTest.kt)
- [`semantics/build.gradle.kts`](./semantics/build.gradle.kts)
- supporting updates in `handoff.md`, `semantics/theorems.md`,
  `arguments/resolver03-one-shot-construction.md`, and `arbitrary/README.md`

## Validation

Repository validation:

```shell
./gradlew check
```

Resolver03 stress validation:

```shell
RESOLVER03_STRESS_CASES=10000 \
RESOLVER03_STRESS_SEED=20260805 \
./gradlew :semantics:resolver03Stress
```

Final stress result:

```text
requestedCases=10000
attemptedCases=10000
verifiedCases=10000
resolverApplications=496873
minimumDepth=4
```

`git diff --check` also passes. The changes are not committed.
